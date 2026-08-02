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
) {
    fun sameAs(other: ProxyStats?): Boolean = this == other

    companion object {
        @JvmStatic
        fun empty(): ProxyStats = ProxyStats(0, 0, 0, 0, 0, 0, 0, 0)
    }
}
