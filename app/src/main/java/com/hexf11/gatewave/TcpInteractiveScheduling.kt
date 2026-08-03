package com.hexf11.gatewave

internal enum class TcpTrafficClass { INTERACTIVE, BULK }

/** Pure policy for keeping browser-sized transfers responsive under connection fan-out. */
internal object TcpInteractiveBudgetPolicy {
    const val INTERACTIVE_BYTES = 256 * 1024L

    fun trafficClass(relayedBytes: Long): TcpTrafficClass =
        if (relayedBytes < INTERACTIVE_BYTES) TcpTrafficClass.INTERACTIVE else TcpTrafficClass.BULK

    fun connectionBudgetBytes(
        baseBudgetBytes: Int,
        activeConnections: Int,
        trafficClass: TcpTrafficClass,
    ): Int {
        val divisor = when {
            activeConnections <= 4 -> 1
            activeConnections <= 16 -> 1
            activeConnections <= 64 -> if (trafficClass == TcpTrafficClass.INTERACTIVE) 1 else 2
            else -> if (trafficClass == TcpTrafficClass.INTERACTIVE) 2 else 4
        }
        return (baseBudgetBytes / divisor).coerceAtLeast(MINIMUM_EVENT_BUDGET_BYTES)
            .coerceAtMost(baseBudgetBytes)
    }

    fun roundBudgetBytes(
        baseBudgetBytes: Int,
        activeConnections: Int,
        trafficClass: TcpTrafficClass,
    ): Int = when (trafficClass) {
        TcpTrafficClass.INTERACTIVE -> baseBudgetBytes
        TcpTrafficClass.BULK -> connectionBudgetBytes(
            baseBudgetBytes,
            activeConnections,
            TcpTrafficClass.BULK,
        )
    }

    private const val MINIMUM_EVENT_BUDGET_BYTES = 8 * 1024
}

/**
 * Per-selector-round byte scheduler.
 *
 * Interactive and bulk traffic have independent pools, so bulk keys visited first cannot consume
 * the latency-sensitive reserve. When multiple LAN clients share a lane, each receives an equal
 * class share for the round. Unused bytes are naturally available again on the next select cycle.
 */
internal class TcpRelayRoundScheduler<K> {
    private var activeClients = 0
    private var interactiveInitial = 0
    private var bulkInitial = 0
    private var interactiveRemaining = 0
    private var bulkRemaining = 0
    private val interactiveUsedByClient = HashMap<K, Int>()
    private val bulkUsedByClient = HashMap<K, Int>()

    fun beginRound(baseBudgetBytes: Int, activeConnections: Int, activeClientCount: Int) {
        activeClients = activeClientCount.coerceAtLeast(0)
        interactiveInitial = TcpInteractiveBudgetPolicy.roundBudgetBytes(
            baseBudgetBytes,
            activeConnections,
            TcpTrafficClass.INTERACTIVE,
        )
        bulkInitial = TcpInteractiveBudgetPolicy.roundBudgetBytes(
            baseBudgetBytes,
            activeConnections,
            TcpTrafficClass.BULK,
        )
        interactiveRemaining = interactiveInitial
        bulkRemaining = bulkInitial
        interactiveUsedByClient.clear()
        bulkUsedByClient.clear()
    }

    fun grant(client: K, trafficClass: TcpTrafficClass, requestedBytes: Int): Int {
        if (requestedBytes <= 0) return 0
        val remaining = classRemaining(trafficClass)
        if (remaining <= 0) return 0
        val byClient = usedByClient(trafficClass)
        val clientRemaining = if (activeClients <= 1) {
            remaining
        } else {
            val share = divideRoundUp(classInitial(trafficClass), activeClients)
            (share - (byClient[client] ?: 0)).coerceAtLeast(0)
        }
        val granted = minOf(requestedBytes, remaining, clientRemaining)
        if (granted <= 0) return 0
        setClassRemaining(trafficClass, remaining - granted)
        byClient[client] = (byClient[client] ?: 0) + granted
        return granted
    }

    fun refund(client: K, trafficClass: TcpTrafficClass, unusedBytes: Int) {
        if (unusedBytes <= 0) return
        val byClient = usedByClient(trafficClass)
        val used = byClient[client] ?: return
        val refunded = minOf(used, unusedBytes)
        if (refunded == used) byClient.remove(client) else byClient[client] = used - refunded
        setClassRemaining(
            trafficClass,
            (classRemaining(trafficClass) + refunded).coerceAtMost(classInitial(trafficClass)),
        )
    }

    private fun classInitial(trafficClass: TcpTrafficClass): Int =
        if (trafficClass == TcpTrafficClass.INTERACTIVE) interactiveInitial else bulkInitial

    private fun classRemaining(trafficClass: TcpTrafficClass): Int =
        if (trafficClass == TcpTrafficClass.INTERACTIVE) interactiveRemaining else bulkRemaining

    private fun setClassRemaining(trafficClass: TcpTrafficClass, value: Int) {
        if (trafficClass == TcpTrafficClass.INTERACTIVE) {
            interactiveRemaining = value
        } else {
            bulkRemaining = value
        }
    }

    private fun usedByClient(trafficClass: TcpTrafficClass): HashMap<K, Int> =
        if (trafficClass == TcpTrafficClass.INTERACTIVE) {
            interactiveUsedByClient
        } else {
            bulkUsedByClient
        }

    private fun divideRoundUp(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()
}
