package com.hexf11.gatewave

import org.junit.Assert.assertEquals
import org.junit.Test

class TcpTimeoutPolicyTest {
    @Test
    fun `半关闭连接使用短排空窗口`() {
        assertEquals(15_000_000_000L, TcpTimeoutPolicy.timeoutNanos(halfClosed = true))
        assertEquals(180_000_000_000L, TcpTimeoutPolicy.timeoutNanos(halfClosed = false))
    }
}
