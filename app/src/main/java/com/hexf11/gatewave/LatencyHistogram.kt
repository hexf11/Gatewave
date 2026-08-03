package com.hexf11.gatewave

import java.util.concurrent.atomic.AtomicLongArray

internal class LatencyHistogram(
    private val upperBounds: LongArray = longArrayOf(10, 25, 50, 100, 250, 500, 1_000, 2_000, 5_000, 10_000),
) {
    private val buckets = AtomicLongArray(upperBounds.size + 1)

    fun record(value: Long) {
        val index = upperBounds.indexOfFirst { value <= it }
            .let { if (it < 0) upperBounds.size else it }
        buckets.incrementAndGet(index)
    }

    fun percentile(percentile: Double): Long {
        val total = (0 until buckets.length()).sumOf(buckets::get)
        if (total == 0L) return 0
        val target = kotlin.math.ceil(total * percentile.coerceIn(0.0, 1.0)).toLong()
        var seen = 0L
        for (index in 0 until buckets.length()) {
            seen += buckets.get(index)
            if (seen >= target) return upperBounds.getOrElse(index) { upperBounds.last() + 1 }
        }
        return upperBounds.last() + 1
    }
}

/** Reduces atomic histogram writes for selector-lane telemetry while retaining tail visibility. */
internal class SampledLatencyHistogram(
    upperBounds: LongArray,
    private val sampleEvery: Int = 16,
) {
    private val histogram = LatencyHistogram(upperBounds)
    private var recordCount = 0

    fun record(value: Long) {
        if (recordCount == 0) histogram.record(value)
        recordCount++
        if (recordCount >= sampleEvery) recordCount = 0
    }

    fun percentile(percentile: Double): Long = histogram.percentile(percentile)
}
