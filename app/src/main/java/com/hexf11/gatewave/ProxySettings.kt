package com.hexf11.gatewave

import android.content.Context
import androidx.core.content.edit

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class PerformanceMode {
    BALANCED,
    TURBO,
    POWER_SAVE,
}

data class ProxySettings(
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    val subscriptionPort: Int = DEFAULT_SUBSCRIPTION_PORT,
    val udpEnabled: Boolean = true,
    val autoResumeVpn: Boolean = true,
    val startOnAppLaunch: Boolean = false,
    val startOnBoot: Boolean = false,
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    fun requiresServerRestart(other: ProxySettings): Boolean =
        socksPort != other.socksPort ||
            subscriptionPort != other.subscriptionPort ||
            udpEnabled != other.udpEnabled ||
            performanceMode != other.performanceMode

    companion object {
        const val DEFAULT_SOCKS_PORT = 1080
        const val DEFAULT_SUBSCRIPTION_PORT = 8080
        const val MIN_PORT = 1024
        const val MAX_PORT = 65_535
    }
}

internal object ProxySettingsStore {
    private const val PREFS = "proxy_settings"
    const val KEY_SUBSCRIPTION_ENABLED = "subscription_enabled"
    private const val KEY_VPN_RESUME_BLOCKED = "vpn_resume_blocked"
    private const val KEY_BOOT_START_PENDING = "boot_start_pending"

    fun load(context: Context): ProxySettings {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val theme = runCatching {
            ThemeMode.valueOf(preferences.getString("theme_mode", null) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        val performance = runCatching {
            PerformanceMode.valueOf(
                preferences.getString("performance_mode", null) ?: PerformanceMode.BALANCED.name,
            )
        }.getOrDefault(PerformanceMode.BALANCED)
        return ProxySettings(
            socksPort = preferences.getInt("socks_port", ProxySettings.DEFAULT_SOCKS_PORT),
            subscriptionPort = preferences.getInt(
                "subscription_port",
                ProxySettings.DEFAULT_SUBSCRIPTION_PORT,
            ),
            udpEnabled = preferences.getBoolean("udp_enabled", true),
            autoResumeVpn = preferences.getBoolean("auto_resume_vpn", true),
            startOnAppLaunch = preferences.getBoolean("start_on_app_launch", false),
            startOnBoot = preferences.getBoolean("start_on_boot", false),
            performanceMode = performance,
            themeMode = theme,
        ).sanitized()
    }

    fun save(context: Context, settings: ProxySettings) {
        val value = settings.sanitized()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putInt("socks_port", value.socksPort)
            putInt("subscription_port", value.subscriptionPort)
            putBoolean("udp_enabled", value.udpEnabled)
            putBoolean("auto_resume_vpn", value.autoResumeVpn)
            putBoolean("start_on_app_launch", value.startOnAppLaunch)
            putBoolean("start_on_boot", value.startOnBoot)
            putString("performance_mode", value.performanceMode.name)
            putString("theme_mode", value.themeMode.name)
        }
    }

    fun subscriptionEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SUBSCRIPTION_ENABLED, false)

    fun setSubscriptionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_SUBSCRIPTION_ENABLED, enabled)
        }
    }

    fun vpnResumeBlocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VPN_RESUME_BLOCKED, false)

    fun setVpnResumeBlocked(context: Context, blocked: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_VPN_RESUME_BLOCKED, blocked)
        }
    }

    fun setBootStartPending(context: Context, pending: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_BOOT_START_PENDING, pending)
        }
    }

    fun consumeBootStartPending(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = preferences.getBoolean(KEY_BOOT_START_PENDING, false)
        if (pending) preferences.edit(commit = true) {
            putBoolean(KEY_BOOT_START_PENDING, false)
        }
        return pending
    }

    fun recordBootAttempt(context: Context, action: String, result: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putString("last_boot_action", action)
            putString("last_boot_result", result)
            putLong("last_boot_timestamp", System.currentTimeMillis())
        }
    }

    fun validatePorts(socksPort: Int, subscriptionPort: Int): String? = when {
        socksPort !in ProxySettings.MIN_PORT..ProxySettings.MAX_PORT ->
            "SOCKS5 端口需在 ${ProxySettings.MIN_PORT}–${ProxySettings.MAX_PORT} 之间"
        subscriptionPort !in ProxySettings.MIN_PORT..ProxySettings.MAX_PORT ->
            "订阅端口需在 ${ProxySettings.MIN_PORT}–${ProxySettings.MAX_PORT} 之间"
        socksPort == subscriptionPort -> "SOCKS5 与订阅端口不能相同"
        else -> null
    }

    private fun ProxySettings.sanitized(): ProxySettings {
        val valid = validatePorts(socksPort, subscriptionPort) == null
        return if (valid) this else copy(
            socksPort = ProxySettings.DEFAULT_SOCKS_PORT,
            subscriptionPort = ProxySettings.DEFAULT_SUBSCRIPTION_PORT,
        )
    }
}
