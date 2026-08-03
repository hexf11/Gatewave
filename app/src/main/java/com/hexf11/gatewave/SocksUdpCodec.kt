package com.hexf11.gatewave

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal data class SocksUdpRequest(
    val host: String,
    val port: Int,
    val payload: ByteArray,
)

/** RFC 1928 UDP framing with one payload allocation per received datagram. */
internal object SocksUdpCodec {
    fun decode(buffer: ByteBuffer, length: Int): SocksUdpRequest {
        if (length < 7 || length > buffer.limit()) throw IOException("UDP datagram too short")
        if (unsigned(buffer.get()) != 0 || unsigned(buffer.get()) != 0) {
            throw IOException("Invalid RSV field")
        }
        if (unsigned(buffer.get()) != 0) throw IOException("UDP fragmentation unsupported")
        val host = when (val addressType = unsigned(buffer.get())) {
            0x01 -> InetAddress.getByAddress(readBytes(buffer, 4)).hostAddress.orEmpty()
            0x03 -> {
                requireRemaining(buffer, 1)
                val domainLength = unsigned(buffer.get())
                if (domainLength == 0) throw IOException("Empty UDP domain")
                String(readBytes(buffer, domainLength), StandardCharsets.US_ASCII)
            }
            0x04 -> InetAddress.getByAddress(readBytes(buffer, 16)).hostAddress.orEmpty()
            else -> throw IOException("Unsupported UDP address type $addressType")
        }
        requireRemaining(buffer, 2)
        val port = (unsigned(buffer.get()) shl 8) or unsigned(buffer.get())
        if (port == 0) throw IOException("Invalid UDP destination port")
        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)
        return SocksUdpRequest(host, port, payload)
    }

    fun encode(
        address: InetAddress,
        port: Int,
        payload: ByteBuffer,
        payloadLength: Int,
    ): ByteArray {
        val raw = address.address
        val addressType = when (address) {
            is Inet4Address -> 0x01
            is Inet6Address -> 0x04
            else -> throw IOException("Unsupported reply address")
        }
        if (payloadLength < 0 || payloadLength > payload.remaining()) {
            throw IOException("Invalid UDP payload length")
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
        payload.get(result, offset, payloadLength)
        return result
    }

    private fun readBytes(buffer: ByteBuffer, length: Int): ByteArray {
        requireRemaining(buffer, length)
        return ByteArray(length).also(buffer::get)
    }

    private fun requireRemaining(buffer: ByteBuffer, needed: Int) {
        if (needed < 0 || buffer.remaining() < needed) throw IOException("Truncated UDP datagram")
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF
}
