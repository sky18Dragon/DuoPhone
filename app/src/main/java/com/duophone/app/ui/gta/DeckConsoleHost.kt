package com.duophone.app.ui.gta

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duophone.app.DuoPhoneApplication
import com.duophone.app.diagnostics.DeviceDiagnostics
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.HostHardwareSnapshot
import com.duophone.app.stage.StageThemeMode
import com.duophone.app.ui.DualDisplayHelper
import com.duophone.app.ui.DuoPhoneScreen
import com.duophone.app.ui.DuoPhoneTheme
import com.duophone.app.ui.DuoTab
import kotlinx.coroutines.launch

/**
 * Shared deck host uniting BottomDeckScreen (GTA 6 3-menu handheld console) and
 * the collapsible legacy engineering diagnostics workbench.
 * Reused by SecondaryDeckActivity (Display#2) and MainActivity's single-screen fallback mode.
 */
@Composable
fun DeckConsoleHost(
    app: DuoPhoneApplication,
    displayId: Int,
    hostHardware: HostHardwareSnapshot,
    hasContactsPermission: Boolean,
    snackbarHostState: SnackbarHostState,
    onRefreshHostHardware: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onRequestRecordAudioPermissionAndStart: () -> Unit,
    onRelaunchDualDisplay: (() -> Unit)? = null,
    onExitSingleScreenDeck: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val stageState by app.gtaStageCoordinator.state.collectAsStateWithLifecycle()
    val modemState by app.modemController.state.collectAsStateWithLifecycle()
    val callState by app.callController.state.collectAsStateWithLifecycle()
    val callLogs by app.callController.callLogs.collectAsStateWithLifecycle()
    val contacts by app.contactRepository.contacts.collectAsStateWithLifecycle()
    val audioState by app.audioBridge.state.collectAsStateWithLifecycle()
    val voiceState by app.voiceRuntime.state.collectAsStateWithLifecycle()
    val configBackupState by app.configManager.state.collectAsStateWithLifecycle()
    val logs by DuoLogger.entries.collectAsStateWithLifecycle()
    val smsMessages by app.smsController.messages.collectAsStateWithLifecycle()
    val isSmsSyncing by app.smsController.isSyncing.collectAsStateWithLifecycle()
    val smsSyncStatus by app.smsController.lastSyncStatus.collectAsStateWithLifecycle()
    val smsThreads = remember(smsMessages) { app.smsRepository.buildThreads(smsMessages) }

    var legacyTab by rememberSaveable { mutableStateOf(DuoTab.STATUS) }
    val isDark = when (stageState.themeMode) {
        StageThemeMode.DARK -> true
        StageThemeMode.LIGHT -> false
        StageThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val secondaryDisplayId = remember(hostHardware) {
        DualDisplayHelper.selectSecondaryDisplayId(
            hostHardware.displays.map {
                com.duophone.app.ui.DisplayDescriptor(it.displayId, it.name, it.isDefault)
            },
        )
    }

    if (stageState.showLegacyDiagnostics) {
        DuoPhoneTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GtaDarkDeckPalette.surface)
                        .border(0.5.dp, GtaDarkDeckPalette.border)
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "高级硬件与 QDC507 诊断工作台",
                        color = GtaDarkDeckPalette.accentTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Button(
                        onClick = { app.gtaStageCoordinator.setShowLegacyDiagnostics(false) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GtaDarkDeckPalette.accentPrimary),
                    ) {
                        Text("返回控制台", fontSize = 11.sp, color = GtaDarkDeckPalette.accentOnPrimary)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    DuoPhoneScreen(
                        selectedTab = legacyTab,
                        onSelectTab = { legacyTab = it },
                        modemState = modemState,
                        hostHardware = hostHardware,
                        logs = logs,
                        smsMessages = smsMessages,
                        smsThreads = smsThreads,
                        isSmsSyncing = isSmsSyncing,
                        smsSyncStatus = smsSyncStatus,
                        selectedSmsAddress = stageState.selectedSmsAddress,
                        callState = callState,
                        callLogs = callLogs,
                        secondaryDisplayId = secondaryDisplayId,
                        snackbarHostState = snackbarHostState,
                        onConnect = { scope.launch { app.modemController.connect() } },
                        onLaunchSecondaryDeck = { onRelaunchDualDisplay?.invoke() },
                        onRefreshTelemetry = {
                            scope.launch { app.modemController.refreshTelemetry(includeIdentityAndConfig = true) }
                        },
                        onRefreshHostHardware = onRefreshHostHardware,
                        onRunAtCommand = { cmd -> scope.launch { app.modemController.runRawAtCommand(cmd) } },
                        onClearConsole = { app.modemController.clearConsole() },
                        onClearLogs = { DuoLogger.clear() },
                        onSelectSmsAddress = { addr ->
                            app.gtaStageCoordinator.selectSmsThread(addr)
                            if (addr != null) {
                                scope.launch { app.smsController.markThreadRead(addr) }
                            }
                        },
                        onSyncSms = { scope.launch { app.smsController.syncFromModem(notifyNew = false) } },
                        onSendSms = { number, body ->
                            scope.launch {
                                val res = app.smsController.sendSms(number, body)
                                if (res.success) {
                                    app.gtaStageCoordinator.onSmsSent(number, body)
                                }
                                val msg = if (res.success) {
                                    "短信已发送 (${res.segmentsSent}/${res.totalSegments} 分片)"
                                } else {
                                    res.errorMessage ?: "发送失败"
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        },
                        onSimulateIncomingSms = {
                            scope.launch {
                                app.smsController.injectSimulatedIncomingSms(
                                    "+8613800138000",
                                    "【DuoPhone 模拟测试】您的登录验证码为 619204，仅用于无硬件 App 通知与持久化验证。",
                                )
                                snackbarHostState.showSnackbar("已写入模拟收件短信并触发通知")
                            }
                        },
                        onCopyOtp = { otp ->
                            scope.launch { snackbarHostState.showSnackbar("已复制验证码：$otp") }
                        },
                        onDeleteSms = { id -> scope.launch { app.smsController.deleteLocalMessage(id) } },
                        onDial = { number ->
                            app.gtaStageCoordinator.onDialTrigger(number)
                            scope.launch {
                                app.callController.dial(number)?.let { err -> snackbarHostState.showSnackbar(err) }
                            }
                        },
                        onAnswerCall = {
                            app.gtaStageCoordinator.onAnswerOrHangupVisualFeedback(isAnswer = true)
                            scope.launch {
                                app.callController.answer()?.let { err -> snackbarHostState.showSnackbar(err) }
                            }
                        },
                        onHangupOrRejectCall = {
                            app.gtaStageCoordinator.onAnswerOrHangupVisualFeedback(isAnswer = false)
                            scope.launch {
                                app.callController.hangupOrReject()?.let { err -> snackbarHostState.showSnackbar(err) }
                            }
                        },
                        onSendDtmf = { digit ->
                            app.gtaStageCoordinator.onDtmfKeyPressed(digit)
                            scope.launch {
                                app.callController.sendDtmf(digit)?.let { err -> snackbarHostState.showSnackbar(err) }
                            }
                        },
                        onToggleCallMute = { app.callController.toggleMute() },
                        onToggleCallSpeaker = { app.callController.toggleSpeaker() },
                        onSimulateIncomingCall = {
                            app.callController.simulateIncomingCall("+8613800138000")
                            scope.launch { snackbarHostState.showSnackbar("已触发模拟来电与系统通知") }
                        },
                        onSimulateOutgoingCall = { num ->
                            app.gtaStageCoordinator.onDialTrigger(num)
                            app.callController.simulateOutgoingCall(num)
                        },
                        onDeleteCallLog = { id -> scope.launch { app.callController.deleteCallLog(id) } },
                        onClearCallLogs = { scope.launch { app.callController.clearCallLogs() } },
                        voiceState = voiceState,
                        audioState = audioState,
                        onProbeVoiceRuntime = {
                            scope.launch {
                                val uacPresent = modemState.usbDevice?.hasUacInterface == true
                                app.voiceRuntime.probeReadOnly(uacDetected = uacPresent)
                                app.audioBridge.refreshDiagnostics()
                            }
                        },
                        onPrepareVoiceRuntime = {
                            scope.launch {
                                val uacPresent = modemState.usbDevice?.hasUacInterface == true
                                app.voiceRuntime.prepareReversibleRuntime(uacDetected = uacPresent).onFailure { err ->
                                    snackbarHostState.showSnackbar(err.message ?: "语音运行时准备失败")
                                }
                            }
                        },
                        onToggleVoiceRoute = {
                            scope.launch {
                                val res = if (voiceState.bridgePid != null) {
                                    app.voiceRuntime.stopVoiceRoute()
                                } else {
                                    app.voiceRuntime.startVoiceRoute()
                                }
                                res.onFailure { err ->
                                    snackbarHostState.showSnackbar(err.message ?: "模块语音路由操作失败")
                                }
                            }
                        },
                        onToggleAudioBridge = {
                            scope.launch {
                                if (audioState.isRunning) {
                                    app.audioBridge.stop()
                                } else if (!app.audioBridge.checkRecordPermission()) {
                                    onRequestRecordAudioPermissionAndStart()
                                } else {
                                    app.audioBridge.start().onFailure { err ->
                                        snackbarHostState.showSnackbar(err.message ?: "UAC 音频桥接启动失败")
                                    }
                                }
                            }
                        },
                        onSelectAudioRoute = { route -> app.audioBridge.applyLocalAudioRoute(route) },
                        configBackupState = configBackupState,
                        onBackupModemConfig = {
                            scope.launch {
                                app.configManager.backupCurrentConfig().fold(
                                    onSuccess = { rec ->
                                        snackbarHostState.showSnackbar("配置备份完成: ${rec.usbCfg.summaryTuple()}")
                                    },
                                    onFailure = { err ->
                                        snackbarHostState.showSnackbar(err.message ?: "配置备份失败")
                                    },
                                )
                            }
                        },
                        onEnableAdbUac = {
                            scope.launch {
                                app.configManager.enableAdbAndUacWithReboot().fold(
                                    onSuccess = { msg -> snackbarHostState.showSnackbar(msg) },
                                    onFailure = { err -> snackbarHostState.showSnackbar(err.message ?: "开启 ADB+UAC 失败") },
                                )
                            }
                        },
                        onRestoreOriginalUsbCfg = {
                            scope.launch {
                                app.configManager.restoreOriginalUsbCfgWithReboot().fold(
                                    onSuccess = { msg -> snackbarHostState.showSnackbar(msg) },
                                    onFailure = { err -> snackbarHostState.showSnackbar(err.message ?: "还原原厂 USB 失败") },
                                )
                            }
                        },
                        contacts = contacts,
                        hasContactsPermission = hasContactsPermission,
                        onAdjustCallVolume = { delta -> app.audioBridge.adjustCallVolume(delta) },
                        onSaveLocalContact = { id, name, phone, note ->
                            scope.launch {
                                runCatching { app.contactRepository.upsertLocal(id, name, phone, note) }.fold(
                                    onSuccess = { snackbarHostState.showSnackbar("已保存联系人：$name") },
                                    onFailure = { err -> snackbarHostState.showSnackbar(err.message ?: "保存联系人失败") },
                                )
                            }
                        },
                        onDeleteLocalContact = { id ->
                            scope.launch {
                                app.contactRepository.deleteLocal(id)
                                snackbarHostState.showSnackbar("已删除本地联系人")
                            }
                        },
                        onSyncSystemContacts = onRequestContactsPermission,
                        onOpenSmsForNumber = { number ->
                            app.gtaStageCoordinator.startNewSms(number)
                            app.gtaStageCoordinator.setShowLegacyDiagnostics(false)
                        },
                    )
                }
            }
        }
    } else {
        GtaPhoneTheme(darkTheme = isDark) {
            BottomDeckScreen(
                displayId = displayId,
                stageState = stageState,
                modemState = modemState,
                callState = callState,
                callLogs = callLogs,
                contacts = contacts,
                hasContactsPermission = hasContactsPermission,
                audioState = audioState,
                smsMessages = smsMessages,
                smsThreads = smsThreads,
                isSmsSyncing = isSmsSyncing,
                smsSyncStatus = smsSyncStatus,
                snackbarHostState = snackbarHostState,
                onSelectMenu = { menu -> app.gtaStageCoordinator.selectMenu(menu) },
                onConnectModem = { scope.launch { app.modemController.connect() } },
                onRefreshTelemetry = {
                    scope.launch { app.modemController.refreshTelemetry(includeIdentityAndConfig = true) }
                },
                onDialKeyPressed = { digit -> app.gtaStageCoordinator.onDialKeyPressed(digit) },
                onDialBackspace = { app.gtaStageCoordinator.onDialBackspace() },
                onDialClear = { app.gtaStageCoordinator.onDialClear() },
                onSetDialBuffer = { num -> app.gtaStageCoordinator.setDialBuffer(num) },
                onDial = { number ->
                    app.gtaStageCoordinator.onDialTrigger(number)
                    scope.launch {
                        app.callController.dial(number)?.let { err -> snackbarHostState.showSnackbar(err) }
                    }
                },
                onAnswer = {
                    app.gtaStageCoordinator.onAnswerOrHangupVisualFeedback(isAnswer = true)
                    scope.launch {
                        app.callController.answer()?.let { err -> snackbarHostState.showSnackbar(err) }
                    }
                },
                onHangupOrReject = {
                    app.gtaStageCoordinator.onAnswerOrHangupVisualFeedback(isAnswer = false)
                    scope.launch {
                        app.callController.hangupOrReject()?.let { err -> snackbarHostState.showSnackbar(err) }
                    }
                },
                onSendDtmf = { digit ->
                    app.gtaStageCoordinator.onDtmfKeyPressed(digit)
                    scope.launch {
                        app.callController.sendDtmf(digit)?.let { err -> snackbarHostState.showSnackbar(err) }
                    }
                },
                onToggleMute = { app.callController.toggleMute() },
                onToggleSpeaker = { app.callController.toggleSpeaker() },
                onSelectAudioRoute = { route -> app.audioBridge.applyLocalAudioRoute(route) },
                onAdjustCallVolume = { delta -> app.audioBridge.adjustCallVolume(delta) },
                onDeleteCallLog = { id -> scope.launch { app.callController.deleteCallLog(id) } },
                onClearCallLogs = { scope.launch { app.callController.clearCallLogs() } },
                onSaveLocalContact = { id, name, phone, note ->
                    scope.launch {
                        runCatching { app.contactRepository.upsertLocal(id, name, phone, note) }.fold(
                            onSuccess = { snackbarHostState.showSnackbar("已保存联系人：$name") },
                            onFailure = { err -> snackbarHostState.showSnackbar(err.message ?: "保存联系人失败") },
                        )
                    }
                },
                onDeleteLocalContact = { id ->
                    scope.launch {
                        app.contactRepository.deleteLocal(id)
                        snackbarHostState.showSnackbar("已删除本地联系人")
                    }
                },
                onSyncSystemContacts = onRequestContactsPermission,
                onSelectSmsThread = { addr ->
                    app.gtaStageCoordinator.selectSmsThread(addr)
                    if (addr != null) {
                        scope.launch { app.smsController.markThreadRead(addr) }
                    }
                },
                onStartNewSms = { prefill -> app.gtaStageCoordinator.startNewSms(prefill) },
                onUpdateSmsDraftNumber = { num -> app.gtaStageCoordinator.updateSmsDraftNumber(num) },
                onUpdateSmsDraftBody = { body -> app.gtaStageCoordinator.updateSmsDraftBody(body) },
                onSyncSms = { scope.launch { app.smsController.syncFromModem(notifyNew = false) } },
                onSendSms = { number, body ->
                    scope.launch {
                        val res = app.smsController.sendSms(number, body)
                        if (res.success) {
                            app.gtaStageCoordinator.onSmsSent(number, body)
                        }
                        val msg = if (res.success) {
                            "短信已发送 (${res.segmentsSent}/${res.totalSegments} 分片)"
                        } else {
                            res.errorMessage ?: "发送失败"
                        }
                        snackbarHostState.showSnackbar(msg)
                    }
                },
                onCopyOtp = { otp ->
                    scope.launch { snackbarHostState.showSnackbar("已复制验证码：$otp") }
                },
                onDeleteSms = { id -> scope.launch { app.smsController.deleteLocalMessage(id) } },
                onSelectThemeMode = { mode -> app.gtaStageCoordinator.setThemeMode(mode) },
                onToggleSound = { enabled -> app.gtaStageCoordinator.setSoundEnabled(enabled) },
                onToggleHaptic = { enabled -> app.gtaStageCoordinator.setHapticEnabled(enabled) },
                onToggleCalibrationGrid = { visible -> app.gtaStageCoordinator.setCalibrationGridVisible(visible) },
                onUpdateOverlay = { transform -> app.gtaStageCoordinator.updatePhoneOverlay(transform) },
                onResetOverlay = { app.gtaStageCoordinator.resetStageConfig() },
                onRescanAssets = {
                    app.gtaStageCoordinator.rescanAssets()
                    scope.launch { snackbarHostState.showSnackbar("已重新扫描 gta_assets 素材目录") }
                },
                onReplayOpening = {
                    app.gtaStageCoordinator.replayOpening()
                    onExitSingleScreenDeck?.invoke()
                },
                onOpenLegacyDiagnostics = { app.gtaStageCoordinator.setShowLegacyDiagnostics(true) },
                onRelaunchDualDisplay = onRelaunchDualDisplay,
                onExitSingleScreenDeck = onExitSingleScreenDeck,
            )
        }
    }
}
