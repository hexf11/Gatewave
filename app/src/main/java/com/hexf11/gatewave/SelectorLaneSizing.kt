package com.hexf11.gatewave

/** Keeps selector concurrency proportional to the device without creating one thread per session. */
internal object SelectorLaneSizing {
    fun relayLanes(
        processors: Int = Runtime.getRuntime().availableProcessors(),
        mode: PerformanceMode = PerformanceMode.BALANCED,
    ): Int = when (mode) {
        PerformanceMode.TURBO -> MAX_RELAY_LANES
        PerformanceMode.BALANCED ->
            (processors.coerceAtLeast(1) / 2).coerceIn(MIN_RELAY_LANES, MAX_RELAY_LANES)
        PerformanceMode.POWER_SAVE -> 1
    }

    fun handshakeLanes(
        processors: Int = Runtime.getRuntime().availableProcessors(),
        mode: PerformanceMode = PerformanceMode.BALANCED,
    ): Int = if (mode != PerformanceMode.POWER_SAVE && processors >= 8) 2 else 1

    fun udpLanes(
        processors: Int = Runtime.getRuntime().availableProcessors(),
        mode: PerformanceMode = PerformanceMode.BALANCED,
    ): Int = if (mode != PerformanceMode.POWER_SAVE && processors >= 8) 2 else 1

    private const val MIN_RELAY_LANES = 2
    private const val MAX_RELAY_LANES = 4
}
