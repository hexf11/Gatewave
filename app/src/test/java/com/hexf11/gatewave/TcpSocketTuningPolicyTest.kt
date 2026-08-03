package com.hexf11.gatewave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpSocketTuningPolicyTest {
    @Test
    fun `极速模式使用八 MiB 上游接收窗口`() {
        val tuning = TcpSocketTuningPolicy.forMode(PerformanceMode.TURBO)

        assertEquals(8 * 1024 * 1024, tuning.receiveBufferBytes)
        assertEquals(2 * 1024 * 1024, tuning.sendBufferBytes)
    }

    @Test
    fun `模式越快接收窗口越大`() {
        val powerSave = TcpSocketTuningPolicy.forMode(PerformanceMode.POWER_SAVE)
        val balanced = TcpSocketTuningPolicy.forMode(PerformanceMode.BALANCED)
        val turbo = TcpSocketTuningPolicy.forMode(PerformanceMode.TURBO)

        assertTrue(powerSave.receiveBufferBytes < balanced.receiveBufferBytes)
        assertTrue(balanced.receiveBufferBytes < turbo.receiveBufferBytes)
    }
}
