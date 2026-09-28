package com.example.supportrouter

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP error from the Support Router API.
 *   status 0   = network error / timeout (server unreachable)
 *   status 401 = wrong X-API-Key
 *   status 404 = session not found (or belongs to another user)
 *   status 429 = rate limited; retryAfterSec comes from the Retry-After header
 */
class ApiException(val status: Int, val retryAfterSec: Int? = null, message: String) : Exception(message)

/**
 * Minimal client using only HttpURLConnection + org.json.
 *
 * All methods are BLOCKING: call them from a background thread
 * (MainActivity uses a single-thread executor).
 */
class SupportApi(private val config: AppConfig) {

    /** GET /healthz (no auth): used by Settings to test the server URL. */
    fun health(): Boolean = JSONObject(request("GET", "/healthz")).optBoolean("ok")

    /** POST /v1/chat. Pass sessionId = null to start a new conversation. */
    fun chat(message: String, sessionId: String?): ChatResponse {
        val body = JSONObject().put("message", message)
        if (sessionId != null) body.put("session_id", sessionId)
        return ChatResponse.from(JSONObject(request("POST", "/v1/chat", body.toString())))
    }

    /** GET /v1/sessions/{id}: restore history after app restart. */
    fun session(sessionId: String): SessionOut =
        SessionOut.from(JSONObject(request("GET", "/v1/sessions/$sessionId")))

    /** POST /v1/sessions/{id}/end: server summarizes it into long-term memory. */
    fun endSession(sessionId: String) {
        request("POST", "/v1/sessions/$sessionId/end", "")
    }

    /** GET /v1/me/memory */
    fun memory(): List<MemoryItem> =
        JSONArray(request("GET", "/v1/me/memory")).objects().map(MemoryItem::from)

    /** DELETE /v1/me/memory: returns number of deleted summaries. */
    fun forgetMe(): Int = JSONObject(request("DELETE", "/v1/me/memory")).optInt("deleted")

    /** GET /v1/service-status: known issues check (mock ServiceNow). */
    fun serviceStatus(): ServiceStatus = ServiceStatus.from(JSONObject(request("GET", "/v1/service-status")))

    /** POST /v1/incidents: raise an incident from the confirmed support summary. */
    fun createIncident(body: JSONObject): Incident =
        Incident.from(JSONObject(request("POST", "/v1/incidents", body.toString())))

    /** GET /v1/incidents/{ref}: track progress. */
    fun incident(reference: String): Incident =
        Incident.from(JSONObject(request("GET", "/v1/incidents/$reference")))

    /**
     * Start a fresh conversation: end the previous session (so the server
     * summarizes it into long-term memory) and forget its id. Blocking.
     */
    fun startNewConversation() {
        val previous = config.sessionId ?: return
        config.sessionId = null
        runCatching { endSession(previous) }
    }

    /**
     * POST /v1/chat with the recovery rules from docs/MOBILE_INTEGRATION.md:
     * stale session (404) -> start fresh; 429 -> wait Retry-After, retry once.
     * Always adopts the returned session_id.
     */
    fun chatWithRecovery(message: String): ChatResponse {
        val res = try {
            chat(message, config.sessionId)
        } catch (e: ApiException) {
            when (e.status) {
                404 -> { config.sessionId = null; chat(message, null) }
                429 -> {
                    Thread.sleep((e.retryAfterSec ?: 3).coerceIn(1, 30) * 1000L)
                    chat(message, config.sessionId)
                }
                else -> throw e
            }
        }
        config.sessionId = res.sessionId
        return res
    }

    // -------------------------------------------------------------------------

    private fun request(method: String, path: String, body: String? = null): String {
        val url = URL(config.baseUrl.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 30_000          // two LLM calls per turn; typically 1-4 s
            setRequestProperty("X-API-Key", config.appKey)
            setRequestProperty("X-User-Id", config.userId)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (body != null) {
                // Also used for body-less POSTs ("") so Content-Length: 0 is sent.
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw ApiException(
                    status = code,
                    retryAfterSec = conn.getHeaderField("Retry-After")?.toIntOrNull(),
                    message = "HTTP $code${if (detail.isNullOrBlank()) "" else ": $detail"}",
                )
            }
            return text
        } catch (e: IOException) {
            throw ApiException(0, message = e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }
}
