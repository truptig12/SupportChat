from support_router.guardrails import (
    SAFE_REPLY_BY_INTENT, RateLimiter, filter_bot_reply, redact_pii, sanitize_text,
)


def test_sanitize_strips_control_chars_and_truncates():
    assert sanitize_text("  hi\x00\x07 there \n\t ", 100) == "hi there"
    assert sanitize_text(None, 100) == ""
    assert len(sanitize_text("a" * 5000, 2000)) == 2000


def test_redacts_valid_card_but_not_order_numbers_or_amounts():
    text, found = redact_pii("card 4111 1111 1111 1111 order #1234567890123 cost $499")
    assert "[CARD]" in text and "4111" not in text
    assert "1234567890123" in text          # fails Luhn -> kept (routing may need it)
    assert "$499" in text
    assert found == {"CARD": 1}


def test_redacts_email_phone_and_gov_ids():
    text, found = redact_pii("mail me at a.b+x@ex.co.in or +91 98765 43210, ssn 123-45-6789, aadhaar 1234 5678 9012")
    assert "[EMAIL]" in text and "[PHONE]" in text and text.count("[GOV_ID]") == 2
    assert "98765" not in text and "ex.co.in" not in text
    assert found["GOV_ID"] == 2


def test_output_filter_blocks_promises():
    for bad in ["I've refunded your $20.", "You will receive a full refund.",
                "We guarantee it's fixed.", "It will arrive within 3 business days."]:
        reply, events = filter_bot_reply(bad, "billing_and_refunds")
        assert reply == SAFE_REPLY_BY_INTENT["billing_and_refunds"], bad
        assert "unauthorized_promise" in events


def test_output_filter_strips_links_and_pii_but_keeps_clean_text():
    reply, events = filter_bot_reply("See https://evil.example/x or mail help@corp.com.", "technical_support")
    assert "http" not in reply and "[EMAIL]" in reply
    assert set(events) == {"url", "pii_in_output"}
    clean = "Sorry about the double charge. Pick an option below."
    assert filter_bot_reply(clean, "billing_and_refunds") == (clean, [])


def test_rate_limiter_blocks_after_capacity():
    rl = RateLimiter(per_minute=2, burst=1)
    assert [rl.check("u")[0] for _ in range(3)] == [True, True, True]
    ok, retry = rl.check("u")
    assert not ok and retry > 0
    assert rl.check("other-user")[0]  # buckets are per key
