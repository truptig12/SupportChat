"""Router logic against the mock backend plus scripted fake backends."""

import pytest

from support_router import knowledge_base as kb
from support_router.guardrails import SAFE_REPLY_BY_INTENT
from support_router.llm import MockBackend
from support_router.router import SupportRouter


@pytest.fixture
def router(settings):
    return SupportRouter(MockBackend(settings), settings)


def ids(result):
    return [o["id"] for o in result.ui_options]


def assert_buttons_from_kb(result):
    """Invariant: every button is a real KB/system option, never model text."""
    valid = {o["id"] for i in kb.KNOWLEDGE_BASE for o in kb.options_for(i)}
    valid |= {f"{i}.{k}" for i, n in kb.KNOWLEDGE_BASE.items() for k in n["options"]}
    valid |= {o["id"] for o in kb.UNKNOWN_OPTIONS} | {kb.HANDOFF_OPTION["id"], kb.CLARIFY_OTHER_OPTION["id"]}
    valid |= {f"clarify.{i}" for i in kb.KNOWLEDGE_BASE}
    assert set(ids(result)) <= valid


class ScriptedBackend:
    """Returns canned payloads (or raises) to exercise edge paths."""

    def __init__(self, classify=None, resolve=None):
        self._c, self._r = classify, resolve

    def classify(self, ctx):
        if isinstance(self._c, Exception):
            raise self._c
        return self._c

    def resolve(self, ctx, intents, language, sentiment, escalating):
        if isinstance(self._r, Exception):
            raise self._r
        return self._r

    def summarize(self, transcript):
        raise NotImplementedError


TRIAGE_BILLING = {"intents": [{"intent": "billing_and_refunds", "confidence": 0.9}], "language": "en",
                  "sentiment": "neutral", "urgency": "low", "risk_flags": [],
                  "needs_clarification": False, "clarifying_question": None}


# ---- basic routing + sub-intents (#5) ----------------------------------------

def test_billing_typo_routes_to_double_charge_subintent(router):
    r = router.route("i got chraged twice for my subscribtion, want a refnd", [], [])
    assert r.primary_intent == "billing_and_refunds"
    assert r.intents[0]["sub_intent"] == "double_charge"
    assert ids(r) == [o["id"] for o in kb.options_for("billing_and_refunds", "double_charge")]
    assert_buttons_from_kb(r)


def test_off_topic_is_unknown_with_human_path(router):
    r = router.route("what's a good banana bread recipe", [], [])
    assert r.primary_intent == kb.UNKNOWN
    assert "unknown.human" in ids(r)


# ---- multi-intent (#9) -----------------------------------------------------------

def test_multi_intent_merges_buttons_from_both_branches(router):
    r = router.route("my package never arrived and i was still charged for it", [], [])
    names = [i["intent"] for i in r.intents]
    assert set(names) == {"shipping_and_delivery", "billing_and_refunds"}
    assert {o["intent"] for o in r.ui_options} == set(names)
    assert len(r.ui_options) <= 6


def test_fraud_is_always_primary(router):
    r = router.route("there's a charge from a store i've never heard of, and my bill is wrong", [], [])
    assert r.primary_intent == "fraud_and_security"


# ---- clarification (#1) --------------------------------------------------------

def test_ambiguous_message_asks_clarifying_question(router):
    r = router.route("I have a problem with my order", [], [])
    assert r.needs_clarification
    assert r.bot_reply.endswith("?")
    assert ids(r)[-1] == "clarify.other"
    assert all(i.startswith("clarify.") for i in ids(r))


def test_never_clarifies_twice_in_a_row(router):
    history = [{"role": "user", "text": "problem with my order"},
               {"role": "bot", "text": "Which one?", "needs_clarification": True}]
    r = router.route("I have a problem with my order", history, [])
    assert not r.needs_clarification


def test_low_confidence_triggers_clarification(settings):
    triage = dict(TRIAGE_BILLING, intents=[{"intent": "billing_and_refunds", "confidence": 0.3}])
    r = SupportRouter(ScriptedBackend(triage, None), settings).route("hmm money thing", [], [])
    assert r.needs_clarification


def test_urgent_cases_are_never_stalled_by_clarification(settings):
    triage = dict(TRIAGE_BILLING, intents=[{"intent": "fraud_and_security", "confidence": 0.3}],
                  urgency="high", needs_clarification=True)
    resolve = {"resolutions": [], "bot_reply": "Let's secure your account."}
    r = SupportRouter(ScriptedBackend(triage, resolve), settings).route("weird login??", [], [])
    assert not r.needs_clarification


# ---- multi-turn memory (#2) ---------------------------------------------------------

def test_short_follow_up_carries_intent_from_history(router):
    history = [{"role": "user", "text": "app keeps crashing", "intents": ["technical_support"]},
               {"role": "bot", "text": "Sorry!", "intents": ["technical_support"]}]
    r = router.route("still doesn't open", history, [])
    assert r.primary_intent == "technical_support"


# ---- escalation (#6) -------------------------------------------------------------

def test_stolen_card_escalates_urgent_but_freeze_button_stays_first(router):
    r = router.route("my credit card was stolen from my wallet, block it", [], [])
    assert r.escalation.escalate and r.escalation.priority == "urgent"
    assert ids(r)[0] == "fraud_and_security.freeze_card"
    assert ids(r)[1] == kb.HANDOFF_OPTION["id"]
    assert "fraud_and_security.fraud_team" not in ids(r)  # redundant with handoff


def test_persistent_anger_escalates(router):
    history = [{"role": "user", "text": "charged again", "sentiment": "negative"}]
    r = router.route("WHY DID YOU CHARGE ME AGAIN?!?! THIRD TIME", history, [])
    assert r.escalation.escalate and "persistent_anger" in r.escalation.reasons
    assert ids(r)[0] == kb.HANDOFF_OPTION["id"]


def test_single_angry_message_does_not_escalate(router):
    r = router.route("WHY DID YOU CHARGE ME AGAIN?!?!", [], [])
    assert not r.escalation.escalate


def test_legal_threat_escalates_high(router):
    r = router.route("refund my double charge or my lawyer will sue you", [], [])
    assert r.escalation.priority == "high" and "legal_threat" in r.escalation.reasons


def test_repeat_contact_from_long_term_memory_escalates(router):
    memory = [{"summary": "Billing issue", "intents": ["billing_and_refunds"], "resolved": False}] * 2
    r = router.route("charged twice again", [], memory)
    assert "repeat_contact" in r.escalation.reasons


# ---- guardrails & degradation (#16) -------------------------------------------------

def test_stage1_failure_degrades_to_safe_unknown(settings):
    r = SupportRouter(ScriptedBackend(RuntimeError("timeout")), settings).route("hi", [], [])
    assert r.degraded and r.primary_intent == kb.UNKNOWN and "unknown.human" in ids(r)


def test_stage2_failure_keeps_intent_buttons(settings):
    r = SupportRouter(ScriptedBackend(TRIAGE_BILLING, RuntimeError("5xx")), settings).route("refund pls", [], [])
    assert r.degraded and r.primary_intent == "billing_and_refunds"
    assert r.bot_reply == SAFE_REPLY_BY_INTENT["billing_and_refunds"]
    assert ids(r) == [o["id"] for o in kb.options_for("billing_and_refunds")]


def test_malformed_triage_is_normalized(settings):
    bad = {"intents": [{"intent": "hacker_intent", "confidence": 1},
                       {"intent": "billing_and_refunds", "confidence": "abc"}],
           "sentiment": "furious", "urgency": "???", "risk_flags": ["made_up"]}
    resolve = {"resolutions": [], "bot_reply": "ok"}
    r = SupportRouter(ScriptedBackend(bad, resolve), settings).route("x", [], [])
    # confidence "abc" -> 0.0 -> clarification; enums coerced to defaults
    assert r.primary_intent == "billing_and_refunds"
    assert (r.sentiment, r.urgency, r.risk_flags) == ("neutral", "low", [])


def test_subintent_from_other_branch_is_ignored(settings):
    resolve = {"resolutions": [{"intent": "billing_and_refunds",
                                "sub_intent": "fraud_and_security.card_lost_stolen"}],
               "bot_reply": "ok"}
    r = SupportRouter(ScriptedBackend(TRIAGE_BILLING, resolve), settings).route("x", [], [])
    assert r.intents[0]["sub_intent"] is None
    assert_buttons_from_kb(r)


def test_model_promise_is_replaced(settings):
    resolve = {"resolutions": [], "bot_reply": "Done! I've refunded you, visit http://x.co"}
    r = SupportRouter(ScriptedBackend(TRIAGE_BILLING, resolve), settings).route("refund", [], [])
    assert r.bot_reply == SAFE_REPLY_BY_INTENT["billing_and_refunds"]
    assert "unauthorized_promise" in r.guardrail_events
