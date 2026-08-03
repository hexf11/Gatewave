package com.hexf11.gatewave

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnDnsResolverTest {
    @Test
    fun `同一 VPN 和域名的并发解析只触发一次 lookup`() {
        val resolver = VpnDnsResolver()
        val lookups = AtomicInteger()
        val callbacks = 32
        val completed = CountDownLatch(callbacks)
        val address = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))

        repeat(callbacks) {
            resolver.resolveForTest(
                networkHandle = 42,
                host = "example.test",
                lookup = {
                    lookups.incrementAndGet()
                    Thread.sleep(50)
                    listOf(address)
                },
            ) { result ->
                assertEquals(listOf(address), result.addresses)
                completed.countDown()
            }
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(1, lookups.get())

        val cacheHit = CountDownLatch(1)
        resolver.resolveForTest(42, "EXAMPLE.TEST", { error("cache miss") }) {
            assertEquals(listOf(address), it.addresses)
            cacheHit.countDown()
        }
        assertTrue(cacheHit.await(1, TimeUnit.SECONDS))
        assertEquals(1, lookups.get())
        resolver.close()
    }

    @Test
    fun `切换 VPN handle 不复用旧缓存`() {
        val resolver = VpnDnsResolver()
        val lookups = AtomicInteger()
        val completed = CountDownLatch(2)
        val address = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

        for (handle in listOf(100L, 101L)) {
            resolver.resolveForTest(handle, "example.test", {
                lookups.incrementAndGet()
                listOf(address)
            }) { completed.countDown() }
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(2, lookups.get())
        resolver.close()
    }
}
