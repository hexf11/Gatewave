package com.hexf11.gatewave

import java.io.File

internal data class ProxyCapacity(
    val maxSessions: Int,
    val acceptBacklog: Int,
    val maxUdpAssociations: Int,
    val clientSoftQuota: Int,
    val reservedForOtherClients: Int,
)

/** Derives conservative data-plane limits from heap and file-descriptor capacity. */
internal object ProxyCapacityPolicy {
    fun detect(mode: PerformanceMode = PerformanceMode.BALANCED): ProxyCapacity = calculate(
        maxHeapBytes = Runtime.getRuntime().maxMemory(),
        openFileLimit = readOpenFileLimit(),
        mode = mode,
    )

    fun calculate(
        maxHeapBytes: Long,
        openFileLimit: Int,
        mode: PerformanceMode = PerformanceMode.BALANCED,
    ): ProxyCapacity {
        val heapTarget = when {
            // Pixel-class devices commonly expose a 256 MiB growth limit even with a 512 MiB
            // final heap. Negotiating/idle sessions do not preallocate relay buffers, so 1024 is
            // still conservative at this tier.
            maxHeapBytes >= 256L * MIB -> 1_024
            maxHeapBytes >= 192L * MIB -> 768
            else -> 512
        }
        // A TCP session owns two sockets. Keep a large reserve for UI, DNS, UDP and the runtime.
        val fdTarget = ((openFileLimit - FD_RESERVE).coerceAtLeast(512) / 2)
        val modeTarget = when (mode) {
            PerformanceMode.TURBO, PerformanceMode.BALANCED -> MAX_SESSIONS
            PerformanceMode.POWER_SAVE -> 512
        }
        val sessions = minOf(heapTarget, fdTarget, modeTarget).coerceAtLeast(MIN_SESSIONS)
        return ProxyCapacity(
            maxSessions = sessions,
            acceptBacklog = sessions,
            maxUdpAssociations = (sessions / 8).coerceIn(64, MAX_UDP_ASSOCIATIONS),
            clientSoftQuota = (sessions / 4).coerceAtLeast(128),
            reservedForOtherClients = (sessions / 8).coerceAtLeast(64),
        )
    }

    private fun readOpenFileLimit(): Int = runCatching {
        val line = File("/proc/self/limits").useLines { lines ->
            lines.first { it.startsWith("Max open files") }
        }
        line.removePrefix("Max open files").trim()
            .split(Regex("\\s+"))
            .first()
            .let { if (it == "unlimited") DEFAULT_FD_LIMIT else it.toInt() }
    }.getOrDefault(DEFAULT_FD_LIMIT)

    private const val MIB = 1024 * 1024
    private const val FD_RESERVE = 512
    private const val DEFAULT_FD_LIMIT = 32_768
    private const val MIN_SESSIONS = 256
    private const val MAX_SESSIONS = 1_024
    private const val MAX_UDP_ASSOCIATIONS = 128
}
