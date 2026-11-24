package com.duophone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duophone.app.data.CallLogRecord
import com.duophone.app.data.SmsRecord
import com.duophone.app.data.SmsThreadSummary
import com.duophone.app.diagnostics.HostHardwareSnapshot
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.diagnostics.LogEntry
import com.duophone.app.diagnostics.PiiRedactor
import com.duophone.app.domain.CallUiState
import com.duophone.app.domain.ModemConnectionState
import com.duophone.app.domain.ModemUiState

enum class DuoTab(val title: String) {
    STATUS("模块状态"),
    CALL("电话"),
    SMS("短信"),
    AT_CONSOLE("AT 调试终端"),
    DIAGNOSTICS("设备与日志诊断"),
}

private val PRESET_AT_COMMANDS = listOf(
    "AT",
    "ATI",
    "AT+CGMR",
    "AT+CPIN?",
    "AT+CSQ",
    "AT+COPS?",
    "AT+CEREG?",
    "AT+QCFG=\"usbnet\"",
    "AT+QCFG=\"usbcfg\"",
    "AT+QCFG=\"ims\"",
    "AT+QPCMV=?",
    "AT+QDAI=?",
)

@Composable
fun DuoPhoneScreen(
    selectedTab: DuoTab,
    onSelectTab: (DuoTab) -> Unit,
    modemState: ModemUiState,
    hostHardware: HostHardwareSnapshot,
    logs: List<LogEntry>,
    smsMessages: List<SmsRecord>,
    smsThreads: List<SmsThreadSummary>,
    isSmsSyncing: Boolean,
    smsSyncStatus: String,
    selectedSmsAddress: String?,
    callState: CallUiState,
    callLogs: List<CallLogRecord>,
    snackbarHostState: SnackbarHostState,
    onConnect: () -> Unit,
    onRefreshTelemetry: () -> Unit,
    onRefreshHostHardware: () -> Unit,
    onRunAtCommand: (String) -> Unit,
    onClearConsole: () -> Unit,
    onClearLogs: () -> Unit,
    onSelectSmsAddress: (String?) -> Unit,
    onSyncSms: () -> Unit,
    onSendSms: (String, String) -> Unit,
    onSimulateIncomingSms: () -> Unit,
    onCopyOtp: (String) -> Unit,
    onDeleteSms: (Long) -> Unit,
    onDial: (String) -> Unit,
    onAnswerCall: () -> Unit,
    onHangupOrRejectCall: () -> Unit,
    onSendDtmf: (String) -> Unit,
    onToggleCallMute: () -> Unit,
    onToggleCallSpeaker: () -> Unit,
    onSimulateIncomingCall: () -> Unit,
    onSimulateOutgoingCall: (String) -> Unit,
    onDeleteCallLog: (Long) -> Unit,
    onClearCallLogs: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DuoBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DuoSurface)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dotColor = stateColor(modemState.connectionState)
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "DUO PHONE",
                                color = DuoTextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                letterSpacing = 1.2.sp,
                            )
                            Text(
                                text = "${modemState.connectionState.label} · L1/R1 切换页签",
                                color = DuoTextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefreshTelemetry, enabled = !modemState.isBusy) {
                            Text("刷新", fontSize = 12.sp)
                        }
                        Button(
                            onClick = onConnect,
                            enabled = !modemState.isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = DuoAccent),
                        ) {
                            Text(
                                text = if (modemState.activeAtInterfaceId >= 0) "重连" else "连接模块",
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                TabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = DuoSurface,
                    contentColor = DuoAccent,
                ) {
                    DuoTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { onSelectTab(tab) },
                            text = { Text(tab.title, fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            when (selectedTab) {
                DuoTab.STATUS -> StatusDashboard(modemState = modemState)
                DuoTab.CALL -> CallTabScreen(
                    callState = callState,
                    callLogs = callLogs,
                    modemReady = modemState.activeAtInterfaceId >= 0,
                    onDial = onDial,
                    onAnswer = onAnswerCall,
                    onHangupOrReject = onHangupOrRejectCall,
                    onSendDtmf = onSendDtmf,
                    onToggleMute = onToggleCallMute,
                    onToggleSpeaker = onToggleCallSpeaker,
                    onSimulateIncoming = onSimulateIncomingCall,
                    onSimulateOutgoing = onSimulateOutgoingCall,
                    onDeleteCallLog = onDeleteCallLog,
                    onClearCallLogs = onClearCallLogs,
                )
                DuoTab.SMS -> SmsTabScreen(
                    messages = smsMessages,
                    threads = smsThreads,
                    modemReady = modemState.activeAtInterfaceId >= 0,
                    isSyncing = isSmsSyncing,
                    syncStatus = smsSyncStatus,
                    selectedAddress = selectedSmsAddress,
                    onSelectAddress = onSelectSmsAddress,
                    onSyncSms = onSyncSms,
                    onSendSms = onSendSms,
                    onSimulateIncomingSms = onSimulateIncomingSms,
                    onCopyOtp = onCopyOtp,
                    onDeleteMessage = onDeleteSms,
                )
                DuoTab.AT_CONSOLE -> AtConsoleView(
                    modemState = modemState,
                    onRunAtCommand = onRunAtCommand,
                    onClearConsole = onClearConsole,
                )
                DuoTab.DIAGNOSTICS -> DiagnosticsView(
                    hostHardware = hostHardware,
                    logs = logs,
                    onRefreshHostHardware = onRefreshHostHardware,
                    onClearLogs = onClearLogs,
                )
            }
        }
    }
}

@Composable
private fun StatusDashboard(modemState: ModemUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard(title = "连接状态机") {
                val color = stateColor(modemState.connectionState)
                Text(
                    text = "${modemState.connectionState.name} · ${modemState.connectionState.label}",
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = modemState.statusDetail,
                    color = DuoTextSecondary,
                    fontSize = 13.sp,
                )
                if (modemState.activeAtInterfaceId >= 0) {
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("AT 活动接口", "IF#${modemState.activeAtInterfaceId}")
                }
            }
        }

        item {
            SectionCard(title = "蜂窝通信遥测") {
                KeyValueRow("SIM 状态", modemState.simState.label)
                KeyValueRow(
                    "运营商",
                    modemState.operator.name.ifBlank { "—" } +
                        if (modemState.operator.accessTechLabel.isNotBlank()) " (${modemState.operator.accessTechLabel})" else "",
                )
                KeyValueRow(
                    "信号强度",
                    if (modemState.signal.dbm != null) {
                        "${modemState.signal.bars}/5 格 · ${modemState.signal.dbm} dBm (CSQ=${modemState.signal.rssi})"
                    } else {
                        "— (CSQ=${modemState.signal.rssi})"
                    },
                )
                KeyValueRow("LTE 注册 (CEREG)", modemState.registration.cereg.label)
                KeyValueRow("CS/PS 注册", "${modemState.registration.creg.label} / ${modemState.registration.cgreg.label}")
            }
        }

        item {
            SectionCard(title = "模块身份与配置（只读）") {
                val id = modemState.identity
                KeyValueRow("制造商 / 型号", listOf(id.manufacturer, id.model).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "—" })
                KeyValueRow("固件版本", id.revision.ifBlank { "—" })
                KeyValueRow("IMEI (脱敏)", if (id.imei.isNotBlank()) PiiRedactor.maskIdentifier(id.imei) else "—")
                KeyValueRow("USB 网卡模式", modemState.usbConfig.usbnetLabel)
                KeyValueRow("USBCFG", modemState.usbConfig.usbcfgRaw.ifBlank { "—" })
                KeyValueRow("IMS / VoLTE", modemState.usbConfig.imsRaw.ifBlank { "—" })
                KeyValueRow("QPCMV 查询", modemState.usbConfig.qpcmvSupportRaw.ifBlank { "—" })
            }
        }

        item {
            val usb = modemState.usbDevice
            SectionCard(title = "USB 设备描述符 (${usb?.vidPidHex ?: "未接入"})") {
                if (usb == null) {
                    Text("尚未枚举到受支持的 USB 4G 模块。", color = DuoTextSecondary, fontSize = 13.sp)
                } else {
                    KeyValueRow("设备路径", usb.deviceName)
                    KeyValueRow("产品 / 厂商", "${usb.productName} / ${usb.manufacturerName}")
                    KeyValueRow("ADB / UAC 存在", "ADB=${usb.hasAdbInterface} · UAC=${usb.hasUacInterface}")
                    Spacer(Modifier.height(6.dp))
                    usb.interfaces.forEach { intf ->
                        val highlight = if (intf.isActiveAtPort) DuoGreen else DuoTextPrimary
                        Text(
                            text = "${if (intf.isActiveAtPort) "★ " else "  "}${intf.summary} — ${intf.role.label}",
                            color = highlight,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        if (modemState.recentUrcs.isNotEmpty()) {
            item {
                SectionCard(title = "最近异步 URC 事件") {
                    modemState.recentUrcs.take(10).forEach { urc ->
                        Text(
                            text = PiiRedactor.redact(urc),
                            color = DuoCyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AtConsoleView(
    modemState: ModemUiState,
    onRunAtCommand: (String) -> Unit,
    onClearConsole: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("ATI") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("输入 AT 指令（如 AT+CSQ）") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (input.isNotBlank()) onRunAtCommand(input)
                    },
                ),
            )
            Button(
                onClick = { if (input.isNotBlank()) onRunAtCommand(input) },
                enabled = modemState.activeAtInterfaceId >= 0 && !modemState.isBusy,
            ) {
                Text("发送")
            }
            OutlinedButton(onClick = onClearConsole) {
                Text("清空")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PRESET_AT_COMMANDS.forEach { preset ->
                FilterChip(
                    selected = false,
                    onClick = {
                        input = preset
                        if (modemState.activeAtInterfaceId >= 0) {
                            onRunAtCommand(preset)
                        }
                    },
                    label = { Text(preset, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF070B14)),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (modemState.consoleRecords.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (modemState.activeAtInterfaceId >= 0) {
                            "AT 通道就绪 (IF#${modemState.activeAtInterfaceId})，点击上方预设或输入指令调试。"
                        } else {
                            "未连接到模块 AT 端口。"
                        },
                        color = DuoTextSecondary,
                        fontSize = 13.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(modemState.consoleRecords.reversed()) { rec ->
                        Column {
                            Text(
                                text = "> ${PiiRedactor.redact(rec.command)}   [${rec.status.name} · ${rec.durationMs}ms]",
                                color = DuoAccent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                            Text(
                                text = PiiRedactor.redact(rec.response),
                                color = DuoTextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(top = 6.dp),
                                color = DuoSurfaceAlt,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsView(
    hostHardware: HostHardwareSnapshot,
    logs: List<LogEntry>,
    onRefreshHostHardware: () -> Unit,
    onClearLogs: () -> Unit,
) {
    var selectedCategory by remember { mutableStateOf<LogCategory?>(null) }
    val filteredLogs = remember(logs, selectedCategory) {
        if (selectedCategory == null) logs else logs.filter { it.category == selectedCategory }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard(title = "宿主设备硬件诊断 (${hostHardware.model})") {
                KeyValueRow("系统版本", "Android ${hostHardware.androidRelease} (API ${hostHardware.sdkInt}) · ${hostHardware.abi}")
                KeyValueRow("检测显示屏数", "${hostHardware.displays.size} 块")
                hostHardware.displays.forEach { d ->
                    Text(
                        text = "  Display#${d.displayId} ${d.name}: ${d.width}x${d.height} @ ${"%.0f".format(d.refreshRate)}Hz" +
                            if (d.isDefault) " [主屏]" else " [副屏]",
                        color = if (d.isDefault) DuoAccent else DuoGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                val gamepads = hostHardware.inputs.filter { it.isGamepad }
                KeyValueRow("实体手柄", gamepads.joinToString { it.name }.ifBlank { "未检测到" })
                val audioSinks = hostHardware.audioDevices.filter { it.isSink }.map { it.typeName }.distinct()
                val audioSources = hostHardware.audioDevices.filter { !it.isSink }.map { it.typeName }.distinct()
                KeyValueRow("音频输出", audioSinks.joinToString(", ").ifBlank { "—" })
                KeyValueRow("音频输入", audioSources.joinToString(", ").ifBlank { "—" })
                KeyValueRow("当前 USB 列表", hostHardware.attachedUsbSummary.joinToString("; ").ifBlank { "无外接 USB 设备" })
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshHostHardware) { Text("刷新硬件快照", fontSize = 12.sp) }
                    OutlinedButton(onClick = onClearLogs) { Text("清空日志", fontSize = 12.sp) }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("ALL (${logs.size})", fontSize = 11.sp) },
                )
                LogCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                        label = { Text(cat.tag.removePrefix("DUO_"), fontSize = 11.sp) },
                    )
                }
            }
        }

        items(filteredLogs.reversed()) { entry ->
            Text(
                text = entry.formatLine(),
                color = when (entry.level) {
                    com.duophone.app.diagnostics.LogLevel.ERROR -> DuoRed
                    com.duophone.app.diagnostics.LogLevel.WARN -> DuoAmber
                    com.duophone.app.diagnostics.LogLevel.INFO -> DuoTextPrimary
                    com.duophone.app.diagnostics.LogLevel.DEBUG -> DuoTextSecondary
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0F1D), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DuoSurface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                color = DuoAccent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = DuoTextSecondary, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = DuoTextPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun stateColor(state: ModemConnectionState): Color = when (state) {
    ModemConnectionState.READY,
    ModemConnectionState.NETWORK_REGISTERED,
    -> DuoGreen
    ModemConnectionState.AT_READY,
    ModemConnectionState.SIM_READY,
    ModemConnectionState.CONNECTING,
    ModemConnectionState.USB_DETECTED,
    ModemConnectionState.RECOVERING,
    -> DuoAmber
    ModemConnectionState.ERROR,
    ModemConnectionState.UNSUPPORTED_DEVICE,
    ModemConnectionState.SIM_NOT_READY,
    ModemConnectionState.NETWORK_UNAVAILABLE,
    ModemConnectionState.PERMISSION_REQUIRED,
    -> DuoRed
    ModemConnectionState.DISCONNECTED -> DuoTextSecondary
}
