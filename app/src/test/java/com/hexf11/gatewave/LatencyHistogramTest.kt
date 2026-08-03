package com.hexf11.gatewave

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyHistogramTest {
    @Test
    fun `计算固定桶百分位`() {
        val histogram = LatencyHistogram(longArrayOf(10, 50, 100))
        listOf(5L, 8L, 20L, 40L, 80L, 200L).forEach(histogram::record)

        assertEquals(50, histogram.percentile(0.5))
        assertEquals(101, histogram.percentile(0.95))
    }

    @Test
    fun `采样直方图只写首个和固定间隔样本`() {
        val histogram = SampledLatencyHistogram(longArrayOf(10, 50, 100), sampleEvery = 4)
        listOf(5L, 200L, 200L, 200L, 40L).forEach(histogram::record)

        assertEquals(10, histogram.percentile(0.5))
        assertEquals(50, histogram.percentile(0.95))
    }
}
