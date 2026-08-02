package com.hexf11.gatewave

import android.net.Network
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/** One RFC 1928 UDP association, scoped to the lifetime of its TCP control session. */
internal class UdpRelay @Throws(IOException::class) constructor(
    private val vpnNetwork: Network,
    relayAddress: InetAddress,
    private val expectedClientAddress: InetAddress,
    requestedClientPort: Int,
    private val executor: ExecutorService,
    private val listener: Listener,
    private val trafficListener: TrafficListener,
) {
    fun interface Listener {
        fun onTerminated(message: String, failed: Boolean)
    }

    fun interface TrafficListener {
        fun onTraffic(uploaded: Long, downloaded: Long)
    }

    private val allowedRemotes = ConcurrentHashMap.newKeySet<InetSocketAddress>()
    private val closed = AtomicBoolean(false)
    private val firstPacketLogged = AtomicBoolean(false)

    @Volatile
    private var lastActivityNanos = System.nanoTime()

    @Volatile
    private var clientSocket: DatagramSocket? = null

    @Volatile
    private var upstreamSocket: DatagramSocket? = null

    @Volatile
    private var clientEndpoint: InetSocketAddress? = null

    private val relayEndpoint: InetSocketAddress

    init {
        val lan = DatagramSocket(null)
        var upstream: DatagramSocket? = null
        try {
            lan.reuseAddress = true
            lan.bind(InetSocketAddress(relayAddress, 0))
            lan.soTimeout = 1_000

            upstream = DatagramSocket(null)
            upstream.reuseAddress = true
            upstream.bind(InetSocketAddress(0))
            vpnNetwork.bindSocket(upstream)
            upstream.soTimeout = 1_000

            clientSocket = lan
            upstreamSocket = upstream
            relayEndpoint = InetSocketAddress(relayAddress, lan.localPort)
            if (requestedClientPort != 0) {
                clientEndpoint = InetSocketAddress(expectedClientAddress, requestedClientPort)
            }
        } catch (error: Exception) {
            lan.close()
            upstream?.close()
            throw error
        }
    }

    fun start() {
        executor.execute(::clientToRemoteLoop)
        executor.execute(::remoteToClientLoop)
        Log.i(TAG, "UDP ASSOCIATE relay=$relayEndpoint via network=$vpnNetwork")
    }

    fun relayEndpoint(): InetSocketAddress = relayEndpoint

    private fun clientToRemoteLoop() {
        val buffer = ByteArray(RECEIVE_BUFFER_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed.get()) {
            try {
                packet.length = buffer.size
                val lan = clientSocket ?: return
                lan.receive(packet)
                if (!acceptClient(packet)) continue

                val request = SocksUdpDatagram.decode(packet.data, packet.length)
                val remoteAddress = resolvePublicAddress(request.host)
                if (remoteAddress == null) {
                    Log.w(TAG, "Dropped blocked/unresolved UDP target ${request.host}")
                    continue
                }

                val remote = InetSocketAddress(remoteAddress, request.port)
                if (!allowedRemotes.contains(remote) && allowedRemotes.size >= MAX_REMOTE_ENDPOINTS) {
                    Log.w(TAG, "Dropped UDP target: remote endpoint limit $MAX_REMOTE_ENDPOINTS")
                    continue
                }
                allowedRemotes.add(remote)
                upstreamSocket?.send(DatagramPacket(request.payload, request.payload.size, remote))
                    ?: return
                lastActivityNanos = System.nanoTime()
                trafficListener.onTraffic(request.payload.size.toLong(), 0)

                if (firstPacketLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "UDP ${request.host}:${request.port} bytes=${request.payload.size} " +
                            "via network=$vpnNetwork",
                    )
                }
            } catch (_: SocketTimeoutException) {
                if (isIdle()) {
                    closeForIdle()
                    return
                }
            } catch (error: SocketException) {
                if (!closed.get()) fatal("LAN UDP socket failed: ${error.message}")
                return
            } catch (error: Exception) {
                Log.w(TAG, "Dropped malformed/failed UDP request: ${error.message}")
            }
        }
    }

    private fun remoteToClientLoop() {
        val buffer = ByteArray(RECEIVE_BUFFER_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed.get()) {
            try {
                packet.length = buffer.size
                val upstream = upstreamSocket ?: return
                upstream.receive(packet)
                val remote = InetSocketAddress(packet.address, packet.port)
                if (!allowedRemotes.contains(remote)) {
                    Log.w(TAG, "Dropped UDP reply from unrequested remote $remote")
                    continue
                }
                val client = clientEndpoint ?: continue
                val response = SocksUdpDatagram.encode(
                    packet.address,
                    packet.port,
                    packet.data,
                    packet.length,
                )
                if (response.size > MAX_DATAGRAM_SIZE) {
                    Log.w(TAG, "Dropped oversized UDP response bytes=${response.size}")
                    continue
                }
                clientSocket?.send(DatagramPacket(response, response.size, client)) ?: return
                lastActivityNanos = System.nanoTime()
                trafficListener.onTraffic(0, packet.length.toLong())
            } catch (_: SocketTimeoutException) {
                if (isIdle()) {
                    closeForIdle()
                    return
                }
            } catch (error: SocketException) {
                if (!closed.get()) fatal("VPN UDP socket failed: ${error.message}")
                return
            } catch (error: Exception) {
                Log.w(TAG, "Dropped failed UDP response: ${error.message}")
            }
        }
    }

    private fun acceptClient(packet: DatagramPacket): Boolean {
        if (expectedClientAddress != packet.address) {
            Log.w(TAG, "Rejected UDP source IP ${packet.address}")
            return false
        }
        val current = clientEndpoint
        if (current == null) {
            clientEndpoint = InetSocketAddress(packet.address, packet.port)
            return true
        }
        return current.port == packet.port
    }

    @Throws(IOException::class)
    private fun resolvePublicAddress(host: String): InetAddress? =
        vpnNetwork.getAllByName(host).firstOrNull { !NetworkUtils.isBlockedTarget(it) }

    private fun fatal(message: String) {
        Log.e(TAG, message)
        if (closeInternal()) listener.onTerminated(message, true)
    }

    fun close() {
        closeInternal()
    }

    private fun isIdle(): Boolean =
        System.nanoTime() - lastActivityNanos >= UDP_IDLE_TIMEOUT_MS * 1_000_000L

    private fun closeForIdle() {
        if (!closeInternal()) return
        Log.i(TAG, "Closed idle UDP association after ${UDP_IDLE_TIMEOUT_MS}ms")
        listener.onTerminated("UDP idle timeout", false)
    }

    private fun closeInternal(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        clientSocket?.close()
        upstreamSocket?.close()
        allowedRemotes.clear()
        return true
    }

    private data class SocksUdpDatagram(
        val host: String,
        val port: Int,
        val payload: ByteArray,
    ) {
        companion object {
            @Throws(IOException::class)
            fun decode(data: ByteArray, length: Int): SocksUdpDatagram {
                if (length < 7) throw IOException("UDP datagram too short")
                var offset = 0
                if (unsigned(data[offset++]) != 0 || unsigned(data[offset++]) != 0) {
                    throw IOException("Invalid RSV field")
                }
                val fragment = unsigned(data[offset++])
                if (fragment != 0) throw IOException("UDP fragmentation is unsupported")
                val addressType = unsigned(data[offset++])

                val host = when (addressType) {
                    0x01 -> {
                        requireBytes(length, offset, 4 + 2)
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
                        requireBytes(length, offset, 16 + 2)
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

            @Throws(IOException::class)
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

            @Throws(IOException::class)
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
        private const val RECEIVE_BUFFER_SIZE = 65_535
        const val UDP_IDLE_TIMEOUT_MS = 120_000
        const val MAX_REMOTE_ENDPOINTS = 128
    }
}
