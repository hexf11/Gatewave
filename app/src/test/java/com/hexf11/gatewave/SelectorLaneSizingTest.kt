package com.hexf11.gatewave

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectorLaneSizingTest {
    @Test
    fun `转发 lane 随核心数增加并限制在二到四条`() {
        assertEquals(2, SelectorLaneSizing.relayLanes(1))
        assertEquals(2, SelectorLaneSizing.relayLanes(4))
        assertEquals(4, SelectorLaneSizing.relayLanes(8))
        assertEquals(4, SelectorLaneSizing.relayLanes(32))
    }

    @Test
    fun `高性能设备使用两条握手 lane`() {
        assertEquals(1, SelectorLaneSizing.handshakeLanes(4))
        assertEquals(2, SelectorLaneSizing.handshakeLanes(8))
    }
}
