package com.hexf11.gatewave

import android.net.Network
import android.util.Log
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** One fully asynchronous SOCKS5 control session. */
internal class Socks5Session(
    private val client: SocketChannel,
    private val udpEnabled: Boolean,
    private val networkProvider: NetworkProvider,
    private val listener: Listener,
    private val tcpRelayPool: TcpRelayPool,
    private val dnsResolver: VpnDnsResolver,
    private val udpRelayPool: UdpRelayPool,
    private val udpSlots: Semaphore,
) {
    enum class Type { NEGOTIATING, TCP, UDP }

    fun interface NetworkProvider {
        fun currentVpnNetwork(): Network?
    }

    internal interface Listener {
        fun onTypeChanged(session: Socks5Session, type: Type)
        fun onConnectResult(milliseconds: Long, success: Boolean)
        fun onClosed(session: Socks5Session)
        fun onTraffic(uploaded: Long, downloaded: Long)
    }

    private enum class State {
        GREETING,
        REQUEST,
        RESOLVING,
        CONNECTING,
        WRITING_REPLY,
        UDP_CONTROL,
        HANDED_OFF,
        CLOSED,
    }

    private data class Request(val command: Int, val host: String, val port: Int)
    private data class ConnectAttempt(
        val channel: SocketChannel,
        val address: InetAddress,
        var key: SelectionKey? = null,
    )

    private val closed = AtomicBoolean(false)
    private val input = ByteBuffer.allocate(CONTROL_BUFFER_SIZE)
    private val clientIp: InetAddress = client.socket().inetAddress
    private val openedAtNanos = System.nanoTime()

    @Volatile
    private var lane: Socks5SessionReactor.Lane? = null

    @Volatile
    private var state = State.GREETING

    @Volatile
    private var type = Type.NEGOTIATING

    @Volatile
    private var failed = false

    private lateinit var clientKey: SelectionKey
    private var remoteKey: SelectionKey? = null
    private var remoteChannel: SocketChannel? = null
    private var udpRelay: UdpRelay? = null
    private var udpSlotHeld = false
    private var requestReceived = false
    private var pendingOutput: ByteBuffer? = null
    private var afterOutput: (() -> Unit)? = null
    private var candidates = emptyList<InetAddress>()
    private var candidateIndex = 0
    private val connectAttempts = mutableListOf<ConnectAttempt>()
    private var connectVpn: Network? = null
    private var nextAttemptScheduled = false
    private var connectGeneration = 0
    private var connectStartedNanos = 0L
    private var connectMetricReported = false
    private var requestPort = 0
    private var deadlineNanos = System.nanoTime() + HANDSHAKE_TIMEOUT_NANOS

    internal fun register(reactorLane: Socks5SessionReactor.Lane, selector: Selector) {
        lane = reactorLane
        client.configureBlocking(false)
        client.socket().tcpNoDelay = true
        client.socket().keepAlive = true
        clientKey = client.register(selector, SelectionKey.OP_READ, this)
    }

    internal fun handleClient(key: SelectionKey) {
        if (closed.get() || !key.isValid) return
        try {
            if (key.isWritable) flushOutput()
            if (!closed.get() && key.isValid && key.isReadable) readClient()
        } catch (_: CancelledKeyException) {
            finish()
        } catch (error: IOException) {
            abort("SOCKS client channel ended", error, markFailed = requestReceived)
        } catch (error: Exception) {
            abort("SOCKS client handling failed", error)
        }
    }

    internal fun handleRemote(key: SelectionKey) {
        if (closed.get() || state != State.CONNECTING || !key.isValid) return
        val attempt = connectAttempts.firstOrNull { it.channel === key.channel() } ?: return
        try {
            if (key.isConnectable && attempt.channel.finishConnect()) {
                key.interestOps(0)
                onConnected(attempt)
            }
        } catch (error: Exception) {
            failAttempt(attempt)
        }
    }

    private fun readClient() {
        if (state == State.UDP_CONTROL) {
            input.clear()
            val count = client.read(input)
            if (count < 0) finish()
            return
        }
        if (state != State.GREETING && state != State.REQUEST) return

        val count = client.read(input)
        if (count < 0) {
            finish()
            return
        }
        if (count == 0) return
        processInput()
        if (!closed.get() && input.position() == input.capacity() &&
            (state == State.GREETING || state == State.REQUEST)
        ) {
            abort("SOCKS control frame exceeds $CONTROL_BUFFER_SIZE bytes", null)
        }
    }

    private fun processInput() {
        when (state) {
            State.GREETING -> parseGreeting()
            State.REQUEST -> parseRequest()
            else -> Unit
        }
    }

    private fun parseGreeting() {
        if (input.position() < 2) return
        val version = unsigned(input.get(0))
        val methodCount = unsigned(input.get(1))
        if (version != SOCKS_VERSION || methodCount == 0) {
            abort("Invalid SOCKS5 greeting", null)
            return
        }
        val frameLength = 2 + methodCount
        if (input.position() < frameLength) return
        var noAuth = false
        for (index in 2 until frameLength) {
            if (unsigned(input.get(index)) == METHOD_NO_AUTH) noAuth = true
        }
        consume(frameLength)
        state = State.WRITING_REPLY
        queueOutput(byteArrayOf(SOCKS_VERSION.toByte(), if (noAuth) 0 else 0xFF.toByte())) {
            if (!noAuth) {
                failed = true
                finish()
            } else {
                state = State.REQUEST
                deadlineNanos = System.nanoTime() + HANDSHAKE_TIMEOUT_NANOS
                setClientInterest(SelectionKey.OP_READ)
                processInput()
            }
        }
    }

    private fun parseRequest() {
        if (input.position() < 4) return
        val version = unsigned(input.get(0))
        val command = unsigned(input.get(1))
        val reserved = unsigned(input.get(2))
        val addressType = unsigned(input.get(3))
        if (version != SOCKS_VERSION || reserved != 0) {
            abort("Invalid SOCKS5 request header", null)
            return
        }

        val addressLength: Int
        val addressOffset: Int
        when (addressType) {
            ADDRESS_IPV4 -> {
                addressOffset = 4
                addressLength = 4
            }
            ADDRESS_DOMAIN -> {
                if (input.position() < 5) return
                addressLength = unsigned(input.get(4))
                addressOffset = 5
                if (addressLength == 0 && command != COMMAND_UDP_ASSOCIATE) {
                    abort("Empty SOCKS5 domain", null)
                    return
                }
            }
            ADDRESS_IPV6 -> {
                addressOffset = 4
                addressLength = 16
            }
            else -> {
                requestReceived = true
                replyAndClose(REPLY_ADDRESS_NOT_SUPPORTED, "Unsupported address type")
                return
            }
        }

        val frameLength = addressOffset + addressLength + 2
        if (input.position() < frameLength) return
        val rawAddress = ByteArray(addressLength)
        for (index in rawAddress.indices) rawAddress[index] = input.get(addressOffset + index)
        val host = when (addressType) {
            ADDRESS_DOMAIN -> if (addressLength == 0) {
                "0.0.0.0"
            } else {
                String(rawAddress, StandardCharsets.US_ASCII)
            }
            else -> InetAddress.getByAddress(rawAddress).hostAddress.orEmpty()
        }
        val portOffset = addressOffset + addressLength
        val port = (unsigned(input.get(portOffset)) shl 8) or unsigned(input.get(portOffset + 1))
        consume(frameLength)
        requestReceived = true
        if (port == 0 && command != COMMAND_UDP_ASSOCIATE) {
            replyAndClose(REPLY_GENERAL_FAILURE, "Invalid destination port")
            return
        }
        onRequest(Request(command, host, port))
    }

    private fun onRequest(request: Request) {
        val vpn = networkProvider.currentVpnNetwork()
        if (vpn == null) {
            replyAndClose(REPLY_NETWORK_UNREACHABLE, "VPN network unavailable (fail closed)")
            return
        }
        when (request.command) {
            COMMAND_CONNECT -> startConnect(vpn, request)
            COMMAND_UDP_ASSOCIATE -> startUdp(vpn, request)
            else -> replyAndClose(REPLY_COMMAND_NOT_SUPPORTED, "Unsupported SOCKS command")
        }
    }

    private fun startConnect(vpn: Network, request: Request) {
        changeType(Type.TCP)
        state = State.RESOLVING
        deadlineNanos = System.nanoTime() + CONNECT_TIMEOUT_NANOS
        requestPort = request.port
        connectVpn = vpn
        connectStartedNanos = System.nanoTime()
        connectGeneration++
        setClientInterest(0)
        dnsResolver.resolve(vpn, request.host) { result ->
            executeOnLane {
                if (closed.get() || state != State.RESOLVING) return@executeOnLane
                candidates = dnsResolver.orderedAddresses(
                    vpn,
                    result.addresses.filterNot(NetworkUtils::isBlockedTarget),
                )
                candidateIndex = 0
                if (candidates.isEmpty()) {
                    val reply = if (result.addresses.isNotEmpty()) {
                        REPLY_CONNECTION_NOT_ALLOWED
                    } else {
                        REPLY_HOST_UNREACHABLE
                    }
                    replyAndClose(reply, result.error?.message ?: "Destination unresolved or blocked")
                } else {
                    state = State.CONNECTING
                    startNextCandidate()
                }
            }
        }
    }

    private fun startNextCandidate() {
        val vpn = connectVpn
        if (vpn == null) {
            replyAndClose(REPLY_NETWORK_UNREACHABLE, "VPN network changed during connect")
            return
        }
        while (candidateIndex < candidates.size) {
            val address = candidates[candidateIndex++]
            var channel: SocketChannel? = null
            try {
                channel = SocketChannel.open()
                channel.configureBlocking(false)
                val socket = channel.socket()
                vpn.bindSocket(socket)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                val attempt = ConnectAttempt(channel, address)
                connectAttempts += attempt
                if (channel.connect(InetSocketAddress(address, requestPort))) {
                    onConnected(attempt)
                } else {
                    attempt.key = checkNotNull(lane).registerRemote(channel, this)
                    scheduleNextCandidate()
                }
                return
            } catch (error: Exception) {
                dnsResolver.recordConnectFailure(vpn, address)
                runCatching { channel?.close() }
            }
        }
        if (connectAttempts.isEmpty()) {
            replyAndClose(REPLY_HOST_UNREACHABLE, "All destination addresses failed")
        }
    }

    private fun scheduleNextCandidate() {
        if (candidateIndex >= candidates.size || nextAttemptScheduled) return
        nextAttemptScheduled = true
        val generation = connectGeneration
        checkNotNull(lane).schedule(HAPPY_EYEBALLS_DELAY_NANOS) {
            nextAttemptScheduled = false
            if (!closed.get() && state == State.CONNECTING && generation == connectGeneration) {
                startNextCandidate()
            }
        }
    }

    private fun failAttempt(attempt: ConnectAttempt) {
        connectAttempts.remove(attempt)
        attempt.key?.cancel()
        runCatching { attempt.channel.close() }
        connectVpn?.let { dnsResolver.recordConnectFailure(it, attempt.address) }
        if (candidateIndex < candidates.size) startNextCandidate()
        else if (connectAttempts.isEmpty()) {
            replyAndClose(REPLY_HOST_UNREACHABLE, "All destination addresses failed")
        }
    }

    private fun onConnected(winner: ConnectAttempt) {
        if (state != State.CONNECTING) {
            runCatching { winner.channel.close() }
            return
        }
        connectAttempts.toList().forEach { attempt ->
            if (attempt !== winner) {
                attempt.key?.cancel()
                runCatching { attempt.channel.close() }
            }
        }
        connectAttempts.clear()
        connectVpn?.let { dnsResolver.recordConnectSuccess(it, winner.address) }
        reportConnectMetric(success = true)
        remoteChannel = winner.channel
        remoteKey = winner.key
        val remote = winner.channel
        val bound = remote.socket().localSocketAddress as? InetSocketAddress
        state = State.WRITING_REPLY
        queueOutput(replyBytes(REPLY_SUCCEEDED, bound)) { handOffTcpRelay() }
    }

    private fun handOffTcpRelay() {
        val remote = remoteChannel ?: run {
            abort("TCP relay channel missing", null)
            return
        }
        state = State.HANDED_OFF
        clientKey.cancel()
        remoteKey?.cancel()
        lane?.detach(this)
        try {
            tcpRelayPool.register(
                client = client,
                remote = remote,
                trafficListener = TcpRelayPool.TrafficListener(listener::onTraffic),
                closeListener = TcpRelayPool.CloseListener { relayFailed ->
                    if (relayFailed) failed = true
                    finish()
                },
            )
        } catch (error: Exception) {
            abort("Unable to hand off TCP relay", error)
        }
    }

    private fun startUdp(vpn: Network, request: Request) {
        if (!udpEnabled) {
            replyAndClose(REPLY_COMMAND_NOT_SUPPORTED, "UDP ASSOCIATE disabled")
            return
        }
        if (!udpSlots.tryAcquire()) {
            replyAndClose(REPLY_GENERAL_FAILURE, "UDP association limit reached")
            return
        }
        udpSlotHeld = true
        changeType(Type.UDP)
        try {
            val relay = UdpRelay(
                vpnNetwork = vpn,
                relayAddress = client.socket().localAddress,
                expectedClientAddress = client.socket().inetAddress,
                requestedClientPort = request.port,
                dnsResolver = dnsResolver,
                lane = udpRelayPool.lane(),
                listener = UdpRelay.Listener { _, relayFailed ->
                    executeOnLane {
                        if (relayFailed) failed = true
                        finish()
                    }
                },
                trafficListener = UdpRelay.TrafficListener(listener::onTraffic),
            )
            udpRelay = relay
            relay.start()
            state = State.WRITING_REPLY
            queueOutput(replyBytes(REPLY_SUCCEEDED, relay.relayEndpoint())) {
                state = State.UDP_CONTROL
                input.clear()
                setClientInterest(SelectionKey.OP_READ)
            }
        } catch (error: Exception) {
            replyAndClose(REPLY_GENERAL_FAILURE, error.message ?: "UDP relay failed")
        }
    }

    private fun queueOutput(bytes: ByteArray, continuation: () -> Unit) {
        if (closed.get()) return
        pendingOutput = ByteBuffer.wrap(bytes)
        afterOutput = continuation
        setClientInterest(SelectionKey.OP_WRITE)
        flushOutput()
    }

    private fun flushOutput() {
        val output = pendingOutput ?: return
        client.write(output)
        if (output.hasRemaining()) return
        pendingOutput = null
        val continuation = afterOutput
        afterOutput = null
        setClientInterest(0)
        continuation?.invoke()
    }

    private fun replyAndClose(reply: Int, message: String) {
        if (closed.get()) return
        failed = true
        Log.d(TAG, "SOCKS request failed: $message")
        state = State.WRITING_REPLY
        queueOutput(replyBytes(reply, null), ::finish)
    }

    private fun consume(length: Int) {
        input.flip()
        input.position(length)
        input.compact()
    }

    private fun changeType(updated: Type) {
        if (type == updated) return
        type = updated
        listener.onTypeChanged(this, updated)
    }

    internal fun isExpired(nowNanos: Long): Boolean = !closed.get() &&
        state != State.UDP_CONTROL && state != State.HANDED_OFF && state != State.CLOSED &&
        nowNanos >= deadlineNanos

    internal fun onTimeout() {
        if (closed.get()) return
        if (requestReceived) {
            replyAndClose(REPLY_HOST_UNREACHABLE, "SOCKS handshake/connect timeout")
        } else {
            failed = true
            finish()
        }
    }

    internal fun abort(
        message: String,
        error: Throwable?,
        markFailed: Boolean = true,
    ) {
        if (markFailed) failed = true
        if (error == null) Log.d(TAG, message) else Log.d(TAG, "$message: ${error.message}")
        finish()
    }

    fun close() = finish()

    private fun finish() {
        if (!closed.compareAndSet(false, true)) return
        reportConnectMetric(success = false)
        state = State.CLOSED
        if (::clientKey.isInitialized) runCatching { clientKey.cancel() }
        runCatching { remoteKey?.cancel() }
        connectAttempts.forEach { attempt ->
            runCatching { attempt.key?.cancel() }
            runCatching { attempt.channel.close() }
        }
        connectAttempts.clear()
        udpRelay?.close()
        runCatching { client.close() }
        runCatching { remoteChannel?.close() }
        if (udpSlotHeld) {
            udpSlotHeld = false
            udpSlots.release()
        }
        lane?.detach(this)
        listener.onClosed(this)
    }

    private fun reportConnectMetric(success: Boolean) {
        if (connectMetricReported || connectStartedNanos == 0L) return
        connectMetricReported = true
        val elapsedMs = (System.nanoTime() - connectStartedNanos).coerceAtLeast(0) / 1_000_000
        listener.onConnectResult(elapsedMs, success)
    }

    fun type(): Type = type

    fun clientAddress(): InetAddress = clientIp

    fun openedAtNanos(): Long = openedAtNanos

    fun failed(): Boolean = failed

    private fun executeOnLane(command: () -> Unit) {
        val currentLane = lane
        if (currentLane == null) {
            finish()
        } else {
            currentLane.execute(command)
        }
    }

    private fun setClientInterest(operations: Int) {
        if (::clientKey.isInitialized && clientKey.isValid) clientKey.interestOps(operations)
    }

    companion object {
        private const val TAG = "GatewaveSession"
        private const val SOCKS_VERSION = 0x05
        private const val METHOD_NO_AUTH = 0x00
        private const val COMMAND_CONNECT = 0x01
        private const val COMMAND_UDP_ASSOCIATE = 0x03
        private const val ADDRESS_IPV4 = 0x01
        private const val ADDRESS_DOMAIN = 0x03
        private const val ADDRESS_IPV6 = 0x04
        private const val REPLY_SUCCEEDED = 0x00
        private const val REPLY_GENERAL_FAILURE = 0x01
        private const val REPLY_CONNECTION_NOT_ALLOWED = 0x02
        private const val REPLY_NETWORK_UNREACHABLE = 0x03
        private const val REPLY_HOST_UNREACHABLE = 0x04
        private const val REPLY_COMMAND_NOT_SUPPORTED = 0x07
        private const val REPLY_ADDRESS_NOT_SUPPORTED = 0x08
        private const val CONTROL_BUFFER_SIZE = 512
        private const val HANDSHAKE_TIMEOUT_NANOS = 15_000_000_000L
        private const val CONNECT_TIMEOUT_NANOS = 30_000_000_000L
        private const val HAPPY_EYEBALLS_DELAY_NANOS = 250_000_000L
        const val TCP_IDLE_TIMEOUT_MS = 180_000

        private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

        private fun replyBytes(reply: Int, bound: InetSocketAddress?): ByteArray {
            val address = bound?.address
            val raw: ByteArray
            val type: Int
            when (address) {
                is Inet6Address -> {
                    raw = address.address
                    type = ADDRESS_IPV6
                }
                is Inet4Address -> {
                    raw = address.address
                    type = ADDRESS_IPV4
                }
                else -> {
                    raw = ByteArray(4)
                    type = ADDRESS_IPV4
                }
            }
            val port = bound?.port ?: 0
            return ByteBuffer.allocate(4 + raw.size + 2)
                .put(SOCKS_VERSION.toByte())
                .put(reply.toByte())
                .put(0)
                .put(type.toByte())
                .put(raw)
                .put(((port ushr 8) and 0xFF).toByte())
                .put((port and 0xFF).toByte())
                .array()
        }
    }
}
