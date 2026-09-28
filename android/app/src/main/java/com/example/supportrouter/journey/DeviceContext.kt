package com.example.supportrouter.journey

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.example.supportrouter.BuildConfig

/**
 * "Additional context (auto-detected)": safe, non-sensitive facts the app
 * already knows, so the customer doesn't have to type them.
 */
data class DeviceContext(val appVersion: String, val device: String, val network: String) {
    companion object {
        fun detect(context: Context): DeviceContext {
            val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
                        else "$maker ${Build.MODEL}"
            return DeviceContext(
                appVersion = "${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})",
                device = model,
                network = network(context),
            )
        }

        private fun network(context: Context): String {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "Unknown"
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
            val base = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val carrier = context.getSystemService(TelephonyManager::class.java)
                        ?.networkOperatorName?.takeIf { it.isNotBlank() }
                    if (carrier != null) "Mobile data ($carrier)" else "Mobile data"
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Connected"
            }
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            return if (vpn) "$base + VPN" else base
        }
    }
}
