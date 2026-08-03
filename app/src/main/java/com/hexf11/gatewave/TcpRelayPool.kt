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
    laneCount: Int = DEFAULT_LANES,
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
        RelayLane("proxy-tcp-${index + 1}")
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

    private class RelayLane(threadName: String) : Closeable {
        private val selector = Selector.open()
        private val running = AtomicBoolean(true)
        private val pending = ConcurrentLinkedQueue<Registration>()
        private val connections = ConcurrentHashMap.newKeySet<Connection>()
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
                    drainRegistrations()
                    selector.select(SELECT_TIMEOUT_MS)
                    val iterator = selector.selectedKeys().iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()
                        val endpoint = key.attachment() as? Endpoint ?: continue
                        endpoint.connection.handle(key, endpoint.clientSide)
                    }
                    closeIdleConnections()
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
                        client = registration.client,
                        remote = registration.remote,
                        trafficListener = registration.trafficListener,
                        closeListener = registration.closeListener,
                    )
                    connection.register(selector)
                    connections.add(connection)
                } catch (error: Exception) {
                    Log.d(TAG, "Unable to register TCP relay: ${error.message}")
                    registration.reject()
                }
            }
        }

        private fun drainAndRejectPending() {
            while (true) pending.poll()?.reject() ?: return
        }

        private fun closeIdleConnections() {
            val now = System.nanoTime()
            connections.toList().forEach { connection ->
                if (now - connection.lastActivityNanos >= IDLE_TIMEOUT_NANOS) {
                    connection.close(failed = false)
                }
            }
        }

        fun remove(connection: Connection) {
            connections.remove(connection)
        }

        override fun close() {
            if (!running.compareAndSet(true, false)) return
            selector.wakeup()
            if (Thread.currentThread() !== thread) runCatching { thread.join(2_000) }
        }
    }

    private class Connection(
        private val lane: RelayLane,
        private val client: SocketChannel,
        private val remote: SocketChannel,
        private val trafficListener: TrafficListener,
        private val closeListener: CloseListener,
    ) {
        private val closed = AtomicBoolean(false)
        private val upload = ByteBuffer.allocateDirect(BUFFER_SIZE)
        private val download = ByteBuffer.allocateDirect(BUFFER_SIZE)

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
            } catch (error: IOException) {
                Log.d(TAG, "TCP relay ended: ${error.message}")
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
            val buffer = if (clientSide) upload else download
            val count = source.read(buffer)
            if (count < 0) {
                close(failed = false)
                return
            }
            if (count == 0) return
            lastActivityNanos = System.nanoTime()
            buffer.flip()
            removeInterest(sourceKey, SelectionKey.OP_READ)
            addInterest(destinationKey, SelectionKey.OP_WRITE)
        }

        private fun write(clientSide: Boolean) {
            val destination = if (clientSide) client else remote
            val destinationKey = if (clientSide) clientKey else remoteKey
            val sourceKey = if (clientSide) remoteKey else clientKey
            val buffer = if (clientSide) download else upload
            val count = destination.write(buffer)
            if (count > 0) {
                lastActivityNanos = System.nanoTime()
                trafficListener.onTraffic(
                    if (clientSide) 0 else count.toLong(),
                    if (clientSide) count.toLong() else 0,
                )
            }
            if (!buffer.hasRemaining()) {
                buffer.clear()
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
            lane.remove(this)
            closeListener.onClosed(failed)
        }

        private fun addInterest(key: SelectionKey, operation: Int) {
            if (key.isValid) key.interestOps(key.interestOps() or operation)
        }

        private fun removeInterest(key: SelectionKey, operation: Int) {
            if (key.isValid) key.interestOps(key.interestOps() and operation.inv())
        }
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
        private const val DEFAULT_LANES = 2
        private const val BUFFER_SIZE = 32 * 1024
        private const val SELECT_TIMEOUT_MS = 1_000L
        private const val IDLE_TIMEOUT_NANOS =
            Socks5Session.TCP_IDLE_TIMEOUT_MS * 1_000_000L
    }
}
