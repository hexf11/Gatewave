package com.hexf11.gatewave

import android.net.Network
import android.util.Log
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.channels.SocketChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

internal class Socks5Session(
    private val client: Socket,
    private val udpEnabled: Boolean,
    private val networkProvider: NetworkProvider,
    private val listener: Listener,
    private val tcpRelayPool: TcpRelayPool,
    private val udpExecutor: ExecutorService,
    private val udpSlots: Semaphore,
) : Runnable {
    enum class Type { NEGOTIATING, TCP, UDP }

    fun interface NetworkProvider {
        fun currentVpnNetwork(): Network?
    }

    internal interface Listener {
        fun onClosed(session: Socks5Session)
        fun onTraffic(uploaded: Long, downloaded: Long)
    }

    private val closed = AtomicBoolean(false)
    private val completionNotified = AtomicBoolean(false)
    private val tcpHandedOff = AtomicBoolean(false)
    private val udpSlotHeld = AtomicBoolean(false)

    @Volatile
    private var upstream: Socket? = null

    @Volatile
    private var udpRelay: UdpRelay? = null

    @Volatile
    private var requestReceived = false

    @Volatile
    private var terminalReplySent = false

    @Volatile
    private var failed = false

    @Volatile
    private var type = Type.NEGOTIATING

    override fun run() {
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

            negotiate(input, output)
            val request = readRequest(input)
            requestReceived = true

            val vpn = networkProvider.currentVpnNetwork()
            if (vpn == null) {
                sendReply(output, 0x03, null)
                terminalReplySent = true
                throw IOException("VPN network unavailable (fail closed)")
            }

            when (request.command) {
                0x01 -> {
                    type = Type.TCP
                    handleConnect(vpn, request, output)
                }
                0x03 -> {
                    if (!udpEnabled) {
                        sendReply(output, 0x07, null)
                        terminalReplySent = true
                        throw IOException("UDP ASSOCIATE disabled by settings")
                    }
                    type = Type.UDP
                    handleUdpAssociate(vpn, request, input, output)
                }
                else -> {
                    sendReply(output, 0x07, null)
                    terminalReplySent = true
                    throw IOException("Unsupported SOCKS command ${request.command}")
                }
            }
        } catch (_: EOFException) {
            // A client can close after negotiation without creating a relay.
        } catch (error: Exception) {
            if (!closed.get()) {
                failed = true
                Log.w(TAG, "Session failed: ${error.message}")
            }
            if (requestReceived && !terminalReplySent && !closed.get()) {
                runCatching { sendReply(client.getOutputStream(), 0x01, null) }
            }
        } finally {
            if (!tcpHandedOff.get()) complete()
        }
    }

    @Throws(IOException::class)
    private fun negotiate(input: DataInputStream, output: OutputStream) {
        val version = input.readUnsignedByte()
        if (version != 0x05) throw IOException("Unsupported SOCKS version $version")
        val methodCount = input.readUnsignedByte()
        var noAuth = false
        repeat(methodCount) {
            if (input.readUnsignedByte() == 0x00) noAuth = true
        }
        output.write(byteArrayOf(0x05, if (noAuth) 0x00 else 0xFF.toByte()))
        output.flush()
        if (!noAuth) throw IOException("Client did not offer no-auth method")
    }

    @Throws(IOException::class)
    private fun readRequest(input: DataInputStream): Request {
        val version = input.readUnsignedByte()
        val command = input.readUnsignedByte()
        input.readUnsignedByte()
        val addressType = input.readUnsignedByte()
        if (version != 0x05) throw IOException("Invalid request version")

        val host = when (addressType) {
            0x01 -> ByteArray(4).also(input::readFully).let {
                InetAddress.getByAddress(it).hostAddress.orEmpty()
            }
            0x03 -> {
                val length = input.readUnsignedByte()
                if (length == 0) {
                    if (command != 0x03) throw IOException("Empty domain name")
                    // Stash/Mihomo uses this as the unspecified UDP client endpoint.
                    "0.0.0.0"
                } else {
                    ByteArray(length).also(input::readFully)
                        .toString(StandardCharsets.US_ASCII)
                }
            }
            0x04 -> ByteArray(16).also(input::readFully).let {
                InetAddress.getByAddress(it).hostAddress.orEmpty()
            }
            else -> throw IOException("Unsupported address type $addressType")
        }
        val port = input.readUnsignedShort()
        if (port == 0 && command != 0x03) throw IOException("Invalid destination port")
        return Request(command, host, port)
    }

    @Throws(IOException::class)
    private fun handleConnect(vpn: Network, request: Request, output: OutputStream) {
        val selected = vpn.getAllByName(request.host)
            .firstOrNull { !NetworkUtils.isBlockedTarget(it) }
        if (selected == null) {
            sendReply(output, 0x02, null)
            terminalReplySent = true
            throw IOException("Destination rejected by local-network guard")
        }

        val clientChannel = client.channel
            ?: throw IOException("Accepted client has no SocketChannel")
        val remoteChannel = SocketChannel.open()
        val remote = remoteChannel.socket()
        upstream = remote
        vpn.bindSocket(remote)
        remote.tcpNoDelay = true
        remote.keepAlive = true
        remote.connect(InetSocketAddress(selected, request.port), CONNECT_TIMEOUT_MS)
        sendReply(output, 0x00, remote.localSocketAddress as InetSocketAddress)
        terminalReplySent = true

        Log.i(
            TAG,
            "CONNECT ${request.host}:${request.port} via network=$vpn " +
                "local=${remote.localAddress.hostAddress}",
        )
        tcpHandedOff.set(true)
        try {
            tcpRelayPool.register(
                client = clientChannel,
                remote = remoteChannel,
                trafficListener = TcpRelayPool.TrafficListener(listener::onTraffic),
                closeListener = TcpRelayPool.CloseListener { relayFailed ->
                    if (relayFailed && !closed.get()) failed = true
                    complete()
                },
            )
        } catch (error: Exception) {
            tcpHandedOff.set(false)
            throw error
        }
    }

    @Throws(IOException::class)
    private fun handleUdpAssociate(
        vpn: Network,
        request: Request,
        controlInput: DataInputStream,
        controlOutput: OutputStream,
    ) {
        if (!udpSlots.tryAcquire()) {
            sendReply(controlOutput, 0x01, null)
            terminalReplySent = true
            throw IOException("UDP association limit reached")
        }
        udpSlotHeld.set(true)
        val relay = UdpRelay(
            vpn,
            client.localAddress,
            client.inetAddress,
            request.port,
            udpExecutor,
            UdpRelay.Listener { _, relayFailed ->
                if (relayFailed) failed = true
                close()
            },
            UdpRelay.TrafficListener(listener::onTraffic),
        )
        udpRelay = relay
        relay.start()
        sendReply(controlOutput, 0x00, relay.relayEndpoint())
        terminalReplySent = true
        client.soTimeout = 0

        // RFC 1928 ties the association lifetime to this TCP control connection.
        while (!closed.get() && controlInput.read() != -1) {
            // No additional TCP payload is defined for UDP ASSOCIATE.
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        udpRelay?.close()
        closeQuietly(upstream)
        closeQuietly(client)
    }

    private fun complete() {
        close()
        if (udpSlotHeld.compareAndSet(true, false)) udpSlots.release()
        if (completionNotified.compareAndSet(false, true)) listener.onClosed(this)
    }

    fun type(): Type = type

    fun failed(): Boolean = failed

    private data class Request(val command: Int, val host: String, val port: Int)

    companion object {
        private const val TAG = "GatewaveSession"
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val TCP_IDLE_TIMEOUT_MS = 180_000

        @Throws(IOException::class)
        private fun sendReply(output: OutputStream, reply: Int, bound: InetSocketAddress?) {
            val address = bound?.address
            val port = bound?.port ?: 0
            val type: Int
            val raw: ByteArray
            if (address is Inet6Address) {
                type = 0x04
                raw = address.address
            } else {
                type = 0x01
                raw = if (address is Inet4Address) address.address else ByteArray(4)
            }
            output.write(byteArrayOf(0x05, reply.toByte(), 0x00, type.toByte()))
            output.write(raw)
            output.write((port ushr 8) and 0xFF)
            output.write(port and 0xFF)
            output.flush()
        }

        private fun closeQuietly(socket: Socket?) {
            runCatching { socket?.close() }
        }
    }
}
