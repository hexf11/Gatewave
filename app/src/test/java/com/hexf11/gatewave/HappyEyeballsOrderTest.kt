package com.hexf11.gatewave

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class HappyEyeballsOrderTest {
    @Test
    fun `保留首选地址族并交替 IPv6 与 IPv4`() {
        val v6a = InetAddress.getByName("2001:db8::1")
        val v6b = InetAddress.getByName("2001:db8::2")
        val v4a = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        val v4b = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

        assertEquals(
            listOf(v6a, v4a, v6b, v4b),
            HappyEyeballsOrder.interleave(listOf(v6a, v6b, v4a, v4b)),
        )
        assertEquals(
            listOf(v4a, v6a, v4b, v6b),
            HappyEyeballsOrder.interleave(listOf(v4a, v4b, v6a, v6b)),
        )
    }
}
