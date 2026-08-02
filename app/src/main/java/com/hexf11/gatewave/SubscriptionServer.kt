package com.hexf11.gatewave

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

internal class SubscriptionServer(
    context: Context,
    private val port: Int,
    private val configProvider: ConfigProvider,
    private val listener: Listener,
) {
    fun interface ConfigProvider {
        fun createConfig(): String
    }

    fun interface Listener {
        fun onError(message: String)
    }

    private val context = context.applicationContext
    private val acceptExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedFactory("subscription-accept"))
    private val clientExecutor: ExecutorService =
        Executors.newFixedThreadPool(4, namedFactory("subscription-http"))

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", port), 16)
        serverSocket = socket
        running = true
        acceptExecutor.execute(::acceptLoop)
        Log.i(TAG, "Subscription listening on 0.0.0.0:$port$CONFIG_PATH")
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = serverSocket?.accept() ?: return
                if (!NetworkUtils.isSameWifiSubnet(context, client.inetAddress)) {
                    Log.w(TAG, "Rejected non-Wi-Fi-subnet HTTP client ${client.inetAddress}")
                    client.close()
                    continue
                }
                clientExecutor.execute { handle(client) }
            } catch (error: IOException) {
                if (running) {
                    Log.e(TAG, "Subscription accept failed", error)
                    listener.onError("订阅监听失败：${error.message}")
                }
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = REQUEST_TIMEOUT_MS
                val header = readHeader(BufferedInputStream(socket.getInputStream()))
                val requestLine = header.split(Regex("\\r?\\n"), limit = 2)[0]
                val parts = requestLine.split(" ")
                if (parts.size != 3) {
                    send(
                        socket.getOutputStream(),
                        400,
                        "Bad Request",
                        "text/plain",
                        false,
                        "Bad Request\n".toByteArray(StandardCharsets.UTF_8),
                    )
                    return
                }

                val method = parts[0]
                var path = parts[1]
                val query = path.indexOf('?')
                if (query >= 0) path = path.substring(0, query)
                val head = method == "HEAD"
                if (!head && method != "GET") {
                    send(
                        socket.getOutputStream(),
                        405,
                        "Method Not Allowed",
                        "text/plain",
                        false,
                        "Method Not Allowed\n".toByteArray(StandardCharsets.UTF_8),
                    )
                    return
                }
                if (path != CONFIG_PATH && path != "/") {
                    send(
                        socket.getOutputStream(),
                        404,
                        "Not Found",
                        "text/plain",
                        head,
                        "Not Found\n".toByteArray(StandardCharsets.UTF_8),
                    )
                    return
                }

                val body = configProvider.createConfig().toByteArray(StandardCharsets.UTF_8)
                send(
                    socket.getOutputStream(),
                    200,
                    "OK",
                    "application/yaml; charset=utf-8",
                    head,
                    body,
                )
                Log.i(
                    TAG,
                    "$method $path bytes=${body.size} client=${socket.inetAddress.hostAddress}",
                )
            }
        } catch (error: Exception) {
            Log.d(TAG, "Subscription request ended: ${error.message}")
        }
    }

    @Synchronized
    fun stop() {
        running = false
        val socket = serverSocket
        serverSocket = null
        runCatching { socket?.close() }
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
    }

    companion object {
        const val CONFIG_PATH = "/clash.yaml"
        private const val TAG = "GatewaveSubscription"
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val REQUEST_TIMEOUT_MS = 5_000

        @Throws(IOException::class)
        private fun readHeader(input: BufferedInputStream): String {
            val output = ByteArrayOutputStream()
            var state = 0
            while (output.size() < MAX_HEADER_BYTES) {
                val value = input.read()
                if (value < 0) throw IOException("Unexpected HTTP EOF")
                output.write(value)
                state = when {
                    (state == 0 || state == 2) && value == '\r'.code -> state + 1
                    (state == 1 || state == 3) && value == '\n'.code -> state + 1
                    value == '\r'.code -> 1
                    else -> 0
                }
                if (state == 4) return output.toString(StandardCharsets.US_ASCII.name())
            }
            throw IOException("HTTP header too large")
        }

        @Throws(IOException::class)
        private fun send(
            output: OutputStream,
            code: Int,
            reason: String,
            contentType: String,
            head: Boolean,
            body: ByteArray,
        ) {
            val headers = buildString {
                append("HTTP/1.1 $code $reason\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Content-Disposition: attachment; filename=\"gatewave.yaml\"\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(headers.toByteArray(StandardCharsets.US_ASCII))
            if (!head) output.write(body)
            output.flush()
        }

        private fun namedFactory(prefix: String): ThreadFactory {
            val index = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${index.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
