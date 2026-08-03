package com.hexf11.gatewave

/** O(1) reschedulable timeout wheel used by selector-owned connections. */
internal class IdleTimeoutWheel<T>(
    private val tickNanos: Long,
    wheelSize: Int,
    startNanos: Long = System.nanoTime(),
) {
    private val buckets = List(wheelSize.coerceAtLeast(2)) { LinkedHashSet<T>() }
    private val deadlines = HashMap<T, Long>()
    private var currentTick = startNanos / tickNanos

    fun schedule(item: T, deadlineNanos: Long) {
        remove(item)
        val deadlineTick = maxOf(currentTick + 1, ceilingTick(deadlineNanos))
        deadlines[item] = deadlineTick
        buckets[index(deadlineTick)].add(item)
    }

    fun remove(item: T) {
        val tick = deadlines.remove(item) ?: return
        buckets[index(tick)].remove(item)
    }

    fun expire(nowNanos: Long, onExpired: (T) -> Unit) {
        val targetTick = nowNanos / tickNanos
        while (currentTick <= targetTick) {
            val bucket = buckets[index(currentTick)]
            val due = bucket.toList()
            bucket.clear()
            due.forEach { item ->
                val deadline = deadlines[item] ?: return@forEach
                if (deadline <= currentTick) {
                    deadlines.remove(item)
                    onExpired(item)
                } else {
                    buckets[index(deadline)].add(item)
                }
            }
            currentTick++
        }
    }

    fun size(): Int = deadlines.size

    private fun ceilingTick(nanos: Long): Long = (nanos + tickNanos - 1) / tickNanos

    private fun index(tick: Long): Int = (tick % buckets.size).toInt()
}
