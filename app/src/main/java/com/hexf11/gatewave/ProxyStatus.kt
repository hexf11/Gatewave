package com.hexf11.gatewave

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    fun publish(context: Context, persistNow: Boolean = false) {
        liveStatus.set(this)
        persistIfDue(context, persistNow)
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    private fun persistIfDue(context: Context, persistNow: Boolean) {
        val now = System.nanoTime()
        val previousPersist = lastPersistNanos.get()
        val shouldPersist = persistNow || previousPersist == 0L ||
            now - previousPersist >= PERSIST_INTERVAL_NANOS
        if (shouldPersist &&
            (persistNow || lastPersistNanos.compareAndSet(previousPersist, now))
        ) {
            if (persistNow) lastPersistNanos.set(now)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
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
                putInt("peakConnections", stats.peakConnections)
                putInt("activeClients", stats.activeClients)
                putInt("largestClientConnections", stats.largestClientConnections)
                putInt("maxSessions", stats.maxSessions)
                putInt("maxUdpAssociations", stats.maxUdpAssociations)
                putLong("dnsCacheHits", stats.dnsCacheHits)
                putLong("dnsCacheMisses", stats.dnsCacheMisses)
                putLong("dnsCoalesced", stats.dnsCoalesced)
                putInt("dnsCacheEntries", stats.dnsCacheEntries)
                putLong("connectP50Ms", stats.connectP50Ms)
                putLong("connectP95Ms", stats.connectP95Ms)
                putLong("uploadBytesPerSecond", stats.uploadBytesPerSecond)
                putLong("downloadBytesPerSecond", stats.downloadBytesPerSecond)
                putLong("udpDropped", stats.udpDropped)
                putLong("fairnessReclaims", stats.fairnessReclaims)
                putInt("tcpSelectorLanes", stats.tcpSelectorLanes)
                putInt("udpSelectorLanes", stats.udpSelectorLanes)
                putInt("tcpPooledBufferBytes", stats.tcpPooledBufferBytes)
                putInt("tcpHalfClosedConnections", stats.tcpHalfClosedConnections)
                putBoolean("subscriptionEnabled", subscriptionEnabled)
                putString("subscriptionUrl", subscriptionUrl)
                putString("message", message)
            }
        }

    }

    companion object {
        const val ACTION_CHANGED = "com.hexf11.gatewave.STATUS_CHANGED"
        private const val PREFS = "proxy_status"
        private const val PERSIST_INTERVAL_NANOS = 5_000_000_000L
        private val liveStatus = AtomicReference<ProxyStatus?>()
        private val lastPersistNanos = AtomicLong(0L)

        /** Flushes a stable in-memory status after the persistence throttle window, without UI I/O. */
        fun flushLatestIfDue(context: Context) {
            liveStatus.get()?.persistIfDue(context, persistNow = false)
        }

        @JvmStatic
        fun load(context: Context): ProxyStatus {
            liveStatus.get()?.let { return it }
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
                    peakConnections = preferences.getInt("peakConnections", 0),
                    activeClients = preferences.getInt("activeClients", 0),
                    largestClientConnections = preferences.getInt("largestClientConnections", 0),
                    maxSessions = preferences.getInt("maxSessions", 0),
                    maxUdpAssociations = preferences.getInt("maxUdpAssociations", 0),
                    dnsCacheHits = preferences.getLong("dnsCacheHits", 0),
                    dnsCacheMisses = preferences.getLong("dnsCacheMisses", 0),
                    dnsCoalesced = preferences.getLong("dnsCoalesced", 0),
                    dnsCacheEntries = preferences.getInt("dnsCacheEntries", 0),
                    connectP50Ms = preferences.getLong("connectP50Ms", 0),
                    connectP95Ms = preferences.getLong("connectP95Ms", 0),
                    uploadBytesPerSecond = preferences.getLong("uploadBytesPerSecond", 0),
                    downloadBytesPerSecond = preferences.getLong("downloadBytesPerSecond", 0),
                    udpDropped = preferences.getLong("udpDropped", 0),
                    fairnessReclaims = preferences.getLong("fairnessReclaims", 0),
                    tcpSelectorLanes = preferences.getInt("tcpSelectorLanes", 0),
                    udpSelectorLanes = preferences.getInt("udpSelectorLanes", 0),
                    tcpPooledBufferBytes = preferences.getInt("tcpPooledBufferBytes", 0),
                    tcpHalfClosedConnections = preferences.getInt("tcpHalfClosedConnections", 0),
                ),
                subscriptionEnabled = preferences.getBoolean("subscriptionEnabled", false),
                subscriptionUrl = preferences.getString("subscriptionUrl", "--") ?: "--",
                message = preferences.getString("message", "服务未启动") ?: "服务未启动",
            )
        }
    }
}
