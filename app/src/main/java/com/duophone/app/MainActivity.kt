package com.duophone.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.duophone.app.diagnostics.DeviceDiagnostics
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.HostHardwareSnapshot
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.service.ModemService
import com.duophone.app.ui.DualDisplayHelper
import com.duophone.app.ui.DuoPhoneScreen
import com.duophone.app.ui.DuoPhoneTheme
import com.duophone.app.ui.DuoTab
import com.duophone.app.ui.GamepadAction
import com.duophone.app.ui.GamepadKeyRouter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var selectedTab by mutableStateOf(DuoTab.STATUS)
    private var selectedSmsAddress by mutableStateOf<String?>(null)
    private lateinit var hostHardware: HostHardwareSnapshot
    private var hardwareState by mutableStateOf<HostHardwareSnapshot?>(null)
    private val snackbarHostState = SnackbarHostState()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        DuoLogger.i(LogCategory.SERVICE, "notif_permission", "POST_NOTIFICATIONS granted=$granted")
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        DuoLogger.i(LogCategory.AUDIO, "record_audio_permission", "RECORD_AUDIO granted=$granted")
        (application as DuoPhoneApplication).audioBridge.refreshDiagnostics()
        lifecycleScope.launch {
            snackbarHostState.showSnackbar(
                if (granted) "已授予麦克风录音权限，可测试 Phase 4 音频桥接" else "未授予 RECORD_AUDIO 权限，仅可执行呼叫控制与只读探测",
            )
        }
    }

    override fun onResume() {
        super.onResume()
        (application as DuoPhoneApplication).audioBridge.refreshDiagnostics()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val app = application as DuoPhoneApplication
        val controller = app.modemController
        val smsController = app.smsController
        val callController = app.callController
        val voiceRuntime = app.voiceRuntime
        val audioBridge = app.audioBridge
        val configManager = app.configManager

        handleNavigationIntent(intent)
        requestNotificationPermissionIfNeeded()
        ModemService.start(this, triggerConnect = true)

        hostHardware = DeviceDiagnostics.capture(this)
        hardwareState = hostHardware

        setContent {
            DuoPhoneTheme {
                val modemState by controller.state.collectAsStateWithLifecycle()
                val logs by DuoLogger.entries.collectAsStateWithLifecycle()
                val smsMessages by smsController.messages.collectAsStateWithLifecycle()
                val isSmsSyncing by smsController.isSyncing.collectAsStateWithLifecycle()
                val smsSyncStatus by smsController.lastSyncStatus.collectAsStateWithLifecycle()
                val smsThreads = remember(smsMessages) {
                    app.smsRepository.buildThreads(smsMessages)
                }
                val callState by callController.state.collectAsStateWithLifecycle()
                val callLogs by callController.callLogs.collectAsStateWithLifecycle()
                val voiceState by voiceRuntime.state.collectAsStateWithLifecycle()
                val audioState by audioBridge.state.collectAsStateWithLifecycle()
                val configBackupState by configManager.state.collectAsStateWithLifecycle()
                val hw = hardwareState ?: hostHardware
                val secondaryDisplayId = remember(hw) {
                    DualDisplayHelper.selectSecondaryDisplayId(
                        hw.displays.map {
                            com.duophone.app.ui.DisplayDescriptor(it.displayId, it.name, it.isDefault)
                        },
                    )
                }

                DuoPhoneScreen(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    modemState = modemState,
                    hostHardware = hw,
                    logs = logs,
                    smsMessages = smsMessages,
                    smsThreads = smsThreads,
                    isSmsSyncing = isSmsSyncing,
                    smsSyncStatus = smsSyncStatus,
                    selectedSmsAddress = selectedSmsAddress,
                    callState = callState,
                    callLogs = callLogs,
                    secondaryDisplayId = secondaryDisplayId,
                    snackbarHostState = snackbarHostState,
                    onConnect = { lifecycleScope.launch { controller.connect() } },
                    onLaunchSecondaryDeck = {
                        if (!DualDisplayHelper.launchSecondaryDeck(this@MainActivity)) {
                            lifecycleScope.launch {
                                snackbarHostState.showSnackbar("当前未检测到可用副屏或副屏启动受限，继续单屏模式")
                            }
                        }
                    },
                    onRefreshTelemetry = {
                        lifecycleScope.launch { controller.refreshTelemetry(includeIdentityAndConfig = true) }
                    },
                    onRefreshHostHardware = {
                        hardwareState = DeviceDiagnostics.capture(this)
                    },
                    onRunAtCommand = { cmd ->
                        lifecycleScope.launch { controller.runRawAtCommand(cmd) }
                    },
                    onClearConsole = { controller.clearConsole() },
                    onClearLogs = { DuoLogger.clear() },
                    onSelectSmsAddress = { addr ->
                        selectedSmsAddress = addr
                        if (addr != null) {
                            lifecycleScope.launch { smsController.markThreadRead(addr) }
                        }
                    },
                    onSyncSms = {
                        lifecycleScope.launch { smsController.syncFromModem(notifyNew = false) }
                    },
                    onSendSms = { number, body ->
                        lifecycleScope.launch {
                            val res = smsController.sendSms(number, body)
                            val msg = if (res.success) {
                                "短信已发送 (${res.segmentsSent}/${res.totalSegments} 分片)"
                            } else {
                                res.errorMessage ?: "发送失败"
                            }
                            snackbarHostState.showSnackbar(msg)
                        }
                    },
                    onSimulateIncomingSms = {
                        lifecycleScope.launch {
                            smsController.injectSimulatedIncomingSms(
                                "+8613800138000",
                                "【DuoPhone 模拟测试】您的登录验证码为 619204，仅用于无硬件 App 通知与持久化验证。",
                            )
                            snackbarHostState.showSnackbar("已写入模拟收件短信并触发通知")
                        }
                    },
                    onCopyOtp = { otp ->
                        lifecycleScope.launch { snackbarHostState.showSnackbar("已复制验证码：$otp") }
                    },
                    onDeleteSms = { id ->
                        lifecycleScope.launch { smsController.deleteLocalMessage(id) }
                    },
                    onDial = { number ->
                        lifecycleScope.launch {
                            callController.dial(number)?.let { err -> snackbarHostState.showSnackbar(err) }
                        }
                    },
                    onAnswerCall = {
                        lifecycleScope.launch {
                            callController.answer()?.let { err -> snackbarHostState.showSnackbar(err) }
                        }
                    },
                    onHangupOrRejectCall = {
                        lifecycleScope.launch {
                            callController.hangupOrReject()?.let { err -> snackbarHostState.showSnackbar(err) }
                        }
                    },
                    onSendDtmf = { digit ->
                        lifecycleScope.launch {
                            callController.sendDtmf(digit)?.let { err -> snackbarHostState.showSnackbar(err) }
                        }
                    },
                    onToggleCallMute = { callController.toggleMute() },
                    onToggleCallSpeaker = { callController.toggleSpeaker() },
                    onSimulateIncomingCall = {
                        callController.simulateIncomingCall("+8613800138000")
                        lifecycleScope.launch { snackbarHostState.showSnackbar("已触发模拟来电与系统通知") }
                    },
                    onSimulateOutgoingCall = { num ->
                        callController.simulateOutgoingCall(num)
                    },
                    onDeleteCallLog = { id ->
                        lifecycleScope.launch { callController.deleteCallLog(id) }
                    },
                    onClearCallLogs = {
                        lifecycleScope.launch { callController.clearCallLogs() }
                    },
                    voiceState = voiceState,
                    audioState = audioState,
                    onProbeVoiceRuntime = {
                        lifecycleScope.launch {
                            val uacPresent = modemState.usbDevice?.hasUacInterface == true
                            voiceRuntime.probeReadOnly(uacDetected = uacPresent)
                            audioBridge.refreshDiagnostics()
                        }
                    },
                    onPrepareVoiceRuntime = {
                        lifecycleScope.launch {
                            val uacPresent = modemState.usbDevice?.hasUacInterface == true
                            voiceRuntime.prepareReversibleRuntime(uacDetected = uacPresent).onFailure { err ->
                                snackbarHostState.showSnackbar(err.message ?: "语音运行时准备失败")
                            }
                        }
                    },
                    onToggleVoiceRoute = {
                        lifecycleScope.launch {
                            val res = if (voiceState.bridgePid != null) {
                                voiceRuntime.stopVoiceRoute()
                            } else {
                                voiceRuntime.startVoiceRoute()
                            }
                            res.onFailure { err ->
                                snackbarHostState.showSnackbar(err.message ?: "模块语音路由操作失败")
                            }
                        }
                    },
                    onToggleAudioBridge = {
                        lifecycleScope.launch {
                            if (audioState.isRunning) {
                                audioBridge.stop()
                            } else if (!audioBridge.checkRecordPermission()) {
                                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                audioBridge.start().onFailure { err ->
                                    snackbarHostState.showSnackbar(err.message ?: "UAC 音频桥接启动失败")
                                }
                            }
                        }
                    },
                    onSelectAudioRoute = { route ->
                        audioBridge.applyLocalAudioRoute(route)
                    },
                    configBackupState = configBackupState,
                    onBackupModemConfig = {
                        lifecycleScope.launch {
                            configManager.backupCurrentConfig().fold(
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
                        lifecycleScope.launch {
                            configManager.enableAdbAndUacWithReboot().fold(
                                onSuccess = { msg -> snackbarHostState.showSnackbar(msg) },
                                onFailure = { err -> snackbarHostState.showSnackbar(err.message ?: "开启 ADB+UAC 失败") },
                            )
                        }
                    },
                    onRestoreOriginalUsbCfg = {
                        lifecycleScope.launch {
                            configManager.restoreOriginalUsbCfgWithReboot().fold(
                                onSuccess = { msg -> snackbarHostState.showSnackbar(msg) },
                                onFailure = { err -> snackbarHostState.showSnackbar(err.message ?: "还原原厂 USB 失败") },
                            )
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            (application as DuoPhoneApplication).modemController.onUsbDeviceAttached()
        }
        handleNavigationIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val callController = (application as DuoPhoneApplication).callController
        val action = GamepadKeyRouter.resolve(
            keyCode = keyCode,
            currentTab = selectedTab,
            callPhase = callController.state.value.phase,
        )
        return when (action) {
            is GamepadAction.SwitchTab -> {
                selectedTab = action.tab
                DuoLogger.d(LogCategory.INPUT, "gamepad_nav", "keyCode=$keyCode -> tab=${action.tab.name}")
                true
            }
            GamepadAction.AnswerIncomingCall -> {
                lifecycleScope.launch { callController.answer() }
                DuoLogger.d(LogCategory.INPUT, "gamepad_a", "Answered incoming call")
                true
            }
            GamepadAction.HangupOrRejectCall -> {
                lifecycleScope.launch { callController.hangupOrReject() }
                DuoLogger.d(LogCategory.INPUT, "gamepad_b", "Rejected/hung up call")
                true
            }
            GamepadAction.ToggleMute -> {
                callController.toggleMute()
                DuoLogger.d(LogCategory.INPUT, "gamepad_x", "Toggled mute")
                true
            }
            GamepadAction.ToggleSpeaker -> {
                callController.toggleSpeaker()
                DuoLogger.d(LogCategory.INPUT, "gamepad_y", "Toggled speaker")
                true
            }
            null -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleNavigationIntent(intent: Intent?) {
        when (intent?.getStringExtra("open_tab")) {
            "CALL" -> selectedTab = DuoTab.CALL
            "SMS" -> {
                selectedTab = DuoTab.SMS
                val addr = intent.getStringExtra("sms_address")
                if (!addr.isNullOrBlank()) {
                    selectedSmsAddress = addr
                    lifecycleScope.launch {
                        (application as DuoPhoneApplication).smsController.markThreadRead(addr)
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
