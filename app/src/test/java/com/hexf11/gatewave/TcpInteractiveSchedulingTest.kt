package com.hexf11.gatewave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpInteractiveSchedulingTest {
    @Test
    fun `new transfer is interactive until threshold`() {
        assertEquals(
            TcpTrafficClass.INTERACTIVE,
            TcpInteractiveBudgetPolicy.trafficClass(0),
        )
        assertEquals(
            TcpTrafficClass.INTERACTIVE,
            TcpInteractiveBudgetPolicy.trafficClass(
                TcpInteractiveBudgetPolicy.INTERACTIVE_BYTES - 1,
            ),
        )
        assertEquals(
            TcpTrafficClass.BULK,
            TcpInteractiveBudgetPolicy.trafficClass(
                TcpInteractiveBudgetPolicy.INTERACTIVE_BYTES,
            ),
        )
    }

    @Test
    fun `connection fan out shrinks bulk before interactive budget`() {
        val base = 512 * 1024
        val interactive = TcpInteractiveBudgetPolicy.connectionBudgetBytes(
            base,
            activeConnections = 100,
            TcpTrafficClass.INTERACTIVE,
        )
        val bulk = TcpInteractiveBudgetPolicy.connectionBudgetBytes(
            base,
            activeConnections = 100,
            TcpTrafficClass.BULK,
        )

        assertEquals(256 * 1024, interactive)
        assertEquals(128 * 1024, bulk)
        assertTrue(interactive > bulk)
    }

    @Test
    fun `bulk cannot consume the interactive round reserve`() {
        val scheduler = TcpRelayRoundScheduler<String>()
        scheduler.beginRound(
            baseBudgetBytes = 512 * 1024,
            activeConnections = 100,
            activeClientCount = 1,
        )

        assertEquals(
            128 * 1024,
            scheduler.grant("client-a", TcpTrafficClass.BULK, 512 * 1024),
        )
        assertEquals(0, scheduler.grant("client-a", TcpTrafficClass.BULK, 1))
        assertEquals(
            128 * 1024,
            scheduler.grant("client-a", TcpTrafficClass.INTERACTIVE, 128 * 1024),
        )
    }

    @Test
    fun `clients receive equal class shares in one selector round`() {
        val scheduler = TcpRelayRoundScheduler<String>()
        scheduler.beginRound(
            baseBudgetBytes = 512 * 1024,
            activeConnections = 32,
            activeClientCount = 2,
        )

        assertEquals(
            256 * 1024,
            scheduler.grant("client-a", TcpTrafficClass.INTERACTIVE, 512 * 1024),
        )
        assertEquals(0, scheduler.grant("client-a", TcpTrafficClass.INTERACTIVE, 1))
        assertEquals(
            256 * 1024,
            scheduler.grant("client-b", TcpTrafficClass.INTERACTIVE, 512 * 1024),
        )
    }

    @Test
    fun `unused reservation is refundable within the round`() {
        val scheduler = TcpRelayRoundScheduler<String>()
        scheduler.beginRound(
            baseBudgetBytes = 256 * 1024,
            activeConnections = 8,
            activeClientCount = 1,
        )
        val granted = scheduler.grant(
            "client-a",
            TcpTrafficClass.INTERACTIVE,
            256 * 1024,
        )
        scheduler.refund("client-a", TcpTrafficClass.INTERACTIVE, granted - 32 * 1024)

        assertEquals(
            224 * 1024,
            scheduler.grant("client-a", TcpTrafficClass.INTERACTIVE, 256 * 1024),
        )
    }
}
