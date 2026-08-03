package com.hexf11.gatewave

import android.net.Network
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
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
        val buffer: ByteBuffer,
        val target: InetSocketAddress,
        val trafficBytes: Int,
    )

    private val closed = AtomicBoolean(false)
    private val allowedRemotes = HashSet<InetSocketAddress>()
    private val destinationCache = UdpDestinationCache(MAX_REMOTE_ENDPOINTS)
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
            if (clientSide) {
                receiveFromClient(source, buffer, length)
            } else {
                receiveFromRemote(source, buffer, length)
            }
        }
    }

    private fun receiveFromClient(source: InetSocketAddress, buffer: ByteBuffer, length: Int) {
        if (source.address != expectedClientAddress) return
        val current = clientEndpoint
        if (current == null) {
            clientEndpoint = source
        } else if (current.port != source.port) {
            return
        }

        val request = try {
            SocksUdpCodec.decode(buffer, length)
        } catch (error: Exception) {
            lane.recordDrop()
            Log.d(TAG, "Dropped malformed UDP request: ${error.message}")
            return
        }
        destinationCache.get(request.host, request.port)?.let { remote ->
            lane.recordFastPath()
            enqueueUpstream(request, remote)
            return
        }
        NumericAddressParser.parse(request.host)?.let { address ->
            if (NetworkUtils.isBlockedTarget(address)) {
                lane.recordDrop()
                return
            }
            val remote = InetSocketAddress(address, request.port)
            if (allow(remote)) {
                destinationCache.put(request.host, request.port, remote)
                lane.recordFastPath()
                enqueueUpstream(request, remote)
            }
            return
        }
        lane.recordResolutionMiss()
        dnsResolver.resolve(vpnNetwork, request.host) { result ->
            lane.execute {
                if (closed.get()) return@execute
                val address = result.addresses.firstOrNull { !NetworkUtils.isBlockedTarget(it) }
                    ?: run {
                        lane.recordDrop()
                        return@execute
                    }
                val remote = InetSocketAddress(address, request.port)
                if (allow(remote)) {
                    destinationCache.put(request.host, request.port, remote)
                    enqueueUpstream(request, remote)
                }
            }
        }
    }

    private fun allow(remote: InetSocketAddress): Boolean {
        if (remote in allowedRemotes) return true
        if (allowedRemotes.size >= MAX_REMOTE_ENDPOINTS) {
            lane.recordDrop()
            return false
        }
        allowedRemotes.add(remote)
        return true
    }

    private fun enqueueUpstream(request: SocksUdpRequest, remote: InetSocketAddress) {
        enqueue(
            queue = toUpstream,
            outgoing = Outgoing(ByteBuffer.wrap(request.payload), remote, request.payload.size),
            key = upstreamKey,
        )
    }

    private fun receiveFromRemote(
        source: InetSocketAddress,
        payload: ByteBuffer,
        payloadLength: Int,
    ) {
        if (source !in allowedRemotes) {
            lane.recordDrop()
            return
        }
        val client = clientEndpoint ?: run {
            lane.recordDrop()
            return
        }
        val response = SocksUdpCodec.encode(source.address, source.port, payload, payloadLength)
        if (response.size > MAX_DATAGRAM_SIZE) {
            lane.recordDrop()
            return
        }
        enqueue(toClient, Outgoing(ByteBuffer.wrap(response), client, payloadLength), clientKey)
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
        lane.observeQueueDepth(queue.size)
        if (key.isValid) key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
        flush(key.attachment().let { (it as UdpRelayPool.Endpoint).clientSide })
    }

    private fun flush(clientSide: Boolean) {
        val channel = if (clientSide) clientChannel else upstreamChannel
        val queue = if (clientSide) toClient else toUpstream
        val key = if (clientSide) clientKey else upstreamKey
        repeat(MAX_IO_BATCH) {
            val outgoing = queue.firstOrNull() ?: return@repeat
            val sent = channel.send(outgoing.buffer, outgoing.target)
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
        destinationCache.clear()
        lane.remove(this)
        return true
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
