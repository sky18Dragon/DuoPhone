package com.duophone.app.ui

import android.view.KeyEvent
import com.duophone.app.domain.CallUiPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamepadKeyRouterTest {

    @Test
    fun selectsSecondaryDisplayAndFallsBackWhenSingleScreen() {
        val single = listOf(DisplayDescriptor(0, "Built-in", isDefault = true))
        assertNull(DualDisplayHelper.selectSecondaryDisplayId(single))

        val dual = listOf(
            DisplayDescriptor(0, "Built-in", isDefault = true),
            DisplayDescriptor(2, "Secondary", isDefault = false),
        )
        assertEquals(2, DualDisplayHelper.selectSecondaryDisplayId(dual))
    }

    @Test
    fun routesNavigationAndContextSensitiveCallKeys() {
        assertEquals(
            GamepadAction.SwitchTab(DuoTab.CALL),
            GamepadKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_START, DuoTab.STATUS, CallUiPhase.IDLE),
        )
        assertEquals(
            GamepadAction.SwitchTab(DuoTab.SMS),
            GamepadKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_SELECT, DuoTab.STATUS, CallUiPhase.IDLE),
        )
        assertEquals(
            GamepadAction.SwitchTab(DuoTab.CALL),
            GamepadKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_R1, DuoTab.STATUS, CallUiPhase.IDLE),
        )
        assertEquals(
            GamepadAction.SwitchTab(DuoTab.DIAGNOSTICS),
            GamepadKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_L1, DuoTab.STATUS, CallUiPhase.IDLE),
        )

        // A/B/X/Y behavior changes safely based on CallUiPhase
        assertNull(GamepadKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_A, DuoTab.CALL, CallUiPhase.IDLE))
        assertEquals(
            GamepadAction.AnswerIncomingCall,
            GamepadKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_A, DuoTab.CALL, CallUiPhase.INCOMING),
        )
        assertEquals(
            GamepadAction.HangupOrRejectCall,
            GamepadKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_B, DuoTab.CALL, CallUiPhase.INCOMING),
        )
        assertEquals(
            GamepadAction.ToggleMute,
            GamepadKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_X, DuoTab.CALL, CallUiPhase.ACTIVE),
        )
        assertEquals(
            GamepadAction.ToggleSpeaker,
            GamepadKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_Y, DuoTab.CALL, CallUiPhase.ACTIVE),
        )
    }
}
