package com.hexf11.gatewave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpHotPathPoliciesTest {
    @Test
    fun `活跃连接每秒最多重排一次超时轮`() {
        val coalescer = IdleTouchCoalescer(minimumIntervalNanos = 1_000)

        assertTrue(coalescer.shouldSchedule(10_000))
        assertFalse(coalescer.shouldSchedule(10_999))
        assertTrue(coalescer.shouldSchedule(11_000))
        assertTrue(coalescer.shouldSchedule(11_001, force = true))
    }

    @Test
    fun `流量达到阈值后一次性精确上报`() {
        val batch = TcpTrafficBatch(byteThreshold = 1_024, flushIntervalNanos = 1_000)

        assertNull(batch.add(uploaded = 400, downloaded = 500, nowNanos = 10_000))
        assertEquals(
            TcpTrafficBatch.Delta(uploaded = 525, downloaded = 500),
            batch.add(uploaded = 125, downloaded = 0, nowNanos = 10_100),
        )
        assertNull(batch.drain())
    }

    @Test
    fun `低流量按时间刷新并在关闭时排空`() {
        val batch = TcpTrafficBatch(byteThreshold = 1_024, flushIntervalNanos = 1_000)

        assertNull(batch.add(uploaded = 20, downloaded = 30, nowNanos = 10_000))
        assertNull(batch.flushIfDue(10_999))
        assertEquals(
            TcpTrafficBatch.Delta(uploaded = 20, downloaded = 30),
            batch.flushIfDue(11_000),
        )
        assertNull(batch.add(uploaded = 7, downloaded = 9, nowNanos = 12_000))
        assertEquals(TcpTrafficBatch.Delta(7, 9), batch.drain())
    }

    @Test
    fun `lane缓冲池复用同一直接缓冲并遵守容量上限`() {
        val pool = LaneDirectBufferPool(maximumPooledBytes = 1_024)
        val first = pool.acquire(1_024)
        pool.release(first)

        val reused = pool.acquire(1_024)
        assertSame(first, reused)
        assertEquals(1, pool.hits())
        assertEquals(1, pool.allocations())
        pool.release(reused)
        pool.release(pool.acquire(2_048))
        assertEquals(1_024, pool.pooledBytes())
    }
}
