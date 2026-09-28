"""
SQLite persistence: sessions, turns, long-term summaries, human handoffs.

Only PII-REDACTED text is ever written here.

Why SQLite: zero infra for the POC, and the stdlib driver is fine for a
single API instance. Every query lives in this class, so moving to Postgres
means reimplementing one file (same schema; swap `?` for `%s`).

Concurrency: one short-lived connection per operation (thread-safe under
FastAPI's threadpool), WAL mode so readers don't block the writer.
"""

from __future__ import annotations

import json
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, Iterator, List, Optional

_SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    last_active_at  TEXT NOT NULL,
    ended_at        TEXT,
    -- none -> pending -> done. 'pending' is an atomic claim so two workers
    -- never summarize the same session; failures reset to 'none' for retry.
    summary_state   TEXT NOT NULL DEFAULT 'none'
);
CREATE INDEX IF NOT EXISTS ix_sessions_user ON sessions(user_id, summary_state);

CREATE TABLE IF NOT EXISTS turns (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    role        TEXT NOT NULL CHECK (role IN ('user', 'bot')),
    text        TEXT NOT NULL,            -- redacted
    meta        TEXT NOT NULL DEFAULT '{}',
    created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_turns_session ON turns(session_id, id);

CREATE TABLE IF NOT EXISTS summaries (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     TEXT NOT NULL,
    session_id  TEXT NOT NULL UNIQUE,
    summary     TEXT NOT NULL,            -- redacted
    intents     TEXT NOT NULL,            -- JSON list
    resolved    INTEGER NOT NULL,
    sentiment   TEXT NOT NULL,
    open_items  TEXT NOT NULL,            -- JSON list
    created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_summaries_user ON summaries(user_id, created_at);

CREATE TABLE IF NOT EXISTS handoffs (
    id          TEXT PRIMARY KEY,
    session_id  TEXT NOT NULL,
    user_id     TEXT NOT NULL,
    priority    TEXT NOT NULL,
    reasons     TEXT NOT NULL,            -- JSON list
    payload     TEXT NOT NULL,            -- JSON: context for the human agent
    status      TEXT NOT NULL DEFAULT 'open',
    created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_handoffs_session ON handoffs(session_id, status);
"""


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")


class MemoryStore:
    def __init__(self, db_path: str):
        self.db_path = db_path
        with self._conn() as c:
            c.execute("PRAGMA journal_mode=WAL")
            c.executescript(_SCHEMA)

    @contextmanager
    def _conn(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.db_path, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    # ---- sessions -----------------------------------------------------------

    def create_session(self, user_id: str) -> str:
        sid, now = uuid.uuid4().hex, _iso(_now())
        with self._conn() as c:
            c.execute("INSERT INTO sessions (id, user_id, created_at, last_active_at) VALUES (?,?,?,?)",
                      (sid, user_id, now, now))
        return sid

    def get_session(self, session_id: str) -> Optional[Dict[str, Any]]:
        with self._conn() as c:
            row = c.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
        return dict(row) if row else None

    def touch_session(self, session_id: str) -> None:
        with self._conn() as c:
            c.execute("UPDATE sessions SET last_active_at=? WHERE id=?", (_iso(_now()), session_id))

    def end_session(self, session_id: str) -> None:
        with self._conn() as c:
            c.execute("UPDATE sessions SET ended_at=COALESCE(ended_at, ?) WHERE id=?",
                      (_iso(_now()), session_id))

    @staticmethod
    def is_idle(session: Dict[str, Any], idle_minutes: int) -> bool:
        last = datetime.fromisoformat(session["last_active_at"])
        return _now() - last > timedelta(minutes=idle_minutes)

    def sessions_to_summarize(self, user_id: Optional[str], idle_minutes: int,
                              exclude: Optional[str] = None) -> List[str]:
        """Ended or idle sessions that haven't been summarized. user_id=None
        scans everyone (used by the periodic sweeper)."""
        cutoff = _iso(_now() - timedelta(minutes=idle_minutes))
        sql = ("SELECT id FROM sessions WHERE summary_state='none' "
               "AND (ended_at IS NOT NULL OR last_active_at < ?)")
        args: List[Any] = [cutoff]
        if user_id is not None:
            sql += " AND user_id=?"
            args.append(user_id)
        if exclude:
            sql += " AND id<>?"
            args.append(exclude)
        with self._conn() as c:
            return [r["id"] for r in c.execute(sql + " LIMIT 200", args)]

    def claim_for_summary(self, session_id: str) -> bool:
        """Atomically move none -> pending. False if someone else got it."""
        with self._conn() as c:
            cur = c.execute("UPDATE sessions SET summary_state='pending' "
                            "WHERE id=? AND summary_state='none'", (session_id,))
            return cur.rowcount == 1

    def set_summary_state(self, session_id: str, state: str) -> None:
        with self._conn() as c:
            c.execute("UPDATE sessions SET summary_state=? WHERE id=?", (state, session_id))

    # ---- turns --------------------------------------------------------------

    def add_turn(self, session_id: str, role: str, text: str, meta: Dict[str, Any]) -> int:
        with self._conn() as c:
            cur = c.execute("INSERT INTO turns (session_id, role, text, meta, created_at) VALUES (?,?,?,?,?)",
                            (session_id, role, text, json.dumps(meta), _iso(_now())))
            return int(cur.lastrowid)

    def turns(self, session_id: str, limit: Optional[int] = None) -> List[Dict[str, Any]]:
        """Chronological turns; with `limit`, only the most recent N."""
        with self._conn() as c:
            if limit:
                rows = c.execute("SELECT * FROM (SELECT * FROM turns WHERE session_id=? "
                                 "ORDER BY id DESC LIMIT ?) ORDER BY id", (session_id, limit)).fetchall()
            else:
                rows = c.execute("SELECT * FROM turns WHERE session_id=? ORDER BY id", (session_id,)).fetchall()
        out = []
        for r in rows:
            meta = json.loads(r["meta"] or "{}")
            out.append({"id": r["id"], "role": r["role"], "text": r["text"],
                        "created_at": r["created_at"], **meta})
        return out

    # ---- long-term memory -----------------------------------------------------

    def save_summary(self, user_id: str, session_id: str, data: Dict[str, Any]) -> None:
        with self._conn() as c:
            c.execute(
                "INSERT OR REPLACE INTO summaries "
                "(user_id, session_id, summary, intents, resolved, sentiment, open_items, created_at) "
                "VALUES (?,?,?,?,?,?,?,?)",
                (user_id, session_id, data["summary"], json.dumps(data["intents"]),
                 int(bool(data["resolved"])), data["sentiment_end"],
                 json.dumps(data["open_items"]), _iso(_now())))

    def recent_summaries(self, user_id: str, limit: int, retention_days: int) -> List[Dict[str, Any]]:
        """Newest-first within the retention window, returned oldest-first so
        the prompt reads chronologically."""
        cutoff = _iso(_now() - timedelta(days=retention_days))
        with self._conn() as c:
            rows = c.execute("SELECT * FROM summaries WHERE user_id=? AND created_at>=? "
                             "ORDER BY created_at DESC, id DESC LIMIT ?",
                             (user_id, cutoff, limit)).fetchall()
        return [{"session_id": r["session_id"], "summary": r["summary"],
                 "intents": json.loads(r["intents"]), "resolved": bool(r["resolved"]),
                 "sentiment": r["sentiment"], "open_items": json.loads(r["open_items"]),
                 "created_at": r["created_at"]} for r in reversed(rows)]

    def delete_summaries(self, user_id: str) -> int:
        """User-initiated "forget me" for personalization (privacy right)."""
        with self._conn() as c:
            return c.execute("DELETE FROM summaries WHERE user_id=?", (user_id,)).rowcount

    def purge_expired(self, retention_days: int) -> int:
        cutoff = _iso(_now() - timedelta(days=retention_days))
        with self._conn() as c:
            return c.execute("DELETE FROM summaries WHERE created_at<?", (cutoff,)).rowcount

    # ---- handoffs -------------------------------------------------------------

    def open_handoff(self, session_id: str) -> Optional[Dict[str, Any]]:
        with self._conn() as c:
            row = c.execute("SELECT * FROM handoffs WHERE session_id=? AND status='open' "
                            "ORDER BY created_at DESC LIMIT 1", (session_id,)).fetchone()
        return dict(row) if row else None

    def create_handoff(self, session_id: str, user_id: str, priority: str,
                       reasons: List[str], payload: Dict[str, Any]) -> str:
        hid = "HO-" + uuid.uuid4().hex[:10].upper()
        with self._conn() as c:
            c.execute("INSERT INTO handoffs (id, session_id, user_id, priority, reasons, payload, created_at) "
                      "VALUES (?,?,?,?,?,?,?)",
                      (hid, session_id, user_id, priority, json.dumps(reasons),
                       json.dumps(payload), _iso(_now())))
        return hid

    def update_handoff(self, handoff_id: str, priority: str, reasons: List[str]) -> None:
        with self._conn() as c:
            c.execute("UPDATE handoffs SET priority=?, reasons=? WHERE id=?",
                      (priority, json.dumps(reasons), handoff_id))
