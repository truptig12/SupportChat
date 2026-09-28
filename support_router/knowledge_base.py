"""
Hierarchical knowledge base: intent -> sub-intents -> UI options.

Why a hierarchy (two-stage routing):
  Stage 1 only sees top-level intents (label + context). Stage 2 only sees the
  sub-intents of the branches Stage 1 picked. Prompt size therefore grows with
  the number of *detected* branches, not the whole tree, so this scales to
  hundreds of leaf intents without bloating every call.

Why options have stable IDs:
  The mobile app localizes button labels by ID (i18n) and maps IDs to native
  flows ("fraud_and_security.freeze_card" opens the card-freeze screen). The
  English label here is only a default. Never rename an ID once shipped.

The LLM never sees or emits options. Code picks them from this file, so a
model can't hallucinate a button (or be prompt-injected into adding one).
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

UNKNOWN = "unknown"
GENERAL = "general"  # sub-intent used when no specific leaf fits

# Top-level intent -> {label, context, priority, options, default_options, sub_intents}
#   priority:         lower = more important; orders multi-intent results and
#                     buttons (security issues always come first).
#   options:          pool of {option_key: default English label}
#   default_options:  buttons when the sub-intent is "general"
#   sub_intents:      {sub_key: {context, options: [option_key, ...]}}
KNOWLEDGE_BASE: Dict[str, Dict[str, Any]] = {
    "fraud_and_security": {
        "label": "Fraud & security",
        "priority": 0,
        "context": (
            "Urgent security issues: lost or stolen credit/debit cards, "
            "unauthorized or unrecognized transactions, a lost or stolen phone "
            "or device that is signed in to the account, suspected hacking, "
            "phishing, or scams. Takes priority over other intents when the "
            "customer signals theft or activity they didn't authorize."
        ),
        "options": {
            "freeze_card": "Freeze my card now",
            "sign_out_all": "Sign out of all devices",
            "report_unauthorized": "Report unauthorized transactions",
            "change_password": "Change my password",
            "fraud_team": "Talk to the fraud team",
        },
        "default_options": ["freeze_card", "sign_out_all", "report_unauthorized", "fraud_team"],
        "sub_intents": {
            "card_lost_stolen": {
                "context": "A physical or virtual card is lost or stolen.",
                "options": ["freeze_card", "report_unauthorized", "fraud_team"],
            },
            "device_lost_stolen": {
                "context": "A phone/tablet/laptop signed in to the account is lost or stolen.",
                "options": ["sign_out_all", "change_password", "freeze_card", "fraud_team"],
            },
            "unauthorized_transaction": {
                "context": "Charges or transfers the customer says they did not make or don't recognize.",
                "options": ["freeze_card", "report_unauthorized", "fraud_team"],
            },
            "account_compromise": {
                "context": "Account hacked, suspicious logins, phishing messages, or scams.",
                "options": ["change_password", "sign_out_all", "fraud_team"],
            },
        },
    },
    "billing_and_refunds": {
        "label": "Billing & refunds",
        "priority": 1,
        "context": (
            "Questions or complaints about charges, invoices, double billing, "
            "unexpected fees, subscription pricing, payment failures, declined "
            "cards/transactions, and requests for refunds or credits. Charges "
            "the customer says they did NOT make belong to fraud_and_security."
        ),
        "options": {
            "view_charges": "View recent charges",
            "refund_request": "Request a refund",
            "check_refund": "Check refund status",
            "update_payment": "Update payment method",
            "retry_payment": "Retry payment",
            "view_plan": "View my plan",
            "change_plan": "Change or cancel plan",
            "billing_agent": "Talk to a billing specialist",
        },
        "default_options": ["view_charges", "refund_request", "update_payment", "billing_agent"],
        "sub_intents": {
            "double_charge": {
                "context": "Charged more than once for the same order or billing period.",
                "options": ["view_charges", "refund_request", "billing_agent"],
            },
            "refund_status": {
                "context": "Already requested a refund; asking where it is or when it arrives.",
                "options": ["check_refund", "view_charges", "billing_agent"],
            },
            "payment_declined": {
                "context": "Card or payment declined, failed checkout, payment errors.",
                "options": ["update_payment", "retry_payment", "billing_agent"],
            },
            "subscription_pricing": {
                "context": "Plan prices, unexpected fees, renewals, changing or cancelling a subscription.",
                "options": ["view_plan", "change_plan", "view_charges", "billing_agent"],
            },
        },
    },
    "shipping_and_delivery": {
        "label": "Orders & delivery",
        "priority": 2,
        "context": (
            "Order tracking, delayed or lost packages, wrong delivery address, "
            "damaged items on arrival, returns/exchanges, delivery options and "
            "shipping costs."
        ),
        "options": {
            "track": "Track my order",
            "missing": "Report a missing package",
            "change_address": "Change delivery address",
            "damaged": "Report a damaged item",
            "start_return": "Start a return",
        },
        "default_options": ["track", "missing", "change_address", "damaged"],
        "sub_intents": {
            "tracking_delay": {
                "context": "Where is my order / it is late / no tracking updates.",
                "options": ["track", "missing"],
            },
            "lost_package": {
                "context": "Marked delivered but not received, or confirmed lost.",
                "options": ["missing", "track"],
            },
            "wrong_address": {
                "context": "Needs to change or fix the delivery address.",
                "options": ["change_address", "track"],
            },
            "damaged_item": {
                "context": "Item arrived broken, damaged, or defective on arrival.",
                "options": ["damaged", "start_return"],
            },
            "returns": {
                "context": "Wants to return or exchange an item.",
                "options": ["start_return", "track"],
            },
        },
    },
    "technical_support": {
        "label": "Technical support",
        "priority": 3,
        "context": (
            "The product, app or website isn't working as expected: crashes, "
            "errors, slowness, login failures caused by bugs, features not "
            "loading, sync issues, or device/connectivity problems."
        ),
        "options": {
            "troubleshoot": "Run automated troubleshooting",
            "status": "Check service status",
            "report_bug": "Report a bug",
            "update_app": "Update the app",
            "clear_cache": "Clear app cache",
            "technician": "Chat with a technician",
        },
        "default_options": ["troubleshoot", "status", "report_bug", "technician"],
        "sub_intents": {
            "app_crash": {
                "context": "App crashes, freezes, or closes on open.",
                "options": ["update_app", "clear_cache", "report_bug", "technician"],
            },
            "login_error": {
                "context": "Can't log in because of an error or bug (not a forgotten password).",
                "options": ["status", "troubleshoot", "technician"],
            },
            "performance": {
                "context": "Slow, not loading, sync not working, features missing.",
                "options": ["status", "troubleshoot", "clear_cache", "technician"],
            },
            "connectivity": {
                "context": "Device pairing, Wi-Fi, Bluetooth, or network problems.",
                "options": ["troubleshoot", "technician"],
            },
        },
    },
    "account_management": {
        "label": "My account",
        "priority": 4,
        "context": (
            "Managing the customer's account: password resets, updating email "
            "or profile details, two-factor authentication, closing or "
            "reactivating an account, and privacy/data requests. If the "
            "customer suspects compromise or a stolen device, use "
            "fraud_and_security instead."
        ),
        "options": {
            "reset_password": "Reset my password",
            "resend_email": "Resend verification email",
            "update_profile": "Update profile details",
            "security_settings": "Manage security settings",
            "download_data": "Download my data",
            "close_account": "Close my account",
        },
        "default_options": ["reset_password", "update_profile", "security_settings", "close_account"],
        "sub_intents": {
            "password_reset": {
                "context": "Forgot password, reset link not arriving.",
                "options": ["reset_password", "resend_email"],
            },
            "profile_update": {
                "context": "Change email, phone, name, or address on the profile.",
                "options": ["update_profile", "resend_email"],
            },
            "two_factor": {
                "context": "2FA / OTP codes not arriving, changing 2FA device.",
                "options": ["security_settings", "reset_password"],
            },
            "close_or_reactivate": {
                "context": "Close, delete, pause, or reactivate the account.",
                "options": ["close_account", "update_profile"],
            },
            "privacy_data": {
                "context": "Data export, data deletion, privacy requests.",
                "options": ["download_data", "close_account"],
            },
        },
    },
    "product_questions": {
        "label": "Product questions",
        "priority": 5,
        "context": (
            "Pre- or post-purchase questions about products: features, "
            "compatibility, sizing, specifications, availability, warranty "
            "coverage, and recommendations."
        ),
        "options": {
            "compare": "Compare products",
            "stock": "Check stock availability",
            "warranty": "View warranty info",
            "recommend": "Get a recommendation",
        },
        "default_options": ["compare", "stock", "warranty", "recommend"],
        "sub_intents": {
            "specs_compat": {
                "context": "Features, specs, compatibility, sizing.",
                "options": ["compare", "recommend"],
            },
            "availability": {
                "context": "In stock? Restock dates, where to buy.",
                "options": ["stock", "recommend"],
            },
            "warranty": {
                "context": "Warranty coverage, claims, extended protection.",
                "options": ["warranty"],
            },
            "recommendation": {
                "context": "Which product should I buy / what fits my needs.",
                "options": ["recommend", "compare"],
            },
        },
    },
}

# Always offer a path to a human — a dead-end bot is the worst CX outcome.
UNKNOWN_OPTIONS: List[Dict[str, str]] = [
    {"id": "unknown.browse", "label": "Browse help topics", "intent": UNKNOWN},
    {"id": "unknown.human", "label": "Talk to a human agent", "intent": UNKNOWN},
    {"id": "unknown.start_over", "label": "Start over", "intent": UNKNOWN},
]
HANDOFF_OPTION: Dict[str, str] = {
    "id": "handoff.human", "label": "Connect me to a person now", "intent": "handoff",
}
CLARIFY_OTHER_OPTION: Dict[str, str] = {
    "id": "clarify.other", "label": "Something else", "intent": "clarify",
}


# ---------------------------------------------------------------------------
# Lookups
# ---------------------------------------------------------------------------

def intent_ids() -> List[str]:
    return list(KNOWLEDGE_BASE)


def intent_label(intent: str) -> str:
    return KNOWLEDGE_BASE.get(intent, {}).get("label", "Other")


def intent_priority(intent: str) -> int:
    return KNOWLEDGE_BASE.get(intent, {}).get("priority", 99)


def qualified_sub_ids(intent: str) -> List[str]:
    """Sub-intent IDs are qualified ("billing_and_refunds.double_charge") so a
    single enum can cover several branches without key collisions."""
    subs = KNOWLEDGE_BASE[intent]["sub_intents"]
    return [f"{intent}.{s}" for s in subs] + [f"{intent}.{GENERAL}"]


def options_for(intent: str, sub_intent: Optional[str] = None) -> List[Dict[str, str]]:
    """Buttons for an intent/sub-intent, as [{id, label, intent}]."""
    if intent not in KNOWLEDGE_BASE:
        return [dict(o) for o in UNKNOWN_OPTIONS]
    node = KNOWLEDGE_BASE[intent]
    sub = node["sub_intents"].get(sub_intent or "")
    keys = sub["options"] if sub else node["default_options"]
    return [
        {"id": f"{intent}.{k}", "label": node["options"][k], "intent": intent}
        for k in keys
    ]


def clarify_options(candidates: List[str]) -> List[Dict[str, str]]:
    """Buttons for a clarifying question: one per candidate top-level intent.
    When tapped, the app sends the label as the next message; multi-turn
    history lets the router resolve it against the original question."""
    picks = [c for c in candidates if c in KNOWLEDGE_BASE] or intent_ids()
    picks = sorted(set(picks), key=intent_priority)[:4]
    return [
        {"id": f"clarify.{i}", "label": KNOWLEDGE_BASE[i]["label"], "intent": "clarify"}
        for i in picks
    ] + [dict(CLARIFY_OTHER_OPTION)]


def stage1_view() -> Dict[str, Dict[str, str]]:
    """What Stage 1 sees: top-level intents only (no sub-intents, no options)."""
    return {k: {"label": v["label"], "context": v["context"]} for k, v in KNOWLEDGE_BASE.items()}


def stage2_view(intents: List[str]) -> Dict[str, Dict[str, str]]:
    """What Stage 2 sees: only sub-intents of the detected branches."""
    return {
        intent: {f"{intent}.{s}": d["context"] for s, d in KNOWLEDGE_BASE[intent]["sub_intents"].items()}
        for intent in intents if intent in KNOWLEDGE_BASE
    }
