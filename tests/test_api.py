"""End-to-end over HTTP with the mock backend and a temp SQLite DB."""

import sqlite3

import pytest
from fastapi.testclient import TestClient

from support_router.api import create_app


def headers(user="alice", key="test-key"):
    return {"X-API-Key": key, "X-User-Id": user}


@pytest.fixture
def client(settings):
    with TestClient(create_app(settings)) as c:
        c.settings = settings
        yield c


def chat(client, message, session_id=None, user="alice"):
    body = {"message": message}
    if session_id:
        body["session_id"] = session_id
    r = client.post("/v1/chat", json=body, headers=headers(user))
    assert r.status_code == 200, r.text
    return r.json()


def test_auth_required(client):
    assert client.post("/v1/chat", json={"message": "hi"}).status_code == 422
    assert client.post("/v1/chat", json={"message": "hi"}, headers=headers(key="nope")).status_code == 401


def test_chat_contract(client):
    r = chat(client, "i got chraged twice, want a refnd")
    assert r["new_session"] and len(r["session_id"]) == 32
    assert r["intent"] == "billing_and_refunds"
    assert r["intents"][0]["sub_intent"] == "double_charge"
    assert all({"id", "label", "intent"} <= set(o) for o in r["ui_options_to_render"])
    assert r["escalation"]["escalate"] is False


def test_multi_turn_session_keeps_context(client):
    first = chat(client, "the app keeps crashing")
    second = chat(client, "still doesn't open", first["session_id"])
    assert second["session_id"] == first["session_id"] and not second["new_session"]
    assert second["intent"] == "technical_support"


def test_clarify_then_answer_flow(client):
    q = chat(client, "I have a problem with my order")
    assert q["needs_clarification"]
    tapped = next(o for o in q["ui_options_to_render"] if o["id"] == "clarify.billing_and_refunds")
    a = chat(client, tapped["label"], q["session_id"])  # app sends the label
    assert a["intent"] == "billing_and_refunds" and not a["needs_clarification"]


def test_cannot_use_another_users_session(client):
    s = chat(client, "refund please", user="alice")["session_id"]
    r = client.post("/v1/chat", json={"message": "hi", "session_id": s}, headers=headers("mallory"))
    assert r.status_code == 404
    assert client.get(f"/v1/sessions/{s}", headers=headers("mallory")).status_code == 404


def test_pii_never_stored(client):
    s = chat(client, "card 4111 1111 1111 1111 was stolen, email me at a@b.com")["session_id"]
    turns = client.get(f"/v1/sessions/{s}", headers=headers()).json()["turns"]
    user_text = turns[0]["text"]
    assert "[CARD]" in user_text and "[EMAIL]" in user_text and "4111" not in user_text
    raw = sqlite3.connect(client.settings.db_path).execute("SELECT group_concat(text) FROM turns").fetchone()[0]
    assert "4111" not in raw and "a@b.com" not in raw


def test_escalation_creates_one_handoff_per_session(client):
    s = chat(client, "my card was stolen, block it")
    assert s["escalation"]["escalate"] and s["escalation"]["handoff_id"].startswith("HO-")
    again = chat(client, "i want a real person now", s["session_id"])
    assert again["escalation"]["handoff_id"] == s["escalation"]["handoff_id"]


def test_end_session_summarizes_into_long_term_memory(client):
    s = chat(client, "charged twice for my subscription")["session_id"]
    assert client.post(f"/v1/sessions/{s}/end", headers=headers()).status_code == 202
    mem = client.get("/v1/me/memory", headers=headers()).json()
    assert len(mem) == 1 and mem[0]["intents"] == ["billing_and_refunds"] and not mem[0]["resolved"]
    # Ended session can't be reused: server rotates to a new one.
    nxt = chat(client, "hello again", s)
    assert nxt["new_session"] and nxt["session_id"] != s


def test_memory_drives_repeat_contact_escalation(client):
    for _ in range(2):
        s = chat(client, "charged twice again")["session_id"]
        client.post(f"/v1/sessions/{s}/end", headers=headers())
    r = chat(client, "charged twice AGAIN")
    assert "repeat_contact" in r["escalation"]["reasons"]
    # Memory is per user: bob with the same message is not a repeat contact.
    assert chat(client, "charged twice AGAIN", user="bob")["escalation"]["escalate"] is False


def test_idle_session_rotates_and_is_summarized(client):
    s = chat(client, "my package never arrived")["session_id"]
    db = sqlite3.connect(client.settings.db_path)
    db.execute("UPDATE sessions SET last_active_at='2000-01-01T00:00:00+00:00' WHERE id=?", (s,))
    db.commit()
    nxt = chat(client, "hi", s)
    assert nxt["new_session"]
    assert [m["session_id"] for m in client.get("/v1/me/memory", headers=headers()).json()] == [s]


def test_forget_me(client):
    s = chat(client, "refund please")["session_id"]
    client.post(f"/v1/sessions/{s}/end", headers=headers())
    assert client.delete("/v1/me/memory", headers=headers()).json() == {"deleted": 1}
    assert client.get("/v1/me/memory", headers=headers()).json() == []


def test_rate_limit_returns_429_with_retry_after(settings):
    settings.rate_limit_per_minute, settings.rate_limit_burst = 1, 0
    with TestClient(create_app(settings)) as c:
        assert c.post("/v1/chat", json={"message": "hi"}, headers=headers()).status_code == 200
        r = c.post("/v1/chat", json={"message": "hi"}, headers=headers())
        assert r.status_code == 429 and int(r.headers["Retry-After"]) >= 1


def test_whitespace_message_greets_without_llm(client):
    r = chat(client, "   \n\t ")
    assert r["intent"] == "unknown" and "help" in r["bot_reply"].lower()


def test_invalid_session_id_format_rejected(client):
    r = client.post("/v1/chat", json={"message": "hi", "session_id": "../../etc"}, headers=headers())
    assert r.status_code == 422
