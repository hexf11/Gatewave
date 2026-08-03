package com.hexf11.gatewave

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexf11.gatewave.ui.GatewaveApp
import com.hexf11.gatewave.ui.GatewaveTheme

class MainActivity : ComponentActivity() {
    private val proxyViewModel by viewModels<ProxyViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        setContent {
            val state by proxyViewModel.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (state.settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            GatewaveTheme(themeMode = state.settings.themeMode) {
                GatewaveApp(
                    state = state,
                    onToggleProxy = { if (state.running) stopProxy() else startProxy(manual = true) },
                    onOpenVpn = ::openGoogleVpn,
                    onToggleSubscription = { toggleSubscription(!state.subscriptionEnabled) },
                    onCopySubscription = {
                        state.subscriptionEnabled && state.subscriptionUrl != "--" &&
                            copyText("Gatewave 订阅", state.subscriptionUrl)
                    },
                    onShareSubscription = {
                        shareSubscription(state.subscriptionUrl, state.subscriptionEnabled)
                    },
                    onSavePorts = proxyViewModel::updatePorts,
                    onSetUdpEnabled = proxyViewModel::setUdpEnabled,
                    onSetAutoResumeVpn = proxyViewModel::setAutoResumeVpn,
                    onSetStartOnAppLaunch = proxyViewModel::setStartOnAppLaunch,
                    onSetStartOnBoot = proxyViewModel::setStartOnBoot,
                    onSetThemeMode = proxyViewModel::setThemeMode,
                    onSetPerformanceMode = proxyViewModel::setPerformanceMode,
                    onResetSettings = proxyViewModel::resetSettings,
                    onRunDiagnostics = proxyViewModel::runDiagnostics,
                    onCopyDiagnostics = {
                        copyText(
                            "Gatewave 诊断报告",
                            state.diagnostics.report?.text.orEmpty(),
                        )
                    },
                    onShareDiagnostics = {
                        shareText(
                            subject = "Gatewave 网络诊断",
                            text = state.diagnostics.report?.text.orEmpty(),
                            chooserTitle = "分享诊断报告",
                        )
                    },
                )
            }
        }

        val saved = ProxyStatus.load(this)
        val settings = ProxySettingsStore.load(this)
        val pendingBootStart = ProxySettingsStore.consumeBootStartPending(this)
        if (intent.getBooleanExtra("start_proxy", false) ||
            saved.running ||
            settings.startOnAppLaunch ||
            pendingBootStart
        ) {
            startProxy(manual = false)
        }
    }

    override fun onResume() {
        super.onResume()
        proxyViewModel.refresh()
    }

    private fun startProxy(manual: Boolean) {
        startForegroundService(
            Intent(this, ProxyService::class.java)
                .setAction(ProxyService.ACTION_START)
                .putExtra(ProxyService.EXTRA_MANUAL_START, manual)
        )
    }

    private fun stopProxy() {
        startService(Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP))
    }

    private fun toggleSubscription(enabled: Boolean) {
        startForegroundService(
            Intent(this, ProxyService::class.java)
                .setAction(ProxyService.ACTION_SET_SUBSCRIPTION)
                .putExtra(ProxyService.EXTRA_SUBSCRIPTION_ENABLED, enabled)
        )
    }

    private fun openGoogleVpn() {
        val direct = Intent(Intent.ACTION_MAIN).setComponent(GOOGLE_VPN_SETTINGS)
        runCatching { startActivity(direct) }
            .onFailure { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
    }

    private fun copyText(label: String, value: String): Boolean {
        if (value.isBlank() || value.startsWith("--")) return false
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, value))
        return true
    }

    private fun shareSubscription(url: String, enabled: Boolean): Boolean {
        if (!enabled || url == "--") return false
        return shareText("Gatewave Clash 配置", url, "分享订阅链接")
    }

    private fun shareText(subject: String, text: String, chooserTitle: String): Boolean {
        if (text.isBlank()) return false
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(share, chooserTitle))
        return true
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private companion object {
        val GOOGLE_VPN_SETTINGS = ComponentName(
            "com.google.android.apps.privacy.wildlife",
            "com.google.android.apps.privacy.wildlife.settings.settingsui.MainSettingsActivity",
        )
    }
}
