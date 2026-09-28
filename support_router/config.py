"""
Settings, read from the environment once at startup.

Every knob that ops might tune without a code change lives here. Tests
construct their own `Settings(...)` instead of patching env vars.

Secrets (OPENAI_API_KEY, APP_API_KEY) go in a `.env` file at the project
root (copy `.env.example`). Real environment variables win over `.env`, so
production can inject secrets from its secret manager instead.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

try:
    from dotenv import load_dotenv

    # Project root = parent of the support_router/ package. override=False:
    # an already-set env var (prod secret manager, CI, tests) always wins.
    load_dotenv(Path(__file__).resolve().parent.parent / ".env", override=False)
except ImportError:  # python-dotenv is optional; plain env vars still work
    pass


def _env_int(name: str, default: int) -> int:
    return int(os.getenv(name, default))


def _env_float(name: str, default: float) -> float:
    return float(os.getenv(name, default))


@dataclass
class Settings:
    # --- LLM -------------------------------------------------------------
    # Empty key => offline mock backend (demos, CI). Never ship that to prod.
    openai_api_key: str = field(default_factory=lambda: os.getenv("OPENAI_API_KEY", ""))
    # Stage 1 (coarse routing) runs every turn: use a fast/cheap model.
    # Stage 2 (sub-intent + reply) and summaries can use a stronger one.
    classify_model: str = field(default_factory=lambda: os.getenv("OPENAI_CLASSIFY_MODEL", os.getenv("OPENAI_MODEL", "gpt-4o-mini")))
    resolve_model: str = field(default_factory=lambda: os.getenv("OPENAI_RESOLVE_MODEL", os.getenv("OPENAI_MODEL", "gpt-4o-mini")))
    summary_model: str = field(default_factory=lambda: os.getenv("OPENAI_SUMMARY_MODEL", os.getenv("OPENAI_MODEL", "gpt-4o-mini")))
    llm_timeout_seconds: float = field(default_factory=lambda: _env_float("LLM_TIMEOUT_SECONDS", 20.0))
    llm_max_retries: int = field(default_factory=lambda: _env_int("LLM_MAX_RETRIES", 2))

    # --- Routing behaviour -------------------------------------------------
    # Below this top-intent confidence we ask a clarifying question instead
    # of guessing (unless urgency is high/critical — never stall on fraud).
    clarify_below_confidence: float = field(default_factory=lambda: _env_float("CLARIFY_BELOW_CONFIDENCE", 0.55))
    # Secondary intents need at least this confidence to be acted on.
    secondary_intent_min_confidence: float = field(default_factory=lambda: _env_float("SECONDARY_INTENT_MIN_CONFIDENCE", 0.5))
    max_intents_per_turn: int = field(default_factory=lambda: _env_int("MAX_INTENTS_PER_TURN", 3))
    max_buttons: int = field(default_factory=lambda: _env_int("MAX_BUTTONS", 6))

    # --- Memory --------------------------------------------------------------
    db_path: str = field(default_factory=lambda: os.getenv("DB_PATH", "support_router.db"))
    history_turns_in_context: int = field(default_factory=lambda: _env_int("HISTORY_TURNS_IN_CONTEXT", 8))
    summaries_in_context: int = field(default_factory=lambda: _env_int("SUMMARIES_IN_CONTEXT", 3))
    memory_retention_days: int = field(default_factory=lambda: _env_int("MEMORY_RETENTION_DAYS", 180))
    # A session untouched this long is closed + summarized on the next request.
    session_idle_minutes: int = field(default_factory=lambda: _env_int("SESSION_IDLE_MINUTES", 30))
    # Same intent unresolved in >= N past sessions => "repeat contact" escalation.
    repeat_contact_threshold: int = field(default_factory=lambda: _env_int("REPEAT_CONTACT_THRESHOLD", 2))

    # --- Guardrails ------------------------------------------------------------
    max_message_chars: int = field(default_factory=lambda: _env_int("MAX_MESSAGE_CHARS", 2000))
    rate_limit_per_minute: int = field(default_factory=lambda: _env_int("RATE_LIMIT_PER_MINUTE", 20))
    rate_limit_burst: int = field(default_factory=lambda: _env_int("RATE_LIMIT_BURST", 5))

    # --- Auth (DEV ONLY — see api.get_current_user) ---------------------------
    app_api_key: str = field(default_factory=lambda: os.getenv("APP_API_KEY", "dev-key-change-me"))
    # Protects the dev-only mock admin endpoints (e.g. toggling service status).
    admin_api_key: str = field(default_factory=lambda: os.getenv("ADMIN_API_KEY", "dev-admin-key-change-me"))

    # --- Mock integrations (stand-ins for ServiceNow) ------------------------
    # Initial service status: "operational" or "known_issue".
    service_status: str = field(default_factory=lambda: os.getenv("SERVICE_STATUS", "operational"))
    service_status_message: str = field(default_factory=lambda: os.getenv(
        "SERVICE_STATUS_MESSAGE", "We're aware of a current issue affecting login."))
    # Mock incident lifecycle: "received" -> "under_investigation" after this long.
    incident_progress_seconds: int = field(default_factory=lambda: _env_int("INCIDENT_PROGRESS_SECONDS", 120))

    @property
    def use_real_llm(self) -> bool:
        return bool(self.openai_api_key)
