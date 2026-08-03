package com.hexf11.gatewave

import android.net.Network
import android.util.Log
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** One RFC 1928 UDP association driven by a shared [UdpRelayPool] selector. */
internal class UdpRelay @Throws(IOException::class) constructor(
    private val vpnNetwork: Network,
    relayAddress: InetAddress,
    private val expectedClientAddress: InetAddress,
    requestedClientPort: Int,
    private val dnsResolver: VpnDnsResolver,
    private val lane: UdpRelayPool.Lane,
    private val listener: Listener,
    private val trafficListener: TrafficListener,
) {
    fun interface Listener {
        fun onTerminated(message: String, failed: Boolean)
    }

    fun interface TrafficListener {
        fun onTraffic(uploaded: Long, downloaded: Long)
    }

    private data class Outgoing(
        val bytes: ByteArray,
        val target: InetSocketAddress,
        val trafficBytes: Int,
    )

    private val closed = AtomicBoolean(false)
    private val allowedRemotes = HashSet<InetSocketAddress>()
    private val toClient = ArrayDeque<Outgoing>()
    private val toUpstream = ArrayDeque<Outgoing>()
    private val clientChannel = DatagramChannel.open()
    private val upstreamChannel = DatagramChannel.open()
    private val relayEndpoint: InetSocketAddress

    private lateinit var clientKey: SelectionKey
    private lateinit var upstreamKey: SelectionKey
    private var clientEndpoint: InetSocketAddress? = null

    @Volatile
    private var lastActivityNanos = System.nanoTime()

    init {
        try {
            clientChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
            clientChannel.bind(InetSocketAddress(relayAddress, 0))
            clientChannel.configureBlocking(false)

            upstreamChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
            upstreamChannel.bind(InetSocketAddress(0))
            vpnNetwork.bindSocket(upstreamChannel.socket())
            upstreamChannel.configureBlocking(false)

            relayEndpoint = clientChannel.localAddress as InetSocketAddress
            if (requestedClientPort != 0) {
                clientEndpoint = InetSocketAddress(expectedClientAddress, requestedClientPort)
            }
        } catch (error: Exception) {
            runCatching { clientChannel.close() }
            runCatching { upstreamChannel.close() }
            throw error
        }
    }

    fun start() = lane.register(this)

    fun relayEndpoint(): InetSocketAddress = relayEndpoint

    internal fun register(udpLane: UdpRelayPool.Lane, selector: Selector) {
        check(udpLane === lane)
        clientKey = clientChannel.register(
            selector,
            SelectionKey.OP_READ,
            UdpRelayPool.Endpoint(this, clientSide = true),
        )
        upstreamKey = upstreamChannel.register(
            selector,
            SelectionKey.OP_READ,
            UdpRelayPool.Endpoint(this, clientSide = false),
        )
    }

    internal fun handle(key: SelectionKey, clientSide: Boolean, receiveBuffer: ByteBuffer) {
        if (closed.get() || !key.isValid) return
        try {
            if (key.isReadable) receive(clientSide, receiveBuffer)
            if (!closed.get() && key.isValid && key.isWritable) flush(clientSide)
        } catch (error: Exception) {
            fatal("UDP channel failed: ${error.message}")
        }
    }

    private fun receive(clientSide: Boolean, buffer: ByteBuffer) {
        val channel = if (clientSide) clientChannel else upstreamChannel
        repeat(MAX_IO_BATCH) {
            buffer.clear()
            val source = channel.receive(buffer) as? InetSocketAddress ?: return
            val length = buffer.position()
            if (length == 0) return@repeat
            buffer.flip()
            val bytes = ByteArray(length)
            buffer.get(bytes)
            if (clientSide) receiveFromClient(source, bytes) else receiveFromRemote(source, bytes)
        }
    }

    private fun receiveFromClient(source: InetSocketAddress, bytes: ByteArray) {
        if (source.address != expectedClientAddress) return
        val current = clientEndpoint
        if (current == null) {
            clientEndpoint = source
        } else if (current.port != source.port) {
            return
        }

        val request = try {
            SocksUdpDatagram.decode(bytes, bytes.size)
        } catch (error: Exception) {
            lane.recordDrop()
            Log.d(TAG, "Dropped malformed UDP request: ${error.message}")
            return
        }
        dnsResolver.resolve(vpnNetwork, request.host) { result ->
            lane.execute {
                if (closed.get()) return@execute
                val address = result.addresses.firstOrNull { !NetworkUtils.isBlockedTarget(it) }
                    ?: run {
                        lane.recordDrop()
                        return@execute
                    }
                val remote = InetSocketAddress(address, request.port)
                if (remote !in allowedRemotes && allowedRemotes.size >= MAX_REMOTE_ENDPOINTS) {
                    lane.recordDrop()
                    return@execute
                }
                allowedRemotes.add(remote)
                enqueue(
                    queue = toUpstream,
                    outgoing = Outgoing(request.payload, remote, request.payload.size),
                    key = upstreamKey,
                )
            }
        }
    }

    private fun receiveFromRemote(source: InetSocketAddress, payload: ByteArray) {
        if (source !in allowedRemotes) {
            lane.recordDrop()
            return
        }
        val client = clientEndpoint ?: run {
            lane.recordDrop()
            return
        }
        val response = SocksUdpDatagram.encode(source.address, source.port, payload, payload.size)
        if (response.size > MAX_DATAGRAM_SIZE) {
            lane.recordDrop()
            return
        }
        enqueue(toClient, Outgoing(response, client, payload.size), clientKey)
    }

    private fun enqueue(
        queue: ArrayDeque<Outgoing>,
        outgoing: Outgoing,
        key: SelectionKey,
    ) {
        if (queue.size >= MAX_PENDING_DATAGRAMS) {
            lane.recordDrop()
            return
        }
        queue.addLast(outgoing)
        if (key.isValid) key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
        flush(key.attachment().let { (it as UdpRelayPool.Endpoint).clientSide })
    }

    private fun flush(clientSide: Boolean) {
        val channel = if (clientSide) clientChannel else upstreamChannel
        val queue = if (clientSide) toClient else toUpstream
        val key = if (clientSide) clientKey else upstreamKey
        repeat(MAX_IO_BATCH) {
            val outgoing = queue.firstOrNull() ?: return@repeat
            val sent = channel.send(ByteBuffer.wrap(outgoing.bytes), outgoing.target)
            if (sent == 0) return
            queue.removeFirst()
            lastActivityNanos = System.nanoTime()
            trafficListener.onTraffic(
                if (clientSide) 0 else outgoing.trafficBytes.toLong(),
                if (clientSide) outgoing.trafficBytes.toLong() else 0,
            )
        }
        if (queue.isEmpty() && key.isValid) {
            key.interestOps(key.interestOps() and SelectionKey.OP_WRITE.inv())
        }
    }

    internal fun isIdle(nowNanos: Long): Boolean =
        !closed.get() && nowNanos - lastActivityNanos >= UDP_IDLE_TIMEOUT_NANOS

    internal fun closeForIdle() {
        if (closeInternal()) listener.onTerminated("UDP idle timeout", false)
    }

    internal fun registrationFailed(error: Throwable) {
        if (closeInternal()) listener.onTerminated("UDP registration failed: ${error.message}", true)
    }

    private fun fatal(message: String) {
        if (closeInternal()) listener.onTerminated(message, true)
    }

    fun close() {
        closeInternal()
    }

    internal fun closeFromPool() {
        closeInternal()
    }

    private fun closeInternal(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        if (::clientKey.isInitialized) runCatching { clientKey.cancel() }
        if (::upstreamKey.isInitialized) runCatching { upstreamKey.cancel() }
        runCatching { clientChannel.close() }
        runCatching { upstreamChannel.close() }
        toClient.clear()
        toUpstream.clear()
        allowedRemotes.clear()
        lane.remove(this)
        return true
    }

    private data class SocksUdpDatagram(
        val host: String,
        val port: Int,
        val payload: ByteArray,
    ) {
        companion object {
            fun decode(data: ByteArray, length: Int): SocksUdpDatagram {
                if (length < 7) throw IOException("UDP datagram too short")
                var offset = 0
                if (unsigned(data[offset++]) != 0 || unsigned(data[offset++]) != 0) {
                    throw IOException("Invalid RSV field")
                }
                if (unsigned(data[offset++]) != 0) throw IOException("UDP fragmentation unsupported")
                val addressType = unsigned(data[offset++])
                val host = when (addressType) {
                    0x01 -> {
                        requireBytes(length, offset, 6)
                        InetAddress.getByAddress(data.copyOfRange(offset, offset + 4))
                            .hostAddress.orEmpty().also { offset += 4 }
                    }
                    0x03 -> {
                        requireBytes(length, offset, 1)
                        val domainLength = unsigned(data[offset++])
                        if (domainLength == 0) throw IOException("Empty UDP domain")
                        requireBytes(length, offset, domainLength + 2)
                        String(data, offset, domainLength, StandardCharsets.US_ASCII)
                            .also { offset += domainLength }
                    }
                    0x04 -> {
                        requireBytes(length, offset, 18)
                        InetAddress.getByAddress(data.copyOfRange(offset, offset + 16))
                            .hostAddress.orEmpty().also { offset += 16 }
                    }
                    else -> throw IOException("Unsupported UDP address type $addressType")
                }
                requireBytes(length, offset, 2)
                val port = (unsigned(data[offset++]) shl 8) or unsigned(data[offset++])
                if (port == 0) throw IOException("Invalid UDP destination port")
                return SocksUdpDatagram(host, port, data.copyOfRange(offset, length))
            }

            fun encode(
                address: InetAddress,
                port: Int,
                payload: ByteArray,
                payloadLength: Int,
            ): ByteArray {
                val raw = address.address
                val addressType = when (address) {
                    is Inet4Address -> 0x01
                    is Inet6Address -> 0x04
                    else -> throw IOException("Unsupported reply address")
                }
                val result = ByteArray(4 + raw.size + 2 + payloadLength)
                var offset = 0
                result[offset++] = 0
                result[offset++] = 0
                result[offset++] = 0
                result[offset++] = addressType.toByte()
                raw.copyInto(result, offset)
                offset += raw.size
                result[offset++] = ((port ushr 8) and 0xFF).toByte()
                result[offset++] = (port and 0xFF).toByte()
                payload.copyInto(result, offset, endIndex = payloadLength)
                return result
            }

            private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

            private fun requireBytes(length: Int, offset: Int, needed: Int) {
                if (offset < 0 || needed < 0 || offset + needed > length) {
                    throw IOException("Truncated UDP datagram")
                }
            }
        }
    }

    companion object {
        private const val TAG = "GatewaveUdp"
        private const val MAX_DATAGRAM_SIZE = 65_507
        private const val MAX_REMOTE_ENDPOINTS = 256
        private const val MAX_PENDING_DATAGRAMS = 256
        private const val MAX_IO_BATCH = 64
        private const val UDP_IDLE_TIMEOUT_NANOS = 120_000_000_000L
    }
}
