package com.hexf11.gatewave

import java.net.Socket

/** Socket-level tuning selected once when the SOCKS server starts. */
internal data class TcpSocketTuning(
    val receiveBufferBytes: Int,
    val sendBufferBytes: Int,
) {
    fun applyToRemote(socket: Socket) {
        socket.receiveBufferSize = receiveBufferBytes
        socket.sendBufferSize = sendBufferBytes
        socket.tcpNoDelay = true
        socket.keepAlive = true
    }
}

internal object TcpSocketTuningPolicy {
    fun forMode(mode: PerformanceMode): TcpSocketTuning = when (mode) {
        PerformanceMode.TURBO -> TcpSocketTuning(
            receiveBufferBytes = 8 * 1024 * 1024,
            sendBufferBytes = 2 * 1024 * 1024,
        )
        PerformanceMode.BALANCED -> TcpSocketTuning(
            receiveBufferBytes = 2 * 1024 * 1024,
            sendBufferBytes = 512 * 1024,
        )
        PerformanceMode.POWER_SAVE -> TcpSocketTuning(
            receiveBufferBytes = 512 * 1024,
            sendBufferBytes = 256 * 1024,
        )
    }
}
