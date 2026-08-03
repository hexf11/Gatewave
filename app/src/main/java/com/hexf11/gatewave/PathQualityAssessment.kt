package com.hexf11.gatewave

import java.util.Locale

internal data class PathQualitySample(
    val tcpRttMs: Long,
    val singleMbps: Double,
    val parallelMbps: Double,
)

internal object PathQualityAssessment {
    data class Result(val level: DiagnosticLevel, val detail: String)

    fun assess(sample: PathQualitySample): Result {
        val ratio = if (sample.singleMbps > 0.0) sample.parallelMbps / sample.singleMbps else 0.0
        val metrics = "TCP RTT ${sample.tcpRttMs}ms · 单流 ${format(sample.singleMbps)} Mbps · " +
            "4 流 ${format(sample.parallelMbps)} Mbps"
        return when {
            sample.tcpRttMs >= HIGH_RTT_MS && ratio >= PARALLEL_GAIN_RATIO -> Result(
                DiagnosticLevel.WARNING,
                "$metrics · 高延迟单流受限",
            )
            sample.tcpRttMs >= HIGH_RTT_MS -> Result(
                DiagnosticLevel.WARNING,
                "$metrics · VPN 路径延迟偏高",
            )
            sample.parallelMbps < MIN_USABLE_MBPS -> Result(
                DiagnosticLevel.WARNING,
                "$metrics · 当前路径吞吐偏低",
            )
            else -> Result(DiagnosticLevel.PASS, "$metrics · 路径正常")
        }
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)

    private const val HIGH_RTT_MS = 250L
    private const val PARALLEL_GAIN_RATIO = 1.5
    private const val MIN_USABLE_MBPS = 2.0
}
