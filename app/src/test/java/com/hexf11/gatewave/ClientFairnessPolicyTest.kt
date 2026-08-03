package com.hexf11.gatewave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientFairnessPolicyTest {
    @Test
    fun `20 个客户端在 1024 会话下获得动态公平份额`() {
        assertEquals(51, ClientFairnessPolicy.fairShare(1_024, 20))
        assertTrue(ClientFairnessPolicy.canReclaim(1_024, 20, 0, 1_024))
        assertTrue(ClientFairnessPolicy.canReclaim(1_024, 20, 50, 56))
        assertFalse(ClientFairnessPolicy.canReclaim(1_024, 20, 51, 56))
        assertFalse(ClientFairnessPolicy.canReclaim(1_024, 20, 20, 51))
    }

    @Test
    fun `两客户端可均分满载容量而单客户端可使用全部容量`() {
        assertEquals(1_024, ClientFairnessPolicy.fairShare(1_024, 1))
        assertEquals(512, ClientFairnessPolicy.fairShare(1_024, 2))
        assertTrue(ClientFairnessPolicy.canReclaim(1_024, 2, 200, 824))
        assertFalse(ClientFairnessPolicy.canReclaim(1_024, 2, 512, 512))
        assertEquals(512, ClientFairnessPolicy.reservationThreshold(1_024, 2, 256))
        assertEquals(256, ClientFairnessPolicy.reservationThreshold(1_024, 20, 256))
    }
}
