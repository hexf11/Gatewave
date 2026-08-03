package com.hexf11.gatewave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpRelayTuningPolicyTest {
    @Test
    fun `turbo uses the largest batching and pooling limits`() {
        val turbo = TcpRelayTuningPolicy.forMode(PerformanceMode.TURBO)
        val balanced = TcpRelayTuningPolicy.forMode(PerformanceMode.BALANCED)
        val powerSave = TcpRelayTuningPolicy.forMode(PerformanceMode.POWER_SAVE)

        assertTrue(turbo.maxBufferSize > balanced.maxBufferSize)
        assertTrue(balanced.maxBufferSize > powerSave.maxBufferSize)
        assertTrue(turbo.readyEventBudgetBytes > balanced.readyEventBudgetBytes)
        assertTrue(balanced.readyEventBudgetBytes > powerSave.readyEventBudgetBytes)
        assertEquals(8 * 1024 * 1024, turbo.maxPooledBytesPerLane)
    }

    @Test
    fun `full reads grow a relay buffer up to its mode maximum`() {
        val first = TcpBufferAdaptationPolicy.afterRead(
            currentSize = 32 * 1024,
            bytesRead = 32 * 1024,
            bufferCapacity = 32 * 1024,
            consecutiveSmallReads = 0,
            maximumSize = 128 * 1024,
        )
        val second = TcpBufferAdaptationPolicy.afterRead(
            currentSize = first.size,
            bytesRead = first.size,
            bufferCapacity = first.size,
            consecutiveSmallReads = first.consecutiveSmallReads,
            maximumSize = 128 * 1024,
        )
        val capped = TcpBufferAdaptationPolicy.afterRead(
            currentSize = second.size,
            bytesRead = second.size,
            bufferCapacity = second.size,
            consecutiveSmallReads = second.consecutiveSmallReads,
            maximumSize = 128 * 1024,
        )

        assertEquals(64 * 1024, first.size)
        assertEquals(128 * 1024, second.size)
        assertEquals(128 * 1024, capped.size)
    }

    @Test
    fun `short bursts shrink only after sustained small reads`() {
        var state = TcpBufferAdaptation(64 * 1024, 0)
        repeat(7) {
            state = TcpBufferAdaptationPolicy.afterRead(
                currentSize = state.size,
                bytesRead = 1024,
                bufferCapacity = state.size,
                consecutiveSmallReads = state.consecutiveSmallReads,
                maximumSize = 128 * 1024,
            )
        }
        assertEquals(64 * 1024, state.size)
        assertEquals(7, state.consecutiveSmallReads)

        state = TcpBufferAdaptationPolicy.afterRead(
            currentSize = state.size,
            bytesRead = 1024,
            bufferCapacity = state.size,
            consecutiveSmallReads = state.consecutiveSmallReads,
            maximumSize = 128 * 1024,
        )
        assertEquals(32 * 1024, state.size)
        assertEquals(0, state.consecutiveSmallReads)
    }

    @Test
    fun `normal read resets shrink streak`() {
        val state = TcpBufferAdaptationPolicy.afterRead(
            currentSize = 64 * 1024,
            bytesRead = 32 * 1024,
            bufferCapacity = 64 * 1024,
            consecutiveSmallReads = 6,
            maximumSize = 128 * 1024,
        )

        assertEquals(64 * 1024, state.size)
        assertEquals(0, state.consecutiveSmallReads)
    }
}
