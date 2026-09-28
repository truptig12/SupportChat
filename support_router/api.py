"""
FastAPI service for the mobile app.

Run:
    uvicorn support_router.api:app --host 0.0.0.0 --port 8000
Docs (OpenAPI, share with the mobile team):
    http://localhost:8000/docs

Endpoints (all under /v1, all require auth headers):
    POST   /v1/chat                    send a message, get reply + buttons
    POST   /v1/sessions/{id}/end       close a chat (summary saved in background)
    GET    /v1/sessions/{id}           restore a chat's history (app relaunch)
    GET    /v1/me/memory               what the bot remembers about me
    DELETE /v1/me/memory               forget me (privacy)
    GET    /healthz                    liveness (no auth)

Mobile client contract:
    - Omit session_id on the first message; ALWAYS store the session_id from
      each response and send it next time (the server may rotate it after an
      idle timeout — `new_session: true` tells you that happened).
    - Render `bot_reply` + `ui_options_to_render`. Localize buttons by `id`;
      `label` is the English default.
    - Button tap: if `id` starts with "clarify." send the button label as the
      next message; otherwise open the native flow mapped to that `id`
      ("handoff.human" = open the live-agent screen).
    - On 429, wait `Retry-After` seconds.
"""

from __future__ import annotations

import asyncio
import hmac
import logging
import os
from contextlib import asynccontextmanager
from typing import List, Optional

from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from .config import Settings
from .guardrails import RateLimiter
from .llm import make_backend
from .memory import MemoryStore
from .service import ChatService, SessionNotFound

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"),
                    format="%(asctime)s %(levelname)s [%(name)s] %(message)s")
log = logging.getLogger("support_router.api")

SWEEP_INTERVAL_SECONDS = int(os.getenv("SWEEP_INTERVAL_SECONDS", "300"))


# ===========================================================================
# Schemas (these become the OpenAPI contract for the mobile app)
# ===========================================================================

class ChatRequest(BaseModel):
    # Accept more than the router uses; sanitize_text truncates. Rejecting
    # a long paste outright is worse UX than trimming it.
    message: str = Field(..., max_length=8000, examples=["i got chraged twice, want a refnd"])
    session_id: Optional[str] = Field(None, max_length=64, pattern=r"^[a-f0-9]{32}$")


class UIOption(BaseModel):
    id: str = Field(..., description="Stable i18n key / action ID, e.g. 'billing_and_refunds.refund_request'")
    label: str = Field(..., description="Default English label")
    intent: str


class IntentOut(BaseModel):
    intent: str
    sub_intent: Optional[str]
    confidence: float
    label: str


class EscalationOut(BaseModel):
    escalate: bool
    priority: Optional[str] = Field(None, description="normal | high | urgent")
    reasons: List[str]
    handoff_id: Optional[str] = None


class ChatResponse(BaseModel):
    session_id: str
    new_session: bool
    turn_id: int
    intent: str = Field(..., description="Primary intent (kept for v0 clients)")
    intents: List[IntentOut]
    bot_reply: str
    language: str
    urgency: str
    needs_clarification: bool
    escalation: EscalationOut
    ui_options_to_render: List[UIOption]
    degraded: bool = Field(..., description="True if a fallback path was used")


class TurnOut(BaseModel):
    id: int
    role: str
    text: str
    created_at: str
    ui_options: Optional[List[UIOption]] = None


class SessionOut(BaseModel):
    session_id: str
    created_at: str
    ended: bool
    turns: List[TurnOut]


class MemoryItem(BaseModel):
    session_id: str
    summary: str
    intents: List[str]
    resolved: bool
    open_items: List[str]
    created_at: str


# ===========================================================================
# App wiring
# ===========================================================================

def create_app(settings: Optional[Settings] = None) -> FastAPI:
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        store = MemoryStore(settings.db_path)
        app.state.settings = settings
        app.state.service = ChatService(store, make_backend(settings), settings)
        app.state.limiter = RateLimiter(settings.rate_limit_per_minute, settings.rate_limit_burst)

        async def sweeper():
            # Summarizes sessions users walked away from and purges expired
            # memory. Runs off the event loop (blocking LLM + SQLite calls).
            while True:
                await asyncio.sleep(SWEEP_INTERVAL_SECONDS)
                try:
                    await asyncio.to_thread(app.state.service.sweep)
                except Exception:  # noqa: BLE001
                    log.exception("sweep failed")

        task = asyncio.create_task(sweeper()) if SWEEP_INTERVAL_SECONDS > 0 else None
        log.info("support router up: backend=%s db=%s",
                 "openai" if settings.use_real_llm else "MOCK", settings.db_path)
        yield
        if task:
            task.cancel()

    app = FastAPI(title="Support Router API", version="1.0.0", lifespan=lifespan)

    @app.exception_handler(SessionNotFound)
    async def _session_not_found(_: Request, __: SessionNotFound):
        return JSONResponse(status_code=404, content={"detail": "session not found"})

    @app.exception_handler(Exception)
    async def _unhandled(_: Request, exc: Exception):
        # Never leak internals (stack traces, SQL, prompts) to the client.
        log.exception("unhandled error: %s", type(exc).__name__)
        return JSONResponse(status_code=500, content={"detail": "internal error"})

    # ---- dependencies ---------------------------------------------------------

    def get_service(request: Request) -> ChatService:
        return request.app.state.service

    def get_current_user(
        x_api_key: str = Header(..., description="App key"),
        x_user_id: str = Header(..., min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_.:@-]+$"),
    ) -> str:
        """
        !!! DEV-ONLY AUTH !!!
        Trusting a client-sent user ID means anyone holding the app key can
        read any user's memory and sessions. Before production, replace this
        function with verification of your identity provider's token
        (Firebase ID token / Auth0 / Cognito JWT: verify signature, audience,
        expiry) and return the `sub` claim. Nothing else needs to change.
        """
        if not hmac.compare_digest(x_api_key, settings.app_api_key):
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid api key")
        return x_user_id

    def rate_limited_user(request: Request, user_id: str = Depends(get_current_user)) -> str:
        ok, retry_after = request.app.state.limiter.check(user_id)
        if not ok:
            raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "rate limit exceeded",
                                headers={"Retry-After": str(max(1, int(retry_after + 0.999)))})
        return user_id

    # ---- routes -------------------------------------------------------------
    # Sync `def` handlers: FastAPI runs them in a threadpool, which suits the
    # blocking OpenAI SDK and sqlite3 calls without stalling the event loop.

    @app.get("/healthz", include_in_schema=False)
    def healthz():
        return {"ok": True}

    @app.post("/v1/chat", response_model=ChatResponse)
    def chat(body: ChatRequest, background: BackgroundTasks,
             user_id: str = Depends(rate_limited_user),
             svc: ChatService = Depends(get_service)):
        out = svc.handle_message(user_id, body.session_id, body.message)
        for sid in out.to_summarize:
            background.add_task(svc.summarize_session, sid)  # after response is sent
        r = out.result
        return ChatResponse(
            session_id=out.session_id, new_session=out.new_session, turn_id=out.turn_id,
            intent=r.primary_intent, intents=r.intents, bot_reply=r.bot_reply,
            language=r.language, urgency=r.urgency,
            needs_clarification=r.needs_clarification,
            escalation=EscalationOut(**r.escalation.__dict__, handoff_id=out.handoff_id),
            ui_options_to_render=r.ui_options, degraded=r.degraded,
        )

    @app.post("/v1/sessions/{session_id}/end", status_code=202)
    def end_session(session_id: str, background: BackgroundTasks,
                    user_id: str = Depends(get_current_user),
                    svc: ChatService = Depends(get_service)):
        svc.end_session(user_id, session_id)
        background.add_task(svc.summarize_session, session_id)
        return {"session_id": session_id, "status": "ended"}

    @app.get("/v1/sessions/{session_id}", response_model=SessionOut)
    def get_session(session_id: str, user_id: str = Depends(get_current_user),
                    svc: ChatService = Depends(get_service)):
        sess = svc.store.get_session(session_id)
        if not sess or sess["user_id"] != user_id:
            raise SessionNotFound(session_id)
        turns = [TurnOut(id=t["id"], role=t["role"], text=t["text"], created_at=t["created_at"],
                         ui_options=t.get("ui_options") if t["role"] == "bot" else None)
                 for t in svc.store.turns(session_id)]
        return SessionOut(session_id=session_id, created_at=sess["created_at"],
                          ended=sess["ended_at"] is not None, turns=turns)

    @app.get("/v1/me/memory", response_model=List[MemoryItem])
    def my_memory(user_id: str = Depends(get_current_user),
                  svc: ChatService = Depends(get_service)):
        # Transparency: users can see exactly what the assistant remembers.
        return svc.store.recent_summaries(user_id, 50, settings.memory_retention_days)

    @app.delete("/v1/me/memory")
    def forget_me(user_id: str = Depends(get_current_user),
                  svc: ChatService = Depends(get_service)):
        return {"deleted": svc.store.delete_summaries(user_id)}

    return app


app = create_app()
