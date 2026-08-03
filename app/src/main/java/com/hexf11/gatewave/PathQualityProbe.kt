package com.hexf11.gatewave

import android.net.Network
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Short, bounded real-path measurement used only when the user starts network diagnostics. */
internal object PathQualityProbe {
    fun run(vpnNetwork: Network, socksPort: Int): PathQualitySample {
        val rttMs = medianTcpRtt(vpnNetwork)
        val single = downloadThroughSocks(socksPort, SAMPLE_BYTES)
        val executor = Executors.newFixedThreadPool(PARALLEL_FLOWS) { runnable ->
            Thread(runnable, "diagnostic-path").apply { isDaemon = true }
        }
        val started = System.nanoTime()
        val downloads = try {
            executor.invokeAll(
                List(PARALLEL_FLOWS) { Callable { downloadThroughSocks(socksPort, SAMPLE_BYTES) } },
                PARALLEL_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            ).map { future ->
                require(!future.isCancelled) { "4 流下载超时" }
                future.get()
            }
        } finally {
            executor.shutdownNow()
        }
        val parallelElapsed = System.nanoTime() - started
        return PathQualitySample(
            tcpRttMs = rttMs,
            singleMbps = mbps(single.bytes, single.elapsedNanos),
            parallelMbps = mbps(downloads.sumOf(Download::bytes), parallelElapsed),
        )
    }

    private fun medianTcpRtt(network: Network): Long {
        val samples = LongArray(RTT_SAMPLES) {
            val started = System.nanoTime()
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(RTT_TARGET, 443), CONNECT_TIMEOUT_MS)
            }
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(1)
        }
        samples.sort()
        return samples[samples.size / 2]
    }

    private data class Download(val bytes: Long, val elapsedNanos: Long)

    private fun downloadThroughSocks(socksPort: Int, expectedBytes: Int): Download {
        val started = System.nanoTime()
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOOPBACK, socksPort))
        val url = URL("https://$DOWNLOAD_HOST/__down?bytes=$expectedBytes&t=$started")
        val connection = url.openConnection(proxy) as HttpsURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty(
                "User-Agent",
                "Gatewave-Diagnostics/${BuildConfig.VERSION_NAME}",
            )
            require(connection.responseCode == 200) {
                "路径测速 HTTP ${connection.responseCode}"
            }
            val buffer = ByteArray(32 * 1024)
            var received = 0L
            connection.inputStream.use { input ->
                while (received < expectedBytes) {
                    val count = input.read(
                        buffer,
                        0,
                        minOf(buffer.size.toLong(), expectedBytes - received).toInt(),
                    )
                    if (count < 0) break
                    received += count
                }
            }
            require(received == expectedBytes.toLong()) {
                "路径测速响应不完整 $received/$expectedBytes"
            }
            return Download(received, System.nanoTime() - started)
        } finally {
            connection.disconnect()
        }
    }

    private fun mbps(bytes: Long, nanos: Long): Double =
        bytes.toDouble() * 8.0 * 1_000_000_000.0 / nanos.coerceAtLeast(1L) / 1_000_000.0

    private const val LOOPBACK = "127.0.0.1"
    private const val RTT_TARGET = "1.1.1.1"
    private const val DOWNLOAD_HOST = "speed.cloudflare.com"
    private const val SAMPLE_BYTES = 256 * 1024
    private const val RTT_SAMPLES = 3
    private const val PARALLEL_FLOWS = 4
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val PARALLEL_TIMEOUT_SECONDS = 25L
}
