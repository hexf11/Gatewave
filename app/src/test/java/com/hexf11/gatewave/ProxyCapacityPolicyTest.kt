package com.hexf11.gatewave

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyCapacityPolicyTest {
    @Test
    fun `Pixel 级堆和 FD 容量启用 1024 会话`() {
        val result = ProxyCapacityPolicy.calculate(256L * 1024 * 1024, 32_768)

        assertEquals(1_024, result.maxSessions)
        assertEquals(1_024, result.acceptBacklog)
        assertEquals(128, result.maxUdpAssociations)
        assertEquals(256, result.clientSoftQuota)
        assertEquals(128, result.reservedForOtherClients)
    }

    @Test
    fun `较小堆自动降低容量`() {
        val result = ProxyCapacityPolicy.calculate(128L * 1024 * 1024, 32_768)

        assertEquals(512, result.maxSessions)
        assertEquals(64, result.maxUdpAssociations)
    }

    @Test
    fun `低 FD 上限优先保护运行时余量`() {
        val result = ProxyCapacityPolicy.calculate(512L * 1024 * 1024, 1_536)

        assertEquals(512, result.maxSessions)
    }
}
