package com.hexf11.gatewave

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
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
    private val acceptExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedFactory("proxy-accept"))
    private val sessionExecutor: ExecutorService =
        newSessionExecutor()
    private val udpExecutor: ExecutorService =
        Executors.newFixedThreadPool(MAX_UDP_ASSOCIATIONS * 2, namedFactory("proxy-udp"))
    private val udpSlots = Semaphore(MAX_UDP_ASSOCIATIONS, true)
    private val tcpRelayPool = TcpRelayPool()
    private val statsExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(namedFactory("proxy-stats"))
    private val totalConnections = AtomicLong()
    private val failedConnections = AtomicLong()
    private val rejectedConnections = AtomicLong()
    private val uploadBytes = AtomicLong()
    private val downloadBytes = AtomicLong()

    @Volatile
    private var serverChannel: ServerSocketChannel? = null

    @Volatile
    private var running = false

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (running) return
        val channel = ServerSocketChannel.open()
        channel.configureBlocking(true)
        val socket = channel.socket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", port), ACCEPT_BACKLOG)
        serverChannel = channel
        running = true
        acceptExecutor.execute(::acceptLoop)
        statsExecutor.scheduleWithFixedDelay(::publishStats, 0, 1, TimeUnit.SECONDS)
        Log.i(TAG, "Listening on 0.0.0.0:$port")
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val clientChannel = serverChannel?.accept() ?: return
                val client = clientChannel.socket()
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
                    tcpRelayPool = tcpRelayPool,
                    udpExecutor = udpExecutor,
                    udpSlots = udpSlots,
                )
                sessions.add(session)
                totalConnections.incrementAndGet()
                try {
                    sessionExecutor.execute(session)
                } catch (_: RejectedExecutionException) {
                    sessions.remove(session)
                    rejectedConnections.incrementAndGet()
                    session.close()
                }
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
        val channel = serverChannel
        serverChannel = null
        runCatching { channel?.close() }
        closeSessions()
        statsExecutor.shutdownNow()
        acceptExecutor.shutdownNow()
        sessionExecutor.shutdownNow()
        udpExecutor.shutdownNow()
        tcpRelayPool.close()
    }

    companion object {
        private const val TAG = "GatewaveServer"
        const val MAX_CONCURRENT_SESSIONS = 256
        const val MAX_UDP_ASSOCIATIONS = 16
        private const val ACCEPT_BACKLOG = 256
        private const val CORE_SESSION_WORKERS = 8
        private const val MAX_SESSION_WORKERS = 128
        private const val SESSION_KEEP_ALIVE_SECONDS = 30L

        private fun newSessionExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
            CORE_SESSION_WORKERS,
            MAX_SESSION_WORKERS,
            SESSION_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            namedFactory("proxy-session"),
            BlockingRejectionHandler(),
        ).apply {
            allowCoreThreadTimeOut(true)
        }

        private class BlockingRejectionHandler : RejectedExecutionHandler {
            override fun rejectedExecution(task: Runnable, executor: ThreadPoolExecutor) {
                while (!executor.isShutdown) {
                    try {
                        if (executor.queue.offer(task, 100, TimeUnit.MILLISECONDS)) return
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw RejectedExecutionException("Interrupted while throttling accepts", error)
                    }
                }
                throw RejectedExecutionException("Session executor is closed")
            }
        }

        private fun namedFactory(prefix: String): ThreadFactory {
            val index = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${index.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
