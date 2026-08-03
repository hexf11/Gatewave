package com.hexf11.gatewave

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

internal class ProxyService : Service(), Socks5Server.Listener {
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var settings = ProxySettings()

    @Volatile
    private var vpnNetwork: Network? = null

    @Volatile
    private var vpnInterface = "--"

    @Volatile
    private var lanIp = "0.0.0.0"

    @Volatile
    private var stats = ProxyStats.empty()

    @Volatile
    private var started = false

    @Volatile
    private var subscriptionEnabled = false

    @Volatile
    private var vpnResumeBlocked = false

    private var server: Socks5Server? = null
    private var subscriptionServer: SubscriptionServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    // PowerManager.THERMAL_STATUS_NONE is an inlined API 29 constant with the value zero.
    private var thermalStatus = 0
    private var lastNotificationState: String? = null

    override fun onCreate() {
        super.onCreate()
        settings = ProxySettingsStore.load(this)
        vpnResumeBlocked = ProxySettingsStore.vpnResumeBlocked(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        createNotificationChannel()
        registerThermalMonitor()
        registerNetworkMonitor()
        refreshLanAddress()
        refreshVpnNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.getBooleanExtra(EXTRA_MANUAL_START, false) == true) {
            setVpnResumeBlocked(false)
            refreshVpnNetwork()
        }

        ProxySettingsStore.setBootStartPending(this, false)

        promoteToForeground()
        if (!started) startProxy()

        when (intent?.action) {
            ACTION_SET_SUBSCRIPTION -> setSubscriptionEnabled(
                intent.getBooleanExtra(EXTRA_SUBSCRIPTION_ENABLED, false),
                persist = true,
            )
            ACTION_APPLY_SETTINGS -> applyPersistedSettings()
        }
        return START_STICKY
    }

    private fun startProxy() {
        settings = ProxySettingsStore.load(this)
        try {
            acquireWakeLock()
            startRuntime(
                wantsSubscription = ProxySettingsStore.subscriptionEnabled(this),
                strictSubscription = false,
            )
            publishStatus(currentRunningMessage())
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start proxy", error)
            publishStatus("启动失败：${error.message}")
            stopSelf()
        }
    }

    @Throws(Exception::class)
    private fun startRuntime(wantsSubscription: Boolean, strictSubscription: Boolean) {
        val candidate = Socks5Server(
            port = settings.socksPort,
            udpEnabled = settings.udpEnabled,
            performanceMode = settings.performanceMode,
            networkProvider = Socks5Session.NetworkProvider { vpnNetwork },
            listener = this,
        )
        try {
            candidate.start()
        } catch (error: Exception) {
            candidate.stop()
            throw error
        }
        server = candidate
        stats = ProxyStats.empty()
        started = true
        updateWifiPerformanceLock()

        if (wantsSubscription) {
            try {
                startSubscriptionOrThrow()
            } catch (error: Exception) {
                if (strictSubscription) throw error
                ProxySettingsStore.setSubscriptionEnabled(this, false)
                subscriptionEnabled = false
                Log.e(TAG, "Unable to restore subscription server", error)
                publishStatus("订阅启动失败：${error.message}")
            }
        }
    }

    private fun stopRuntime() {
        started = false
        releaseWifiLock()
        subscriptionEnabled = false
        subscriptionServer?.stop()
        subscriptionServer = null
        server?.stop()
        server = null
        stats = ProxyStats.empty()
    }

    @Synchronized
    private fun applyPersistedSettings() {
        val previous = settings
        val updated = ProxySettingsStore.load(this)
        if (updated == previous) {
            publishStatus("设置已是最新状态")
            return
        }

        if (!previous.requiresServerRestart(updated)) {
            settings = updated
            if (settings.autoResumeVpn && vpnResumeBlocked) {
                setVpnResumeBlocked(false)
                refreshVpnNetwork()
            } else {
                publishStatus("设置已应用")
            }
            return
        }

        val wantsSubscription = ProxySettingsStore.subscriptionEnabled(this)
        stopRuntime()
        settings = updated
        if (settings.autoResumeVpn && vpnResumeBlocked) {
            setVpnResumeBlocked(false)
            refreshVpnNetwork()
        }
        try {
            startRuntime(wantsSubscription, strictSubscription = true)
            publishStatus(
                "设置已应用 · SOCKS5 ${settings.socksPort} · 订阅 ${settings.subscriptionPort}",
            )
        } catch (newError: Exception) {
            Log.e(TAG, "New settings failed; restoring previous runtime", newError)
            stopRuntime()
            settings = previous
            ProxySettingsStore.save(this, previous)
            try {
                startRuntime(wantsSubscription, strictSubscription = false)
                publishStatus("新设置不可用，已恢复：${newError.message}")
            } catch (rollbackError: Exception) {
                Log.e(TAG, "Unable to restore previous settings", rollbackError)
                publishStatus("设置恢复失败：${rollbackError.message}")
                stopSelf()
            }
        }
    }

    private fun registerNetworkMonitor() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshVpnNetwork()

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = refreshVpnNetwork()

            override fun onLost(network: Network) {
                if (network == vpnNetwork) {
                    Log.w(TAG, "VPN lost: $network; closing active sessions")
                    server?.invalidateDnsCache()
                    vpnNetwork = null
                    vpnInterface = "--"
                    if (!settings.autoResumeVpn) setVpnResumeBlocked(true)
                    server?.closeSessions()
                    publishStatus(
                        if (vpnResumeBlocked) {
                            "系统 VPN 已断开；恢复后需要手动重启代理"
                        } else {
                            "系统 VPN 已断开，转发已暂停"
                        },
                    )
                } else {
                    refreshVpnNetwork()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                refreshVpnNetwork()
        }
        connectivityManager.registerDefaultNetworkCallback(checkNotNull(networkCallback))
    }

    @Synchronized
    private fun refreshVpnNetwork() {
        refreshLanAddress()
        val active = connectivityManager.activeNetwork
        val capabilities = active?.let(connectivityManager::getNetworkCapabilities)
        val ready = capabilities != null &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        if (!ready) {
            val previous = vpnNetwork
            vpnNetwork = null
            vpnInterface = "--"
            if (previous != null) {
                server?.invalidateDnsCache()
                if (!settings.autoResumeVpn) setVpnResumeBlocked(true)
                Log.w(TAG, "VPN unavailable: $previous; closing active sessions")
                server?.closeSessions()
            }
            publishStatus(
                when {
                    vpnResumeBlocked -> "等待手动重启代理（VPN 自动恢复已关闭）"
                    started -> "等待系统 VPN（防泄漏暂停）"
                    else -> "系统 VPN 未接入"
                },
            )
            return
        }

        if (vpnResumeBlocked && !settings.autoResumeVpn) {
            vpnNetwork = null
            vpnInterface = "--"
            publishStatus("VPN 已恢复，请停止并重新启动代理")
            return
        }

        val previous = vpnNetwork
        if (previous != null && previous != active) {
            Log.i(TAG, "VPN switching $previous -> $active; closing active sessions")
            server?.invalidateDnsCache()
            vpnNetwork = null
            server?.closeSessions()
        }
        vpnNetwork = active
        val properties = connectivityManager.getLinkProperties(active)
        vpnInterface = properties?.interfaceName?.let { "$it / network $active" }
            ?: "network $active"
        Log.i(TAG, "VPN ready: $vpnInterface")
        publishStatus(if (started) currentRunningMessage() else "系统 VPN 已接入")
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        lastNotificationState = notificationState()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        var state = if (vpnNetwork == null) {
            "等待 VPN"
        } else {
            "VPN 已锁定 · TCP ${stats.activeTcp} / UDP ${stats.activeUdp}"
        }
        if (subscriptionEnabled) state += " · 订阅已开启"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.gatewave_notification)
            .setContentTitle("Gatewave · 端口 ${settings.socksPort}")
            .setContentText(state)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .build()
    }

    private fun notificationState(): String = listOf(
        settings.socksPort,
        vpnNetwork != null,
        stats.activeTcp,
        stats.activeUdp,
        subscriptionEnabled,
    ).joinToString("|")

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "代理服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示局域网 SOCKS5 代理的运行状态"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        // 前台代理服务需要持续持锁；onDestroy 会显式释放，固定超时会中断长期共享。
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:proxy",
        ).also { it.acquire() }
    }

    @Suppress("DEPRECATION")
    private fun updateWifiPerformanceLock() {
        val shouldHold = started &&
            settings.performanceMode == PerformanceMode.TURBO &&
            (Build.VERSION.SDK_INT < 29 || thermalStatus < PowerManager.THERMAL_STATUS_SEVERE)
        if (!shouldHold) {
            releaseWifiLock()
            return
        }
        if (wifiLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(mode, "$packageName:turbo").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun registerThermalMonitor() {
        if (Build.VERSION.SDK_INT < 29) return
        val powerManager = getSystemService(PowerManager::class.java)
        thermalStatus = powerManager.currentThermalStatus
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            thermalStatus = status
            updateWifiPerformanceLock()
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                server?.trimMemory()
                publishStatus("设备温度较高，已降低 Wi-Fi 性能锁并回收空闲缓冲")
            }
        }
        thermalListener = listener
        powerManager.addThermalStatusListener(listener)
    }

    private fun currentRunningMessage(): String = when {
        vpnResumeBlocked -> "等待手动重启代理（VPN 自动恢复已关闭）"
        vpnNetwork != null -> "代理已启动，出口锁定系统 VPN"
        else -> "等待系统 VPN（防泄漏暂停）"
    }

    private fun setVpnResumeBlocked(blocked: Boolean) {
        vpnResumeBlocked = blocked
        ProxySettingsStore.setVpnResumeBlocked(this, blocked)
    }

    private fun publishStatus(message: String, persistNow: Boolean = false) {
        val endpoint = "$lanIp:${settings.socksPort}"
        val subscriptionUrl = if (subscriptionEnabled && lanIp != "0.0.0.0") {
            "http://$lanIp:${settings.subscriptionPort}${SubscriptionServer.CONFIG_PATH}"
        } else {
            "--"
        }
        ProxyStatus(
            running = started,
            vpnReady = vpnNetwork != null,
            endpoint = endpoint,
            vpnInterface = vpnInterface,
            stats = stats,
            subscriptionEnabled = subscriptionEnabled,
            subscriptionUrl = subscriptionUrl,
            message = message,
        ).publish(this, persistNow)
        if (started) {
            val notificationState = notificationState()
            if (notificationState != lastNotificationState) {
                lastNotificationState = notificationState
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun refreshLanAddress() {
        lanIp = NetworkUtils.findLanIpv4(this)
    }

    override fun onStatsChanged(stats: ProxyStats) {
        if (stats.sameAs(this.stats)) {
            ProxyStatus.flushLatestIfDue(this)
            return
        }
        this.stats = stats
        publishStatus(currentRunningMessage())
    }

    override fun onServerError(message: String) {
        publishStatus(message)
    }

    @Throws(Exception::class)
    private fun startSubscriptionOrThrow() {
        val candidate = SubscriptionServer(
            context = this,
            port = settings.subscriptionPort,
            configProvider = SubscriptionServer.ConfigProvider {
                ClashConfigGenerator.generate(
                    serverAddress = lanIp,
                    socksPort = settings.socksPort,
                    udpEnabled = settings.udpEnabled,
                )
            },
            listener = SubscriptionServer.Listener(::onServerError),
        )
        candidate.start()
        subscriptionServer = candidate
        subscriptionEnabled = true
        Log.i(TAG, "Subscription enabled at ${settings.subscriptionPort}")
    }

    @Synchronized
    private fun setSubscriptionEnabled(enabled: Boolean, persist: Boolean) {
        if (persist) ProxySettingsStore.setSubscriptionEnabled(this, enabled)
        if (enabled && subscriptionServer == null) {
            try {
                startSubscriptionOrThrow()
            } catch (error: Exception) {
                subscriptionEnabled = false
                ProxySettingsStore.setSubscriptionEnabled(this, false)
                Log.e(TAG, "Unable to start subscription server", error)
                publishStatus("订阅启动失败：${error.message}")
                return
            }
        } else if (!enabled && subscriptionServer != null) {
            subscriptionServer?.stop()
            subscriptionServer = null
            subscriptionEnabled = false
            Log.i(TAG, "Subscription disabled")
        }
        publishStatus(currentRunningMessage())
    }

    override fun onDestroy() {
        stopRuntime()
        vpnNetwork = null
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        releaseWifiLock()
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener?.let {
                runCatching { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
            }
        }
        ProxyStatus(
            running = false,
            vpnReady = false,
            endpoint = "$lanIp:${settings.socksPort}",
            vpnInterface = "--",
            stats = ProxyStats.empty(),
            subscriptionEnabled = false,
            subscriptionUrl = "--",
            message = "服务已停止",
        ).publish(this, persistNow = true)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) server?.trimMemory()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.hexf11.gatewave.START"
        const val ACTION_STOP = "com.hexf11.gatewave.STOP"
        const val ACTION_SET_SUBSCRIPTION = "com.hexf11.gatewave.SET_SUBSCRIPTION"
        const val ACTION_APPLY_SETTINGS = "com.hexf11.gatewave.APPLY_SETTINGS"
        const val EXTRA_SUBSCRIPTION_ENABLED = "subscription_enabled"
        const val EXTRA_MANUAL_START = "manual_start"

        private const val TAG = "GatewaveService"
        private const val CHANNEL_ID = "gatewave_proxy_service"
        private const val NOTIFICATION_ID = 1080
    }
}
