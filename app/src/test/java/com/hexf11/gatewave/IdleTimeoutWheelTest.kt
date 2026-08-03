package com.hexf11.gatewave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleTimeoutWheelTest {
    @Test
    fun `只处理到期桶并支持重新调度`() {
        val wheel = IdleTimeoutWheel<String>(tickNanos = 10, wheelSize = 8, startNanos = 0)
        val expired = mutableListOf<String>()
        wheel.schedule("a", 25)
        wheel.schedule("b", 95)
        wheel.schedule("a", 55)

        wheel.expire(49, expired::add)
        assertTrue(expired.isEmpty())
        wheel.expire(60, expired::add)
        assertEquals(listOf("a"), expired)
        wheel.expire(100, expired::add)
        assertEquals(listOf("a", "b"), expired)
        assertEquals(0, wheel.size())
    }

    @Test
    fun `跨越整个时间轮仍在真实 deadline 到期`() {
        val wheel = IdleTimeoutWheel<String>(tickNanos = 10, wheelSize = 4, startNanos = 0)
        val expired = mutableListOf<String>()
        wheel.schedule("long", 105)

        wheel.expire(80, expired::add)
        assertTrue(expired.isEmpty())
        wheel.expire(110, expired::add)
        assertEquals(listOf("long"), expired)
    }
}
