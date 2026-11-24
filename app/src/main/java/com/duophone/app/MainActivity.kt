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
import com.duophone.app.ui.DuoPhoneScreen
import com.duophone.app.ui.DuoPhoneTheme
import com.duophone.app.ui.DuoTab
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

                DuoPhoneScreen(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    modemState = modemState,
                    hostHardware = hardwareState ?: hostHardware,
                    logs = logs,
                    smsMessages = smsMessages,
                    smsThreads = smsThreads,
                    isSmsSyncing = isSmsSyncing,
                    smsSyncStatus = smsSyncStatus,
                    selectedSmsAddress = selectedSmsAddress,
                    callState = callState,
                    callLogs = callLogs,
                    snackbarHostState = snackbarHostState,
                    onConnect = { lifecycleScope.launch { controller.connect() } },
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
        val tabs = DuoTab.entries
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                val next = (selectedTab.ordinal - 1 + tabs.size) % tabs.size
                selectedTab = tabs[next]
                DuoLogger.d(LogCategory.INPUT, "gamepad_l1", "Switched tab -> ${selectedTab.name}")
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                val next = (selectedTab.ordinal + 1) % tabs.size
                selectedTab = tabs[next]
                DuoLogger.d(LogCategory.INPUT, "gamepad_r1", "Switched tab -> ${selectedTab.name}")
                true
            }
            KeyEvent.KEYCODE_BUTTON_START -> {
                selectedTab = DuoTab.CALL
                DuoLogger.d(LogCategory.INPUT, "gamepad_start", "Switched tab -> CALL")
                true
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                selectedTab = DuoTab.SMS
                DuoLogger.d(LogCategory.INPUT, "gamepad_select", "Switched tab -> SMS")
                true
            }
            else -> super.onKeyDown(keyCode, event)
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
