package com.duophone.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.ui.DuoPhoneTheme
import com.duophone.app.ui.DuoTab
import com.duophone.app.ui.GamepadAction
import com.duophone.app.ui.GamepadKeyRouter
import com.duophone.app.ui.SecondaryDeckScreen
import kotlinx.coroutines.launch

/**
 * Interactive deck activity designed for Retroid Pocket Duo's secondary internal display (Display #2, 1280x960).
 */
class SecondaryDeckActivity : ComponentActivity() {
    private val snackbarHostState = SnackbarHostState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val currentDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.displayId
        }
        DuoLogger.i(LogCategory.DISPLAY, "secondary_create", "SecondaryDeckActivity created on displayId=$currentDisplayId")

        val app = application as DuoPhoneApplication
        val modemController = app.modemController
        val callController = app.callController
        val smsController = app.smsController
        val contactRepository = app.contactRepository
        val audioBridge = app.audioBridge

        setContent {
            DuoPhoneTheme {
                val modemState by modemController.state.collectAsStateWithLifecycle()
                val callState by callController.state.collectAsStateWithLifecycle()
                val contacts by contactRepository.contacts.collectAsStateWithLifecycle()
                val audioState by audioBridge.state.collectAsStateWithLifecycle()

                SecondaryDeckScreen(
                    displayId = currentDisplayId,
                    modemState = modemState,
                    callState = callState,
                    snackbarHostState = snackbarHostState,
                    onDial = { number ->
                        lifecycleScope.launch {
                            callController.dial(number)?.let { err -> snackbarHostState.showSnackbar(err) }
                        }
                    },
                    onAnswer = {
                        lifecycleScope.launch {
                            callController.answer()?.let { err -> snackbarHostState.showSnackbar(err) }
                        }
                    },
                    onHangupOrReject = {
                        lifecycleScope.launch {
                            callController.hangupOrReject()?.let { err -> snackbarHostState.showSnackbar(err) }
                        }
                    },
                    onSendDtmf = { tone ->
                        lifecycleScope.launch {
                            callController.sendDtmf(tone)?.let { err -> snackbarHostState.showSnackbar(err) }
                        }
                    },
                    onToggleMute = { callController.toggleMute() },
                    onToggleSpeaker = { callController.toggleSpeaker() },
                    onSendSms = { number, body ->
                        lifecycleScope.launch {
                            val res = smsController.sendSms(number, body)
                            val msg = if (res.success) "短信已发送" else (res.errorMessage ?: "发送失败")
                            snackbarHostState.showSnackbar(msg)
                        }
                    },
                    onRunAt = { cmd ->
                        lifecycleScope.launch { modemController.runRawAtCommand(cmd) }
                    },
                    onSimulateIncomingCall = {
                        callController.simulateIncomingCall("+8613800138000")
                    },
                    onCloseDeck = { finish() },
                    contacts = contacts,
                    audioState = audioState,
                    onSelectAudioRoute = { route -> audioBridge.applyLocalAudioRoute(route) },
                    onAdjustCallVolume = { delta -> audioBridge.adjustCallVolume(delta) },
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val callController = (application as DuoPhoneApplication).callController
        val action = GamepadKeyRouter.resolve(
            keyCode = keyCode,
            currentTab = DuoTab.CALL,
            callPhase = callController.state.value.phase,
        )
        return when (action) {
            GamepadAction.AnswerIncomingCall -> {
                lifecycleScope.launch { callController.answer() }
                DuoLogger.d(LogCategory.INPUT, "deck_gamepad_a", "Answered incoming call")
                true
            }
            GamepadAction.HangupOrRejectCall -> {
                lifecycleScope.launch { callController.hangupOrReject() }
                DuoLogger.d(LogCategory.INPUT, "deck_gamepad_b", "Rejected/hung up call")
                true
            }
            GamepadAction.ToggleMute -> {
                callController.toggleMute()
                true
            }
            GamepadAction.ToggleSpeaker -> {
                callController.toggleSpeaker()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
