package com.duophone.app

import android.Manifest
import android.graphics.Color
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
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.duophone.app.diagnostics.DeviceDiagnostics
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.HostHardwareSnapshot
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.ui.DualDisplayHelper
import com.duophone.app.ui.GamepadAction
import com.duophone.app.ui.GamepadKeyRouter
import com.duophone.app.ui.gta.DeckConsoleHost
import kotlinx.coroutines.launch

/**
 * Interactive GTA 6 handheld deck activity designed for Retroid Pocket Duo's secondary internal display
 * (Display #2, 1280x960 4:3). Hosts Phone / SMS / Settings menus and real-time calibration controls.
 */
class SecondaryDeckActivity : ComponentActivity() {
    private val snackbarHostState = SnackbarHostState()
    private var hardwareState by mutableStateOf<HostHardwareSnapshot?>(null)
    private var hasContactsPermState by mutableStateOf(false)
    private var pendingStartAudioBridgeAfterPermission = false

    private val readContactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasContactsPermState = granted
        DuoLogger.i(LogCategory.SERVICE, "deck_contacts_permission", "READ_CONTACTS granted=$granted")
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
        DuoLogger.i(LogCategory.AUDIO, "deck_record_audio_permission", "RECORD_AUDIO granted=$granted")
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
                    if (granted) "已授予麦克风录音权限" else "未授予 RECORD_AUDIO 权限",
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as DuoPhoneApplication
        app.audioBridge.refreshDiagnostics()
        hasContactsPermState = app.contactRepository.hasSystemContactsPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        val currentDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.displayId
        }
        DuoLogger.i(LogCategory.DISPLAY, "secondary_create", "SecondaryDeckActivity created on displayId=$currentDisplayId")

        val app = application as DuoPhoneApplication
        hasContactsPermState = app.contactRepository.hasSystemContactsPermission()
        val initialHw = DeviceDiagnostics.capture(this)
        hardwareState = initialHw

        setContent {
            val hw = hardwareState ?: initialHw
            DeckConsoleHost(
                app = app,
                displayId = currentDisplayId,
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
                onRelaunchDualDisplay = {
                    if (!DualDisplayHelper.launchSecondaryDeck(this@SecondaryDeckActivity)) {
                        lifecycleScope.launch {
                            snackbarHostState.showSnackbar("当前未检测到副屏或已处于当前屏幕")
                        }
                    }
                },
            )
        }
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
                DuoLogger.d(LogCategory.INPUT, "deck_gamepad_nav", "keyCode=$keyCode -> menu=${action.menu.name}")
                true
            }
            GamepadAction.AnswerIncomingCall -> {
                app.gtaStageCoordinator.onAnswerOrHangupVisualFeedback(isAnswer = true)
                lifecycleScope.launch { app.callController.answer() }
                DuoLogger.d(LogCategory.INPUT, "deck_gamepad_a", "Answered incoming call")
                true
            }
            GamepadAction.HangupOrRejectCall -> {
                app.gtaStageCoordinator.onAnswerOrHangupVisualFeedback(isAnswer = false)
                lifecycleScope.launch { app.callController.hangupOrReject() }
                DuoLogger.d(LogCategory.INPUT, "deck_gamepad_b", "Rejected/hung up call")
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
}
