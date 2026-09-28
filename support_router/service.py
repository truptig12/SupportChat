"""
ChatService: the use-case layer between HTTP and the router.

Owns session lifecycle, redaction-before-persistence, handoff tickets and
long-term summaries. The FastAPI layer stays a thin adapter, and this class
is testable without HTTP.

Session lifecycle & long-term memory:
    - A session ends explicitly (POST /end), or implicitly after
      SESSION_IDLE_MINUTES of inactivity.
    - Ended/idle sessions are summarized (redacted, <=60 words, with intents
      + resolved flag) and stored per user.
    - Every new turn gets the user's last N summaries as context, so the bot
      can say "I see you contacted us about this before" and the escalation
      policy can detect repeat contacts.
    - Summaries are produced in the background (never on the request path),
      triggered by: /end, the user returning after idle, and a periodic sweep.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from . import knowledge_base as kb
from .config import Settings
from .guardrails import redact_pii, sanitize_text
from .llm import LLMBackend, SENTIMENTS, hash_user_id
from .memory import MemoryStore
from .router import Escalation, RouteResult, SupportRouter

log = logging.getLogger("support_router.service")

GREETING = "Hi there! What can I help you with today?"


class SessionNotFound(Exception):
    """Unknown session, or one that belongs to another user (same error on
    purpose: don't leak which session IDs exist)."""


@dataclass
class ChatOutcome:
    session_id: str
    new_session: bool
    turn_id: int
    result: RouteResult
    handoff_id: Optional[str] = None
    # Sessions the caller should summarize in the background.
    to_summarize: List[str] = field(default_factory=list)


class ChatService:
    def __init__(self, store: MemoryStore, backend: LLMBackend, settings: Settings):
        self.store = store
        self.backend = backend
        self.router = SupportRouter(backend, settings)
        self.s = settings

    # ------------------------------------------------------------------------
    # Chat turn
    # ------------------------------------------------------------------------

    def handle_message(self, user_id: str, session_id: Optional[str], raw_message: Any) -> ChatOutcome:
        session_id, new_session, to_summarize = self._resolve_session(user_id, session_id)

        text = sanitize_text(raw_message, self.s.max_message_chars)
        redacted, pii = redact_pii(text)

        if not redacted:
            # Nothing to route; don't spend an LLM call.
            result = SupportRouter._fallback()
            result.bot_reply, result.degraded = GREETING, False
        else:
            history = self.store.turns(session_id, limit=self.s.history_turns_in_context)
            memory = self.store.recent_summaries(user_id, self.s.summaries_in_context,
                                                 self.s.memory_retention_days)
            result = self.router.route(redacted, history, memory, user_ref=hash_user_id(user_id))

        intent_names = [i["intent"] for i in result.intents]
        if redacted:
            self.store.add_turn(session_id, "user", redacted, {
                "intents": intent_names, "sentiment": result.sentiment,
                "urgency": result.urgency, "pii_redacted": sorted(pii),
            })
        turn_id = self.store.add_turn(session_id, "bot", result.bot_reply, {
            "intents": intent_names,
            "needs_clarification": result.needs_clarification,
            "escalation": result.escalation.__dict__,
            "ui_options": result.ui_options,
            "guardrail_events": result.guardrail_events,
            "degraded": result.degraded,
        })

        handoff_id = self._maybe_handoff(user_id, session_id, result) if result.escalation.escalate else None
        self.store.touch_session(session_id)

        # Structured audit line — IDs and labels only, never message text.
        log.info("turn session=%s intents=%s clarify=%s escalate=%s priority=%s pii=%s events=%s",
                 session_id, intent_names, result.needs_clarification, result.escalation.escalate,
                 result.escalation.priority, sorted(pii), result.guardrail_events)

        return ChatOutcome(session_id=session_id, new_session=new_session, turn_id=turn_id,
                           result=result, handoff_id=handoff_id, to_summarize=to_summarize)

    def _resolve_session(self, user_id: str, session_id: Optional[str]):
        """Returns (session_id, is_new, sessions_to_summarize)."""
        to_summarize: List[str] = []
        if session_id:
            sess = self.store.get_session(session_id)
            if not sess or sess["user_id"] != user_id:
                raise SessionNotFound(session_id)
            if sess["ended_at"] is None and not self.store.is_idle(sess, self.s.session_idle_minutes):
                return session_id, False, to_summarize
            # Ended or idle: close it out and transparently start fresh. The
            # client must always adopt the session_id from the response.
            self.store.end_session(session_id)

        new_id = self.store.create_session(user_id)
        # Any of this user's finished-but-unsummarized sessions (including the
        # one just closed) get summarized now, so they're in memory next turn.
        to_summarize = self.store.sessions_to_summarize(user_id, self.s.session_idle_minutes, exclude=new_id)
        return new_id, True, to_summarize

    def _maybe_handoff(self, user_id: str, session_id: str, result: RouteResult) -> str:
        """One open ticket per session; later turns can only raise its priority."""
        esc: Escalation = result.escalation
        existing = self.store.open_handoff(session_id)
        if existing:
            rank = {"normal": 0, "high": 1, "urgent": 2}
            reasons = sorted(set(json.loads(existing["reasons"])) | set(esc.reasons))
            priority = max(existing["priority"], esc.priority or "normal", key=rank.__getitem__)
            self.store.update_handoff(existing["id"], priority, reasons)
            return existing["id"]

        # Everything the human agent needs so the customer never repeats
        # themselves. All text is already redacted.
        payload = {
            "intents": result.intents,
            "urgency": result.urgency,
            "sentiment": result.sentiment,
            "risk_flags": result.risk_flags,
            "language": result.language,
            "transcript": [{"role": t["role"], "text": t["text"], "at": t["created_at"]}
                           for t in self.store.turns(session_id, limit=20)],
            "customer_history": self.store.recent_summaries(user_id, self.s.summaries_in_context,
                                                            self.s.memory_retention_days),
        }
        hid = self.store.create_handoff(session_id, user_id, esc.priority or "normal", esc.reasons, payload)
        log.warning("handoff created id=%s priority=%s reasons=%s", hid, esc.priority, esc.reasons)
        return hid

    # ------------------------------------------------------------------------
    # Session end + summaries (long-term memory)
    # ------------------------------------------------------------------------

    def end_session(self, user_id: str, session_id: str) -> None:
        sess = self.store.get_session(session_id)
        if not sess or sess["user_id"] != user_id:
            raise SessionNotFound(session_id)
        self.store.end_session(session_id)

    def summarize_session(self, session_id: str) -> Optional[Dict[str, Any]]:
        """Idempotent and safe to call concurrently (atomic claim). Runs in a
        background task, so it logs instead of raising."""
        if not self.store.claim_for_summary(session_id):
            return None
        try:
            sess = self.store.get_session(session_id)
            turns = self.store.turns(session_id)
            if not sess or not any(t["role"] == "user" for t in turns):
                self.store.set_summary_state(session_id, "done")  # nothing to remember
                return None

            transcript = [{"role": t["role"], "text": t["text"], "intents": t.get("intents")}
                          for t in turns[-40:]]  # cap prompt size for very long chats
            data = self._validate_summary(self.backend.summarize(transcript))
            self.store.save_summary(sess["user_id"], session_id, data)
            self.store.set_summary_state(session_id, "done")
            log.info("summarized session=%s intents=%s resolved=%s",
                     session_id, data["intents"], data["resolved"])
            return data
        except Exception:  # noqa: BLE001
            log.exception("summary failed session=%s; will retry on next sweep", session_id)
            self.store.set_summary_state(session_id, "none")
            return None

    @staticmethod
    def _validate_summary(raw: Dict[str, Any]) -> Dict[str, Any]:
        summary = raw.get("summary") if isinstance(raw.get("summary"), str) else ""
        # Redact again: the model was told not to include PII, but verify.
        summary, _ = redact_pii(summary.strip()[:600])
        if not summary:
            raise ValueError("empty summary")
        intents = [i for i in (raw.get("intents") or []) if i in kb.KNOWLEDGE_BASE] or [kb.UNKNOWN]
        open_items = [redact_pii(str(o))[0][:200] for o in (raw.get("open_items") or [])][:5]
        return {
            "summary": summary,
            "intents": list(dict.fromkeys(intents)),
            "resolved": bool(raw.get("resolved")),
            "sentiment_end": raw.get("sentiment_end") if raw.get("sentiment_end") in SENTIMENTS else "neutral",
            "open_items": open_items,
        }

    def sweep(self) -> int:
        """Periodic job: summarize every idle/ended session, purge expired
        memory. Safe to run on every replica (claims are atomic)."""
        done = 0
        for sid in self.store.sessions_to_summarize(None, self.s.session_idle_minutes):
            if self.summarize_session(sid):
                done += 1
        purged = self.store.purge_expired(self.s.memory_retention_days)
        if done or purged:
            log.info("sweep summarized=%d purged=%d", done, purged)
        return done
