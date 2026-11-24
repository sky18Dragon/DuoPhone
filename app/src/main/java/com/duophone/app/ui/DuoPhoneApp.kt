package com.duophone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import com.duophone.app.audio.AudioBridgeState
import com.duophone.app.audio.LocalAudioRoute
import com.duophone.app.data.CallLogRecord
import com.duophone.app.data.SmsRecord
import com.duophone.app.device.ModemConfigBackupState
import com.duophone.app.device.UsbCfgTuple
import com.duophone.app.device.VoiceRuntimeSnapshot
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
    secondaryDisplayId: Int?,
    snackbarHostState: SnackbarHostState,
    onConnect: () -> Unit,
    onLaunchSecondaryDeck: () -> Unit,
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
    voiceState: VoiceRuntimeSnapshot = VoiceRuntimeSnapshot(),
    audioState: AudioBridgeState = AudioBridgeState(),
    onProbeVoiceRuntime: () -> Unit = {},
    onPrepareVoiceRuntime: () -> Unit = {},
    onToggleVoiceRoute: () -> Unit = {},
    onToggleAudioBridge: () -> Unit = {},
    onSelectAudioRoute: (LocalAudioRoute) -> Unit = {},
    configBackupState: ModemConfigBackupState = ModemConfigBackupState(),
    onBackupModemConfig: () -> Unit = {},
    onEnableAdbUac: () -> Unit = {},
    onRestoreOriginalUsbCfg: () -> Unit = {},
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
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val dotColor = stateColor(modemState.connectionState)
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "DUO PHONE",
                                color = DuoTextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                letterSpacing = 1.sp,
                            )
                            Text(
                                text = "${modemState.connectionState.label} · L1/R1 切换页签",
                                color = DuoTextSecondary,
                                fontSize = 10.sp,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (secondaryDisplayId != null) {
                            OutlinedButton(
                                onClick = onLaunchSecondaryDeck,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) {
                                Text("下屏#$secondaryDisplayId", fontSize = 11.sp, color = DuoCyan)
                            }
                        }
                        OutlinedButton(
                            onClick = onRefreshTelemetry,
                            enabled = !modemState.isBusy,
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Text("刷新", fontSize = 11.sp)
                        }
                        Button(
                            onClick = onConnect,
                            enabled = !modemState.isBusy,
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DuoAccent),
                        ) {
                            Text(
                                text = if (modemState.activeAtInterfaceId >= 0) "重连" else "连接模块",
                                fontSize = 11.sp,
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
                            modifier = Modifier.height(36.dp),
                            text = { Text(tab.title, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
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
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
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
                    currentUsbCfgRaw = modemState.usbConfig.usbcfgRaw,
                    modemReady = modemState.activeAtInterfaceId >= 0,
                    voiceState = voiceState,
                    audioState = audioState,
                    configBackupState = configBackupState,
                    onRefreshHostHardware = onRefreshHostHardware,
                    onClearLogs = onClearLogs,
                    onProbeVoiceRuntime = onProbeVoiceRuntime,
                    onPrepareVoiceRuntime = onPrepareVoiceRuntime,
                    onToggleVoiceRoute = onToggleVoiceRoute,
                    onToggleAudioBridge = onToggleAudioBridge,
                    onSelectAudioRoute = onSelectAudioRoute,
                    onBackupModemConfig = onBackupModemConfig,
                    onEnableAdbUac = onEnableAdbUac,
                    onRestoreOriginalUsbCfg = onRestoreOriginalUsbCfg,
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
    currentUsbCfgRaw: String,
    modemReady: Boolean,
    voiceState: VoiceRuntimeSnapshot,
    audioState: AudioBridgeState,
    configBackupState: ModemConfigBackupState,
    onRefreshHostHardware: () -> Unit,
    onClearLogs: () -> Unit,
    onProbeVoiceRuntime: () -> Unit,
    onPrepareVoiceRuntime: () -> Unit,
    onToggleVoiceRoute: () -> Unit,
    onToggleAudioBridge: () -> Unit,
    onSelectAudioRoute: (LocalAudioRoute) -> Unit,
    onBackupModemConfig: () -> Unit,
    onEnableAdbUac: () -> Unit,
    onRestoreOriginalUsbCfg: () -> Unit,
) {
    var selectedCategory by remember { mutableStateOf<LogCategory?>(null) }
    var showEnableAdbUacDialog by remember { mutableStateOf(false) }
    var showRestoreUsbCfgDialog by remember { mutableStateOf(false) }
    val filteredLogs = remember(logs, selectedCategory) {
        if (selectedCategory == null) logs else logs.filter { it.category == selectedCategory }
    }
    val liveTuple = remember(currentUsbCfgRaw, configBackupState.currentUsbCfg) {
        UsbCfgTuple.parse(currentUsbCfgRaw) ?: configBackupState.currentUsbCfg
    }
    val proposedWriteCmd = liveTuple?.withAdbAndUac(adbEnabled = true, uacEnabled = true)?.toWriteCommand()
        ?: "AT+QCFG=\"usbcfg\",0x2CA3,0x4006,1,1,1,1,1,1,1"

    if (showEnableAdbUacDialog) {
        AlertDialog(
            onDismissRequest = { showEnableAdbUacDialog = false },
            title = { Text("确认开启 QDC507 ADB 与 UAC", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = buildString {
                        appendLine("将执行以下受控步骤：")
                        appendLine("1. 校验按 IMEI 保存的原始配置备份与空语音通话状态；")
                        appendLine("2. 若当前 adb=0，则查询 AT+QADBKEY? 在本机内存派生解锁口令完成持久 ADB 授权（口令不记录、不上传）；")
                        appendLine("3. 写入保留原厂 VID:PID 的组合命令：")
                        appendLine(proposedWriteCmd)
                        appendLine("4. 回读确认后下发 AT+CFUN=1,1 重启模块（将短时断网约 10~15 秒，请重新允许 USB 权限）。")
                        append("注意：配置备份可还原 USB 接口开关，但 AT+QADBKEY 授权为持久状态。")
                    },
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEnableAdbUacDialog = false
                        onEnableAdbUac()
                    },
                ) {
                    Text("确认执行", color = DuoAmber, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnableAdbUacDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showRestoreUsbCfgDialog) {
        val restoreCmd = configBackupState.backup?.usbCfg?.toWriteCommand() ?: "（无匹配备份）"
        AlertDialog(
            onDismissRequest = { showRestoreUsbCfgDialog = false },
            title = { Text("确认还原原厂 USB 组合", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "将按当前 IMEI 匹配本地备份并写入：\n$restoreCmd\n随后下发 AT+CFUN=1,1 重启模块生效。\n注：AT+QADBKEY 持久解锁状态不会被本操作撤销。",
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreUsbCfgDialog = false
                        onRestoreOriginalUsbCfg()
                    },
                ) {
                    Text("确认还原", color = DuoRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreUsbCfgDialog = false }) {
                    Text("取消")
                }
            },
        )
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
            SectionCard(title = "Phase 4 语音运行时与 UAC 音频桥接 (${voiceState.stage.name})") {
                KeyValueRow(
                    "IMEI 配置备份",
                    configBackupState.backup?.let { "已备份 (${it.usbCfg.summaryTuple()})" } ?: "未备份（写入 usbcfg 前必选）",
                )
                KeyValueRow(
                    "实时 usbcfg",
                    liveTuple?.summaryTuple() ?: currentUsbCfgRaw.ifBlank { "未读取" },
                )
                KeyValueRow("模块侧 ADB / UAC", "ADB=${voiceState.adbDetected} · UAC(USB描述)=${voiceState.uacDetected}")
                KeyValueRow("模块内核与驱动", "${voiceState.kernelVersion.ifBlank { "未探测" }} · apr=${voiceState.aprLoaded} voice=${voiceState.voiceLoaded}")
                KeyValueRow("ALSA /dev/snd", voiceState.pcmDevices.joinToString(", ").ifBlank { "—" })
                KeyValueRow("模块桥接进程", voiceState.bridgePid?.let { "PID=$it" } ?: "未运行")
                KeyValueRow("麦克风权限 / UAC", "RECORD_AUDIO=${audioState.hasRecordPermission} · UAC完整=${audioState.uacPair.isComplete}")
                KeyValueRow(
                    "UAC AudioDevice",
                    "in=${audioState.uacPair.inputName ?: "—"} / out=${audioState.uacPair.outputName ?: "—"}",
                )
                KeyValueRow(
                    "实时电平 (dBFS)",
                    "上行 ${"%.1f".format(audioState.uplinkRmsDbfs)} dBFS · 下行 ${"%.1f".format(audioState.downlinkRmsDbfs)} dBFS",
                )
                if (audioState.androidCaptureConcurrencyWarning) {
                    Text(
                        text = "告警：检测到本地麦克风连续全零帧 (${audioState.uplinkZeroChunks})，可能受 Android 16 并发采集静音策略或硬件静音影响。",
                        color = DuoAmber,
                        fontSize = 11.sp,
                    )
                }
                voiceState.localAssets.forEach { asset ->
                    val statusText = when {
                        asset.valid -> "就绪 (SHA-256 匹配)"
                        asset.exists -> "校验失败 (SHA 不匹配)"
                        else -> "未放置到 files/voice_runtime"
                    }
                    KeyValueRow("外部资源 ${asset.spec.fileName}", statusText)
                }
                val combinedErr = configBackupState.lastError ?: voiceState.lastError ?: audioState.lastError
                if (!combinedErr.isNullOrBlank()) {
                    Text(text = combinedErr, color = DuoRed, fontSize = 11.sp)
                } else {
                    Text(
                        text = "${configBackupState.lastStatus} | ${voiceState.lastSummary} | ${audioState.lastSummary}",
                        color = DuoTextSecondary,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onBackupModemConfig,
                        enabled = modemReady && !configBackupState.isBusy,
                    ) { Text("1.备份配置", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = { showEnableAdbUacDialog = true },
                        enabled = modemReady && !configBackupState.isBusy && configBackupState.backup != null,
                    ) { Text("2.开启 ADB+UAC", fontSize = 11.sp, color = DuoAmber) }
                    OutlinedButton(
                        onClick = { showRestoreUsbCfgDialog = true },
                        enabled = modemReady && !configBackupState.isBusy && configBackupState.backup != null,
                    ) { Text("还原原厂 USB", fontSize = 11.sp) }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onProbeVoiceRuntime) { Text("3.只读探测", fontSize = 11.sp) }
                    OutlinedButton(onClick = onPrepareVoiceRuntime) { Text("4.部署 /tmp 运行时", fontSize = 11.sp) }
                    OutlinedButton(onClick = onToggleVoiceRoute) {
                        Text(if (voiceState.bridgePid != null) "停止模块路由" else "5.启动模块路由", fontSize = 11.sp)
                    }
                    Button(
                        onClick = onToggleAudioBridge,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (audioState.isRunning) DuoRed else DuoAccent,
                        ),
                    ) {
                        Text(if (audioState.isRunning) "停止 UAC 桥接" else "6.启动 UAC 桥接", fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LocalAudioRoute.entries.forEach { route ->
                        FilterChip(
                            selected = audioState.localRoute == route,
                            onClick = { onSelectAudioRoute(route) },
                            label = { Text(route.label, fontSize = 11.sp) },
                        )
                    }
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
