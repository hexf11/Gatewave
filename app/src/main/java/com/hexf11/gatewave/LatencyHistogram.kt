package com.hexf11.gatewave

import java.util.concurrent.atomic.AtomicLongArray

internal class LatencyHistogram(
    private val upperBoundsMs: LongArray = longArrayOf(10, 25, 50, 100, 250, 500, 1_000, 2_000, 5_000, 10_000),
) {
    private val buckets = AtomicLongArray(upperBoundsMs.size + 1)

    fun record(milliseconds: Long) {
        val index = upperBoundsMs.indexOfFirst { milliseconds <= it }
            .let { if (it < 0) upperBoundsMs.size else it }
        buckets.incrementAndGet(index)
    }

    fun percentile(percentile: Double): Long {
        val total = (0 until buckets.length()).sumOf(buckets::get)
        if (total == 0L) return 0
        val target = kotlin.math.ceil(total * percentile.coerceIn(0.0, 1.0)).toLong()
        var seen = 0L
        for (index in 0 until buckets.length()) {
            seen += buckets.get(index)
            if (seen >= target) return upperBoundsMs.getOrElse(index) { upperBoundsMs.last() + 1 }
        }
        return upperBoundsMs.last() + 1
    }
}
