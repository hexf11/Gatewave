package com.hexf11.gatewave

import android.util.Log
import java.io.Closeable
import java.io.IOException
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
import java.util.concurrent.atomic.AtomicLong

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
        initialClientData: ByteArray = EMPTY_CLIENT_DATA,
        trafficListener: TrafficListener,
        closeListener: CloseListener,
    ) {
        if (closed.get()) throw IOException("TCP relay pool is closed")
        val index = (nextLane.getAndIncrement() and Int.MAX_VALUE) % lanes.size
        lanes[index].register(client, remote, initialClientData, trafficListener, closeListener)
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
        private val halfClosedConnections = AtomicInteger()
        private val buffers = DirectBufferPool(tuning.maxPooledBytesPerLane)
        private val bufferedBytes = AtomicLong()
        private val peakBufferedBytes = AtomicLong()
        private val eagerWriteBytes = AtomicLong()
        private val partialWriteEvents = AtomicLong()
        private val readyBudgetYields = AtomicLong()
        private val idleWheel = IdleTimeoutWheel<Connection>(
            tickNanos = IDLE_TICK_NANOS,
            wheelSize = IDLE_WHEEL_SIZE,
        )
        private val thread = Thread(::runLoop, threadName).apply {
            isDaemon = true
            start()
        }

        @Throws(IOException::class)
        fun register(
            client: SocketChannel,
            remote: SocketChannel,
            initialClientData: ByteArray,
            trafficListener: TrafficListener,
            closeListener: CloseListener,
        ) {
            if (!running.get()) throw IOException("TCP relay lane is closed")
            pending.add(
                Registration(client, remote, initialClientData, trafficListener, closeListener),
            )
            selector.wakeup()
        }

        private fun runLoop() {
            try {
                while (running.get()) {
                    drainMaintenance()
                    drainRegistrations()
                    selector.select(SELECT_TIMEOUT_MS)
                    val iterator = selector.selectedKeys().iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()
                        val endpoint = key.attachment() as? Endpoint ?: continue
                        endpoint.connection.handle(key, endpoint.clientSide)
                    }
                    expireIdleConnections()
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
                        initialClientData = registration.initialClientData,
                        trafficListener = registration.trafficListener,
                        closeListener = registration.closeListener,
                        tuning = tuning,
                    )
                    connection.register(selector)
                    connections.add(connection)
                    touch(connection)
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
                    touch(connection)
                }
            }
        }

        fun touch(connection: Connection) {
            idleWheel.schedule(connection, connection.timeoutDeadlineNanos())
        }

        fun remove(connection: Connection) {
            connections.remove(connection)
            idleWheel.remove(connection)
        }

        fun markHalfClosed() = halfClosedConnections.incrementAndGet()

        fun unmarkHalfClosed() = halfClosedConnections.decrementAndGet()

        fun onBuffered(bytes: Int) {
            val current = bufferedBytes.addAndGet(bytes.toLong())
            peakBufferedBytes.accumulateAndGet(current, ::maxOf)
        }

        fun onDrained(bytes: Int, eager: Boolean) {
            bufferedBytes.addAndGet(-bytes.toLong())
            if (eager) eagerWriteBytes.addAndGet(bytes.toLong())
        }

        fun onDiscarded(bytes: Int) {
            if (bytes > 0) bufferedBytes.addAndGet(-bytes.toLong())
        }

        fun onPartialWrite() {
            partialWriteEvents.incrementAndGet()
        }

        fun onReadyBudgetYield() {
            readyBudgetYields.incrementAndGet()
        }

        fun trimMemory() {
            maintenance.add(buffers::clear)
            selector.wakeup()
        }

        fun connectionCount(): Int = connections.size

        fun halfClosedConnectionCount(): Int = halfClosedConnections.get().coerceAtLeast(0)

        fun pooledBufferBytes(): Int = buffers.pooledBytes()

        fun bufferedBytes(): Long = bufferedBytes.get().coerceAtLeast(0L)

        fun peakBufferedBytes(): Long = peakBufferedBytes.get().coerceAtLeast(0L)

        fun eagerWriteBytes(): Long = eagerWriteBytes.get()

        fun partialWriteEvents(): Long = partialWriteEvents.get()

        fun readyBudgetYields(): Long = readyBudgetYields.get()

        fun bufferPoolHits(): Long = buffers.hits()

        fun directBufferAllocations(): Long = buffers.allocations()

        override fun close() {
            if (!running.compareAndSet(true, false)) return
            selector.wakeup()
            if (Thread.currentThread() !== thread) runCatching { thread.join(2_000) }
        }
    }

    private class Connection(
        private val lane: RelayLane,
        private val buffers: DirectBufferPool,
        private val client: SocketChannel,
        private val remote: SocketChannel,
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
        private var clientInputClosed = false
        private var remoteInputClosed = false
        private var halfCloseCounted = false

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
                val buffer = buffers.acquire(
                    maxOf(tuning.initialBufferSize, initialClientData.size),
                )
                buffer.put(initialClientData)
                buffer.flip()
                upload = buffer
                queuedUploadBytes = initialClientData.size
                lane.onBuffered(initialClientData.size)
                drainDirection(sourceClientSide = true, eager = true)
            }
        }

        fun handle(key: SelectionKey, clientSide: Boolean) {
            if (closed.get() || !key.isValid) return
            try {
                // Drain an existing backpressured write before accepting more data in the
                // opposite direction. This keeps queue latency low when a key has both flags.
                if (key.isWritable) write(clientSide)
                if (!closed.get() && key.isValid && key.isReadable) read(clientSide)
            } catch (_: CancelledKeyException) {
                close(failed = false)
            } catch (_: IOException) {
                close(failed = true)
            } catch (error: Exception) {
                Log.w(TAG, "TCP relay failed", error)
                close(failed = true)
            }
        }

        private fun read(sourceClientSide: Boolean) {
            val source = if (sourceClientSide) client else remote
            val sourceKey = if (sourceClientSide) clientKey else remoteKey
            val destinationKey = if (sourceClientSide) remoteKey else clientKey
            var eventBytes = 0

            while (!closed.get() && eventBytes < tuning.readyEventBudgetBytes) {
                val buffer = bufferForRead(sourceClientSide)
                val readCapacity = minOf(
                    buffer.remaining(),
                    tuning.readyEventBudgetBytes - eventBytes,
                )
                if (readCapacity < buffer.remaining()) {
                    buffer.limit(buffer.position() + readCapacity)
                }
                val count = source.read(buffer)
                if (count < 0) {
                    releaseBuffer(sourceClientSide)
                    onInputClosed(sourceClientSide, sourceKey)
                    return
                }
                if (count == 0) {
                    releaseBuffer(sourceClientSide)
                    return
                }

                eventBytes += count
                addQueuedBytes(sourceClientSide, count)
                lane.onBuffered(count)
                lastActivityNanos = System.nanoTime()
                lane.touch(this)
                adaptBufferSize(sourceClientSide, count, readCapacity)
                buffer.flip()

                // The LAN-facing side is usually immediately writable. Flushing here avoids a
                // selector round trip for every 8-128 KiB chunk on long-fat VPN paths.
                if (!drainDirection(sourceClientSide, eager = true)) {
                    removeInterest(sourceKey, SelectionKey.OP_READ)
                    addInterest(destinationKey, SelectionKey.OP_WRITE)
                    return
                }
            }

            if (eventBytes >= tuning.readyEventBudgetBytes) lane.onReadyBudgetYield()
        }

        private fun write(destinationClientSide: Boolean) {
            val sourceClientSide = !destinationClientSide
            val destinationKey = if (sourceClientSide) remoteKey else clientKey
            val sourceKey = if (sourceClientSide) clientKey else remoteKey
            val buffer = if (sourceClientSide) upload else download
            if (buffer == null) {
                removeInterest(destinationKey, SelectionKey.OP_WRITE)
                if (!inputClosed(sourceClientSide)) addInterest(sourceKey, SelectionKey.OP_READ)
                return
            }

            drainDirection(sourceClientSide, eager = false)
        }

        private fun drainDirection(sourceClientSide: Boolean, eager: Boolean): Boolean {
            val destination = if (sourceClientSide) remote else client
            val destinationKey = if (sourceClientSide) remoteKey else clientKey
            val sourceKey = if (sourceClientSide) clientKey else remoteKey
            val buffer = (if (sourceClientSide) upload else download) ?: return true
            val count = destination.write(buffer)
            if (count > 0) {
                removeQueuedBytes(sourceClientSide, count)
                lane.onDrained(count, eager)
                lastActivityNanos = System.nanoTime()
                lane.touch(this)
                trafficListener.onTraffic(
                    if (sourceClientSide) count.toLong() else 0,
                    if (sourceClientSide) 0 else count.toLong(),
                )
            }
            if (buffer.hasRemaining()) {
                lane.onPartialWrite()
                removeInterest(sourceKey, SelectionKey.OP_READ)
                addInterest(destinationKey, SelectionKey.OP_WRITE)
                return false
            }
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

        private fun onInputClosed(clientSide: Boolean, sourceKey: SelectionKey) {
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
            lastActivityNanos = System.nanoTime()
            lane.touch(this)
            if (clientInputClosed && remoteInputClosed) close(failed = false)
        }

        fun timeoutDeadlineNanos(): Long = lastActivityNanos +
            TcpTimeoutPolicy.timeoutNanos(clientInputClosed || remoteInputClosed)

        fun isTimedOut(nowNanos: Long): Boolean = nowNanos >= timeoutDeadlineNanos()

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

        private fun addQueuedBytes(clientSide: Boolean, count: Int) {
            if (clientSide) queuedUploadBytes += count else queuedDownloadBytes += count
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

        private fun addInterest(key: SelectionKey, operation: Int) {
            if (key.isValid) key.interestOps(key.interestOps() or operation)
        }

        private fun removeInterest(key: SelectionKey, operation: Int) {
            if (key.isValid) key.interestOps(key.interestOps() and operation.inv())
        }
    }

    private class DirectBufferPool(private val maximumPooledBytes: Int) {
        private val available = HashMap<Int, ConcurrentLinkedQueue<ByteBuffer>>()
        private val pooledBytes = AtomicInteger()
        private val hits = AtomicLong()
        private val allocations = AtomicLong()

        fun acquire(size: Int): ByteBuffer {
            val queue = available.getOrPut(size) { ConcurrentLinkedQueue() }
            val buffer = queue.poll()
            if (buffer != null) {
                hits.incrementAndGet()
                pooledBytes.addAndGet(-buffer.capacity())
                buffer.clear()
                return buffer
            }
            allocations.incrementAndGet()
            return ByteBuffer.allocateDirect(size)
        }

        fun release(buffer: ByteBuffer) {
            buffer.clear()
            while (true) {
                val bytes = pooledBytes.get()
                if (bytes + buffer.capacity() > maximumPooledBytes) return
                if (pooledBytes.compareAndSet(bytes, bytes + buffer.capacity())) {
                    available.getOrPut(buffer.capacity()) { ConcurrentLinkedQueue() }.add(buffer)
                    return
                }
            }
        }


        fun clear() {
            available.clear()
            pooledBytes.set(0)
        }

        fun pooledBytes(): Int = pooledBytes.get()

        fun hits(): Long = hits.get()

        fun allocations(): Long = allocations.get()
    }

    private data class Endpoint(
        val connection: Connection,
        val clientSide: Boolean,
    )

    private data class Registration(
        val client: SocketChannel,
        val remote: SocketChannel,
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
    }
}
