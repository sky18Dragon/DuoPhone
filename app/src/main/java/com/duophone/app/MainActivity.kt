package com.duophone.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Display
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.duophone.app.diagnostics.DeviceDiagnostics
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.HostHardwareSnapshot
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.service.ModemService
import com.duophone.app.ui.DualDisplayHelper
import com.duophone.app.ui.GamepadAction
import com.duophone.app.ui.GamepadKeyRouter
import com.duophone.app.ui.gta.DeckConsoleHost
import com.duophone.app.ui.gta.GtaPhoneTheme
import com.duophone.app.ui.gta.TopStageScreen
import kotlinx.coroutines.launch

/**
 * Top immersive GTA 6 visual stage (Display#0, 1920x1080 landscape).
 * Automatically pairs with SecondaryDeckActivity on Retroid Pocket Duo's Display#2,
 * and seamlessly offers a single-screen toggle when no secondary display is present.
 */
class MainActivity : ComponentActivity() {

    private lateinit var hostHardware: HostHardwareSnapshot
    private var hardwareState by mutableStateOf<HostHardwareSnapshot?>(null)
    private var hasContactsPermState by mutableStateOf(false)
    private var pendingStartAudioBridgeAfterPermission = false
    private var singleScreenShowDeck by mutableStateOf(false)
    private var secondaryAutoLaunched = false
    private val snackbarHostState = SnackbarHostState()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        DuoLogger.i(LogCategory.SERVICE, "notif_permission", "POST_NOTIFICATIONS granted=$granted")
    }

    private val readContactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasContactsPermState = granted
        DuoLogger.i(LogCategory.SERVICE, "contacts_permission", "READ_CONTACTS granted=$granted")
        lifecycleScope.launch {
            if (granted) {
                val repo = (application as DuoPhoneApplication).contactRepository
                repo.reload()
                snackbarHostState.showSnackbar("已导入系统通讯录（共 ${repo.contacts.value.size} 位联系人）")
            } else {
                snackbarHostState.showSnackbar("未授予通讯录权限，仍可使用本地通讯录")
            }
        }
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        DuoLogger.i(LogCategory.AUDIO, "record_audio_permission", "RECORD_AUDIO granted=$granted")
        val app = application as DuoPhoneApplication
        app.audioBridge.refreshDiagnostics()
        val shouldAutoStart = granted && pendingStartAudioBridgeAfterPermission
        pendingStartAudioBridgeAfterPermission = false
        lifecycleScope.launch {
            if (shouldAutoStart) {
                app.audioBridge.start().fold(
                    onSuccess = { snackbarHostState.showSnackbar("麦克风权限已就绪，已自动启动 UAC 双向音频桥接") },
                    onFailure = { err -> snackbarHostState.showSnackbar(err.message ?: "UAC 音频桥接启动失败") },
                )
            } else {
                snackbarHostState.showSnackbar(
                    if (granted) "已授予麦克风录音权限，可测试 Phase 4 音频桥接" else "未授予 RECORD_AUDIO 权限，仅可执行呼叫控制与只读探测",
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as DuoPhoneApplication
        app.audioBridge.refreshDiagnostics()
        hasContactsPermState = app.contactRepository.hasSystemContactsPermission()
        configureImmersiveSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        configureImmersiveSystemBars()

        val app = application as DuoPhoneApplication
        hasContactsPermState = app.contactRepository.hasSystemContactsPermission()
        handleNavigationIntent(intent)
        requestNotificationPermissionIfNeeded()
        ModemService.start(this, triggerConnect = true)

        hostHardware = DeviceDiagnostics.capture(this)
        hardwareState = hostHardware

        if (!secondaryAutoLaunched && DualDisplayHelper.findSecondaryDisplayId(this) != null) {
            secondaryAutoLaunched = true
            DualDisplayHelper.launchSecondaryDeck(this)
        }

        setContent {
            val hw = hardwareState ?: hostHardware
            val hasSecondaryDisplay = remember(hw) {
                DualDisplayHelper.selectSecondaryDisplayId(
                    hw.displays.map {
                        com.duophone.app.ui.DisplayDescriptor(it.displayId, it.name, it.isDefault)
                    },
                ) != null
            }

            if (!hasSecondaryDisplay && singleScreenShowDeck) {
                DeckConsoleHost(
                    app = app,
                    displayId = Display.DEFAULT_DISPLAY,
                    hostHardware = hw,
                    hasContactsPermission = hasContactsPermState,
                    snackbarHostState = snackbarHostState,
                    onRefreshHostHardware = { hardwareState = DeviceDiagnostics.capture(this) },
                    onRequestContactsPermission = {
                        if (app.contactRepository.hasSystemContactsPermission()) {
                            lifecycleScope.launch {
                                app.contactRepository.reload()
                                snackbarHostState.showSnackbar("已同步系统通讯录（共 ${app.contactRepository.contacts.value.size} 条）")
                            }
                        } else {
                            readContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                    },
                    onRequestRecordAudioPermissionAndStart = {
                        pendingStartAudioBridgeAfterPermission = true
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onExitSingleScreenDeck = { singleScreenShowDeck = false },
                )
            } else {
                GtaPhoneTheme {
                    val stageState by app.gtaStageCoordinator.state.collectAsStateWithLifecycle()
                    val modemState by app.modemController.state.collectAsStateWithLifecycle()
                    val callState by app.callController.state.collectAsStateWithLifecycle()
                    val audioState by app.audioBridge.state.collectAsStateWithLifecycle()
                    val smsMessages by app.smsController.messages.collectAsStateWithLifecycle()
                    val smsThreads = remember(smsMessages) { app.smsRepository.buildThreads(smsMessages) }
                    val contacts by app.contactRepository.contacts.collectAsStateWithLifecycle()

                    com.duophone.app.pocket.PocketTopContent(
                        app = app,
                        onOpenSingleScreenDeck = if (!hasSecondaryDisplay) ({ singleScreenShowDeck = true }) else null,
                    ) {
                        TopStageScreen(
                            stageState = stageState,
                            events = app.gtaStageCoordinator.events,
                            modemState = modemState,
                            callState = callState,
                            audioState = audioState,
                            smsMessages = smsMessages,
                            smsThreads = smsThreads,
                            contacts = contacts,
                            onOpeningFinished = { app.gtaStageCoordinator.onOpeningFinished() },
                            onSkipOpening = { app.gtaStageCoordinator.skipOpening() },
                            hasSecondaryDisplay = hasSecondaryDisplay,
                            singleScreenPreviewDeck = singleScreenShowDeck,
                            onToggleSingleScreenDeck = { singleScreenShowDeck = !singleScreenShowDeck },
                        )
                    }
                }
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
        val app = application as DuoPhoneApplication
        val action = GamepadKeyRouter.resolveDeckMenu(
            keyCode = keyCode,
            currentMenu = app.gtaStageCoordinator.state.value.activeMenu,
            callPhase = app.callController.state.value.phase,
        )
        return when (action) {
            is GamepadAction.SwitchDeckMenu -> {
                app.gtaStageCoordinator.selectMenu(action.menu)
                DuoLogger.d(LogCategory.INPUT, "stage_gamepad_nav", "keyCode=$keyCode -> menu=${action.menu.name}")
                true
            }
            GamepadAction.AnswerIncomingCall -> {
                app.gtaStageCoordinator.onAnswerOrHangupVisualFeedback(isAnswer = true)
                lifecycleScope.launch { app.callController.answer() }
                true
            }
            GamepadAction.HangupOrRejectCall -> {
                app.gtaStageCoordinator.onAnswerOrHangupVisualFeedback(isAnswer = false)
                lifecycleScope.launch { app.callController.hangupOrReject() }
                true
            }
            GamepadAction.ToggleMute -> {
                app.callController.toggleMute()
                true
            }
            GamepadAction.ToggleSpeaker -> {
                app.callController.toggleSpeaker()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleNavigationIntent(intent: Intent?) {
        val tab = intent?.getStringExtra("open_tab")
        val smsAddr = intent?.getStringExtra("sms_address")
        if (tab != null) {
            (application as DuoPhoneApplication).gtaStageCoordinator.navigateFromNotification(tab, smsAddr)
            if (tab == "SMS" && !smsAddr.isNullOrBlank()) {
                lifecycleScope.launch {
                    (application as DuoPhoneApplication).smsController.markThreadRead(smsAddr)
                }
            }
        }
    }

    private fun configureImmersiveSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
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
