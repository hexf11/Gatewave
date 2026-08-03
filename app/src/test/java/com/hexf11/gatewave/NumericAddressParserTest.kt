package com.hexf11.gatewave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericAddressParserTest {
    @Test
    fun `解析 IPv4 和 IPv6 字面量`() {
        assertEquals("1.1.1.1", NumericAddressParser.parse("1.1.1.1")?.hostAddress)
        assertTrue(NumericAddressParser.parse("2001:db8::1")?.hostAddress?.contains(':') == true)
    }

    @Test
    fun `域名和越界 IPv4 不走数字地址路径`() {
        assertNull(NumericAddressParser.parse("example.com"))
        assertNull(NumericAddressParser.parse("256.1.1.1"))
        assertNull(NumericAddressParser.parse("1.1.1"))
    }
}
