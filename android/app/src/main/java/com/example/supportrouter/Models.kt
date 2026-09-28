package com.example.supportrouter

import org.json.JSONArray
import org.json.JSONObject

// Plain data classes + hand-written parsing with org.json (built into Android).
// Unknown fields are ignored, so newer servers don't break older apps.

data class UIOption(val id: String, val label: String, val intent: String) {
    companion object {
        fun from(o: JSONObject) = UIOption(o.getString("id"), o.getString("label"), o.optString("intent"))
    }
}

data class IntentOut(val intent: String, val subIntent: String?, val confidence: Double, val label: String) {
    companion object {
        fun from(o: JSONObject) = IntentOut(
            intent = o.getString("intent"),
            subIntent = o.stringOrNull("sub_intent"),
            confidence = o.optDouble("confidence", 0.0),
            label = o.optString("label"),
        )
    }
}

data class Escalation(val escalate: Boolean, val priority: String?, val reasons: List<String>, val handoffId: String?) {
    companion object {
        fun from(o: JSONObject?) = Escalation(
            escalate = o?.optBoolean("escalate") ?: false,
            priority = o?.stringOrNull("priority"),
            reasons = o?.optJSONArray("reasons").strings(),
            handoffId = o?.stringOrNull("handoff_id"),
        )
    }
}

/** Response of POST /v1/chat. */
data class ChatResponse(
    val sessionId: String,
    val newSession: Boolean,
    val intent: String,
    val intents: List<IntentOut>,
    val botReply: String,
    val language: String,
    val urgency: String,
    val needsClarification: Boolean,
    val escalation: Escalation,
    val options: List<UIOption>,
    val degraded: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = ChatResponse(
            sessionId = o.getString("session_id"),
            newSession = o.optBoolean("new_session"),
            intent = o.optString("intent"),
            intents = o.optJSONArray("intents").objects().map(IntentOut::from),
            botReply = o.optString("bot_reply"),
            language = o.optString("language", "en"),
            urgency = o.optString("urgency", "low"),
            needsClarification = o.optBoolean("needs_clarification"),
            escalation = Escalation.from(o.optJSONObject("escalation")),
            options = o.optJSONArray("ui_options_to_render").objects().map(UIOption::from),
            degraded = o.optBoolean("degraded"),
        )
    }
}

/** Response of GET /v1/sessions/{id}. */
data class SessionOut(val sessionId: String, val ended: Boolean, val turns: List<Turn>) {
    data class Turn(val role: String, val text: String, val options: List<UIOption>)

    companion object {
        fun from(o: JSONObject) = SessionOut(
            sessionId = o.getString("session_id"),
            ended = o.optBoolean("ended"),
            turns = o.optJSONArray("turns").objects().map { t ->
                Turn(
                    role = t.optString("role"),
                    text = t.optString("text"),
                    options = t.optJSONArray("ui_options").objects().map(UIOption::from),
                )
            },
        )
    }
}

/** One item of GET /v1/me/memory. */
data class MemoryItem(val summary: String, val intents: List<String>, val resolved: Boolean, val createdAt: String) {
    companion object {
        fun from(o: JSONObject) = MemoryItem(
            summary = o.optString("summary"),
            intents = o.optJSONArray("intents").strings(),
            resolved = o.optBoolean("resolved"),
            createdAt = o.optString("created_at"),
        )
    }
}

/** GET /v1/service-status (mock ServiceNow known-issues check). */
data class ServiceStatus(val status: String, val title: String, val message: String, val affects: List<String>, val checkedAt: String) {
    val isKnownIssue get() = status == "known_issue"
    val affectsLogin get() = isKnownIssue && ("login" in affects || affects.isEmpty())

    companion object {
        fun from(o: JSONObject) = ServiceStatus(
            status = o.optString("status", "operational"),
            title = o.optString("title"),
            message = o.optString("message"),
            affects = o.optJSONArray("affects").strings(),
            checkedAt = o.optString("checked_at"),
        )
    }
}

/** POST/GET /v1/incidents (mock ServiceNow incident). */
data class Incident(
    val reference: String,
    val status: String,
    val statusLabel: String,
    val createdAt: String,
    val timeline: List<TimelineStep>,
) {
    data class TimelineStep(val key: String, val label: String, val done: Boolean, val at: String?)

    companion object {
        fun from(o: JSONObject) = Incident(
            reference = o.getString("reference"),
            status = o.optString("status"),
            statusLabel = o.optString("status_label"),
            createdAt = o.optString("created_at"),
            timeline = o.optJSONArray("timeline").objects().map {
                TimelineStep(it.optString("key"), it.optString("label"), it.optBoolean("done"), it.stringOrNull("at"))
            },
        )
    }
}

// ---- org.json helpers -------------------------------------------------------

/** optString() returns "null" for JSON null; this returns a real null. */
fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }
