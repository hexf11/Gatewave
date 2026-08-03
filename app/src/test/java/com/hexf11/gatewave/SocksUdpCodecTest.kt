package com.hexf11.gatewave

import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SocksUdpCodecTest {
    @Test
    fun `解码 IPv4 请求且只保留 payload`() {
        val bytes = byteArrayOf(
            0, 0, 0, 1,
            1, 1, 1, 1,
            0, 53,
            10, 20, 30,
        )

        val request = SocksUdpCodec.decode(ByteBuffer.wrap(bytes), bytes.size)

        assertEquals("1.1.1.1", request.host)
        assertEquals(53, request.port)
        assertArrayEquals(byteArrayOf(10, 20, 30), request.payload)
    }

    @Test
    fun `解码域名请求`() {
        val host = "example.com".toByteArray()
        val bytes = ByteBuffer.allocate(4 + 1 + host.size + 2 + 1)
            .put(byteArrayOf(0, 0, 0, 3, host.size.toByte()))
            .put(host)
            .putShort(443)
            .put(7.toByte())
            .array()

        val request = SocksUdpCodec.decode(ByteBuffer.wrap(bytes), bytes.size)

        assertEquals("example.com", request.host)
        assertEquals(443, request.port)
        assertArrayEquals(byteArrayOf(7), request.payload)
    }

    @Test
    fun `编码响应包含地址端口和 payload`() {
        val encoded = SocksUdpCodec.encode(
            address = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
            port = 53,
            payload = ByteBuffer.wrap(byteArrayOf(1, 2, 3)),
            payloadLength = 3,
        )

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 8, 8, 8, 8, 0, 53, 1, 2, 3),
            encoded,
        )
    }

    @Test(expected = IOException::class)
    fun `拒绝 SOCKS UDP 分片`() {
        val bytes = byteArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 0, 53)
        SocksUdpCodec.decode(ByteBuffer.wrap(bytes), bytes.size)
    }
}
