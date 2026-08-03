package com.hexf11.gatewave

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProxyUiState(
    val running: Boolean = false,
    val vpnReady: Boolean = false,
    val endpoint: String = "--:1080",
    val vpnInterface: String = "--",
    val activeConnections: Int = 0,
    val activeTcp: Int = 0,
    val activeUdp: Int = 0,
    val totalConnections: Long = 0,
    val failedConnections: Long = 0,
    val rejectedConnections: Long = 0,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val peakConnections: Int = 0,
    val activeClients: Int = 0,
    val largestClientConnections: Int = 0,
    val maxSessions: Int = 0,
    val maxUdpAssociations: Int = 0,
    val dnsCacheHits: Long = 0,
    val dnsCacheMisses: Long = 0,
    val dnsCoalesced: Long = 0,
    val dnsCacheEntries: Int = 0,
    val connectP50Ms: Long = 0,
    val connectP95Ms: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val udpDropped: Long = 0,
    val fairnessReclaims: Long = 0,
    val tcpSelectorLanes: Int = 0,
    val udpSelectorLanes: Int = 0,
    val tcpPooledBufferBytes: Int = 0,
    val tcpHalfClosedConnections: Int = 0,
    val tcpReceiveBufferBytes: Int = 0,
    val udpFastPathHits: Long = 0,
    val udpResolutionMisses: Long = 0,
    val udpMaxQueueDepth: Int = 0,
    val subscriptionEnabled: Boolean = false,
    val subscriptionUrl: String = "--",
    val message: String = "服务未启动",
    val settings: ProxySettings = ProxySettings(),
    val diagnostics: DiagnosticsUiState = DiagnosticsUiState(),
) {
    val totalBytes: Long get() = uploadBytes + downloadBytes
}

data class SettingsUpdateResult(val success: Boolean, val message: String)

class ProxyViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<Application>()
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<ProxyUiState> = _state.asStateFlow()
    private var diagnosticsJob: Job? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    init {
        val filter = IntentFilter(ProxyStatus.ACTION_CHANGED)
        ContextCompat.registerReceiver(
            app,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun refresh() {
        val diagnostics = _state.value.diagnostics
        _state.value = loadState().copy(diagnostics = diagnostics)
    }

    fun runDiagnostics() {
        if (_state.value.diagnostics.running) return
        val previousReport = _state.value.diagnostics.report
        _state.value = _state.value.copy(
            diagnostics = DiagnosticsUiState(running = true, report = previousReport),
        )
        diagnosticsJob?.cancel()
        diagnosticsJob = viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { NetworkDiagnostics.run(app) }
                    .getOrElse(NetworkDiagnostics::failureReport)
            }
            _state.value = loadState().copy(
                diagnostics = DiagnosticsUiState(running = false, report = report),
            )
        }
    }

    fun updatePorts(socksPortText: String, subscriptionPortText: String): SettingsUpdateResult {
        val socksPort = socksPortText.toIntOrNull()
            ?: return SettingsUpdateResult(false, "请输入有效的 SOCKS5 端口")
        val subscriptionPort = subscriptionPortText.toIntOrNull()
            ?: return SettingsUpdateResult(false, "请输入有效的订阅端口")
        ProxySettingsStore.validatePorts(socksPort, subscriptionPort)?.let {
            return SettingsUpdateResult(false, it)
        }
        val current = ProxySettingsStore.load(app)
        if (current.socksPort == socksPort && current.subscriptionPort == subscriptionPort) {
            return SettingsUpdateResult(true, "端口设置未发生变化")
        }
        persistSettings(
            current.copy(socksPort = socksPort, subscriptionPort = subscriptionPort),
            applyToService = true,
        )
        return SettingsUpdateResult(true, "正在应用端口设置")
    }

    fun setUdpEnabled(enabled: Boolean): SettingsUpdateResult {
        val current = ProxySettingsStore.load(app)
        persistSettings(current.copy(udpEnabled = enabled), applyToService = true)
        return SettingsUpdateResult(true, if (enabled) "UDP 转发已开启" else "UDP 转发已关闭")
    }

    fun setAutoResumeVpn(enabled: Boolean): SettingsUpdateResult {
        val current = ProxySettingsStore.load(app)
        persistSettings(current.copy(autoResumeVpn = enabled), applyToService = true)
        return SettingsUpdateResult(
            true,
            if (enabled) "VPN 恢复后将自动继续" else "VPN 恢复后需要手动重启代理",
        )
    }

    fun setStartOnAppLaunch(enabled: Boolean): SettingsUpdateResult {
        val current = ProxySettingsStore.load(app)
        persistSettings(current.copy(startOnAppLaunch = enabled), applyToService = false)
        return SettingsUpdateResult(true, if (enabled) "打开 App 时将启动代理" else "已关闭 App 自动启动")
    }

    fun setStartOnBoot(enabled: Boolean): SettingsUpdateResult {
        val current = ProxySettingsStore.load(app)
        persistSettings(current.copy(startOnBoot = enabled), applyToService = false)
        return SettingsUpdateResult(true, if (enabled) "开机启动已开启" else "开机启动已关闭")
    }

    fun setThemeMode(themeMode: ThemeMode): SettingsUpdateResult {
        val current = ProxySettingsStore.load(app)
        persistSettings(current.copy(themeMode = themeMode), applyToService = false)
        return SettingsUpdateResult(true, "主题已更新")
    }

    fun setPerformanceMode(mode: PerformanceMode): SettingsUpdateResult {
        val current = ProxySettingsStore.load(app)
        persistSettings(current.copy(performanceMode = mode), applyToService = true)
        val label = when (mode) {
            PerformanceMode.BALANCED -> "均衡"
            PerformanceMode.TURBO -> "极速"
            PerformanceMode.POWER_SAVE -> "省电"
        }
        return SettingsUpdateResult(true, "正在切换到${label}模式")
    }

    fun resetSettings(): SettingsUpdateResult {
        val running = ProxyStatus.load(app).running
        ProxySettingsStore.save(app, ProxySettings())
        ProxySettingsStore.setSubscriptionEnabled(app, false)
        ProxySettingsStore.setVpnResumeBlocked(app, false)
        ProxySettingsStore.setBootStartPending(app, false)
        if (running) {
            ContextCompat.startForegroundService(
                app,
                Intent(app, ProxyService::class.java)
                    .setAction(ProxyService.ACTION_SET_SUBSCRIPTION)
                    .putExtra(ProxyService.EXTRA_SUBSCRIPTION_ENABLED, false),
            )
            applySettingsToService()
        }
        refresh()
        return SettingsUpdateResult(true, "已恢复默认设置")
    }

    private fun persistSettings(settings: ProxySettings, applyToService: Boolean) {
        ProxySettingsStore.save(app, settings)
        val running = ProxyStatus.load(app).running
        refresh()
        if (applyToService && running) applySettingsToService()
    }

    private fun applySettingsToService() {
        ContextCompat.startForegroundService(
            app,
            Intent(app, ProxyService::class.java).setAction(ProxyService.ACTION_APPLY_SETTINGS),
        )
    }

    private fun loadState(): ProxyUiState {
        val status = ProxyStatus.load(app)
        val stats = status.stats
        return ProxyUiState(
            running = status.running,
            vpnReady = status.vpnReady,
            endpoint = status.endpoint,
            vpnInterface = status.vpnInterface,
            activeConnections = stats.activeConnections,
            activeTcp = stats.activeTcp,
            activeUdp = stats.activeUdp,
            totalConnections = stats.totalConnections,
            failedConnections = stats.failedConnections,
            rejectedConnections = stats.rejectedConnections,
            uploadBytes = stats.uploadBytes,
            downloadBytes = stats.downloadBytes,
            peakConnections = stats.peakConnections,
            activeClients = stats.activeClients,
            largestClientConnections = stats.largestClientConnections,
            maxSessions = stats.maxSessions,
            maxUdpAssociations = stats.maxUdpAssociations,
            dnsCacheHits = stats.dnsCacheHits,
            dnsCacheMisses = stats.dnsCacheMisses,
            dnsCoalesced = stats.dnsCoalesced,
            dnsCacheEntries = stats.dnsCacheEntries,
            connectP50Ms = stats.connectP50Ms,
            connectP95Ms = stats.connectP95Ms,
            uploadBytesPerSecond = stats.uploadBytesPerSecond,
            downloadBytesPerSecond = stats.downloadBytesPerSecond,
            udpDropped = stats.udpDropped,
            fairnessReclaims = stats.fairnessReclaims,
            tcpSelectorLanes = stats.tcpSelectorLanes,
            udpSelectorLanes = stats.udpSelectorLanes,
            tcpPooledBufferBytes = stats.tcpPooledBufferBytes,
            tcpHalfClosedConnections = stats.tcpHalfClosedConnections,
            tcpReceiveBufferBytes = stats.tcpReceiveBufferBytes,
            udpFastPathHits = stats.udpFastPathHits,
            udpResolutionMisses = stats.udpResolutionMisses,
            udpMaxQueueDepth = stats.udpMaxQueueDepth,
            subscriptionEnabled = status.subscriptionEnabled,
            subscriptionUrl = status.subscriptionUrl,
            message = status.message,
            settings = ProxySettingsStore.load(app),
        )
    }

    override fun onCleared() {
        runCatching { app.unregisterReceiver(statusReceiver) }
        super.onCleared()
    }
}
