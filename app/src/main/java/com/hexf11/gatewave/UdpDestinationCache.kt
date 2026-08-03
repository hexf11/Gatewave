package com.hexf11.gatewave

import java.net.InetSocketAddress
import java.util.LinkedHashMap
import java.util.Locale

/** Per-association destination cache; accessed only by one UDP selector lane. */
internal class UdpDestinationCache(private val maxEntries: Int) {
    private data class Key(val host: String, val port: Int)

    private val entries = object : LinkedHashMap<Key, InetSocketAddress>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, InetSocketAddress>?,
        ): Boolean = size > maxEntries
    }

    fun get(host: String, port: Int): InetSocketAddress? = entries[key(host, port)]

    fun put(host: String, port: Int, endpoint: InetSocketAddress) {
        entries[key(host, port)] = endpoint
    }

    fun clear() = entries.clear()

    fun size(): Int = entries.size

    private fun key(host: String, port: Int) = Key(host.lowercase(Locale.ROOT), port)
}
