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

    private data class Key(val networkHandle: Long, val host: String)
    private data class CacheEntry(val result: Result, val expiresAtNanos: Long)

    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val cache = object : LinkedHashMap<Key, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CacheEntry>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private val inFlight = HashMap<Key, MutableList<Callback>>()
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
            } else {
                if (entry != null) cache.remove(key)
                val waiters = inFlight[key]
                if (waiters != null) {
                    waiters += callback
                } else {
                    inFlight[key] = mutableListOf(callback)
                    startLookup = true
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
        synchronized(lock) { cache.clear() }
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
        private const val LOOKUP_TIMEOUT_SECONDS = 10L
        private const val POSITIVE_TTL_NANOS = 60_000_000_000L
        private const val NEGATIVE_TTL_NANOS = 5_000_000_000L
    }
}
