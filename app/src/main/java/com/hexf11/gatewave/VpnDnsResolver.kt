package com.hexf11.gatewave

import android.net.Network
import android.util.Log
import java.io.Closeable
import java.net.InetAddress
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Small VPN-scoped DNS cache with single-flight lookup coalescing.
 *
 * Cache keys include the Android network handle, so an address learned through an old VPN is never
 * reused after the system moves Gatewave to another VPN network.
 */
internal class VpnDnsResolver : Closeable {
    fun interface Callback {
        fun onResult(result: Result)
    }

    data class Result(
        val addresses: List<InetAddress>,
        val error: Throwable? = null,
    )

    data class Stats(val hits: Long, val misses: Long, val coalesced: Long, val cacheEntries: Int)

    private data class Key(val networkHandle: Long, val host: String)
    private data class AddressKey(val networkHandle: Long, val address: String)
    private data class CacheEntry(val result: Result, val expiresAtNanos: Long)
    private data class AddressHealth(var failures: Int, var lastFailureNanos: Long)

    private val closed = AtomicBoolean(false)
    private val cacheHits = AtomicLong()
    private val cacheMisses = AtomicLong()
    private val coalescedLookups = AtomicLong()
    private val lock = Any()
    private val cache = object : LinkedHashMap<Key, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CacheEntry>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private val inFlight = HashMap<Key, MutableList<Callback>>()
    private val addressHealth = HashMap<AddressKey, AddressHealth>()
    private val executor: ExecutorService = Executors.newFixedThreadPool(
        DNS_WORKERS,
    ) { runnable ->
        Thread(runnable, "proxy-dns").apply { isDaemon = true }
    }

    fun resolve(network: Network, host: String, callback: Callback) {
        if (closed.get()) {
            callback.onResult(Result(emptyList(), IllegalStateException("DNS resolver is closed")))
            return
        }

        parseNumericAddress(host)?.let {
            callback.onResult(Result(listOf(it)))
            return
        }

        resolve(
            key = Key(network.networkHandle, host.lowercase(Locale.ROOT)),
            lookup = { network.getAllByName(host).distinct() },
            callback = callback,
        )
    }

    internal fun resolveForTest(
        networkHandle: Long,
        host: String,
        lookup: () -> List<InetAddress>,
        callback: Callback,
    ) = resolve(Key(networkHandle, host.lowercase(Locale.ROOT)), lookup, callback)

    private fun resolve(key: Key, lookup: () -> List<InetAddress>, callback: Callback) {
        val now = System.nanoTime()
        var cached: Result? = null
        var startLookup = false
        synchronized(lock) {
            val entry = cache[key]
            if (entry != null && entry.expiresAtNanos > now) {
                cached = entry.result
                cacheHits.incrementAndGet()
            } else {
                if (entry != null) cache.remove(key)
                val waiters = inFlight[key]
                if (waiters != null) {
                    waiters += callback
                    coalescedLookups.incrementAndGet()
                } else {
                    inFlight[key] = mutableListOf(callback)
                    startLookup = true
                    cacheMisses.incrementAndGet()
                }
            }
        }

        cached?.let {
            callback.onResult(it)
            return
        }
        if (!startLookup) return

        runCatching {
            executor.execute {
                val result = try {
                    Result(lookup().distinct())
                } catch (error: Throwable) {
                    Result(emptyList(), error)
                }
                complete(key, result)
            }
        }.onFailure { complete(key, Result(emptyList(), it)) }
    }

    fun resolveBlocking(network: Network, host: String): Result {
        var result: Result? = null
        val completed = CountDownLatch(1)
        resolve(network, host) {
            result = it
            completed.countDown()
        }
        if (!completed.await(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return Result(emptyList(), java.net.SocketTimeoutException("VPN DNS lookup timed out"))
        }
        return result ?: Result(emptyList(), IllegalStateException("VPN DNS lookup produced no result"))
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            addressHealth.clear()
        }
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(cacheHits.get(), cacheMisses.get(), coalescedLookups.get(), cache.size)
    }

    fun orderedAddresses(network: Network, addresses: List<InetAddress>): List<InetAddress> =
        orderedAddresses(network.networkHandle, addresses)

    internal fun orderedAddressesForTest(
        networkHandle: Long,
        addresses: List<InetAddress>,
    ): List<InetAddress> = orderedAddresses(networkHandle, addresses)

    private fun orderedAddresses(networkHandle: Long, addresses: List<InetAddress>): List<InetAddress> =
        synchronized(lock) {
            addresses.withIndex()
                .groupBy { indexed ->
                    addressHealth[AddressKey(networkHandle, indexed.value.hostAddress.orEmpty())]
                        ?.failures ?: 0
                }
                .toSortedMap()
                .values
                .flatMap { tier -> HappyEyeballsOrder.interleave(tier.map { it.value }) }
        }

    fun recordConnectSuccess(network: Network, address: InetAddress) {
        recordConnectSuccess(network.networkHandle, address)
    }

    internal fun recordConnectSuccessForTest(networkHandle: Long, address: InetAddress) {
        recordConnectSuccess(networkHandle, address)
    }

    private fun recordConnectSuccess(networkHandle: Long, address: InetAddress) {
        synchronized(lock) {
            addressHealth.remove(AddressKey(networkHandle, address.hostAddress.orEmpty()))
        }
    }

    fun recordConnectFailure(network: Network, address: InetAddress) {
        recordConnectFailure(network.networkHandle, address)
    }

    internal fun recordConnectFailureForTest(networkHandle: Long, address: InetAddress) {
        recordConnectFailure(networkHandle, address)
    }

    private fun recordConnectFailure(networkHandle: Long, address: InetAddress) {
        synchronized(lock) {
            val key = AddressKey(networkHandle, address.hostAddress.orEmpty())
            val health = addressHealth.getOrPut(key) { AddressHealth(0, 0) }
            health.failures = (health.failures + 1).coerceAtMost(MAX_FAILURE_SCORE)
            health.lastFailureNanos = System.nanoTime()
            if (addressHealth.size > MAX_ADDRESS_HEALTH_ENTRIES) {
                addressHealth.entries.minByOrNull { it.value.lastFailureNanos }
                    ?.let { addressHealth.remove(it.key) }
            }
        }
    }

    private fun complete(key: Key, result: Result) {
        val callbacks: List<Callback>
        synchronized(lock) {
            val ttl = if (result.addresses.isEmpty()) NEGATIVE_TTL_NANOS else POSITIVE_TTL_NANOS
            if (!closed.get()) cache[key] = CacheEntry(result, System.nanoTime() + ttl)
            callbacks = inFlight.remove(key).orEmpty()
        }
        callbacks.forEach { callback ->
            runCatching { callback.onResult(result) }
                .onFailure { Log.d(TAG, "DNS callback ended: ${it.message}") }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val callbacks: List<Callback>
        synchronized(lock) {
            cache.clear()
            addressHealth.clear()
            callbacks = inFlight.values.flatten()
            inFlight.clear()
        }
        val result = Result(emptyList(), IllegalStateException("DNS resolver stopped"))
        callbacks.forEach { runCatching { it.onResult(result) } }
        executor.shutdownNow()
    }

    private fun parseNumericAddress(host: String): InetAddress? {
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
        // A colon cannot occur in a DNS hostname. The platform parser therefore cannot leak this
        // path to a DNS resolver, while still handling compressed IPv6 literals correctly.
        return if (':' in host) runCatching { InetAddress.getByName(host) }.getOrNull() else null
    }

    companion object {
        private const val TAG = "GatewaveDns"
        private const val MAX_CACHE_ENTRIES = 512
        private const val DNS_WORKERS = 4
        private const val MAX_ADDRESS_HEALTH_ENTRIES = 1_024
        private const val MAX_FAILURE_SCORE = 5
        private const val LOOKUP_TIMEOUT_SECONDS = 10L
        private const val POSITIVE_TTL_NANOS = 60_000_000_000L
        private const val NEGATIVE_TTL_NANOS = 5_000_000_000L
    }
}
