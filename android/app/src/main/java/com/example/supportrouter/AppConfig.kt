package com.example.supportrouter

import android.content.Context
import java.util.UUID

/**
 * Server URL, app key and the X-User-Id to send. Defaults come from
 * BuildConfig (android/local.properties); debug builds can override the
 * URL and key at runtime (Login -> Demo controls -> Server settings).
 */
class AppConfig(context: Context) {
    private val prefs = context.getSharedPreferences("support_router", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", null) ?: BuildConfig.SUPPORT_API_URL
        set(v) = prefs.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    var appKey: String
        get() = prefs.getString("app_key", null) ?: BuildConfig.SUPPORT_APP_KEY
        set(v) = prefs.edit().putString("app_key", v.trim()).apply()

    /**
     * The login-support journey is UNAUTHENTICATED: the customer can't log
     * in, so there's no verified user id. We send an anonymous per-install
     * id instead. The User ID they typed on the login screen is sent only
     * as an unverified `customer_reference` inside the incident.
     */
    val userId: String
        get() = BuildConfig.SUPPORT_USER_ID.ifBlank {
            prefs.getString("anon_id", null) ?: ("anon-" + UUID.randomUUID().toString().replace("-", ""))
                .also { prefs.edit().putString("anon_id", it).apply() }
        }

    var sessionId: String?
        get() = prefs.getString("session_id.$userId", null)
        set(v) = prefs.edit().apply {
            if (v == null) remove("session_id.$userId") else putString("session_id.$userId", v)
        }.apply()
}
