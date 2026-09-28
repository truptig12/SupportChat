"""Mock ServiceNow endpoints: service status + incidents."""

import sqlite3

import pytest
from fastapi.testclient import TestClient

from support_router.api import create_app


def headers(user="anon-device-1"):
    return {"X-API-Key": "test-key", "X-User-Id": user}


ADMIN = {"X-Admin-Key": "test-admin"}

INCIDENT = {
    "issue": "Security code (OTP) not received",
    "journey_stage": "Login - OTP verification",
    "error_code": "OTP-2001",
    "steps_tried": ["Waited 2 minutes before requesting another code"],
    "context": {"app_version": "1.0 (debug)", "device": "Samsung Galaxy S24 · Android 15",
                "network": "Mobile data", "attempts": 2},
    "service_status": "No known outages",
    "suggested_cause": "Possible OTP delivery issue",
    "customer_reference": "John123",
    "message": "call me on +44 7700 900123",
    "confirmed": True,
}


@pytest.fixture
def client(settings):
    settings.admin_api_key = "test-admin"
    settings.incident_progress_seconds = 120
    with TestClient(create_app(settings)) as c:
        c.settings = settings
        yield c


def test_service_status_defaults_to_operational(client):
    r = client.get("/v1/service-status", headers=headers())
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "operational" and body["affects"] == [] and body["checked_at"]


def test_admin_can_toggle_known_issue(client):
    assert client.put("/v1/admin/service-status", json={"status": "known_issue"},
                      headers={"X-Admin-Key": "wrong"}).status_code == 401
    r = client.put("/v1/admin/service-status", headers=ADMIN,
                   json={"status": "known_issue", "message": "Login delays for some customers"})
    assert r.status_code == 200
    s = client.get("/v1/service-status", headers=headers()).json()
    assert s["status"] == "known_issue" and s["affects"] == ["login"]
    assert s["message"] == "Login delays for some customers"
    assert client.put("/v1/admin/service-status", headers=ADMIN,
                      json={"status": "broken"}).status_code == 422


def test_create_incident_returns_reference_and_timeline(client):
    r = client.post("/v1/incidents", json=INCIDENT, headers=headers())
    assert r.status_code == 201, r.text
    inc = r.json()
    assert inc["reference"].startswith("INC-1000")
    assert inc["status"] == "received"
    assert [t["key"] for t in inc["timeline"]] == ["received", "under_investigation", "updates"]
    assert [t["done"] for t in inc["timeline"]] == [True, False, False]
    # PII typed into the editable summary is redacted before storage.
    assert "[PHONE]" in inc["summary"]["message"]
    raw = sqlite3.connect(client.settings.db_path).execute("SELECT data FROM incidents").fetchone()[0]
    assert "7700" not in raw


def test_incident_requires_confirmation(client):
    r = client.post("/v1/incidents", json=dict(INCIDENT, confirmed=False), headers=headers())
    assert r.status_code == 422


def test_incident_progresses_over_time(client):
    ref = client.post("/v1/incidents", json=INCIDENT, headers=headers()).json()["reference"]
    db = sqlite3.connect(client.settings.db_path)
    db.execute("UPDATE incidents SET created_at='2000-01-01T00:00:00+00:00' WHERE reference=?", (ref,))
    db.commit()
    inc = client.get(f"/v1/incidents/{ref}", headers=headers()).json()
    assert inc["status"] == "under_investigation"
    assert [t["done"] for t in inc["timeline"]] == [True, True, False]


def test_incident_is_private_to_its_creator(client):
    ref = client.post("/v1/incidents", json=INCIDENT, headers=headers("a")).json()["reference"]
    assert client.get(f"/v1/incidents/{ref}", headers=headers("b")).status_code == 404
    assert client.get("/v1/incidents/INC-999999", headers=headers("a")).status_code == 404


def test_references_are_sequential(client):
    refs = [client.post("/v1/incidents", json=INCIDENT, headers=headers()).json()["reference"] for _ in range(2)]
    assert int(refs[1][4:]) == int(refs[0][4:]) + 1
