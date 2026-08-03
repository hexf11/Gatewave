package com.hexf11.gatewave

/** Pure fairness calculations shared by admission and regression tests. */
internal object ClientFairnessPolicy {
    fun fairShare(maxSessions: Int, clientCountIncludingIncoming: Int): Int =
        (maxSessions / clientCountIncludingIncoming.coerceAtLeast(1)).coerceAtLeast(1)

    fun reservationThreshold(
        maxSessions: Int,
        activeClientCount: Int,
        configuredSoftQuota: Int,
    ): Int = maxOf(configuredSoftQuota, fairShare(maxSessions, activeClientCount))

    fun canReclaim(
        maxSessions: Int,
        clientCountIncludingIncoming: Int,
        incomingSessions: Int,
        largestOtherClientSessions: Int,
    ): Boolean {
        val target = fairShare(maxSessions, clientCountIncludingIncoming)
        return incomingSessions < target && largestOtherClientSessions > target
    }
}
