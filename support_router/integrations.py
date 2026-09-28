"""
Mock stand-ins for ServiceNow: service status and incidents.

These exist so the mobile journey (check known issues -> troubleshoot ->
raise incident -> track it) works end to end today. Each class has the same
shape a real ServiceNow client would have, so swapping in the real thing
means reimplementing these two classes, not the API or the app.

  ServiceStatusProvider  current platform status ("operational" | "known_issue")
  IncidentService        create + read incidents with a mock lifecycle
"""

from __future__ import annotations

import threading
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

from .guardrails import redact_pii
from .memory import MemoryStore

STATUSES = ("operational", "known_issue")


class ServiceStatusProvider:
    """
    In-memory, per-process status. Real version: query ServiceNow's
    outage / major-incident table for services tagged "login".
    """

    def __init__(self, status: str, message: str):
        self._lock = threading.Lock()
        self._state: Dict[str, Any] = {}
        self.set(status, message, ["login"])

    def get(self) -> Dict[str, Any]:
        with self._lock:
            return dict(self._state, checked_at=_now_iso())

    def set(self, status: str, message: Optional[str], affects: List[str]) -> Dict[str, Any]:
        if status not in STATUSES:
            raise ValueError(f"status must be one of {STATUSES}")
        with self._lock:
            self._state = {
                "status": status,
                "title": "No known outages" if status == "operational" else "Known issue",
                "message": ("Our systems are running normally." if status == "operational"
                            else (message or "We're aware of a current issue.")),
                "affects": [] if status == "operational" else affects,
            }
        return self.get()


class IncidentService:
    """
    Incidents raised from the app. Real version: POST to ServiceNow's
    incident table and read its state back.

    Mock lifecycle (time-based so a demo progresses by itself):
        received  -> under_investigation after `progress_seconds`
    """

    TIMELINE = [
        ("received", "Incident received"),
        ("under_investigation", "Under investigation"),
        ("updates", "We'll keep you updated"),
    ]

    def __init__(self, store: MemoryStore, progress_seconds: int):
        self.store = store
        self.progress_seconds = progress_seconds

    def create(self, user_id: str, data: Dict[str, Any]) -> Dict[str, Any]:
        # Everything user-editable is redacted before storage (the summary is
        # editable in the app, so users can type anything into it).
        clean = _redact_tree(data)
        ref = self.store.create_incident(user_id, clean)
        return self.get(user_id, ref)

    def get(self, user_id: str, reference: str) -> Optional[Dict[str, Any]]:
        row = self.store.get_incident(reference)
        if not row or row["user_id"] != user_id:
            return None
        created = datetime.fromisoformat(row["created_at"])
        progressed_at = created + timedelta(seconds=self.progress_seconds)
        progressed = _now() >= progressed_at
        status = "under_investigation" if progressed else "received"
        done = {"received": created, "under_investigation": progressed_at if progressed else None}
        timeline = [{"key": key, "label": label, "done": done.get(key) is not None,
                     "at": done[key].isoformat(timespec="seconds") if done.get(key) else None}
                    for key, label in self.TIMELINE]
        return {
            "reference": row["reference"],
            "status": status,
            "status_label": dict(self.TIMELINE)[status],
            "created_at": row["created_at"],
            "timeline": timeline,
            "summary": row["data"],
        }


def _redact_tree(value: Any) -> Any:
    if isinstance(value, str):
        return redact_pii(value)[0]
    if isinstance(value, list):
        return [_redact_tree(v) for v in value]
    if isinstance(value, dict):
        return {k: _redact_tree(v) for k, v in value.items()}
    return value


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _now_iso() -> str:
    return _now().isoformat(timespec="seconds")
