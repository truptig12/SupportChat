"""
Routing orchestration: one customer turn in, one structured response out.

    redacted message + session history + long-term memory
        │
        ▼
    Stage 1  classify   ──► intents[] (multi-intent), confidence, language,
        │                   sentiment, urgency, risk flags, clarification
        ▼
    normalize (code)    ──► validate enums, order security-first, drop weak
        │                   secondaries, cap count
        ▼
    escalation policy   ──► deterministic rules over triage + history + memory
        │
        ├─ ambiguous? ──► clarifying question + topic buttons (skip Stage 2)
        ▼
    Stage 2  resolve    ──► sub-intent per intent + empathetic reply
        ▼
    buttons (code)      ──► from KB only; merged across intents; handoff first
        ▼
    output filter       ──► block promises / links / PII in bot_reply

Decisions that carry risk (escalate? which buttons?) are made in code, not by
the model. The model supplies signals; policy stays auditable and testable.

`route()` never raises. Any backend failure degrades to a safe response.
"""

from __future__ import annotations

import logging
from dataclasses import asdict, dataclass, field
from typing import Any, Dict, List, Optional

from . import knowledge_base as kb
from .config import Settings
from .guardrails import SAFE_REPLY_BY_INTENT, SAFE_REPLY_DEFAULT, filter_bot_reply
from .llm import RISK_FLAGS, SENTIMENTS, URGENCIES, LLMBackend, TurnContext

log = logging.getLogger("support_router.router")

_PRIORITY_RANK = {"normal": 0, "high": 1, "urgent": 2}
DEFAULT_CLARIFY_QUESTION = "Could you tell me a bit more? Which of these is it about?"


@dataclass
class Escalation:
    escalate: bool = False
    priority: Optional[str] = None      # normal | high | urgent
    reasons: List[str] = field(default_factory=list)


@dataclass
class RouteResult:
    intents: List[Dict[str, Any]]       # [{intent, sub_intent, confidence, label}]
    primary_intent: str
    bot_reply: str
    language: str
    sentiment: str
    urgency: str
    risk_flags: List[str]
    needs_clarification: bool
    escalation: Escalation
    ui_options: List[Dict[str, str]]    # [{id, label, intent}]
    guardrail_events: List[str] = field(default_factory=list)
    degraded: bool = False              # True if a fallback path was used

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


# ---------------------------------------------------------------------------
# Stage 1 normalization
# ---------------------------------------------------------------------------

def normalize_triage(raw: Dict[str, Any], s: Settings) -> Dict[str, Any]:
    """Defense in depth: strict mode should guarantee shape, but models,
    proxies and SDKs change. Coerce anything off-contract to safe defaults."""
    seen, intents = set(), []
    for item in raw.get("intents") or []:
        if not isinstance(item, dict):
            continue
        intent = item.get("intent")
        if intent not in kb.KNOWLEDGE_BASE and intent != kb.UNKNOWN:
            continue
        if intent in seen:
            continue
        seen.add(intent)
        try:
            conf = max(0.0, min(1.0, float(item.get("confidence", 0))))
        except (TypeError, ValueError):
            conf = 0.0
        intents.append({"intent": intent, "confidence": conf})

    known = [i for i in intents if i["intent"] != kb.UNKNOWN]
    # Every plausible reading, before weak ones are dropped: these become the
    # clarifying-question buttons when the router isn't sure.
    candidates = [i["intent"] for i in known]
    if known:
        # Security first (stable sort keeps the model's order otherwise).
        known.sort(key=lambda i: i["intent"] != "fraud_and_security")
        primary, rest = known[0], known[1:]
        rest = [i for i in rest if i["confidence"] >= s.secondary_intent_min_confidence]
        intents = ([primary] + rest)[: s.max_intents_per_turn]
    else:
        intents = [{"intent": kb.UNKNOWN, "confidence": intents[0]["confidence"] if intents else 1.0}]

    lang = raw.get("language")
    question = raw.get("clarifying_question")
    return {
        "intents": intents,
        "candidates": candidates,
        "language": lang if isinstance(lang, str) and 0 < len(lang) <= 16 else "en",
        "sentiment": raw.get("sentiment") if raw.get("sentiment") in SENTIMENTS else "neutral",
        "urgency": raw.get("urgency") if raw.get("urgency") in URGENCIES else "low",
        "risk_flags": [f for f in (raw.get("risk_flags") or []) if f in RISK_FLAGS],
        "needs_clarification": bool(raw.get("needs_clarification")),
        "clarifying_question": question.strip() if isinstance(question, str) and question.strip() else None,
    }


# ---------------------------------------------------------------------------
# Escalation policy (#6) — deterministic, explainable
# ---------------------------------------------------------------------------

def decide_escalation(triage: Dict[str, Any], history: List[Dict[str, Any]],
                      memory: List[Dict[str, Any]], s: Settings) -> Escalation:
    reasons: Dict[str, str] = {}  # reason -> priority
    flags, sentiment = set(triage["risk_flags"]), triage["sentiment"]

    if "safety_risk" in flags:
        reasons["safety_risk"] = "urgent"
    if triage["urgency"] == "critical":
        reasons["critical_urgency"] = "urgent"
    if "legal_threat" in flags:
        reasons["legal_threat"] = "high"
    if "vulnerable_customer" in flags:
        reasons["vulnerable_customer"] = "high"

    # Anger that persists across turns (one angry message alone is normal).
    prior_upset = sum(1 for t in history
                      if t.get("role") == "user" and t.get("sentiment") in ("negative", "angry"))
    if sentiment == "angry" and prior_upset >= 1:
        reasons["persistent_anger"] = "high"

    if "requests_human" in flags:
        reasons["customer_requested_human"] = "normal"
    if "churn_risk" in flags and sentiment in ("negative", "angry"):
        reasons["churn_risk"] = "normal"

    # Long-term memory: same problem unresolved across several past sessions.
    current = {i["intent"] for i in triage["intents"]} - {kb.UNKNOWN}
    for intent in current:
        misses = sum(1 for m in memory
                     if intent in (m.get("intents") or []) and not m.get("resolved"))
        if misses >= s.repeat_contact_threshold:
            reasons["repeat_contact"] = "normal"

    if not reasons:
        return Escalation()
    top = max(reasons.values(), key=_PRIORITY_RANK.__getitem__)
    return Escalation(escalate=True, priority=top, reasons=sorted(reasons))


# ---------------------------------------------------------------------------
# Buttons
# ---------------------------------------------------------------------------

def build_buttons(resolved: List[Dict[str, Any]], escalation: Escalation,
                  s: Settings) -> List[Dict[str, str]]:
    """
    Merge buttons across intents: primary intent gets more slots, each
    secondary gets its top 2. Handoff goes first when escalating, except for
    pure critical-urgency cases (e.g. stolen card) where the self-serve
    containment action ("Freeze my card now") is faster than any human.
    """
    known = [r for r in resolved if r["intent"] in kb.KNOWLEDGE_BASE]
    if not known:
        buttons = kb.options_for(kb.UNKNOWN)
    else:
        per_intent = 4 if len(known) == 1 else 3
        buttons = []
        for idx, r in enumerate(known):
            opts = kb.options_for(r["intent"], r.get("sub_intent"))
            buttons.extend(opts[: per_intent if idx == 0 else 2])

    if escalation.escalate:
        pos = 1 if set(escalation.reasons) == {"critical_urgency"} and known else 0
        buttons.insert(pos, dict(kb.HANDOFF_OPTION))
        # Drop redundant "talk to a person" style buttons when handing off.
        buttons = [b for b in buttons if b["id"] == kb.HANDOFF_OPTION["id"]
                   or not b["id"].endswith(("_agent", "technician", "fraud_team", ".human"))]

    deduped, seen = [], set()
    for b in buttons:
        if b["id"] not in seen:
            seen.add(b["id"])
            deduped.append(b)
    return deduped[: s.max_buttons]


# ---------------------------------------------------------------------------
# The router
# ---------------------------------------------------------------------------

class SupportRouter:
    def __init__(self, backend: LLMBackend, settings: Settings):
        self.backend = backend
        self.s = settings

    def route(self, message: str, history: List[Dict[str, Any]],
              memory: List[Dict[str, Any]], user_ref: str = "") -> RouteResult:
        """`message` must already be sanitized and PII-redacted."""
        ctx = TurnContext(message=message, history=history, memory=memory, user_ref=user_ref)

        # ---- Stage 1 ---------------------------------------------------------
        try:
            triage = normalize_triage(self.backend.classify(ctx), self.s)
        except Exception:  # noqa: BLE001 — never crash the chat
            log.exception("stage1 classify failed")
            return self._fallback()

        escalation = decide_escalation(triage, history, memory, self.s)
        top = triage["intents"][0]

        # ---- Clarification (#1) ------------------------------------------------
        if self._should_clarify(triage, top, escalation, history):
            candidates = triage["candidates"]
            question = triage["clarifying_question"] or DEFAULT_CLARIFY_QUESTION
            reply, events = filter_bot_reply(question, kb.UNKNOWN)
            return RouteResult(
                intents=[self._intent_entry(i, None) for i in triage["intents"]],
                primary_intent=top["intent"], bot_reply=reply,
                language=triage["language"], sentiment=triage["sentiment"],
                urgency=triage["urgency"], risk_flags=triage["risk_flags"],
                needs_clarification=True, escalation=escalation,
                ui_options=kb.clarify_options(candidates), guardrail_events=events,
            )

        # ---- Stage 2 (#5 sub-intents, #9 multi-intent) -------------------------
        intent_names = [i["intent"] for i in triage["intents"]]
        degraded = False
        try:
            raw2 = self.backend.resolve(ctx, intent_names, triage["language"],
                                        triage["sentiment"], escalation.escalate)
            subs = self._validate_resolutions(raw2, intent_names)
            reply_raw = raw2.get("bot_reply") if isinstance(raw2.get("bot_reply"), str) else ""
        except Exception:  # noqa: BLE001
            log.exception("stage2 resolve failed; using defaults")
            subs, degraded = {}, True
            reply_raw = SAFE_REPLY_BY_INTENT.get(top["intent"], SAFE_REPLY_DEFAULT)

        resolved = [self._intent_entry(i, subs.get(i["intent"])) for i in triage["intents"]]

        # ---- Guardrails (#16) --------------------------------------------------
        reply, events = filter_bot_reply(reply_raw, top["intent"])
        if events:
            log.warning("output filter events=%s intent=%s", events, top["intent"])

        return RouteResult(
            intents=resolved, primary_intent=top["intent"], bot_reply=reply,
            language=triage["language"], sentiment=triage["sentiment"],
            urgency=triage["urgency"], risk_flags=triage["risk_flags"],
            needs_clarification=False, escalation=escalation,
            ui_options=build_buttons(resolved, escalation, self.s),
            guardrail_events=events, degraded=degraded,
        )

    # -- helpers --------------------------------------------------------------

    def _should_clarify(self, triage, top, escalation, history) -> bool:
        if top["intent"] == kb.UNKNOWN or escalation.escalate:
            return False
        # Never stall a customer who's losing money or locked out.
        if triage["urgency"] in ("high", "critical"):
            return False
        # Never ask twice in a row: if the last bot turn was already a
        # clarification, commit to the best guess instead of looping.
        last_bot = next((t for t in reversed(history) if t.get("role") == "bot"), None)
        if last_bot and last_bot.get("needs_clarification"):
            return False
        return triage["needs_clarification"] or top["confidence"] < self.s.clarify_below_confidence

    @staticmethod
    def _validate_resolutions(raw: Dict[str, Any], intents: List[str]) -> Dict[str, str]:
        """Map intent -> sub-intent key; ignore anything outside the branch."""
        out: Dict[str, str] = {}
        for r in raw.get("resolutions") or []:
            if not isinstance(r, dict):
                continue
            intent, qualified = r.get("intent"), r.get("sub_intent") or ""
            if intent not in intents or intent not in kb.KNOWLEDGE_BASE:
                continue
            if not qualified.startswith(intent + "."):
                continue
            sub = qualified.split(".", 1)[1]
            if sub in kb.KNOWLEDGE_BASE[intent]["sub_intents"] or sub == kb.GENERAL:
                out.setdefault(intent, sub)
        return out

    @staticmethod
    def _intent_entry(i: Dict[str, Any], sub: Optional[str]) -> Dict[str, Any]:
        return {"intent": i["intent"], "sub_intent": sub, "confidence": round(i["confidence"], 3),
                "label": kb.intent_label(i["intent"])}

    @staticmethod
    def _fallback() -> RouteResult:
        return RouteResult(
            intents=[{"intent": kb.UNKNOWN, "sub_intent": None, "confidence": 0.0, "label": "Other"}],
            primary_intent=kb.UNKNOWN, bot_reply=SAFE_REPLY_DEFAULT, language="en",
            sentiment="neutral", urgency="low", risk_flags=[], needs_clarification=False,
            escalation=Escalation(), ui_options=kb.options_for(kb.UNKNOWN), degraded=True,
        )
