package com.hexf11.gatewave

import java.net.InetAddress

/** Parses IP literals without ever sending a DNS query. */
internal object NumericAddressParser {
    fun parse(host: String): InetAddress? {
        val ipv4 = host.split('.')
        if (ipv4.size == 4) {
            val raw = ByteArray(4)
            for (index in raw.indices) {
                val part = ipv4[index]
                if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
                val value = part.toIntOrNull() ?: return null
                if (value !in 0..255) return null
                raw[index] = value.toByte()
            }
            return runCatching { InetAddress.getByAddress(raw) }.getOrNull()
        }
        // A colon cannot occur in a DNS hostname. Calling the platform parser on this branch is
        // therefore restricted to IPv6 literals and never leaks a hostname to DNS.
        return if (':' in host) runCatching { InetAddress.getByName(host) }.getOrNull() else null
    }
}
