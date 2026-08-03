package com.hexf11.gatewave

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class DiagnosticLevel {
    PASS,
    WARNING,
    FAIL,
}

data class DiagnosticCheck(
    val id: String,
    val title: String,
    val detail: String,
    val level: DiagnosticLevel,
)

data class DiagnosticReport(
    val generatedAt: String,
    val checks: List<DiagnosticCheck>,
    val passed: Boolean,
    val warningCount: Int,
    val exitIp: String,
    val text: String,
)

data class DiagnosticsUiState(
    val running: Boolean = false,
    val report: DiagnosticReport? = null,
)

internal object NetworkDiagnostics {
    const val REPORT_FILE = "last-diagnostic-report.txt"

    fun failureReport(error: Throwable): DiagnosticReport {
        val generatedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now())
        val detail = error.message ?: error.javaClass.simpleName
        val check = fail("diagnostics", "诊断执行", detail)
        val text = "Gatewave v$APP_VERSION 网络诊断\n时间: $generatedAt\n" +
            "[FAIL] 诊断执行: $detail\n\n结果: 未通过（失败 1 项）\n"
        return DiagnosticReport(generatedAt, listOf(check), false, 0, "--", text)
    }

    fun run(context: Context): DiagnosticReport {
        val app = context.applicationContext
        val settings = ProxySettingsStore.load(app)
        val status = ProxyStatus.load(app)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork
        val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        val vpnReady = activeNetwork != null && capabilities != null &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val vpnInterface = activeNetwork?.let(connectivity::getLinkProperties)?.interfaceName ?: "--"
        val lanIp = NetworkUtils.findLanIpv4(app)
        val checks = mutableListOf<DiagnosticCheck>()

        checks += if (app.applicationInfo.targetSdkVersion == 36) {
            pass("sdk", "Android 版本", "targetSdk 36 · 设备 API ${Build.VERSION.SDK_INT}")
        } else {
            fail(
                "sdk",
                "Android 版本",
                "targetSdk ${app.applicationInfo.targetSdkVersion}，预期 36",
            )
        }

        checks += pass(
            "lan_permission",
            "局域网权限模型",
            "targetSdk 36 使用 INTERNET 隐式局域网授权；API 37 权限迁移已记录",
        )

        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        checks += if (notificationsGranted) {
            pass("notifications", "通知权限", "前台服务通知可显示")
        } else {
            warning("notifications", "通知权限", "服务可运行，但通知抽屉不会显示状态")
        }

        checks += if (status.running) {
            pass("service", "代理服务", "正在监听 SOCKS5 ${settings.socksPort}")
        } else {
            fail("service", "代理服务", "服务未启动")
        }

        checks += if (lanIp != "0.0.0.0") {
            pass("lan", "局域网地址", "$lanIp:${settings.socksPort}")
        } else {
            fail("lan", "局域网地址", "未检测到 Wi-Fi IPv4 地址")
        }

        checks += if (vpnReady) {
            pass("vpn", "系统 VPN", "$vpnInterface / network $activeNetwork")
        } else {
            fail("vpn", "系统 VPN", "当前默认网络不是可用 VPN")
        }

        checks += checked("socks_tcp", "SOCKS5 TCP") {
            require(status.running) { "代理服务未启动" }
            require(vpnReady) { "系统 VPN 未就绪" }
            probeSocksTcp(settings.socksPort)
        }

        checks += if (!settings.udpEnabled) {
            warning("socks_udp", "SOCKS5 UDP", "设置中已关闭，未执行 UDP ASSOCIATE")
        } else {
            checked("socks_udp", "SOCKS5 UDP") {
                require(status.running) { "代理服务未启动" }
                require(vpnReady) { "系统 VPN 未就绪" }
                probeSocksUdp(settings.socksPort)
            }
        }

        checks += if (!status.subscriptionEnabled) {
            warning("subscription", "配置订阅", "订阅开关已关闭，未执行 HTTP/YAML 检查")
        } else {
            checked("subscription", "配置订阅") {
                probeSubscription(settings)
            }
        }

        var exitIp = "--"
        checks += checked("exit_ip", "VPN 出口 IP") {
            require(vpnReady) { "系统 VPN 未就绪" }
            exitIp = fetchExitIp(checkNotNull(activeNetwork))
            exitIp
        }

        val generatedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now())
        val passed = checks.none { it.level == DiagnosticLevel.FAIL }
        val warningCount = checks.count { it.level == DiagnosticLevel.WARNING }
        val text = renderReport(
            generatedAt = generatedAt,
            settings = settings,
            lanIp = lanIp,
            vpnInterface = vpnInterface,
            checks = checks,
            passed = passed,
            warningCount = warningCount,
            exitIp = exitIp,
        )
        runCatching {
            app.openFileOutput(REPORT_FILE, Context.MODE_PRIVATE).bufferedWriter()
                .use { it.write(text) }
        }
        return DiagnosticReport(generatedAt, checks, passed, warningCount, exitIp, text)
    }

    private fun checked(id: String, title: String, block: () -> String): DiagnosticCheck =
        try {
            pass(id, title, block())
        } catch (error: Exception) {
            fail(id, title, error.message ?: error.javaClass.simpleName)
        }

    private fun pass(id: String, title: String, detail: String) =
        DiagnosticCheck(id, title, detail, DiagnosticLevel.PASS)

    private fun warning(id: String, title: String, detail: String) =
        DiagnosticCheck(id, title, detail, DiagnosticLevel.WARNING)

    private fun fail(id: String, title: String, detail: String) =
        DiagnosticCheck(id, title, detail, DiagnosticLevel.FAIL)

    private fun probeSocksTcp(port: Int): String {
        Socket().use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.connect(InetSocketAddress(IPV4_LOOPBACK, port), SOCKET_TIMEOUT_MS)
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(socket.getOutputStream())
            negotiate(output, input)
            val host = "api.ipify.org".toByteArray(StandardCharsets.US_ASCII)
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()))
            output.write(host)
            output.writeShort(443)
            output.flush()
            val reply = readSocksReply(input)
            require(reply == 0) { "CONNECT 返回 SOCKS 错误 $reply" }
        }
        return "CONNECT api.ipify.org:443 成功"
    }

    private fun probeSocksUdp(port: Int): String {
        Socket().use { control ->
            control.soTimeout = SOCKET_TIMEOUT_MS
            control.connect(InetSocketAddress(IPV4_LOOPBACK, port), SOCKET_TIMEOUT_MS)
            val input = DataInputStream(BufferedInputStream(control.getInputStream()))
            val output = DataOutputStream(control.getOutputStream())
            negotiate(output, input)
            output.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            val reply = readSocksReplyWithEndpoint(input)
            require(reply.code == 0) { "UDP ASSOCIATE 返回 SOCKS 错误 ${reply.code}" }
            val relayAddress = if (reply.address.isAnyLocalAddress) IPV4_LOOPBACK else reply.address

            DatagramSocket(InetSocketAddress(IPV4_LOOPBACK, 0)).use { udp ->
                udp.soTimeout = UDP_TIMEOUT_MS
                val query = dnsQuery()
                val request = ByteArrayOutputStream().also { bytes ->
                    DataOutputStream(bytes).use { data ->
                        data.write(byteArrayOf(0, 0, 0, 1))
                        data.write(InetAddress.getByName(DNS_SERVER).address)
                        data.writeShort(53)
                        data.write(query)
                    }
                }.toByteArray()
                udp.send(
                    DatagramPacket(
                        request,
                        request.size,
                        InetSocketAddress(relayAddress, reply.port),
                    ),
                )
                val buffer = ByteArray(4096)
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                val payloadOffset = socksUdpPayloadOffset(buffer, packet.length)
                require(packet.length - payloadOffset >= 12) { "DNS 响应过短" }
                val id = unsignedShort(buffer, payloadOffset)
                val flags = unsignedShort(buffer, payloadOffset + 2)
                val answers = unsignedShort(buffer, payloadOffset + 6)
                require(id == DNS_QUERY_ID) { "DNS 事务 ID 不匹配" }
                require(flags and 0x000F == 0) { "DNS RCODE=${flags and 0x000F}" }
                require(answers > 0) { "DNS 无应答记录" }
                return "UDP ASSOCIATE + DNS example.com 成功（$answers 条应答）"
            }
        }
    }

    private fun probeSubscription(settings: ProxySettings): String {
        val response = Socket().use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.connect(
                InetSocketAddress(IPV4_LOOPBACK, settings.subscriptionPort),
                SOCKET_TIMEOUT_MS,
            )
            socket.getOutputStream().write(
                (
                    "GET ${SubscriptionServer.CONFIG_PATH} HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\nConnection: close\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
            )
            socket.getOutputStream().flush()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val input = socket.getInputStream()
            while (output.size() <= MAX_HTTP_BYTES) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            require(output.size() <= MAX_HTTP_BYTES) { "订阅响应过大" }
            output.toString(StandardCharsets.UTF_8.name())
        }
        require(response.startsWith("HTTP/1.1 200 OK")) { "订阅 HTTP 状态不是 200" }
        val body = response.substringAfter("\r\n\r\n", missingDelimiterValue = "")
        require(body.contains("type: socks5")) { "YAML 缺少 socks5 节点" }
        require(body.contains("port: ${settings.socksPort}")) { "YAML SOCKS5 端口不匹配" }
        require(body.contains("udp: ${settings.udpEnabled}")) { "YAML UDP 字段不匹配" }
        require(body.contains("MATCH,漏网之鱼")) { "YAML 缺少最终规则" }
        return "HTTP 200 · YAML 字段一致 · ${body.toByteArray().size} bytes"
    }

    private fun fetchExitIp(network: Network): String {
        val connection = network.openConnection(URL(EXIT_IP_URL)) as HttpURLConnection
        return try {
            connection.connectTimeout = SOCKET_TIMEOUT_MS
            connection.readTimeout = SOCKET_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Gatewave-Diagnostics/$APP_VERSION")
            val code = connection.responseCode
            require(code == 200) { "HTTP $code" }
            val value = connection.inputStream.bufferedReader().use { it.readText().trim() }
            require(value.isNotEmpty() && value.length <= 64) { "出口 IP 响应异常" }
            value
        } finally {
            connection.disconnect()
        }
    }

    private fun negotiate(output: DataOutputStream, input: DataInputStream) {
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        require(input.readUnsignedByte() == 0x05 && input.readUnsignedByte() == 0x00) {
            "SOCKS5 NO_AUTH 协商失败"
        }
    }

    private data class SocksReply(val code: Int, val address: InetAddress, val port: Int)

    private fun readSocksReply(input: DataInputStream): Int = readSocksReplyWithEndpoint(input).code

    private fun readSocksReplyWithEndpoint(input: DataInputStream): SocksReply {
        require(input.readUnsignedByte() == 0x05) { "SOCKS 回复版本错误" }
        val code = input.readUnsignedByte()
        input.readUnsignedByte()
        val address = when (input.readUnsignedByte()) {
            0x01 -> ByteArray(4).also(input::readFully).let(InetAddress::getByAddress)
            0x03 -> {
                val length = input.readUnsignedByte()
                val domain = ByteArray(length).also(input::readFully)
                    .toString(StandardCharsets.US_ASCII)
                InetAddress.getByName(domain)
            }
            0x04 -> ByteArray(16).also(input::readFully).let(InetAddress::getByAddress)
            else -> error("SOCKS 回复地址类型错误")
        }
        return SocksReply(code, address, input.readUnsignedShort())
    }

    private fun dnsQuery(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeShort(DNS_QUERY_ID)
            data.writeShort(0x0100)
            data.writeShort(1)
            data.writeShort(0)
            data.writeShort(0)
            data.writeShort(0)
            "example.com".split('.').forEach { label ->
                val raw = label.toByteArray(StandardCharsets.US_ASCII)
                data.writeByte(raw.size)
                data.write(raw)
            }
            data.writeByte(0)
            data.writeShort(1)
            data.writeShort(1)
        }
    }.toByteArray()

    private fun socksUdpPayloadOffset(data: ByteArray, length: Int): Int {
        require(length >= 7 && data[0].toInt() == 0 && data[1].toInt() == 0) {
            "SOCKS UDP 响应头错误"
        }
        var offset = 4
        offset += when (data[3].toInt() and 0xFF) {
            0x01 -> 4
            0x03 -> 1 + (data[offset].toInt() and 0xFF)
            0x04 -> 16
            else -> error("SOCKS UDP 地址类型错误")
        }
        offset += 2
        require(offset <= length) { "SOCKS UDP 响应被截断" }
        return offset
    }

    private fun unsignedShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun renderReport(
        generatedAt: String,
        settings: ProxySettings,
        lanIp: String,
        vpnInterface: String,
        checks: List<DiagnosticCheck>,
        passed: Boolean,
        warningCount: Int,
        exitIp: String,
    ): String = buildString {
        appendLine("Gatewave v$APP_VERSION 网络诊断")
        appendLine("时间: $generatedAt")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统: Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("目标版本: API 36")
        appendLine("LAN: $lanIp:${settings.socksPort}")
        appendLine("订阅端口: ${settings.subscriptionPort}")
        appendLine("VPN 接口: $vpnInterface")
        appendLine("出口 IP: $exitIp")
        appendLine()
        checks.forEach { check ->
            appendLine("[${check.level.name}] ${check.title}: ${check.detail}")
        }
        appendLine()
        appendLine(
            if (passed) "结果: 通过（提醒 $warningCount 项）"
            else "结果: 未通过（失败 ${checks.count { it.level == DiagnosticLevel.FAIL }} 项）",
        )
    }

    private val IPV4_LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    private const val SOCKET_TIMEOUT_MS = 7_000
    private const val UDP_TIMEOUT_MS = 8_000
    private const val MAX_HTTP_BYTES = 128 * 1024
    private const val APP_VERSION = "0.1.1"
    private const val DNS_QUERY_ID = 0x5058
    private const val DNS_SERVER = "8.8.4.4"
    private const val EXIT_IP_URL = "https://api.ipify.org"
}
