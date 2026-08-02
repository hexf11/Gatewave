package com.hexf11.gatewave

import android.content.Context
import android.content.Intent
import androidx.core.content.edit

internal data class ProxyStatus(
    val running: Boolean,
    val vpnReady: Boolean,
    val endpoint: String,
    val vpnInterface: String,
    val stats: ProxyStats,
    val subscriptionEnabled: Boolean,
    val subscriptionUrl: String,
    val message: String,
) {
    fun publish(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean("running", running)
            putBoolean("vpnReady", vpnReady)
            putString("endpoint", endpoint)
            putString("vpnInterface", vpnInterface)
            putInt("clients", stats.activeConnections)
            putInt("activeConnections", stats.activeConnections)
            putInt("activeTcp", stats.activeTcp)
            putInt("activeUdp", stats.activeUdp)
            putLong("totalConnections", stats.totalConnections)
            putLong("failedConnections", stats.failedConnections)
            putLong("rejectedConnections", stats.rejectedConnections)
            putLong("uploadBytes", stats.uploadBytes)
            putLong("downloadBytes", stats.downloadBytes)
            putBoolean("subscriptionEnabled", subscriptionEnabled)
            putString("subscriptionUrl", subscriptionUrl)
            putString("message", message)
        }

        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    companion object {
        const val ACTION_CHANGED = "com.hexf11.gatewave.STATUS_CHANGED"
        private const val PREFS = "proxy_status"

        @JvmStatic
        fun load(context: Context): ProxyStatus {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ProxyStatus(
                running = preferences.getBoolean("running", false),
                vpnReady = preferences.getBoolean("vpnReady", false),
                endpoint = preferences.getString("endpoint", "--:1080") ?: "--:1080",
                vpnInterface = preferences.getString("vpnInterface", "--") ?: "--",
                stats = ProxyStats(
                    activeConnections = preferences.getInt(
                        "activeConnections",
                        preferences.getInt("clients", 0),
                    ),
                    activeTcp = preferences.getInt("activeTcp", 0),
                    activeUdp = preferences.getInt("activeUdp", 0),
                    totalConnections = preferences.getLong("totalConnections", 0),
                    failedConnections = preferences.getLong("failedConnections", 0),
                    rejectedConnections = preferences.getLong("rejectedConnections", 0),
                    uploadBytes = preferences.getLong("uploadBytes", 0),
                    downloadBytes = preferences.getLong("downloadBytes", 0),
                ),
                subscriptionEnabled = preferences.getBoolean("subscriptionEnabled", false),
                subscriptionUrl = preferences.getString("subscriptionUrl", "--") ?: "--",
                message = preferences.getString("message", "服务未启动") ?: "服务未启动",
            )
        }
    }
}
