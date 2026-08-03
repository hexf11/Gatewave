package com.hexf11.gatewave

/** Coalesces timeout-wheel mutations while activity itself is still recorded precisely. */
internal class IdleTouchCoalescer(
    private val minimumIntervalNanos: Long,
) {
    private var lastScheduledNanos = UNSET

    fun shouldSchedule(nowNanos: Long, force: Boolean = false): Boolean {
        if (
            !force &&
            lastScheduledNanos != UNSET &&
            nowNanos - lastScheduledNanos < minimumIntervalNanos
        ) {
            return false
        }
        lastScheduledNanos = nowNanos
        return true
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}

/**
 * Accumulates relay byte counters so the service/UI is not notified for every socket write.
 * This object is selector-lane confined.
 */
internal class TcpTrafficBatch(
    private val byteThreshold: Long,
    private val flushIntervalNanos: Long,
) {
    data class Delta(val uploaded: Long, val downloaded: Long)

    private var pendingUploaded = 0L
    private var pendingDownloaded = 0L
    private var pendingSinceNanos = 0L

    fun add(uploaded: Long, downloaded: Long, nowNanos: Long): Delta? {
        if (uploaded <= 0L && downloaded <= 0L) return null
        if (pendingUploaded == 0L && pendingDownloaded == 0L) pendingSinceNanos = nowNanos
        pendingUploaded += uploaded.coerceAtLeast(0L)
        pendingDownloaded += downloaded.coerceAtLeast(0L)
        return if (pendingUploaded + pendingDownloaded >= byteThreshold) drain() else null
    }

    fun flushIfDue(nowNanos: Long): Delta? {
        if (pendingUploaded == 0L && pendingDownloaded == 0L) return null
        return if (nowNanos - pendingSinceNanos >= flushIntervalNanos) drain() else null
    }

    fun drain(): Delta? {
        if (pendingUploaded == 0L && pendingDownloaded == 0L) return null
        return Delta(pendingUploaded, pendingDownloaded).also {
            pendingUploaded = 0L
            pendingDownloaded = 0L
            pendingSinceNanos = 0L
        }
    }
}
