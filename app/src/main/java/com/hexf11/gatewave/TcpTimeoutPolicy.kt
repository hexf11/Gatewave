package com.hexf11.gatewave

/** Keeps a short drain window after one side half-closes without retaining dead peers for minutes. */
internal object TcpTimeoutPolicy {
    const val HALF_CLOSE_TIMEOUT_MS = 15_000L

    fun timeoutNanos(halfClosed: Boolean): Long =
        (if (halfClosed) HALF_CLOSE_TIMEOUT_MS else Socks5Session.TCP_IDLE_TIMEOUT_MS.toLong()) *
            1_000_000L
}
