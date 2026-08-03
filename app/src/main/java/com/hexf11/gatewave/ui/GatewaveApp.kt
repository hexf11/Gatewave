package com.hexf11.gatewave.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.hexf11.gatewave.DiagnosticCheck
import com.hexf11.gatewave.DiagnosticLevel
import com.hexf11.gatewave.DiagnosticsUiState
import com.hexf11.gatewave.ProxyUiState
import com.hexf11.gatewave.PerformanceMode
import com.hexf11.gatewave.ProxySettingsStore
import com.hexf11.gatewave.SettingsUpdateResult
import com.hexf11.gatewave.ThemeMode
import kotlinx.coroutines.launch

private enum class AppTab(val label: String, val icon: ImageVector) {
    Overview("概览", Icons.Outlined.Home),
    Logs("日志", Icons.AutoMirrored.Outlined.ReceiptLong),
    Settings("设置", Icons.Outlined.Settings),
}

@Composable
fun GatewaveApp(
    state: ProxyUiState,
    onToggleProxy: () -> Unit,
    onOpenVpn: () -> Unit,
    onToggleSubscription: () -> Unit,
    onCopySubscription: () -> Boolean,
    onShareSubscription: () -> Boolean,
    onSavePorts: (String, String) -> SettingsUpdateResult,
    onSetUdpEnabled: (Boolean) -> SettingsUpdateResult,
    onSetAutoResumeVpn: (Boolean) -> SettingsUpdateResult,
    onSetStartOnAppLaunch: (Boolean) -> SettingsUpdateResult,
    onSetStartOnBoot: (Boolean) -> SettingsUpdateResult,
    onSetThemeMode: (ThemeMode) -> SettingsUpdateResult,
    onSetPerformanceMode: (PerformanceMode) -> SettingsUpdateResult,
    onResetSettings: () -> SettingsUpdateResult,
    onRunDiagnostics: () -> Unit,
    onCopyDiagnostics: () -> Boolean,
    onShareDiagnostics: () -> Boolean,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    if (showQr && state.subscriptionEnabled && state.subscriptionUrl != "--") {
        SubscriptionQrDialog(url = state.subscriptionUrl, onDismiss = { showQr = false })
    }

    if (showDiagnostics) {
        DiagnosticsDialog(
            state = state.diagnostics,
            onRun = onRunDiagnostics,
            onCopy = {
                notify(if (onCopyDiagnostics()) "诊断报告已复制" else "诊断报告尚未生成")
            },
            onShare = {
                if (!onShareDiagnostics()) notify("诊断报告尚未生成")
            },
            onDismiss = { showDiagnostics = false },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AppBottomBar(
                selected = selectedTab,
                onSelected = { selectedTab = it },
            )
        },
    ) { padding ->
        when (selectedTab) {
            0 -> OverviewScreen(
                state = state,
                contentPadding = padding,
                onToggleProxy = onToggleProxy,
                onOpenVpn = onOpenVpn,
                onToggleSubscription = onToggleSubscription,
                onCopySubscription = {
                    notify(if (onCopySubscription()) "订阅链接已复制" else "请先开启配置订阅")
                },
                onShareSubscription = {
                    if (!onShareSubscription()) notify("请先开启配置订阅")
                },
                onShowQr = {
                    if (state.subscriptionEnabled && state.subscriptionUrl != "--") {
                        showQr = true
                    } else {
                        notify("请先开启配置订阅")
                    }
                },
            )
            1 -> LogsScreen(state = state, contentPadding = padding)
            else -> SettingsScreen(
                state = state,
                contentPadding = padding,
                onOpenVpn = onOpenVpn,
                onToggleSubscription = onToggleSubscription,
                onSavePorts = { socks, subscription ->
                    onSavePorts(socks, subscription).also { notify(it.message) }.success
                },
                onSetUdpEnabled = { notify(onSetUdpEnabled(it).message) },
                onSetAutoResumeVpn = { notify(onSetAutoResumeVpn(it).message) },
                onSetStartOnAppLaunch = { notify(onSetStartOnAppLaunch(it).message) },
                onSetStartOnBoot = { notify(onSetStartOnBoot(it).message) },
                onSetThemeMode = { notify(onSetThemeMode(it).message) },
                onSetPerformanceMode = { notify(onSetPerformanceMode(it).message) },
                onResetSettings = { notify(onResetSettings().message) },
                onOpenDiagnostics = {
                    showDiagnostics = true
                    onRunDiagnostics()
                },
            )
        }
    }
}

@Composable
private fun AppBottomBar(selected: Int, onSelected: (Int) -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            AppTab.entries.forEachIndexed { index, tab ->
                NavigationBarItem(
                    selected = selected == index,
                    onClick = { onSelected(index) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

@Composable
private fun OverviewScreen(
    state: ProxyUiState,
    contentPadding: PaddingValues,
    onToggleProxy: () -> Unit,
    onOpenVpn: () -> Unit,
    onToggleSubscription: () -> Unit,
    onCopySubscription: () -> Unit,
    onShareSubscription: () -> Unit,
    onShowQr: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        contentPadding = PaddingValues(
            start = 24.dp,
            top = 18.dp,
            end = 24.dp,
            bottom = 28.dp,
        ),
    ) {
        item {
            AppHeader()
            Spacer(Modifier.height(42.dp))
            Text(
                text = "网络共享",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            HealthLabel(state)
            Spacer(Modifier.height(15.dp))
            Text(
                text = when {
                    !state.running -> "准备共享 Google VPN"
                    state.vpnReady -> "Google VPN 已共享"
                    else -> "等待 Google VPN"
                },
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(16.dp))
            EndpointRow(state.endpoint)
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (state.vpnReady) {
                    "局域网设备的代理流量将通过当前系统 VPN"
                } else {
                    "VPN 接入前转发保持暂停，避免普通 Wi-Fi 出口泄漏"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(18.dp))
            StatusStrip(state)
            Spacer(Modifier.height(38.dp))
            SectionTitle("实时活动")
            Spacer(Modifier.height(22.dp))
            StatsRow(state)
            Spacer(Modifier.height(28.dp))
            MainActions(state.running, onToggleProxy, onOpenVpn)
            Spacer(Modifier.height(42.dp))
            SectionTitle("配置订阅")
            Spacer(Modifier.height(16.dp))
            SubscriptionCard(
                state = state,
                onToggle = onToggleSubscription,
                onCopy = onCopySubscription,
                onShare = onShareSubscription,
                onQr = onShowQr,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "同一 Wi-Fi 下可从 URL 安装配置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Text("Gatewave", style = MaterialTheme.typography.headlineLarge)
}

@Composable
private fun HealthLabel(state: ProxyUiState) {
    val healthy = state.running && state.vpnReady
    val color = if (healthy) HealthyGreen else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(11.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = when {
                healthy -> "运行正常"
                state.running -> "等待 VPN"
                else -> "服务已停止"
            },
            style = MaterialTheme.typography.titleLarge,
            color = color,
        )
    }
}

@Composable
private fun EndpointRow(endpoint: String) {
    Text(
        text = endpoint,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.5).sp,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StatusStrip(state: ProxyUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusItem(
            icon = Icons.Outlined.VpnKey,
            label = "VPN",
            value = if (state.vpnReady) "已连接" else "未连接",
            healthy = state.vpnReady,
            modifier = Modifier.weight(1f),
        )
        VerticalDivider(Modifier.height(36.dp), color = MaterialTheme.colorScheme.outlineVariant)
        StatusItem(
            icon = Icons.Outlined.Lan,
            label = "SOCKS5",
            value = if (state.running) state.activeConnections.toString() else "关闭",
            healthy = state.running,
            modifier = Modifier.weight(1f),
        )
        VerticalDivider(Modifier.height(36.dp), color = MaterialTheme.colorScheme.outlineVariant)
        StatusItem(
            icon = Icons.Outlined.BookmarkBorder,
            label = "订阅",
            value = if (state.subscriptionEnabled) "已开启" else "已关闭",
            healthy = state.subscriptionEnabled,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatusItem(
    icon: ImageVector,
    label: String,
    value: String,
    healthy: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (healthy) HealthyGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(7.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(value: String) {
    Text(value, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun StatsRow(state: ProxyUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatColumn(
            value = state.activeConnections.toString(),
            label = "活跃连接",
            detail = "TCP ${state.activeTcp} · UDP ${state.activeUdp}",
            modifier = Modifier.weight(1f),
        )
        VerticalDivider(Modifier.height(118.dp), color = MaterialTheme.colorScheme.outlineVariant)
        StatColumn(
            value = formatBytes(state.totalBytes),
            label = "累计流量",
            detail = "↑${formatBytes(state.uploadBytes)} · ↓${formatBytes(state.downloadBytes)}",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatColumn(value: String, label: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Spacer(Modifier.height(5.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(7.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun MainActions(running: Boolean, onToggleProxy: () -> Unit, onOpenVpn: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onToggleProxy,
            modifier = Modifier
                .weight(0.95f)
                .height(58.dp)
                .semantics { contentDescription = "toggle_proxy" },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (running) Icons.Outlined.StopCircle else Icons.Outlined.PlayCircle,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (running) "停止代理" else "启动代理")
            }
        }
        TextButton(
            onClick = onOpenVpn,
            modifier = Modifier
                .weight(1.25f)
                .height(58.dp)
                .semantics { contentDescription = "open_google_vpn" },
        ) {
            Icon(Icons.Outlined.Security, contentDescription = null)
            Spacer(Modifier.width(7.dp))
            Text("打开 Google VPN", maxLines = 1, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SubscriptionCard(
    state: ProxyUiState,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onQr: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Clash 配置", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (state.subscriptionEnabled) "可供局域网客户端更新" else "当前未共享订阅链接",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.subscriptionEnabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.semantics { contentDescription = "toggle_subscription" },
                )
            }
            Spacer(Modifier.height(17.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 7.dp, end = 2.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (state.subscriptionEnabled) state.subscriptionUrl else "开启后生成订阅 URL",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = if (state.subscriptionEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = onCopy,
                        enabled = state.subscriptionEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = "copy_subscription_url"
                        },
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SubscriptionAction(
                    Icons.Outlined.ContentCopy,
                    "复制",
                    "copy_subscription_action",
                    onCopy,
                )
                SubscriptionAction(Icons.Outlined.Share, "分享", "share_subscription", onShare)
                SubscriptionAction(Icons.Outlined.QrCode2, "二维码", "show_subscription_qr", onQr)
            }
        }
    }
}

@Composable
private fun SubscriptionAction(
    icon: ImageVector,
    label: String,
    actionDescription: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = actionDescription },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(7.dp))
        Text(label)
    }
}

@Composable
private fun LogsScreen(state: ProxyUiState, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        contentPadding = PaddingValues(
            start = 24.dp,
            top = 22.dp,
            end = 24.dp,
            bottom = 28.dp,
        ),
    ) {
        item {
            Text("活动日志", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "当前会话的服务状态和累计计数",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(34.dp))
            SessionEvent(
                icon = Icons.Outlined.CloudDone,
                title = "代理服务",
                detail = state.message,
                active = state.running,
            )
            SessionEvent(
                icon = Icons.Outlined.VpnKey,
                title = "系统 VPN",
                detail = if (state.vpnReady) state.vpnInterface else "尚未接入",
                active = state.vpnReady,
            )
            SessionEvent(
                icon = Icons.Outlined.Link,
                title = "配置订阅",
                detail = if (state.subscriptionEnabled) state.subscriptionUrl else "已关闭",
                active = state.subscriptionEnabled,
            )
            Spacer(Modifier.height(28.dp))
            SectionTitle("会话计数")
            Spacer(Modifier.height(14.dp))
            CounterRow("累计连接", state.totalConnections.toString())
            CounterRow("失败连接", state.failedConnections.toString())
            CounterRow("拒绝连接", state.rejectedConnections.toString())
            CounterRow("峰值连接", state.peakConnections.toString())
            CounterRow("活跃客户端", state.activeClients.toString())
            CounterRow("单客户端最高", state.largestClientConnections.toString())
            CounterRow("动态容量", "${state.maxSessions} TCP/总会话 · ${state.maxUdpAssociations} UDP")
            CounterRow("Selector", "TCP ${state.tcpSelectorLanes} · UDP ${state.udpSelectorLanes}")
            CounterRow("建连延迟", "P50 ${state.connectP50Ms}ms · P95 ${state.connectP95Ms}ms")
            CounterRow(
                "DNS 缓存",
                "${state.dnsCacheEntries} 条 · 命中 ${state.dnsCacheHits} · 查询 ${state.dnsCacheMisses} · 合并 ${state.dnsCoalesced}",
            )
            CounterRow("TCP 缓冲池", formatBytes(state.tcpPooledBufferBytes.toLong()))
            CounterRow("TCP 上游窗口", formatBytes(state.tcpReceiveBufferBytes.toLong()))
            CounterRow("TCP 半关闭排空", state.tcpHalfClosedConnections.toString())
            CounterRow("实时上传", "${formatBytes(state.uploadBytesPerSecond)}/s")
            CounterRow("实时下载", "${formatBytes(state.downloadBytesPerSecond)}/s")
            CounterRow("UDP 丢弃", state.udpDropped.toString())
            CounterRow(
                "UDP 快路径",
                "命中 ${state.udpFastPathHits} · 解析 ${state.udpResolutionMisses} · 队列峰值 ${state.udpMaxQueueDepth}",
            )
            CounterRow("公平回收", state.fairnessReclaims.toString())
            CounterRow("上传流量", formatBytes(state.uploadBytes))
            CounterRow("下载流量", formatBytes(state.downloadBytes))
        }
    }
}

@Composable
private fun SessionEvent(icon: ImageVector, title: String, detail: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = CircleShape,
            color = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(10.dp).size(21.dp),
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(9.dp)
                .background(if (active) HealthyGreen else MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun CounterRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsScreen(
    state: ProxyUiState,
    contentPadding: PaddingValues,
    onOpenVpn: () -> Unit,
    onToggleSubscription: () -> Unit,
    onSavePorts: (String, String) -> Boolean,
    onSetUdpEnabled: (Boolean) -> Unit,
    onSetAutoResumeVpn: (Boolean) -> Unit,
    onSetStartOnAppLaunch: (Boolean) -> Unit,
    onSetStartOnBoot: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetPerformanceMode: (PerformanceMode) -> Unit,
    onResetSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var showPortDialog by rememberSaveable { mutableStateOf(false) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    if (showPortDialog) {
        PortSettingsDialog(
            socksPort = state.settings.socksPort,
            subscriptionPort = state.settings.subscriptionPort,
            onSave = { socks, subscription ->
                onSavePorts(socks, subscription).also { success ->
                    if (success) showPortDialog = false
                }
            },
            onDismiss = { showPortDialog = false },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认设置？") },
            text = {
                Text("端口、UDP、性能模式、自动启动、VPN 恢复和主题将恢复默认值，配置订阅也会关闭。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onResetSettings()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "confirm_reset_settings"
                    },
                ) { Text("恢复默认") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        contentPadding = PaddingValues(
            start = 24.dp,
            top = 22.dp,
            end = 24.dp,
            bottom = 28.dp,
        ),
    ) {
        item {
            Text("设置", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "代理、启动行为与外观",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(34.dp))
            SectionTitle("代理服务")
            Spacer(Modifier.height(10.dp))
            SettingsItem(
                icon = Icons.Outlined.Lan,
                title = "服务端口",
                value = "SOCKS5 ${state.settings.socksPort} · 订阅 ${state.settings.subscriptionPort}",
                actionLabel = "修改",
                actionDescription = "edit_service_ports",
                onClick = { showPortDialog = true },
            )
            SettingsToggleItem(
                title = "UDP 转发",
                detail = "同步写入 Clash 节点的 udp 字段",
                checked = state.settings.udpEnabled,
                description = "settings_udp_enabled",
                onCheckedChange = onSetUdpEnabled,
            )
            SettingsToggleItem(
                title = "VPN 恢复后自动继续",
                detail = "关闭后需要手动重启代理才能继续转发",
                checked = state.settings.autoResumeVpn,
                description = "settings_auto_resume_vpn",
                onCheckedChange = onSetAutoResumeVpn,
            )
            Spacer(Modifier.height(24.dp))
            SectionTitle("性能模式")
            Spacer(Modifier.height(12.dp))
            PerformanceModeSelector(
                selected = state.settings.performanceMode,
                onSelected = onSetPerformanceMode,
            )
            Spacer(Modifier.height(30.dp))
            SectionTitle("启动行为")
            Spacer(Modifier.height(10.dp))
            SettingsToggleItem(
                title = "打开 App 时启动",
                detail = "进入 Gatewave 时自动启动服务",
                checked = state.settings.startOnAppLaunch,
                description = "settings_start_on_app_launch",
                onCheckedChange = onSetStartOnAppLaunch,
            )
            SettingsToggleItem(
                title = "手机开机后启动",
                detail = "设备启动完成后恢复代理服务",
                checked = state.settings.startOnBoot,
                description = "settings_start_on_boot",
                onCheckedChange = onSetStartOnBoot,
            )
            Spacer(Modifier.height(30.dp))
            SectionTitle("配置订阅")
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(0.dp),
                modifier = Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    MaterialTheme.shapes.medium,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("局域网订阅链接", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "HTTP 端口 ${state.settings.subscriptionPort} · 同 Wi-Fi 子网",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.subscriptionEnabled,
                        onCheckedChange = { onToggleSubscription() },
                        modifier = Modifier.semantics {
                            contentDescription = "settings_toggle_subscription"
                        },
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
            SectionTitle("外观")
            Spacer(Modifier.height(12.dp))
            ThemeModeSelector(
                selected = state.settings.themeMode,
                onSelected = onSetThemeMode,
            )
            Spacer(Modifier.height(30.dp))
            SectionTitle("系统与网络")
            Spacer(Modifier.height(10.dp))
            SettingsItem(
                Icons.Outlined.Security,
                "VPN 接口",
                state.vpnInterface,
                actionLabel = "打开",
                actionDescription = "settings_open_google_vpn",
                onClick = onOpenVpn,
            )
            SettingsItem(Icons.Outlined.Wifi, "访问范围", "仅当前 Wi-Fi 私有子网")
            SettingsItem(
                icon = Icons.Outlined.NetworkCheck,
                title = "网络自检",
                value = when {
                    state.diagnostics.running -> "正在检查 VPN、TCP、UDP、路径质量与订阅"
                    state.diagnostics.report == null -> "检查完整代理链路并生成诊断报告"
                    state.diagnostics.report.passed -> {
                        "上次检查通过 · 提醒 ${state.diagnostics.report.warningCount} 项"
                    }
                    else -> "上次检查发现异常"
                },
                actionLabel = if (state.diagnostics.running) "查看" else "检查",
                actionDescription = "open_network_diagnostics",
                onClick = onOpenDiagnostics,
            )
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "代理仅在系统 VPN 可用时转发；VPN 断开后会关闭现有会话，避免流量回落到普通网络。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(28.dp))
            OutlinedButton(
                onClick = { showResetDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .semantics { contentDescription = "reset_settings" },
            ) {
                Text("恢复默认设置")
            }
        }
    }
}

@Composable
private fun DiagnosticsDialog(
    state: DiagnosticsUiState,
    onRun: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val report = state.report
    var copyConfirmed by remember(report?.generatedAt) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NetworkCheck, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("网络自检")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state.running) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                            .semantics { contentDescription = "diagnostics_running" },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("正在检查完整代理链路", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "将测量 VPN RTT、单流与 4 流吞吐，通常需要 10–30 秒",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (report == null) {
                    Text(
                        "准备检查 Android 兼容性、LAN、系统 VPN、SOCKS5 TCP/UDP、路径质量、订阅配置和出口 IP。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Surface(
                        color = if (report.passed) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = if (report.passed) {
                                    "diagnostics_result_pass"
                                } else {
                                    "diagnostics_result_fail"
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (report.passed) Icons.Outlined.CheckCircle
                                else Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = if (report.passed) HealthyGreen
                                else MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    if (report.passed) "代理链路检查通过" else "检查发现需要处理的问题",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "${report.checks.size} 项检查 · ${report.warningCount} 项提醒",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    report.checks.forEach { check ->
                        DiagnosticCheckRow(check)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "出口 IP · ${report.exitIp}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (copyConfirmed) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "报告已复制到剪贴板",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HealthyGreen,
                            modifier = Modifier.semantics {
                                contentDescription = "diagnostic_copy_confirmed"
                            },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onRun,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "rerun_network_diagnostics" },
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("重新检测")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        onCopy()
                        copyConfirmed = true
                    },
                    enabled = report != null && !state.running,
                    modifier = Modifier.semantics {
                        contentDescription = "copy_diagnostic_report"
                    },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("复制")
                }
                TextButton(
                    onClick = onShare,
                    enabled = report != null && !state.running,
                    modifier = Modifier.semantics {
                        contentDescription = "share_diagnostic_report"
                    },
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("分享")
                }
            }
        },
    )
}

@Composable
private fun DiagnosticCheckRow(check: DiagnosticCheck) {
    val icon = when (check.level) {
        DiagnosticLevel.PASS -> Icons.Outlined.CheckCircle
        DiagnosticLevel.WARNING -> Icons.Outlined.WarningAmber
        DiagnosticLevel.FAIL -> Icons.Outlined.ErrorOutline
    }
    val tint = when (check.level) {
        DiagnosticLevel.PASS -> HealthyGreen
        DiagnosticLevel.WARNING -> Color(0xFFE08A00)
        DiagnosticLevel.FAIL -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp)
            .semantics {
                contentDescription = "diagnostic_${check.id}_${check.level.name.lowercase()}"
            },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(check.title, style = MaterialTheme.typography.titleMedium)
            Text(
                check.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    value: String,
    actionLabel: String = "",
    actionDescription: String = "",
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onClick != null) {
            TextButton(
                onClick = onClick,
                modifier = Modifier.semantics { contentDescription = actionDescription },
            ) { Text(actionLabel) }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsToggleItem(
    title: String,
    detail: String,
    checked: Boolean,
    description: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ThemeModeSelector(selected: ThemeMode, onSelected: (ThemeMode) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
    ) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            val label = when (mode) {
                ThemeMode.SYSTEM -> "跟随系统"
                ThemeMode.LIGHT -> "浅色"
                ThemeMode.DARK -> "深色"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelected(mode) }
                    .padding(horizontal = 16.dp, vertical = 11.dp)
                    .semantics { contentDescription = "theme_${mode.name.lowercase()}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == mode, onClick = { onSelected(mode) })
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
            if (index != ThemeMode.entries.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun PerformanceModeSelector(
    selected: PerformanceMode,
    onSelected: (PerformanceMode) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
    ) {
        PerformanceMode.entries.forEachIndexed { index, mode ->
            val (label, detail) = when (mode) {
                PerformanceMode.BALANCED -> "均衡" to "动态 Selector 与 1024 会话容量"
                PerformanceMode.TURBO -> "极速" to "Wi-Fi 低延迟锁与积极缓冲策略"
                PerformanceMode.POWER_SAVE -> "省电" to "单 lane 与 512 会话容量"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelected(mode) }
                    .padding(horizontal = 16.dp, vertical = 11.dp)
                    .semantics { contentDescription = "performance_${mode.name.lowercase()}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == mode, onClick = { onSelected(mode) })
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (index != PerformanceMode.entries.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun PortSettingsDialog(
    socksPort: Int,
    subscriptionPort: Int,
    onSave: (String, String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var socksText by remember(socksPort) { mutableStateOf(socksPort.toString()) }
    var subscriptionText by remember(subscriptionPort) {
        mutableStateOf(subscriptionPort.toString())
    }
    var validationMessage by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务端口") },
        text = {
            Column {
                Text(
                    "保存后会安全重启监听服务；端口占用时自动恢复原设置。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = socksText,
                    onValueChange = { socksText = it.filter(Char::isDigit).take(5) },
                    label = { Text("SOCKS5 端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "socks_port_input" },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = subscriptionText,
                    onValueChange = { subscriptionText = it.filter(Char::isDigit).take(5) },
                    label = { Text("订阅 HTTP 端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "subscription_port_input" },
                )
                if (validationMessage.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        validationMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics {
                            contentDescription = "port_validation_error"
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val socks = socksText.toIntOrNull()
                    val subscription = subscriptionText.toIntOrNull()
                    validationMessage = when {
                        socks == null -> "请输入有效的 SOCKS5 端口"
                        subscription == null -> "请输入有效的订阅端口"
                        else -> ProxySettingsStore.validatePorts(socks, subscription).orEmpty()
                    }
                    if (validationMessage.isEmpty() && !onSave(socksText, subscriptionText)) {
                        validationMessage = "端口设置保存失败"
                    }
                },
                modifier = Modifier.semantics { contentDescription = "save_service_ports" },
            ) { Text("保存并应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun SubscriptionQrDialog(url: String, onDismiss: () -> Unit) {
    val bitmap = remember(url) { createQrBitmap(url, 720) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫描订阅二维码") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Clash 订阅二维码",
                        modifier = Modifier.padding(12.dp).size(260.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    url,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "请使用同一 Wi-Fi 下的客户端扫描",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun createQrBitmap(value: String, size: Int): Bitmap {
    val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2,
    )
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    val black = android.graphics.Color.rgb(18, 20, 22)
    val white = android.graphics.Color.WHITE
    for (y in 0 until size) {
        val offset = y * size
        for (x in 0 until size) pixels[offset + x] = if (matrix[x, y]) black else white
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
