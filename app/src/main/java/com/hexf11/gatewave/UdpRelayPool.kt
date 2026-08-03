package com.hexf11.gatewave

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedSelectorException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Shared DatagramChannel selectors; UDP associations no longer own blocking worker threads. */
internal class UdpRelayPool(
    laneCount: Int = SelectorLaneSizing.udpLanes(),
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val nextLane = AtomicInteger()
    private val activeAssociations = AtomicInteger()
    private val droppedDatagrams = AtomicLong()
    private val fastPathHits = AtomicLong()
    private val resolutionMisses = AtomicLong()
    private val maxQueueDepth = AtomicInteger()
    private val lanes = List(laneCount.coerceAtLeast(1)) {
        Lane(
            "proxy-udp-${it + 1}",
            activeDelta = activeAssociations::addAndGet,
            dropCounter = droppedDatagrams::incrementAndGet,
            fastPathCounter = fastPathHits::incrementAndGet,
            resolutionCounter = resolutionMisses::incrementAndGet,
            queueDepthObserver = { depth -> maxQueueDepth.accumulateAndGet(depth, ::maxOf) },
        )
    }

    init {
        Log.i(TAG, "Started ${lanes.size} UDP selector lane(s)")
    }

    fun lane(): Lane {
        if (closed.get()) throw IOException("UDP relay pool is closed")
        val index = (nextLane.getAndIncrement() and Int.MAX_VALUE) % lanes.size
        return lanes[index]
    }

    data class Stats(
        val activeAssociations: Int,
        val droppedDatagrams: Long,
        val lanes: Int,
        val fastPathHits: Long,
        val resolutionMisses: Long,
        val maxQueueDepth: Int,
    )

    fun stats(): Stats = Stats(
        activeAssociations = activeAssociations.get(),
        droppedDatagrams = droppedDatagrams.get(),
        lanes = lanes.size,
        fastPathHits = fastPathHits.get(),
        resolutionMisses = resolutionMisses.get(),
        maxQueueDepth = maxQueueDepth.get(),
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lanes.forEach(Lane::close)
    }

    internal class Lane(
        threadName: String,
        private val activeDelta: (Int) -> Int,
        private val dropCounter: () -> Long,
        private val fastPathCounter: () -> Long,
        private val resolutionCounter: () -> Long,
        private val queueDepthObserver: (Int) -> Unit,
    ) : Closeable {
        private val selector = Selector.open()
        private val running = AtomicBoolean(true)
        private val commands = ConcurrentLinkedQueue<() -> Unit>()
        private val associations = ConcurrentHashMap.newKeySet<UdpRelay>()
        private val receiveBuffer = ByteBuffer.allocateDirect(MAX_DATAGRAM_BUFFER)
        private val thread = Thread(::runLoop, threadName).apply {
            isDaemon = true
            start()
        }

        fun register(relay: UdpRelay) = execute {
            if (!running.get()) {
                relay.closeFromPool()
                return@execute
            }
            try {
                relay.register(this, selector)
                associations.add(relay)
                activeDelta(1)
            } catch (error: Exception) {
                relay.registrationFailed(error)
            }
        }

        fun execute(command: () -> Unit) {
            if (!running.get()) return
            commands.add(command)
            selector.wakeup()
        }

        fun remove(relay: UdpRelay) {
            if (associations.remove(relay)) activeDelta(-1)
        }


        fun recordDrop() {
            dropCounter()
        }

        fun recordFastPath() {
            fastPathCounter()
        }

        fun recordResolutionMiss() {
            resolutionCounter()
        }

        fun observeQueueDepth(depth: Int) {
            queueDepthObserver(depth)
        }

        private fun runLoop() {
            try {
                while (running.get()) {
                    drainCommands()
                    selector.select(SELECT_TIMEOUT_MS)
                    drainCommands()
                    val iterator = selector.selectedKeys().iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()
                        val endpoint = key.attachment() as? Endpoint ?: continue
                        endpoint.relay.handle(key, endpoint.clientSide, receiveBuffer)
                    }
                    val now = System.nanoTime()
                    associations.toList().forEach { if (it.isIdle(now)) it.closeForIdle() }
                }
            } catch (_: ClosedSelectorException) {
                // Expected while stopping.
            } catch (error: Exception) {
                if (running.get()) Log.e(TAG, "UDP selector stopped", error)
            } finally {
                running.set(false)
                drainCommands()
                associations.toList().forEach(UdpRelay::closeFromPool)
                associations.clear()
                runCatching { selector.close() }
            }
        }

        private fun drainCommands() {
            while (true) commands.poll()?.invoke() ?: return
        }

        override fun close() {
            if (!running.compareAndSet(true, false)) return
            selector.wakeup()
            if (Thread.currentThread() !== thread) runCatching { thread.join(2_000) }
        }
    }

    internal data class Endpoint(val relay: UdpRelay, val clientSide: Boolean)

    companion object {
        private const val TAG = "GatewaveUdpPool"
        private const val SELECT_TIMEOUT_MS = 1_000L
        private const val MAX_DATAGRAM_BUFFER = 65_535
    }
}
