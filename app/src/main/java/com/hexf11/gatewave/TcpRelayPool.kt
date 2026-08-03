package com.hexf11.gatewave

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared non-blocking TCP relay.
 *
 * Each connection is assigned to one selector lane instead of keeping two blocking threads alive.
 */
internal class TcpRelayPool(
    laneCount: Int = SelectorLaneSizing.relayLanes(),
    private val tuning: TcpRelayTuning = TcpRelayTuningPolicy.forMode(PerformanceMode.BALANCED),
) : Closeable {
    fun interface CloseListener {
        fun onClosed(failed: Boolean)
    }

    fun interface TrafficListener {
        fun onTraffic(uploaded: Long, downloaded: Long)
    }

    private val closed = AtomicBoolean(false)
    private val nextLane = AtomicInteger()
    private val lanes = List(laneCount.coerceAtLeast(1)) { index ->
        RelayLane("proxy-tcp-${index + 1}", tuning)
    }

    init {
        Log.i(TAG, "Started ${lanes.size} dynamic TCP selector lane(s)")
    }

    @Throws(IOException::class)
    fun register(
        client: SocketChannel,
        remote: SocketChannel,
        clientAddress: InetAddress = client.socket().inetAddress,
        initialClientData: ByteArray = EMPTY_CLIENT_DATA,
        trafficListener: TrafficListener,
        closeListener: CloseListener,
    ) {
        if (closed.get()) throw IOException("TCP relay pool is closed")
        val index = (nextLane.getAndIncrement() and Int.MAX_VALUE) % lanes.size
        lanes[index].register(
            client,
            remote,
            clientAddress,
            initialClientData,
            trafficListener,
            closeListener,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lanes.forEach(RelayLane::close)
    }

    fun trimMemory() {
        lanes.forEach(RelayLane::trimMemory)
    }

    data class Stats(
        val connections: Int,
        val halfClosedConnections: Int,
        val lanes: Int,
        val pooledBufferBytes: Int,
        val bufferedBytes: Long,
        val peakBufferedBytes: Long,
        val eagerWriteBytes: Long,
        val partialWriteEvents: Long,
        val readyBudgetYields: Long,
        val bufferPoolHits: Long,
        val directBufferAllocations: Long,
        val interactiveWriteBytes: Long,
        val fairnessDeferredReads: Long,
        val queueDelayP50Us: Long,
        val queueDelayP95Us: Long,
        val rescheduleDelayP50Us: Long,
        val rescheduleDelayP95Us: Long,
    )

    fun stats(): Stats = Stats(
        connections = lanes.sumOf(RelayLane::connectionCount),
        halfClosedConnections = lanes.sumOf(RelayLane::halfClosedConnectionCount),
        lanes = lanes.size,
        pooledBufferBytes = lanes.sumOf(RelayLane::pooledBufferBytes),
        bufferedBytes = lanes.sumOf(RelayLane::bufferedBytes),
        peakBufferedBytes = lanes.sumOf(RelayLane::peakBufferedBytes),
        eagerWriteBytes = lanes.sumOf(RelayLane::eagerWriteBytes),
        partialWriteEvents = lanes.sumOf(RelayLane::partialWriteEvents),
        readyBudgetYields = lanes.sumOf(RelayLane::readyBudgetYields),
        bufferPoolHits = lanes.sumOf(RelayLane::bufferPoolHits),
        directBufferAllocations = lanes.sumOf(RelayLane::directBufferAllocations),
        interactiveWriteBytes = lanes.sumOf(RelayLane::interactiveWriteBytes),
        fairnessDeferredReads = lanes.sumOf(RelayLane::fairnessDeferredReads),
        queueDelayP50Us = lanes.maxOfOrNull(RelayLane::queueDelayP50Us) ?: 0,
        queueDelayP95Us = lanes.maxOfOrNull(RelayLane::queueDelayP95Us) ?: 0,
        rescheduleDelayP50Us = lanes.maxOfOrNull(RelayLane::rescheduleDelayP50Us) ?: 0,
        rescheduleDelayP95Us = lanes.maxOfOrNull(RelayLane::rescheduleDelayP95Us) ?: 0,
    )

    private class RelayLane(
        threadName: String,
        private val tuning: TcpRelayTuning,
    ) : Closeable {
        private val selector = Selector.open()
        private val running = AtomicBoolean(true)
        private val pending = ConcurrentLinkedQueue<Registration>()
        private val maintenance = ConcurrentLinkedQueue<() -> Unit>()
        private val connections = ConcurrentHashMap.newKeySet<Connection>()
        private val buffers = LaneDirectBufferPool(tuning.maxPooledBytesPerLane)

        private var halfClosedConnectionCount = 0
        private var bufferedByteCount = 0L
        private var peakBufferedByteCount = 0L
        private var eagerWriteByteCount = 0L
        private var partialWriteEventCount = 0L
        private var readyBudgetYieldCount = 0L
        private var interactiveWriteByteCount = 0L
        private var fairnessDeferredReadCount = 0L

        private val queueDelayMicros = SampledLatencyHistogram(RELAY_LATENCY_BOUNDS_US)
        private val rescheduleDelayMicros = SampledLatencyHistogram(RELAY_LATENCY_BOUNDS_US)
        private val downloadScheduler = TcpRelayRoundScheduler<InetAddress>()
        private val clientConnections = HashMap<InetAddress, Int>()
        private val idleWheel = IdleTimeoutWheel<Connection>(
            tickNanos = IDLE_TICK_NANOS,
            wheelSize = IDLE_WHEEL_SIZE,
        )
        @Volatile
        private var statsSnapshot = LaneStats()

        private var nextPeriodicMaintenanceNanos =
            System.nanoTime() + TRAFFIC_FLUSH_INTERVAL_NANOS
        private val thread = Thread(::runLoop, threadName).apply {
            isDaemon = true
            start()
        }

        @Throws(IOException::class)
        fun register(
            client: SocketChannel,
            remote: SocketChannel,
            clientAddress: InetAddress,
            initialClientData: ByteArray,
            trafficListener: TrafficListener,
            closeListener: CloseListener,
        ) {
            if (!running.get()) throw IOException("TCP relay lane is closed")
            pending.add(
                Registration(
                    client,
                    remote,
                    clientAddress,
                    initialClientData,
                    trafficListener,
                    closeListener,
                ),
            )
            selector.wakeup()
        }

        private fun runLoop() {
            try {
                while (running.get()) {
                    drainMaintenance()
                    drainRegistrations()
                    selector.select(SELECT_TIMEOUT_MS)
                    downloadScheduler.beginRound(
                        baseBudgetBytes = tuning.readyEventBudgetBytes,
                        activeConnections = connections.size,
                        activeClientCount = clientConnections.size,
                    )
                    val iterator = selector.selectedKeys().iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()
                        val endpoint = key.attachment() as? Endpoint ?: continue
                        endpoint.connection.handle(key, endpoint.clientSide)
                    }
                    expireIdleConnections()
                    runPeriodicMaintenance()
                }
            } catch (_: ClosedSelectorException) {
                // Normal during shutdown.
            } catch (error: Exception) {
                if (running.get()) Log.e(TAG, "TCP selector lane stopped", error)
            } finally {
                running.set(false)
                drainAndRejectPending()
                connections.toList().forEach { it.close(failed = false) }
                runCatching { selector.close() }
            }
        }

        private fun drainRegistrations() {
            while (true) {
                val registration = pending.poll() ?: return
                if (!running.get()) {
                    registration.reject()
                    continue
                }
                try {
                    registration.client.configureBlocking(false)
                    registration.remote.configureBlocking(false)
                    val connection = Connection(
                        lane = this,
                        buffers = buffers,
                        client = registration.client,
                        remote = registration.remote,
                        clientAddress = registration.clientAddress,
                        initialClientData = registration.initialClientData,
                        trafficListener = registration.trafficListener,
                        closeListener = registration.closeListener,
                        tuning = tuning,
                    )
                    connection.register(selector)
                    add(connection)
                    connection.scheduleTimeout(force = true)
                } catch (error: Exception) {
                    Log.d(TAG, "Unable to register TCP relay: ${error.message}")
                    registration.reject()
                }
            }
        }

        private fun drainMaintenance() {
            while (true) maintenance.poll()?.invoke() ?: return
        }

        private fun drainAndRejectPending() {
            while (true) pending.poll()?.reject() ?: return
        }

        private fun expireIdleConnections() {
            val now = System.nanoTime()
            idleWheel.expire(now) { connection ->
                if (connection.isTimedOut(now)) {
                    connection.close(failed = false)
                } else {
                    connection.scheduleTimeout(now, force = true)
                }
            }
        }

        private fun runPeriodicMaintenance() {
            val now = System.nanoTime()
            if (now < nextPeriodicMaintenanceNanos) return
            connections.forEach { it.flushTrafficIfDue(now) }
            statsSnapshot = LaneStats(
                connections = connections.size,
                halfClosedConnections = halfClosedConnectionCount.coerceAtLeast(0),
                pooledBufferBytes = buffers.pooledBytes(),
                bufferedBytes = bufferedByteCount.coerceAtLeast(0L),
                peakBufferedBytes = peakBufferedByteCount.coerceAtLeast(0L),
                eagerWriteBytes = eagerWriteByteCount,
                partialWriteEvents = partialWriteEventCount,
                readyBudgetYields = readyBudgetYieldCount,
                bufferPoolHits = buffers.hits(),
                directBufferAllocations = buffers.allocations(),
                interactiveWriteBytes = interactiveWriteByteCount,
                fairnessDeferredReads = fairnessDeferredReadCount,
                queueDelayP50Us = queueDelayMicros.percentile(0.50),
                queueDelayP95Us = queueDelayMicros.percentile(0.95),
                rescheduleDelayP50Us = rescheduleDelayMicros.percentile(0.50),
                rescheduleDelayP95Us = rescheduleDelayMicros.percentile(0.95),
            )
            nextPeriodicMaintenanceNanos = now + TRAFFIC_FLUSH_INTERVAL_NANOS
        }

        fun touch(connection: Connection) {
            idleWheel.schedule(connection, connection.timeoutDeadlineNanos())
        }

        private fun add(connection: Connection) {
            if (!connections.add(connection)) return
            clientConnections[connection.clientAddress] =
                (clientConnections[connection.clientAddress] ?: 0) + 1
        }

        fun remove(connection: Connection) {
            if (!connections.remove(connection)) return
            val remaining = (clientConnections[connection.clientAddress] ?: 1) - 1
            if (remaining <= 0) {
                clientConnections.remove(connection.clientAddress)
            } else {
                clientConnections[connection.clientAddress] = remaining
            }
            idleWheel.remove(connection)
        }

        fun markHalfClosed() {
            halfClosedConnectionCount++
        }

        fun unmarkHalfClosed() {
            halfClosedConnectionCount--
        }

        fun onBuffered(bytes: Int) {
            bufferedByteCount += bytes
            peakBufferedByteCount = maxOf(peakBufferedByteCount, bufferedByteCount)
        }

        fun onDrained(bytes: Int, eager: Boolean) {
            bufferedByteCount -= bytes
            if (eager) eagerWriteByteCount += bytes
        }

        fun onDiscarded(bytes: Int) {
            if (bytes > 0) bufferedByteCount -= bytes
        }

        fun onPartialWrite() {
            partialWriteEventCount++
        }

        fun onReadyBudgetYield() {
            readyBudgetYieldCount++
        }

        fun connectionBudget(trafficClass: TcpTrafficClass): Int {
            return TcpInteractiveBudgetPolicy.connectionBudgetBytes(
                baseBudgetBytes = tuning.readyEventBudgetBytes,
                activeConnections = connections.size,
                trafficClass = trafficClass,
            )
        }

        fun grantDownload(
            clientAddress: InetAddress,
            trafficClass: TcpTrafficClass,
            requestedBytes: Int,
        ): Int = downloadScheduler.grant(clientAddress, trafficClass, requestedBytes).also {
            if (it == 0) fairnessDeferredReadCount++
        }

        fun refundDownload(
            clientAddress: InetAddress,
            trafficClass: TcpTrafficClass,
            unusedBytes: Int,
        ) = downloadScheduler.refund(clientAddress, trafficClass, unusedBytes)

        fun onInteractiveWrite(bytes: Int) {
            if (bytes > 0) interactiveWriteByteCount += bytes
        }

        fun onQueueDelay(nanos: Long) {
            queueDelayMicros.record((nanos.coerceAtLeast(0L) + 999L) / 1_000L)
        }

        fun onRescheduleDelay(nanos: Long) {
            rescheduleDelayMicros.record((nanos.coerceAtLeast(0L) + 999L) / 1_000L)
        }

        fun trimMemory() {
            maintenance.add(buffers::clear)
            selector.wakeup()
        }

        fun connectionCount(): Int = statsSnapshot.connections

        fun halfClosedConnectionCount(): Int = statsSnapshot.halfClosedConnections

        fun pooledBufferBytes(): Int = statsSnapshot.pooledBufferBytes

        fun bufferedBytes(): Long = statsSnapshot.bufferedBytes

        fun peakBufferedBytes(): Long = statsSnapshot.peakBufferedBytes

        fun eagerWriteBytes(): Long = statsSnapshot.eagerWriteBytes

        fun partialWriteEvents(): Long = statsSnapshot.partialWriteEvents

        fun readyBudgetYields(): Long = statsSnapshot.readyBudgetYields

        fun bufferPoolHits(): Long = statsSnapshot.bufferPoolHits

        fun directBufferAllocations(): Long = statsSnapshot.directBufferAllocations

        fun interactiveWriteBytes(): Long = statsSnapshot.interactiveWriteBytes

        fun fairnessDeferredReads(): Long = statsSnapshot.fairnessDeferredReads

        fun queueDelayP50Us(): Long = statsSnapshot.queueDelayP50Us

        fun queueDelayP95Us(): Long = statsSnapshot.queueDelayP95Us

        fun rescheduleDelayP50Us(): Long = statsSnapshot.rescheduleDelayP50Us

        fun rescheduleDelayP95Us(): Long = statsSnapshot.rescheduleDelayP95Us

        private data class LaneStats(
            val connections: Int = 0,
            val halfClosedConnections: Int = 0,
            val pooledBufferBytes: Int = 0,
            val bufferedBytes: Long = 0,
            val peakBufferedBytes: Long = 0,
            val eagerWriteBytes: Long = 0,
            val partialWriteEvents: Long = 0,
            val readyBudgetYields: Long = 0,
            val bufferPoolHits: Long = 0,
            val directBufferAllocations: Long = 0,
            val interactiveWriteBytes: Long = 0,
            val fairnessDeferredReads: Long = 0,
            val queueDelayP50Us: Long = 0,
            val queueDelayP95Us: Long = 0,
            val rescheduleDelayP50Us: Long = 0,
            val rescheduleDelayP95Us: Long = 0,
        )

        override fun close() {
            if (!running.compareAndSet(true, false)) return
            selector.wakeup()
            if (Thread.currentThread() !== thread) runCatching { thread.join(2_000) }
        }
    }

    private class Connection(
        private val lane: RelayLane,
        private val buffers: LaneDirectBufferPool,
        private val client: SocketChannel,
        private val remote: SocketChannel,
        val clientAddress: InetAddress,
        private val initialClientData: ByteArray,
        private val trafficListener: TrafficListener,
        private val closeListener: CloseListener,
        private val tuning: TcpRelayTuning,
    ) {
        private val closed = AtomicBoolean(false)
        private var upload: ByteBuffer? = null
        private var download: ByteBuffer? = null
        private var uploadBufferSize = tuning.initialBufferSize
        private var downloadBufferSize = tuning.initialBufferSize
        private var uploadSmallReads = 0
        private var downloadSmallReads = 0
        private var queuedUploadBytes = 0
        private var queuedDownloadBytes = 0
        private var uploadedRelayBytes = 0L
        private var downloadedRelayBytes = 0L
        private var uploadQueuedAtNanos = 0L
        private var downloadQueuedAtNanos = 0L
        private var uploadRescheduleAtNanos = 0L
        private var downloadRescheduleAtNanos = 0L
        private var clientInputClosed = false
        private var remoteInputClosed = false
        private var halfCloseCounted = false
        private val idleTouch = IdleTouchCoalescer(IDLE_TOUCH_INTERVAL_NANOS)
        private val trafficBatch = TcpTrafficBatch(
            byteThreshold = TRAFFIC_FLUSH_BYTES,
            flushIntervalNanos = TRAFFIC_FLUSH_INTERVAL_NANOS,
        )

        private lateinit var clientKey: SelectionKey
        private lateinit var remoteKey: SelectionKey

        @Volatile
        var lastActivityNanos: Long = System.nanoTime()
            private set

        fun register(selector: Selector) {
            val hasPrefetchedData = initialClientData.isNotEmpty()
            clientKey = client.register(
                selector,
                if (hasPrefetchedData) 0 else SelectionKey.OP_READ,
            )
            remoteKey = remote.register(
                selector,
                SelectionKey.OP_READ or
                    (if (hasPrefetchedData) SelectionKey.OP_WRITE else 0),
            )
            clientKey.attach(Endpoint(this, clientSide = true))
            remoteKey.attach(Endpoint(this, clientSide = false))
            if (hasPrefetchedData) {
                val now = System.nanoTime()
                val buffer = buffers.acquire(
                    maxOf(tuning.initialBufferSize, initialClientData.size),
                )
                buffer.put(initialClientData)
                buffer.flip()
                upload = buffer
                queuedUploadBytes = initialClientData.size
                lane.onBuffered(initialClientData.size)
                drainDirection(sourceClientSide = true, eager = true, nowNanos = now)
            }
        }

        fun handle(key: SelectionKey, clientSide: Boolean) {
            if (closed.get() || !key.isValid) return
            try {
                val now = System.nanoTime()
                // Drain an existing backpressured write before accepting more data in the
                // opposite direction. This keeps queue latency low when a key has both flags.
                if (key.isWritable) write(clientSide, now)
                if (!closed.get() && key.isValid && key.isReadable) read(clientSide, now)
            } catch (_: CancelledKeyException) {
                close(failed = false)
            } catch (_: IOException) {
                close(failed = true)
            } catch (error: Exception) {
                Log.w(TAG, "TCP relay failed", error)
                close(failed = true)
            }
        }

        private fun read(sourceClientSide: Boolean, nowNanos: Long) {
            val source = if (sourceClientSide) client else remote
            val sourceKey = if (sourceClientSide) clientKey else remoteKey
            val destinationKey = if (sourceClientSide) remoteKey else clientKey
            recordRescheduleDelay(sourceClientSide, nowNanos)
            val relayedBytes = if (sourceClientSide) uploadedRelayBytes else downloadedRelayBytes
            val trafficClass = TcpInteractiveBudgetPolicy.trafficClass(relayedBytes)
            val connectionBudget = lane.connectionBudget(trafficClass)
            val eventBudget = if (sourceClientSide) {
                connectionBudget
            } else {
                lane.grantDownload(clientAddress, trafficClass, connectionBudget)
            }
            if (eventBudget <= 0) {
                markRescheduleStart(sourceClientSide, nowNanos)
                return
            }
            var eventBytes = 0

            try {
                while (!closed.get() && eventBytes < eventBudget) {
                    val buffer = bufferForRead(sourceClientSide)
                    val readCapacity = minOf(buffer.remaining(), eventBudget - eventBytes)
                    if (readCapacity < buffer.remaining()) {
                        buffer.limit(buffer.position() + readCapacity)
                    }
                    val count = source.read(buffer)
                    if (count < 0) {
                        releaseBuffer(sourceClientSide)
                        onInputClosed(sourceClientSide, sourceKey, nowNanos)
                        return
                    }
                    if (count == 0) {
                        releaseBuffer(sourceClientSide)
                        return
                    }

                    eventBytes += count
                    addQueuedBytes(sourceClientSide, count, nowNanos)
                    lane.onBuffered(count)
                    markActivity(nowNanos)
                    adaptBufferSize(sourceClientSide, count, readCapacity)
                    buffer.flip()

                    // The LAN-facing side is usually immediately writable. Flushing here avoids
                    // a selector round trip for every 8-128 KiB chunk on long-fat VPN paths.
                    if (!drainDirection(sourceClientSide, eager = true, nowNanos = nowNanos)) {
                        removeInterest(sourceKey, SelectionKey.OP_READ)
                        addInterest(destinationKey, SelectionKey.OP_WRITE)
                        return
                    }
                }
            } finally {
                if (!sourceClientSide && eventBytes < eventBudget) {
                    lane.refundDownload(
                        clientAddress,
                        trafficClass,
                        eventBudget - eventBytes,
                    )
                }
            }

            if (eventBytes >= eventBudget) {
                lane.onReadyBudgetYield()
                markRescheduleStart(sourceClientSide, nowNanos)
            }
        }

        private fun write(destinationClientSide: Boolean, nowNanos: Long) {
            val sourceClientSide = !destinationClientSide
            val destinationKey = if (sourceClientSide) remoteKey else clientKey
            val sourceKey = if (sourceClientSide) clientKey else remoteKey
            val buffer = if (sourceClientSide) upload else download
            if (buffer == null) {
                removeInterest(destinationKey, SelectionKey.OP_WRITE)
                if (!inputClosed(sourceClientSide)) addInterest(sourceKey, SelectionKey.OP_READ)
                return
            }

            drainDirection(sourceClientSide, eager = false, nowNanos = nowNanos)
        }

        private fun drainDirection(
            sourceClientSide: Boolean,
            eager: Boolean,
            nowNanos: Long,
        ): Boolean {
            val destination = if (sourceClientSide) remote else client
            val destinationKey = if (sourceClientSide) remoteKey else clientKey
            val sourceKey = if (sourceClientSide) clientKey else remoteKey
            val buffer = (if (sourceClientSide) upload else download) ?: return true
            val count = destination.write(buffer)
            if (count > 0) {
                val previousRelayed = if (sourceClientSide) {
                    uploadedRelayBytes
                } else {
                    downloadedRelayBytes
                }
                val interactiveRemaining =
                    (TcpInteractiveBudgetPolicy.INTERACTIVE_BYTES - previousRelayed)
                        .coerceAtLeast(0L)
                lane.onInteractiveWrite(minOf(count.toLong(), interactiveRemaining).toInt())
                if (sourceClientSide) {
                    uploadedRelayBytes += count
                } else {
                    downloadedRelayBytes += count
                }
                removeQueuedBytes(sourceClientSide, count)
                lane.onDrained(count, eager)
                markActivity(nowNanos)
                trafficBatch.add(
                    uploaded = if (sourceClientSide) count.toLong() else 0,
                    downloaded = if (sourceClientSide) 0 else count.toLong(),
                    nowNanos = nowNanos,
                )?.let(::publishTraffic)
            }
            if (buffer.hasRemaining()) {
                lane.onPartialWrite()
                removeInterest(sourceKey, SelectionKey.OP_READ)
                addInterest(destinationKey, SelectionKey.OP_WRITE)
                return false
            }
            recordQueueDelay(sourceClientSide, nowNanos)
            releaseBuffer(sourceClientSide)
            removeInterest(destinationKey, SelectionKey.OP_WRITE)
            if (!inputClosed(sourceClientSide)) addInterest(sourceKey, SelectionKey.OP_READ)
            return true
        }

        fun close(failed: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            runCatching { clientKey.cancel() }
            runCatching { remoteKey.cancel() }
            runCatching { client.close() }
            runCatching { remote.close() }
            releaseBuffer(clientSide = true, discardQueued = true)
            releaseBuffer(clientSide = false, discardQueued = true)
            trafficBatch.drain()?.let(::publishTraffic)
            if (halfCloseCounted) lane.unmarkHalfClosed()
            lane.remove(this)
            closeListener.onClosed(failed)
        }

        private fun bufferForRead(clientSide: Boolean): ByteBuffer {
            if (clientSide) {
                return upload ?: buffers.acquire(uploadBufferSize).also { upload = it }
            }
            return download ?: buffers.acquire(downloadBufferSize).also { download = it }
        }

        private fun adaptBufferSize(clientSide: Boolean, count: Int, capacity: Int) {
            val current = if (clientSide) uploadBufferSize else downloadBufferSize
            val smallReads = if (clientSide) uploadSmallReads else downloadSmallReads
            val adaptation = TcpBufferAdaptationPolicy.afterRead(
                currentSize = current,
                bytesRead = count,
                bufferCapacity = capacity,
                consecutiveSmallReads = smallReads,
                maximumSize = tuning.maxBufferSize,
            )
            if (clientSide) {
                uploadBufferSize = adaptation.size
                uploadSmallReads = adaptation.consecutiveSmallReads
            } else {
                downloadBufferSize = adaptation.size
                downloadSmallReads = adaptation.consecutiveSmallReads
            }
        }

        private fun onInputClosed(
            clientSide: Boolean,
            sourceKey: SelectionKey,
            nowNanos: Long,
        ) {
            removeInterest(sourceKey, SelectionKey.OP_READ)
            if (!halfCloseCounted) {
                halfCloseCounted = true
                lane.markHalfClosed()
            }
            if (clientSide) {
                clientInputClosed = true
                runCatching { remote.shutdownOutput() }
            } else {
                remoteInputClosed = true
                runCatching { client.shutdownOutput() }
            }
            markActivity(nowNanos, forceTimeoutSchedule = true)
            if (clientInputClosed && remoteInputClosed) close(failed = false)
        }

        fun scheduleTimeout(nowNanos: Long = System.nanoTime(), force: Boolean = false) {
            if (idleTouch.shouldSchedule(nowNanos, force)) lane.touch(this)
        }

        fun flushTrafficIfDue(nowNanos: Long) {
            trafficBatch.flushIfDue(nowNanos)?.let(::publishTraffic)
        }

        fun timeoutDeadlineNanos(): Long = lastActivityNanos +
            TcpTimeoutPolicy.timeoutNanos(clientInputClosed || remoteInputClosed)

        fun isTimedOut(nowNanos: Long): Boolean = nowNanos >= timeoutDeadlineNanos()

        private fun markActivity(nowNanos: Long, forceTimeoutSchedule: Boolean = false) {
            lastActivityNanos = nowNanos
            scheduleTimeout(nowNanos, forceTimeoutSchedule)
        }

        private fun publishTraffic(delta: TcpTrafficBatch.Delta) {
            trafficListener.onTraffic(delta.uploaded, delta.downloaded)
        }

        private fun releaseBuffer(clientSide: Boolean, discardQueued: Boolean = false) {
            if (clientSide) {
                if (discardQueued) lane.onDiscarded(queuedUploadBytes)
                queuedUploadBytes = 0
                upload?.let(buffers::release)
                upload = null
            } else {
                if (discardQueued) lane.onDiscarded(queuedDownloadBytes)
                queuedDownloadBytes = 0
                download?.let(buffers::release)
                download = null
            }
        }

        private fun addQueuedBytes(clientSide: Boolean, count: Int, nowNanos: Long) {
            if (clientSide) {
                if (queuedUploadBytes == 0) uploadQueuedAtNanos = nowNanos
                queuedUploadBytes += count
            } else {
                if (queuedDownloadBytes == 0) downloadQueuedAtNanos = nowNanos
                queuedDownloadBytes += count
            }
        }

        private fun removeQueuedBytes(clientSide: Boolean, count: Int) {
            if (clientSide) {
                queuedUploadBytes = (queuedUploadBytes - count).coerceAtLeast(0)
            } else {
                queuedDownloadBytes = (queuedDownloadBytes - count).coerceAtLeast(0)
            }
        }

        private fun inputClosed(clientSide: Boolean): Boolean =
            if (clientSide) clientInputClosed else remoteInputClosed

        private fun recordQueueDelay(clientSide: Boolean, nowNanos: Long) {
            val queuedAt = if (clientSide) uploadQueuedAtNanos else downloadQueuedAtNanos
            if (queuedAt != 0L) lane.onQueueDelay(nowNanos - queuedAt)
            if (clientSide) uploadQueuedAtNanos = 0L else downloadQueuedAtNanos = 0L
        }

        private fun markRescheduleStart(clientSide: Boolean, nowNanos: Long) {
            if (clientSide) {
                if (uploadRescheduleAtNanos == 0L) uploadRescheduleAtNanos = nowNanos
            } else if (downloadRescheduleAtNanos == 0L) {
                downloadRescheduleAtNanos = nowNanos
            }
        }

        private fun recordRescheduleDelay(clientSide: Boolean, nowNanos: Long) {
            val started = if (clientSide) uploadRescheduleAtNanos else downloadRescheduleAtNanos
            if (started != 0L) lane.onRescheduleDelay(nowNanos - started)
            if (clientSide) uploadRescheduleAtNanos = 0L else downloadRescheduleAtNanos = 0L
        }

        private fun addInterest(key: SelectionKey, operation: Int) {
            if (!key.isValid) return
            val current = key.interestOps()
            val updated = current or operation
            if (updated != current) key.interestOps(updated)
        }

        private fun removeInterest(key: SelectionKey, operation: Int) {
            if (!key.isValid) return
            val current = key.interestOps()
            val updated = current and operation.inv()
            if (updated != current) key.interestOps(updated)
        }
    }

    private data class Endpoint(
        val connection: Connection,
        val clientSide: Boolean,
    )

    private data class Registration(
        val client: SocketChannel,
        val remote: SocketChannel,
        val clientAddress: InetAddress,
        val initialClientData: ByteArray,
        val trafficListener: TrafficListener,
        val closeListener: CloseListener,
    ) {
        fun reject() {
            runCatching { client.close() }
            runCatching { remote.close() }
            closeListener.onClosed(true)
        }
    }

    companion object {
        private const val TAG = "GatewaveTcpRelay"
        private val EMPTY_CLIENT_DATA = ByteArray(0)
        private const val SELECT_TIMEOUT_MS = 1_000L
        private const val IDLE_TICK_NANOS = 1_000_000_000L
        private const val IDLE_WHEEL_SIZE = 256
        private const val IDLE_TOUCH_INTERVAL_NANOS = 1_000_000_000L
        private const val TRAFFIC_FLUSH_INTERVAL_NANOS = 1_000_000_000L
        private const val TRAFFIC_FLUSH_BYTES = 256L * 1024L
        private val RELAY_LATENCY_BOUNDS_US = longArrayOf(
            50,
            100,
            250,
            500,
            1_000,
            2_500,
            5_000,
            10_000,
            25_000,
            50_000,
            100_000,
            250_000,
        )
    }
}
