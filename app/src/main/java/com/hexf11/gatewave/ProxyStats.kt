package com.hexf11.gatewave

/** Immutable server counters used by the service, notification, and UI. */
internal data class ProxyStats(
    @JvmField val activeConnections: Int,
    @JvmField val activeTcp: Int,
    @JvmField val activeUdp: Int,
    @JvmField val totalConnections: Long,
    @JvmField val failedConnections: Long,
    @JvmField val rejectedConnections: Long,
    @JvmField val uploadBytes: Long,
    @JvmField val downloadBytes: Long,
    @JvmField val peakConnections: Int = 0,
    @JvmField val activeClients: Int = 0,
    @JvmField val largestClientConnections: Int = 0,
    @JvmField val maxSessions: Int = 0,
    @JvmField val maxUdpAssociations: Int = 0,
    @JvmField val dnsCacheHits: Long = 0,
    @JvmField val dnsCacheMisses: Long = 0,
    @JvmField val dnsCoalesced: Long = 0,
    @JvmField val dnsCacheEntries: Int = 0,
    @JvmField val connectP50Ms: Long = 0,
    @JvmField val connectP95Ms: Long = 0,
    @JvmField val uploadBytesPerSecond: Long = 0,
    @JvmField val downloadBytesPerSecond: Long = 0,
    @JvmField val udpDropped: Long = 0,
    @JvmField val fairnessReclaims: Long = 0,
    @JvmField val tcpSelectorLanes: Int = 0,
    @JvmField val udpSelectorLanes: Int = 0,
    @JvmField val tcpPooledBufferBytes: Int = 0,
    @JvmField val tcpHalfClosedConnections: Int = 0,
    @JvmField val tcpReceiveBufferBytes: Int = 0,
    @JvmField val tcpBufferedBytes: Long = 0,
    @JvmField val tcpPeakBufferedBytes: Long = 0,
    @JvmField val tcpEagerWriteBytes: Long = 0,
    @JvmField val tcpPartialWriteEvents: Long = 0,
    @JvmField val tcpReadyBudgetYields: Long = 0,
    @JvmField val tcpBufferPoolHits: Long = 0,
    @JvmField val tcpDirectBufferAllocations: Long = 0,
    @JvmField val tcpInteractiveWriteBytes: Long = 0,
    @JvmField val tcpFairnessDeferredReads: Long = 0,
    @JvmField val tcpQueueDelayP50Us: Long = 0,
    @JvmField val tcpQueueDelayP95Us: Long = 0,
    @JvmField val tcpRescheduleDelayP50Us: Long = 0,
    @JvmField val tcpRescheduleDelayP95Us: Long = 0,
    @JvmField val udpFastPathHits: Long = 0,
    @JvmField val udpResolutionMisses: Long = 0,
    @JvmField val udpMaxQueueDepth: Int = 0,
) {
    fun sameAs(other: ProxyStats?): Boolean = this == other

    companion object {
        @JvmStatic
        fun empty(): ProxyStats = ProxyStats(0, 0, 0, 0, 0, 0, 0, 0)
    }
}
