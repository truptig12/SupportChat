"""
LLM-driven customer support router, served over FastAPI.

Modules:
    config          env-driven settings
    knowledge_base  hierarchical intents -> sub-intents -> UI options
    guardrails      input sanitizing, PII redaction, output filter, rate limiter
    llm             OpenAI (structured outputs) + offline mock backends
    router          two-stage routing, multi-intent, clarification, escalation
    memory          SQLite store: sessions, turns, long-term summaries, handoffs
    api             FastAPI app for the mobile client
"""
