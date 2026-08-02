package com.hexf11.gatewave

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class Socks5Server(
    private val port: Int,
    private val udpEnabled: Boolean,
    private val networkProvider: Socks5Session.NetworkProvider,
    private val listener: Listener,
) : Socks5Session.Listener {
    internal interface Listener {
        fun onStatsChanged(stats: ProxyStats)
        fun onServerError(message: String)
    }

    private val sessions = ConcurrentHashMap.newKeySet<Socks5Session>()
    private val executor: ExecutorService = Executors.newCachedThreadPool(namedFactory("proxy-io"))
    private val statsExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(namedFactory("proxy-stats"))
    private val totalConnections = AtomicLong()
    private val failedConnections = AtomicLong()
    private val rejectedConnections = AtomicLong()
    private val uploadBytes = AtomicLong()
    private val downloadBytes = AtomicLong()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", port), 64)
        serverSocket = socket
        running = true
        executor.execute(::acceptLoop)
        statsExecutor.scheduleWithFixedDelay(::publishStats, 0, 1, TimeUnit.SECONDS)
        Log.i(TAG, "Listening on 0.0.0.0:$port")
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = serverSocket?.accept() ?: return
                if (!NetworkUtils.isAllowedClient(client.inetAddress)) {
                    Log.w(TAG, "Rejected non-LAN client ${client.inetAddress}")
                    rejectedConnections.incrementAndGet()
                    client.close()
                    continue
                }
                if (sessions.size >= MAX_CONCURRENT_SESSIONS) {
                    Log.w(TAG, "Rejected client: concurrent session limit $MAX_CONCURRENT_SESSIONS")
                    rejectedConnections.incrementAndGet()
                    client.close()
                    continue
                }
                val session = Socks5Session(
                    client = client,
                    udpEnabled = udpEnabled,
                    networkProvider = networkProvider,
                    listener = this,
                    relayExecutor = executor,
                )
                sessions.add(session)
                totalConnections.incrementAndGet()
                executor.execute(session)
            } catch (error: IOException) {
                if (running) {
                    Log.e(TAG, "Accept failed", error)
                    listener.onServerError("监听失败：${error.message}")
                }
            }
        }
    }

    override fun onClosed(session: Socks5Session) {
        sessions.remove(session)
        if (session.failed()) failedConnections.incrementAndGet()
    }

    override fun onTraffic(uploaded: Long, downloaded: Long) {
        if (uploaded > 0) uploadBytes.addAndGet(uploaded)
        if (downloaded > 0) downloadBytes.addAndGet(downloaded)
    }

    fun closeSessions() {
        sessions.forEach(Socks5Session::close)
        sessions.clear()
        publishStats()
    }

    private fun publishStats() {
        val tcp = sessions.count { it.type() == Socks5Session.Type.TCP }
        val udp = sessions.count { it.type() == Socks5Session.Type.UDP }
        listener.onStatsChanged(
            ProxyStats(
                activeConnections = sessions.size,
                activeTcp = tcp,
                activeUdp = udp,
                totalConnections = totalConnections.get(),
                failedConnections = failedConnections.get(),
                rejectedConnections = rejectedConnections.get(),
                uploadBytes = uploadBytes.get(),
                downloadBytes = downloadBytes.get(),
            ),
        )
    }

    @Synchronized
    fun stop() {
        running = false
        val socket = serverSocket
        serverSocket = null
        runCatching { socket?.close() }
        closeSessions()
        statsExecutor.shutdownNow()
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "GatewaveServer"
        const val MAX_CONCURRENT_SESSIONS = 256

        private fun namedFactory(prefix: String): ThreadFactory {
            val index = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${index.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
