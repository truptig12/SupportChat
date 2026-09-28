"""
Guardrails: everything that protects users, the business, and the LLM bill.

  sanitize_text     normalize raw input, cap length
  redact_pii        strip cards/emails/phones/gov IDs BEFORE the LLM, logs, or DB
  filter_bot_reply  block replies that promise outcomes, leak PII, or add links
  RateLimiter       per-user token bucket

Order of operations in the request path:
    raw -> sanitize_text -> redact_pii -> (LLM) -> filter_bot_reply -> client
The raw message is never persisted or logged; only the redacted text is.
"""

from __future__ import annotations

import re
import threading
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Tuple

# ---------------------------------------------------------------------------
# Input
# ---------------------------------------------------------------------------

_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


def sanitize_text(raw: Any, max_chars: int) -> str:
    """Coerce to str, drop control chars, collapse whitespace, cap length."""
    text = "" if raw is None else str(raw)
    text = _CONTROL_CHARS.sub("", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:max_chars]


# ---------------------------------------------------------------------------
# PII redaction
# ---------------------------------------------------------------------------
# Routing never needs the actual number/email, so we redact before the LLM
# too (data minimization: less PII at a third-party processor). Placeholders
# keep meaning intact: "my card [CARD] was stolen" still routes to fraud.
#
# Regex redaction is a baseline, not a guarantee. For regulated workloads add
# a dedicated PII service (e.g. Presidio / cloud DLP) in front of this.

_EMAIL = re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b")
_SSN = re.compile(r"\b\d{3}-\d{2}-\d{4}\b")
# 12-digit Indian gov ID; lookarounds stop it matching inside a longer card number.
_AADHAAR = re.compile(r"(?<!\d)(?<!\d[ -])\d{4}[ -]\d{4}[ -]\d{4}(?![ -]?\d)")
_CARD_CANDIDATE = re.compile(r"\b(?:\d[ -]?){12,18}\d\b")
_PHONE = re.compile(r"(?<![\w$#])\+?\d[\d ().-]{8,}\d\b")


def _luhn_ok(digits: str) -> bool:
    total, parity = 0, len(digits) % 2
    for i, ch in enumerate(digits):
        d = int(ch)
        if i % 2 == parity:
            d *= 2
            if d > 9:
                d -= 9
        total += d
    return total % 10 == 0


def _redact_cards(text: str) -> Tuple[str, int]:
    count = 0

    def repl(m: re.Match) -> str:
        nonlocal count
        digits = re.sub(r"\D", "", m.group())
        # Luhn check avoids redacting order numbers that merely look long.
        if 13 <= len(digits) <= 19 and _luhn_ok(digits):
            count += 1
            return "[CARD]"
        return m.group()

    return _CARD_CANDIDATE.sub(repl, text), count


def _redact_phones(text: str) -> Tuple[str, int]:
    count = 0

    def repl(m: re.Match) -> str:
        nonlocal count
        # Phone = 10-15 digits (E.164 max 15) that look dialable: a leading
        # "+", separators, or exactly 10 digits. A bare 13-digit run is more
        # likely an order/tracking ID, which routing may need. ("#" before a
        # number is excluded by the regex for the same reason.)
        raw = m.group()
        n = len(re.sub(r"\D", "", raw))
        dialable = raw.startswith("+") or re.search(r"[ ().-]", raw) or n == 10
        if 10 <= n <= 15 and dialable:
            count += 1
            return "[PHONE]"
        return m.group()

    return _PHONE.sub(repl, text), count


def redact_pii(text: str) -> Tuple[str, Dict[str, int]]:
    """Return (redacted_text, {pii_type: count}). Order matters: specific
    patterns run before the broad phone pattern that would swallow them."""
    found: Dict[str, int] = {}

    def sub(pattern: re.Pattern, tag: str, t: str) -> str:
        new, n = pattern.subn(f"[{tag}]", t)
        if n:
            found[tag] = found.get(tag, 0) + n
        return new

    text = sub(_EMAIL, "EMAIL", text)
    text = sub(_SSN, "GOV_ID", text)
    text, n = _redact_cards(text)  # before Aadhaar: a card contains 12-digit runs
    if n:
        found["CARD"] = n
    text = sub(_AADHAAR, "GOV_ID", text)
    text, n = _redact_phones(text)
    if n:
        found["PHONE"] = n
    return text, found


# ---------------------------------------------------------------------------
# Output filter
# ---------------------------------------------------------------------------
# The router has no tools: it can't actually refund, fix, or ship anything.
# So any reply claiming it did (or will) is a hallucinated commitment, which
# creates real liability. We block those and fall back to a safe template.
# Patterns are English; non-English replies rely on the prompt rule alone.
# Swap in an LLM-judge/moderation pass if that gap matters.

_PROMISE_PATTERNS = [
    re.compile(p, re.IGNORECASE) for p in [
        r"\b(i|we)\s*(have|'ve)?\s*(already\s+)?(refunded|reversed|credited|cancell?ed|fixed|resolved|blocked|frozen|froze)\b",
        r"\b(i|we)\s*(will|'ll)\s+(refund|reverse|credit|replace|compensate)\b",
        r"\byou\s*(will|'ll)\s+(receive|get)\s+(a\s+|your\s+|the\s+)?(full\s+)?(refund|credit|replacement|compensation)\b",
        r"\bguarantee[ds]?\b",
        r"\bwithin\s+\d+\s*(minutes?|hours?|days?|business\s+days?|weeks?)\b",
    ]
]
_URL = re.compile(r"(https?://|www\.)\S+", re.IGNORECASE)

SAFE_REPLY_BY_INTENT: Dict[str, str] = {
    "fraud_and_security": "I'm sorry you're dealing with this. Please use the options below to secure your account right away.",
    "billing_and_refunds": "I'm sorry about the trouble with your payment. The options below will help us sort it out.",
    "shipping_and_delivery": "I understand how frustrating order issues are. Let's take a look using the options below.",
    "technical_support": "Sorry that things aren't working as expected. Let's troubleshoot with the options below.",
    "account_management": "Happy to help with your account. Please choose an option below.",
    "product_questions": "Good question! The options below should help you find what you need.",
}
SAFE_REPLY_DEFAULT = (
    "Sorry, I'm having trouble with that right now. You can pick a help topic "
    "below or I can connect you with a person."
)


def filter_bot_reply(reply: str, primary_intent: str) -> Tuple[str, List[str]]:
    """Return (safe_reply, violations). Violations are logged for prompt QA."""
    violations: List[str] = []
    if not reply or not reply.strip():
        return SAFE_REPLY_BY_INTENT.get(primary_intent, SAFE_REPLY_DEFAULT), ["empty"]

    if any(p.search(reply) for p in _PROMISE_PATTERNS):
        violations.append("unauthorized_promise")
        return SAFE_REPLY_BY_INTENT.get(primary_intent, SAFE_REPLY_DEFAULT), violations

    if _URL.search(reply):
        violations.append("url")
        reply = _URL.sub("", reply)

    reply, pii = redact_pii(reply)
    if pii:
        violations.append("pii_in_output")

    return re.sub(r"\s+", " ", reply).strip(), violations


# ---------------------------------------------------------------------------
# Rate limiting
# ---------------------------------------------------------------------------

@dataclass
class _Bucket:
    tokens: float
    updated: float


class RateLimiter:
    """
    Per-key token bucket: `per_minute` sustained, `burst` extra headroom.

    In-process only. With multiple uvicorn workers/pods, each has its own
    buckets, so move this to Redis (INCR + EXPIRE, or a Lua token bucket)
    before scaling out.
    """

    def __init__(self, per_minute: int, burst: int):
        self.rate = per_minute / 60.0
        self.capacity = float(per_minute + burst)
        self._buckets: Dict[str, _Bucket] = {}
        self._lock = threading.Lock()

    def check(self, key: str) -> Tuple[bool, float]:
        """Consume one token. Returns (allowed, retry_after_seconds)."""
        now = time.monotonic()
        with self._lock:
            b = self._buckets.get(key)
            if b is None:
                b = self._buckets[key] = _Bucket(self.capacity, now)
            b.tokens = min(self.capacity, b.tokens + (now - b.updated) * self.rate)
            b.updated = now
            if b.tokens >= 1:
                b.tokens -= 1
                return True, 0.0
            return False, (1 - b.tokens) / self.rate
