package com.hexf11.gatewave

import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UdpDestinationCacheTest {
    @Test
    fun `域名键忽略大小写并区分端口`() {
        val cache = UdpDestinationCache(4)
        val endpoint = InetSocketAddress("1.1.1.1", 443)

        cache.put("Example.COM", 443, endpoint)

        assertEquals(endpoint, cache.get("example.com", 443))
        assertNull(cache.get("example.com", 53))
    }

    @Test
    fun `超过容量时回收最久未使用目标`() {
        val cache = UdpDestinationCache(2)
        val first = InetSocketAddress("1.1.1.1", 53)
        val second = InetSocketAddress("8.8.8.8", 53)
        val third = InetSocketAddress("9.9.9.9", 53)
        cache.put("a.test", 53, first)
        cache.put("b.test", 53, second)
        cache.get("a.test", 53)

        cache.put("c.test", 53, third)

        assertEquals(first, cache.get("a.test", 53))
        assertNull(cache.get("b.test", 53))
        assertEquals(2, cache.size())
    }
}
