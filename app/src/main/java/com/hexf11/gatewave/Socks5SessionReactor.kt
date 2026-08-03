package com.hexf11.gatewave

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Shared selectors for SOCKS greeting, request parsing, and non-blocking upstream connect. */
internal class Socks5SessionReactor(
    laneCount: Int = SelectorLaneSizing.handshakeLanes(),
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val nextLane = AtomicInteger()
    private val lanes = List(laneCount.coerceAtLeast(1)) { Lane("proxy-handshake-${it + 1}") }

    init {
        Log.i(TAG, "Started ${lanes.size} asynchronous handshake selector lane(s)")
    }

    @Throws(IOException::class)
    fun register(session: Socks5Session) {
        if (closed.get()) throw IOException("SOCKS reactor is closed")
        val index = (nextLane.getAndIncrement() and Int.MAX_VALUE) % lanes.size
        lanes[index].register(session)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lanes.forEach(Lane::close)
    }

    internal class Lane(threadName: String) : Closeable {
        private val selector = Selector.open()
        private val running = AtomicBoolean(true)
        private val commands = ConcurrentLinkedQueue<() -> Unit>()
        private val scheduled = PriorityQueue<ScheduledCommand>(compareBy(ScheduledCommand::deadlineNanos))
        private val sessions = ConcurrentHashMap.newKeySet<Socks5Session>()
        private val thread = Thread(::runLoop, threadName).apply {
            isDaemon = true
            start()
        }

        fun register(session: Socks5Session) {
            execute {
                if (!running.get()) {
                    session.close()
                    return@execute
                }
                try {
                    session.register(this, selector)
                    sessions.add(session)
                } catch (error: Exception) {
                    session.abort("Unable to register SOCKS session", error)
                }
            }
        }

        fun execute(command: () -> Unit) {
            if (!running.get()) return
            commands.add(command)
            selector.wakeup()
        }

        fun registerRemote(channel: SocketChannel, session: Socks5Session): SelectionKey =
            channel.register(selector, SelectionKey.OP_CONNECT, RemoteEndpoint(session))

        fun schedule(delayNanos: Long, command: () -> Unit) = execute {
            scheduled.add(ScheduledCommand(System.nanoTime() + delayNanos, command))
        }

        fun detach(session: Socks5Session) {
            sessions.remove(session)
        }

        private fun runLoop() {
            try {
                while (running.get()) {
                    drainCommands()
                    runScheduled()
                    selector.select(nextSelectTimeoutMs())
                    drainCommands()
                    runScheduled()
                    val iterator = selector.selectedKeys().iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()
                        when (val attachment = key.attachment()) {
                            is Socks5Session -> attachment.handleClient(key)
                            is RemoteEndpoint -> attachment.session.handleRemote(key)
                        }
                    }
                    expireSessions()
                }
            } catch (_: ClosedSelectorException) {
                // Expected during shutdown.
            } catch (error: Exception) {
                if (running.get()) Log.e(TAG, "SOCKS selector lane stopped", error)
            } finally {
                running.set(false)
                drainCommands()
                sessions.toList().forEach(Socks5Session::close)
                sessions.clear()
                runCatching { selector.close() }
            }
        }

        private fun drainCommands() {
            while (true) commands.poll()?.invoke() ?: return
        }

        private fun runScheduled() {
            val now = System.nanoTime()
            while (true) {
                val task = scheduled.peek() ?: return
                if (task.deadlineNanos > now) return
                scheduled.poll()?.command?.invoke()
            }
        }

        private fun nextSelectTimeoutMs(): Long {
            val next = scheduled.peek()?.deadlineNanos ?: return SELECT_TIMEOUT_MS
            val remainingNanos = (next - System.nanoTime()).coerceAtLeast(0)
            return ((remainingNanos + 999_999) / 1_000_000).coerceIn(1, SELECT_TIMEOUT_MS)
        }

        private fun expireSessions() {
            val now = System.nanoTime()
            sessions.toList().forEach { if (it.isExpired(now)) it.onTimeout() }
        }

        override fun close() {
            if (!running.compareAndSet(true, false)) return
            selector.wakeup()
            if (Thread.currentThread() !== thread) runCatching { thread.join(2_000) }
        }
    }

    private data class RemoteEndpoint(val session: Socks5Session)
    private data class ScheduledCommand(val deadlineNanos: Long, val command: () -> Unit)

    companion object {
        private const val TAG = "GatewaveHandshake"
        private const val SELECT_TIMEOUT_MS = 500L
    }
}
