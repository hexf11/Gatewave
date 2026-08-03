package com.hexf11.gatewave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathQualityAssessmentTest {
    @Test
    fun `高 RTT 且并发提升明显时识别单流受限`() {
        val result = PathQualityAssessment.assess(
            PathQualitySample(tcpRttMs = 310, singleMbps = 5.0, parallelMbps = 20.0),
        )

        assertEquals(DiagnosticLevel.WARNING, result.level)
        assertTrue(result.detail.contains("高延迟单流受限"))
    }

    @Test
    fun `低延迟且吞吐正常时通过`() {
        val result = PathQualityAssessment.assess(
            PathQualitySample(tcpRttMs = 40, singleMbps = 20.0, parallelMbps = 40.0),
        )

        assertEquals(DiagnosticLevel.PASS, result.level)
        assertTrue(result.detail.contains("路径正常"))
    }
}
