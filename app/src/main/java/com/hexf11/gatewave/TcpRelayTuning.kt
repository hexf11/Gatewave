package com.hexf11.gatewave

/** Data-plane limits for one TCP selector lane. */
internal data class TcpRelayTuning(
    val initialBufferSize: Int,
    val maxBufferSize: Int,
    val readyEventBudgetBytes: Int,
    val maxPooledBytesPerLane: Int,
)

internal object TcpRelayTuningPolicy {
    fun forMode(mode: PerformanceMode): TcpRelayTuning = when (mode) {
        PerformanceMode.TURBO -> TcpRelayTuning(
            initialBufferSize = 32 * 1024,
            maxBufferSize = 128 * 1024,
            readyEventBudgetBytes = 512 * 1024,
            maxPooledBytesPerLane = 8 * 1024 * 1024,
        )
        PerformanceMode.BALANCED -> TcpRelayTuning(
            initialBufferSize = 16 * 1024,
            maxBufferSize = 64 * 1024,
            readyEventBudgetBytes = 256 * 1024,
            maxPooledBytesPerLane = 4 * 1024 * 1024,
        )
        PerformanceMode.POWER_SAVE -> TcpRelayTuning(
            initialBufferSize = 8 * 1024,
            maxBufferSize = 32 * 1024,
            readyEventBudgetBytes = 64 * 1024,
            maxPooledBytesPerLane = 1024 * 1024,
        )
    }
}

internal data class TcpBufferAdaptation(
    val size: Int,
    val consecutiveSmallReads: Int,
)

/** Pure policy kept outside the selector loop so growth/shrink behavior remains testable. */
internal object TcpBufferAdaptationPolicy {
    fun afterRead(
        currentSize: Int,
        bytesRead: Int,
        bufferCapacity: Int,
        consecutiveSmallReads: Int,
        maximumSize: Int,
    ): TcpBufferAdaptation {
        if (bytesRead == bufferCapacity && currentSize < maximumSize) {
            return TcpBufferAdaptation(
                size = (currentSize * 2).coerceAtMost(maximumSize),
                consecutiveSmallReads = 0,
            )
        }
        if (bytesRead < currentSize / 4 && currentSize > MINIMUM_BUFFER_SIZE) {
            val updatedSmallReads = consecutiveSmallReads + 1
            if (updatedSmallReads >= SMALL_READS_TO_SHRINK) {
                return TcpBufferAdaptation(
                    size = (currentSize / 2).coerceAtLeast(MINIMUM_BUFFER_SIZE),
                    consecutiveSmallReads = 0,
                )
            }
            return TcpBufferAdaptation(currentSize, updatedSmallReads)
        }
        return TcpBufferAdaptation(currentSize, 0)
    }

    private const val MINIMUM_BUFFER_SIZE = 8 * 1024
    private const val SMALL_READS_TO_SHRINK = 8
}
