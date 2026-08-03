package com.hexf11.gatewave

import java.net.Inet4Address
import java.net.InetAddress

/** Alternates address families while preserving the resolver's preferred first family. */
internal object HappyEyeballsOrder {
    fun interleave(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size < 2) return addresses
        val preferIpv4 = addresses.first() is Inet4Address
        val preferred = ArrayDeque(addresses.filter { (it is Inet4Address) == preferIpv4 })
        val alternate = ArrayDeque(addresses.filter { (it is Inet4Address) != preferIpv4 })
        return buildList(addresses.size) {
            while (preferred.isNotEmpty() || alternate.isNotEmpty()) {
                preferred.removeFirstOrNull()?.let(::add)
                alternate.removeFirstOrNull()?.let(::add)
            }
        }
    }
}
