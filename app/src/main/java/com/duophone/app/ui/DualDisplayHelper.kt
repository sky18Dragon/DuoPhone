package com.duophone.app.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.KeyEvent
import com.duophone.app.SecondaryDeckActivity
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.domain.CallUiPhase
import com.duophone.app.stage.DeckMenu

data class DisplayDescriptor(
    val displayId: Int,
    val name: String,
    val isDefault: Boolean,
)

object DualDisplayHelper {
    fun selectSecondaryDisplayId(displays: List<DisplayDescriptor>): Int? =
        displays.firstOrNull { !it.isDefault && it.displayId != Display.DEFAULT_DISPLAY }?.displayId

    fun findSecondaryDisplayId(context: Context): Int? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val descriptors = dm.displays.map {
            DisplayDescriptor(
                displayId = it.displayId,
                name = it.name.orEmpty(),
                isDefault = it.displayId == Display.DEFAULT_DISPLAY,
            )
        }
        return selectSecondaryDisplayId(descriptors)
    }

    fun launchSecondaryDeck(activity: Activity): Boolean {
        val secondaryId = findSecondaryDisplayId(activity)
        if (secondaryId == null) {
            DuoLogger.w(LogCategory.DISPLAY, "launch_secondary", "No secondary display available; staying single-screen")
            return false
        }
        return runCatching {
            val intent = Intent(activity, SecondaryDeckActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(secondaryId)
            activity.startActivity(intent, options.toBundle())
            DuoLogger.i(LogCategory.DISPLAY, "launch_secondary", "Launched SecondaryDeckActivity on displayId=$secondaryId")
            true
        }.getOrElse { err ->
            DuoLogger.e(
                LogCategory.DISPLAY,
                "launch_secondary_failed",
                "Failed to launch SecondaryDeckActivity on displayId=$secondaryId",
                throwable = err,
            )
            false
        }
    }
}

sealed interface GamepadAction {
    data class SwitchTab(val tab: DuoTab) : GamepadAction
    data class SwitchDeckMenu(val menu: DeckMenu) : GamepadAction
    data object AnswerIncomingCall : GamepadAction
    data object HangupOrRejectCall : GamepadAction
    data object ToggleMute : GamepadAction
    data object ToggleSpeaker : GamepadAction
}

object GamepadKeyRouter {
    fun resolve(
        keyCode: Int,
        currentTab: DuoTab,
        callPhase: CallUiPhase,
    ): GamepadAction? {
        val tabs = DuoTab.entries
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                val prev = (currentTab.ordinal - 1 + tabs.size) % tabs.size
                GamepadAction.SwitchTab(tabs[prev])
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                val next = (currentTab.ordinal + 1) % tabs.size
                GamepadAction.SwitchTab(tabs[next])
            }
            KeyEvent.KEYCODE_BUTTON_START -> GamepadAction.SwitchTab(DuoTab.CALL)
            KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadAction.SwitchTab(DuoTab.SMS)
            KeyEvent.KEYCODE_BUTTON_A -> if (callPhase == CallUiPhase.INCOMING) GamepadAction.AnswerIncomingCall else null
            KeyEvent.KEYCODE_BUTTON_B -> if (callPhase != CallUiPhase.IDLE) GamepadAction.HangupOrRejectCall else null
            KeyEvent.KEYCODE_BUTTON_X -> if (callPhase == CallUiPhase.ACTIVE) GamepadAction.ToggleMute else null
            KeyEvent.KEYCODE_BUTTON_Y -> if (callPhase == CallUiPhase.ACTIVE) GamepadAction.ToggleSpeaker else null
            else -> null
        }
    }

    fun resolveDeckMenu(
        keyCode: Int,
        currentMenu: DeckMenu,
        callPhase: CallUiPhase,
    ): GamepadAction? {
        val menus = DeckMenu.entries
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                val prev = (currentMenu.ordinal - 1 + menus.size) % menus.size
                GamepadAction.SwitchDeckMenu(menus[prev])
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                val next = (currentMenu.ordinal + 1) % menus.size
                GamepadAction.SwitchDeckMenu(menus[next])
            }
            KeyEvent.KEYCODE_BUTTON_START -> GamepadAction.SwitchDeckMenu(DeckMenu.PHONE)
            KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadAction.SwitchDeckMenu(DeckMenu.SMS)
            KeyEvent.KEYCODE_BUTTON_A -> if (callPhase == CallUiPhase.INCOMING) GamepadAction.AnswerIncomingCall else null
            KeyEvent.KEYCODE_BUTTON_B -> if (callPhase != CallUiPhase.IDLE) GamepadAction.HangupOrRejectCall else null
            KeyEvent.KEYCODE_BUTTON_X -> if (callPhase == CallUiPhase.ACTIVE) GamepadAction.ToggleMute else null
            KeyEvent.KEYCODE_BUTTON_Y -> if (callPhase == CallUiPhase.ACTIVE) GamepadAction.ToggleSpeaker else null
            else -> null
        }
    }
}
