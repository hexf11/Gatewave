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

/**
 * Shared non-blocking TCP relay.
 *
 * Each connection is assigned to one selector lane instead of keeping two blocking threads alive.
 */
internal class TcpRelayPool(
    laneCount: Int = SelectorLaneSizing.relayLanes(),
    private val initialBufferSize: Int = DEFAULT_INITIAL_BUFFER_SIZE,
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
        RelayLane("proxy-tcp-${index + 1}", initialBufferSize)
    }

    init {
        Log.i(TAG, "Started ${lanes.size} dynamic TCP selector lane(s)")
    }

    @Throws(IOException::class)
    fun register(
        client: SocketChannel,
        remote: SocketChannel,
        trafficListener: TrafficListener,
        closeListener: CloseListener,
    ) {
        if (closed.get()) throw IOException("TCP relay pool is closed")
        val index = (nextLane.getAndIncrement() and Int.MAX_VALUE) % lanes.size
        lanes[index].register(client, remote, trafficListener, closeListener)
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
    )

    fun stats(): Stats = Stats(
        connections = lanes.sumOf(RelayLane::connectionCount),
        halfClosedConnections = lanes.sumOf(RelayLane::halfClosedConnectionCount),
        lanes = lanes.size,
        pooledBufferBytes = lanes.sumOf(RelayLane::pooledBufferBytes),
    )

    private class RelayLane(threadName: String, private val initialBufferSize: Int) : Closeable {
        private val selector = Selector.open()
        private val running = AtomicBoolean(true)
        private val pending = ConcurrentLinkedQueue<Registration>()
        private val maintenance = ConcurrentLinkedQueue<() -> Unit>()
        private val connections = ConcurrentHashMap.newKeySet<Connection>()
        private val halfClosedConnections = AtomicInteger()
        private val buffers = DirectBufferPool()
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
            trafficListener: TrafficListener,
            closeListener: CloseListener,
        ) {
            if (!running.get()) throw IOException("TCP relay lane is closed")
            pending.add(Registration(client, remote, trafficListener, closeListener))
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
                        trafficListener = registration.trafficListener,
                        closeListener = registration.closeListener,
                        initialBufferSize = initialBufferSize,
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


        fun trimMemory() {
            maintenance.add(buffers::clear)
            selector.wakeup()
        }

        fun connectionCount(): Int = connections.size

        fun halfClosedConnectionCount(): Int = halfClosedConnections.get().coerceAtLeast(0)

        fun pooledBufferBytes(): Int = buffers.pooledBytes()

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
        private val trafficListener: TrafficListener,
        private val closeListener: CloseListener,
        initialBufferSize: Int,
    ) {
        private val closed = AtomicBoolean(false)
        private var upload: ByteBuffer? = null
        private var download: ByteBuffer? = null
        private var uploadBufferSize = initialBufferSize
        private var downloadBufferSize = initialBufferSize
        private var uploadSmallReads = 0
        private var downloadSmallReads = 0
        private var clientInputClosed = false
        private var remoteInputClosed = false
        private var halfCloseCounted = false

        private lateinit var clientKey: SelectionKey
        private lateinit var remoteKey: SelectionKey

        @Volatile
        var lastActivityNanos: Long = System.nanoTime()
            private set

        fun register(selector: Selector) {
            clientKey = client.register(selector, SelectionKey.OP_READ)
            remoteKey = remote.register(selector, SelectionKey.OP_READ)
            clientKey.attach(Endpoint(this, clientSide = true))
            remoteKey.attach(Endpoint(this, clientSide = false))
        }

        fun handle(key: SelectionKey, clientSide: Boolean) {
            if (closed.get() || !key.isValid) return
            try {
                if (key.isReadable) read(clientSide)
                if (!closed.get() && key.isValid && key.isWritable) write(clientSide)
            } catch (_: CancelledKeyException) {
                close(failed = false)
            } catch (_: IOException) {
                close(failed = true)
            } catch (error: Exception) {
                Log.w(TAG, "TCP relay failed", error)
                close(failed = true)
            }
        }

        private fun read(clientSide: Boolean) {
            val source = if (clientSide) client else remote
            val sourceKey = if (clientSide) clientKey else remoteKey
            val destinationKey = if (clientSide) remoteKey else clientKey
            val buffer = bufferForRead(clientSide)
            val count = source.read(buffer)
            if (count < 0) {
                releaseBuffer(clientSide)
                onInputClosed(clientSide, sourceKey)
                return
            }
            if (count == 0) {
                releaseBuffer(clientSide)
                return
            }
            lastActivityNanos = System.nanoTime()
            lane.touch(this)
            adaptBufferSize(clientSide, count, buffer.capacity())
            buffer.flip()
            removeInterest(sourceKey, SelectionKey.OP_READ)
            addInterest(destinationKey, SelectionKey.OP_WRITE)
        }

        private fun write(clientSide: Boolean) {
            val destination = if (clientSide) client else remote
            val destinationKey = if (clientSide) clientKey else remoteKey
            val sourceKey = if (clientSide) remoteKey else clientKey
            val buffer = if (clientSide) download else upload
            if (buffer == null) {
                removeInterest(destinationKey, SelectionKey.OP_WRITE)
                addInterest(sourceKey, SelectionKey.OP_READ)
                return
            }
            val count = destination.write(buffer)
            if (count > 0) {
                lastActivityNanos = System.nanoTime()
                lane.touch(this)
                trafficListener.onTraffic(
                    if (clientSide) 0 else count.toLong(),
                    if (clientSide) count.toLong() else 0,
                )
            }
            if (!buffer.hasRemaining()) {
                releaseBuffer(clientSide = !clientSide)
                removeInterest(destinationKey, SelectionKey.OP_WRITE)
                addInterest(sourceKey, SelectionKey.OP_READ)
            }
        }

        fun close(failed: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            runCatching { clientKey.cancel() }
            runCatching { remoteKey.cancel() }
            runCatching { client.close() }
            runCatching { remote.close() }
            upload?.let(buffers::release)
            download?.let(buffers::release)
            upload = null
            download = null
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
            val updated: Int
            val updatedSmallReads: Int
            if (count == capacity && current < MAX_BUFFER_SIZE) {
                updated = (current * 2).coerceAtMost(MAX_BUFFER_SIZE)
                updatedSmallReads = 0
            } else if (count < current / 4 && current > MIN_BUFFER_SIZE) {
                updatedSmallReads = smallReads + 1
                updated = if (updatedSmallReads >= SMALL_READS_TO_SHRINK) {
                    (current / 2).coerceAtLeast(MIN_BUFFER_SIZE)
                } else {
                    current
                }
            } else {
                updated = current
                updatedSmallReads = 0
            }
            if (clientSide) {
                uploadBufferSize = updated
                uploadSmallReads = if (updated != current) 0 else updatedSmallReads
            } else {
                downloadBufferSize = updated
                downloadSmallReads = if (updated != current) 0 else updatedSmallReads
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

        private fun releaseBuffer(clientSide: Boolean) {
            if (clientSide) {
                upload?.let(buffers::release)
                upload = null
            } else {
                download?.let(buffers::release)
                download = null
            }
        }

        private fun addInterest(key: SelectionKey, operation: Int) {
            if (key.isValid) key.interestOps(key.interestOps() or operation)
        }

        private fun removeInterest(key: SelectionKey, operation: Int) {
            if (key.isValid) key.interestOps(key.interestOps() and operation.inv())
        }
    }

    private class DirectBufferPool {
        private val available = HashMap<Int, ConcurrentLinkedQueue<ByteBuffer>>()
        private val pooledBytes = AtomicInteger()

        fun acquire(size: Int): ByteBuffer {
            val queue = available.getOrPut(size) { ConcurrentLinkedQueue() }
            val buffer = queue.poll()
            if (buffer != null) {
                pooledBytes.addAndGet(-buffer.capacity())
                buffer.clear()
                return buffer
            }
            return ByteBuffer.allocateDirect(size)
        }

        fun release(buffer: ByteBuffer) {
            buffer.clear()
            while (true) {
                val bytes = pooledBytes.get()
                if (bytes + buffer.capacity() > MAX_POOLED_BYTES_PER_LANE) return
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
    }

    private data class Endpoint(
        val connection: Connection,
        val clientSide: Boolean,
    )

    private data class Registration(
        val client: SocketChannel,
        val remote: SocketChannel,
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
        private const val MIN_BUFFER_SIZE = 8 * 1024
        private const val DEFAULT_INITIAL_BUFFER_SIZE = 16 * 1024
        private const val MAX_BUFFER_SIZE = 64 * 1024
        private const val SMALL_READS_TO_SHRINK = 8
        private const val MAX_POOLED_BYTES_PER_LANE = 4 * 1024 * 1024
        private const val SELECT_TIMEOUT_MS = 1_000L
        private const val IDLE_TICK_NANOS = 1_000_000_000L
        private const val IDLE_WHEEL_SIZE = 256
    }
}
