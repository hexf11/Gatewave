package com.hexf11.gatewave

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class Socks5Server(
    private val port: Int,
    private val udpEnabled: Boolean,
    private val performanceMode: PerformanceMode,
    private val networkProvider: Socks5Session.NetworkProvider,
    private val listener: Listener,
) : Socks5Session.Listener {
    internal interface Listener {
        fun onStatsChanged(stats: ProxyStats)
        fun onServerError(message: String)
    }

    private val capacity = ProxyCapacityPolicy.detect(performanceMode)
    private val tcpSocketTuning = TcpSocketTuningPolicy.forMode(performanceMode)
    private val sessions = ConcurrentHashMap.newKeySet<Socks5Session>()
    private val clientSessions = ConcurrentHashMap<InetAddress, MutableSet<Socks5Session>>()
    private val acceptExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedFactory("proxy-accept"))
    private val udpSlots = Semaphore(capacity.maxUdpAssociations, true)
    private val dnsResolver = VpnDnsResolver()
    private val sessionReactor = Socks5SessionReactor(
        SelectorLaneSizing.handshakeLanes(mode = performanceMode),
    )
    private val tcpRelayPool = TcpRelayPool(
        laneCount = SelectorLaneSizing.relayLanes(mode = performanceMode),
        tuning = TcpRelayTuningPolicy.forMode(performanceMode),
    )
    private val udpRelayPool = UdpRelayPool(
        SelectorLaneSizing.udpLanes(mode = performanceMode),
    )
    private val statsExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(namedFactory("proxy-stats"))
    private val totalConnections = AtomicLong()
    private val failedConnections = AtomicLong()
    private val rejectedConnections = AtomicLong()
    private val uploadBytes = AtomicLong()
    private val downloadBytes = AtomicLong()
    private val peakConnections = AtomicInteger()
    private val fairnessReclaims = AtomicLong()
    private val connectLatency = LatencyHistogram()
    private val activeConnections = AtomicInteger()
    private val activeTcp = AtomicInteger()
    private val activeUdp = AtomicInteger()

    @Volatile
    private var serverChannel: ServerSocketChannel? = null

    @Volatile
    private var running = false
    private var lastStatsNanos = System.nanoTime()
    private var lastUploadBytes = 0L
    private var lastDownloadBytes = 0L

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (running) return
        val channel = ServerSocketChannel.open()
        try {
            channel.configureBlocking(true)
            val socket = channel.socket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port), capacity.acceptBacklog)
        } catch (error: Exception) {
            runCatching { channel.close() }
            throw error
        }
        serverChannel = channel
        running = true
        acceptExecutor.execute(::acceptLoop)
        statsExecutor.scheduleWithFixedDelay(::publishStats, 0, 1, TimeUnit.SECONDS)
        Log.i(
            TAG,
            "Listening on 0.0.0.0:$port capacity=${capacity.maxSessions} " +
                "udp=${capacity.maxUdpAssociations} backlog=${capacity.acceptBacklog}",
        )
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
                val session = Socks5Session(
                    client = clientChannel,
                    udpEnabled = udpEnabled,
                    networkProvider = networkProvider,
                    listener = this,
                    tcpRelayPool = tcpRelayPool,
                    tcpSocketTuning = tcpSocketTuning,
                    dnsResolver = dnsResolver,
                    udpRelayPool = udpRelayPool,
                    udpSlots = udpSlots,
                )
                if (!admit(session)) {
                    rejectedConnections.incrementAndGet()
                    session.close()
                    continue
                }
                totalConnections.incrementAndGet()
                try {
                    sessionReactor.register(session)
                } catch (error: Exception) {
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

    @Synchronized
    private fun admit(session: Socks5Session): Boolean {
        val address = session.clientAddress()
        var current = activeConnections.get()
        val own = clientSessions[address]?.size ?: 0
        val otherClientsPresent = clientSessions.any { (key, value) ->
            key != address && value.isNotEmpty()
        }
        val clientCount = clientSessions.count { it.value.isNotEmpty() } + if (own == 0) 1 else 0

        if (current >= capacity.maxSessions) {
            // At saturation, a client below its dynamic fair share may reclaim repeatedly from a
            // dominant client. This lets late-arriving devices grow beyond a single token.
            val largestOther = clientSessions.entries
                .asSequence()
                .filter { it.key != address }
                .maxOfOrNull { it.value.size } ?: 0
            if (!ClientFairnessPolicy.canReclaim(
                    maxSessions = capacity.maxSessions,
                    clientCountIncludingIncoming = clientCount,
                    incomingSessions = own,
                    largestOtherClientSessions = largestOther,
                ) || !reclaimFor(address, ClientFairnessPolicy.fairShare(capacity.maxSessions, clientCount))
            ) {
                return false
            }
            current = activeConnections.get()
            if (current >= capacity.maxSessions) return false
        }
        if (otherClientsPresent &&
            own >= ClientFairnessPolicy.reservationThreshold(
                maxSessions = capacity.maxSessions,
                activeClientCount = clientCount,
                configuredSoftQuota = capacity.clientSoftQuota,
            ) &&
            current >= capacity.maxSessions - capacity.reservedForOtherClients
        ) {
            return false
        }

        sessions.add(session)
        clientSessions.computeIfAbsent(address) { ConcurrentHashMap.newKeySet() }.add(session)
        val active = activeConnections.incrementAndGet()
        peakConnections.accumulateAndGet(active, ::maxOf)
        return true
    }

    /** Reclaims negotiation/oldest work from a dominant client so a new LAN client can enter. */
    private fun reclaimFor(incoming: InetAddress, fairShare: Int): Boolean {
        val donor = clientSessions.entries
            .asSequence()
            .filter { it.key != incoming && it.value.size > fairShare }
            .maxByOrNull { it.value.size }
            ?: return false
        val victim = donor.value
            .filter { it.type() == Socks5Session.Type.NEGOTIATING }
            .minByOrNull(Socks5Session::openedAtNanos)
            ?: donor.value.minByOrNull(Socks5Session::openedAtNanos)
            ?: return false
        victim.close()
        fairnessReclaims.incrementAndGet()
        return true
    }

    override fun onTypeChanged(session: Socks5Session, type: Socks5Session.Type) {
        if (!sessions.contains(session)) return
        when (type) {
            Socks5Session.Type.TCP -> activeTcp.incrementAndGet()
            Socks5Session.Type.UDP -> activeUdp.incrementAndGet()
            Socks5Session.Type.NEGOTIATING -> Unit
        }
    }

    override fun onConnectResult(milliseconds: Long, success: Boolean) {
        if (success) connectLatency.record(milliseconds)
    }

    @Synchronized
    override fun onClosed(session: Socks5Session) {
        if (!sessions.remove(session)) return
        clientSessions[session.clientAddress()]?.let { clientSet ->
            clientSet.remove(session)
            if (clientSet.isEmpty()) clientSessions.remove(session.clientAddress(), clientSet)
        }
        activeConnections.decrementAndGet()
        when (session.type()) {
            Socks5Session.Type.TCP -> activeTcp.decrementAndGet()
            Socks5Session.Type.UDP -> activeUdp.decrementAndGet()
            Socks5Session.Type.NEGOTIATING -> Unit
        }
        if (session.failed()) failedConnections.incrementAndGet()
    }

    override fun onTraffic(uploaded: Long, downloaded: Long) {
        if (uploaded > 0) uploadBytes.addAndGet(uploaded)
        if (downloaded > 0) downloadBytes.addAndGet(downloaded)
    }

    fun closeSessions() {
        sessions.toList().forEach(Socks5Session::close)
        publishStats()
    }

    fun invalidateDnsCache() = dnsResolver.clear()

    fun trimMemory() = tcpRelayPool.trimMemory()

    private fun publishStats() {
        val now = System.nanoTime()
        val uploaded = uploadBytes.get()
        val downloaded = downloadBytes.get()
        val elapsed = (now - lastStatsNanos).coerceAtLeast(1L)
        val uploadRate = ((uploaded - lastUploadBytes).coerceAtLeast(0L) * 1_000_000_000L) / elapsed
        val downloadRate = ((downloaded - lastDownloadBytes).coerceAtLeast(0L) * 1_000_000_000L) / elapsed
        lastStatsNanos = now
        lastUploadBytes = uploaded
        lastDownloadBytes = downloaded
        val dns = dnsResolver.stats()
        val tcp = tcpRelayPool.stats()
        val udp = udpRelayPool.stats()
        val clientCounts = clientSessions.values.map { it.size }
        listener.onStatsChanged(
            ProxyStats(
                activeConnections = activeConnections.get().coerceAtLeast(0),
                activeTcp = activeTcp.get().coerceAtLeast(0),
                activeUdp = activeUdp.get().coerceAtLeast(0),
                totalConnections = totalConnections.get(),
                failedConnections = failedConnections.get(),
                rejectedConnections = rejectedConnections.get(),
                uploadBytes = uploaded,
                downloadBytes = downloaded,
                peakConnections = peakConnections.get(),
                activeClients = clientCounts.count { it > 0 },
                largestClientConnections = clientCounts.maxOrNull() ?: 0,
                maxSessions = capacity.maxSessions,
                maxUdpAssociations = capacity.maxUdpAssociations,
                dnsCacheHits = dns.hits,
                dnsCacheMisses = dns.misses,
                dnsCoalesced = dns.coalesced,
                dnsCacheEntries = dns.cacheEntries,
                connectP50Ms = connectLatency.percentile(0.50),
                connectP95Ms = connectLatency.percentile(0.95),
                uploadBytesPerSecond = uploadRate,
                downloadBytesPerSecond = downloadRate,
                udpDropped = udp.droppedDatagrams,
                fairnessReclaims = fairnessReclaims.get(),
                tcpSelectorLanes = tcp.lanes,
                udpSelectorLanes = udp.lanes,
                tcpPooledBufferBytes = tcp.pooledBufferBytes,
                tcpHalfClosedConnections = tcp.halfClosedConnections,
                tcpReceiveBufferBytes = tcpSocketTuning.receiveBufferBytes,
                tcpBufferedBytes = tcp.bufferedBytes,
                tcpPeakBufferedBytes = tcp.peakBufferedBytes,
                tcpEagerWriteBytes = tcp.eagerWriteBytes,
                tcpPartialWriteEvents = tcp.partialWriteEvents,
                tcpReadyBudgetYields = tcp.readyBudgetYields,
                tcpBufferPoolHits = tcp.bufferPoolHits,
                tcpDirectBufferAllocations = tcp.directBufferAllocations,
                udpFastPathHits = udp.fastPathHits,
                udpResolutionMisses = udp.resolutionMisses,
                udpMaxQueueDepth = udp.maxQueueDepth,
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
        sessionReactor.close()
        udpRelayPool.close()
        tcpRelayPool.close()
        dnsResolver.close()
    }

    companion object {
        private const val TAG = "GatewaveServer"
        private fun namedFactory(prefix: String): ThreadFactory {
            val index = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${index.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
