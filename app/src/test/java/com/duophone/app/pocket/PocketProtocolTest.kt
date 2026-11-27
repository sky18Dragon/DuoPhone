package com.duophone.app.pocket

import com.duophone.app.domain.CallUiPhase
import com.duophone.app.domain.CallUiState
import com.duophone.app.domain.ModemConnectionState
import com.duophone.app.domain.ModemUiState
import com.duophone.app.protocol.SignalReading
import com.duophone.app.stage.DeckMenu
import com.duophone.app.stage.GtaStageState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PocketProtocolTest {
    @Test fun snapshotVersionAndInitialization() {
        val snapshot = PocketStateMapper.map(PocketDisplayRole.TOP, GtaStageState(), ModemUiState(), CallUiState(), 4)
        val json = JSONObject(snapshot.json("initialize"))
        assertEquals(1, json.getInt("version"))
        assertEquals("initialize", json.getString("kind"))
        assertEquals("TOP", json.getString("role"))
        assertFalse(json.getBoolean("safeMode"))
        assertEquals(4, json.getInt("unreadCount"))
    }
    @Test fun mappingUsesKotlinTruthAndDisplaysTypedInput() {
        val asciiSnapshot = PocketStateMapper.map(PocketDisplayRole.TOP,
            GtaStageState(activeMenu = DeckMenu.SMS, dialBuffer = "123456789", smsDraftNumber = "10086", smsDraftBody = "Hello 4G"),
            ModemUiState(connectionState = ModemConnectionState.READY, signal = SignalReading(bars = 3)), CallUiState(), 2)
        assertEquals("READY", asciiSnapshot.connection)
        assertEquals(3, asciiSnapshot.signalBars)
        assertEquals("SMS", asciiSnapshot.page)
        assertEquals("123****89", asciiSnapshot.dialDisplay)
        assertEquals("100**", asciiSnapshot.smsTarget)
        assertEquals("Hello 4G", asciiSnapshot.smsDraftPreview)

        val cjkSnapshot = PocketStateMapper.map(PocketDisplayRole.TOP,
            GtaStageState(activeMenu = DeckMenu.SMS, selectedSmsAddress = "13800138000", smsDraftBody = "你好测试"),
            ModemUiState(), CallUiState(), 0)
        assertEquals("138****8000", cjkSnapshot.smsTarget)
        assertEquals("Draft: 4 chars", cjkSnapshot.smsDraftPreview)

        val incomingSnapshot = PocketStateMapper.map(PocketDisplayRole.TOP,
            GtaStageState(activeMenu = DeckMenu.PHONE),
            ModemUiState(), CallUiState(phase = CallUiPhase.INCOMING, remoteNumber = "13812345678"), 0)
        assertEquals("138****5678", incomingSnapshot.dialDisplay)
    }
    @Test fun typedNavigationAndDigitParsing() {
        assertEquals(PocketUiIntent.Navigate(DeckMenu.SMS), PocketProtocol.parse("""{"version":1,"type":"navigate","page":"SMS"}"""))
        assertEquals(PocketUiIntent.DialDigit("#"), PocketProtocol.parse("""{"version":1,"type":"digit","digit":"#"}"""))
    }
    @Test fun invalidIntentRejected() {
        listOf("{}", "not-json", """{"version":1,"type":"ATD"}""",
            """{"version":1,"type":"digit","digit":"12"}""",
            """{"version":1,"type":"digit","digit":";"}""",
            """{"version":1,"type":"navigate","page":"USB"}""",
            """{"version":1,"type":"call","number":"123"}""",
            """{"version":1,"type":"call","type":"back"}""",
            """{"version":1,"type":"back"} trailing""",
            """{"version":1,"type":"back",}""",
            "x".repeat(4097)).forEach { assertNull(it, PocketProtocol.parse(it)) }
    }
    @Test fun versionMustBeExactInteger() {
        listOf("0", "2", "\"1\"", "1.1", "null", "true").forEach {
            assertNull(PocketProtocol.parse("""{"version":$it,"type":"back"}"""))
        }
    }
    @Test fun callHangupAndSmsIntentsDispatchToTarget() {
        val invoked = mutableListOf<String>()
        val dispatcher = PocketIntentDispatcher(target(invoked))
        for ((type, code) in listOf("call" to "DIALING", "hangup" to "HANGUP", "sendSms" to "SMS_SENDING")) {
            val intent = PocketProtocol.parse("""{"version":1,"type":"$type"}""")!!
            assertEquals(code, dispatcher.dispatch(intent))
        }
        assertEquals(listOf("call", "hangup", "sendSms"), invoked)
    }
    @Test fun validIntentDispatchesExactlyOnce() {
        val invoked = mutableListOf<String>()
        val dispatcher = PocketIntentDispatcher(target(invoked))
        dispatcher.dispatch(PocketUiIntent.Navigate(DeckMenu.PHONE))
        dispatcher.dispatch(PocketUiIntent.DialDigit("7"))
        dispatcher.dispatch(PocketUiIntent.Back)
        dispatcher.dispatch(PocketUiIntent.EditDraft)
        assertEquals(listOf("PHONE", "7", "back", "edit"), invoked)
    }
    @Test fun eventHasVersionAndNoPayloadLeak() {
        val event = JSONObject(PocketProtocol.event("SAFE_CALL_BLOCKED"))
        assertEquals(1, event.getInt("version"))
        assertEquals("event", event.getString("kind"))
        assertEquals(setOf("version", "kind", "code"), event.keys().asSequence().toSet())
    }
    private fun target(invoked: MutableList<String>) = object : PocketIntentDispatcher.Target {
        override fun navigate(menu: DeckMenu) { invoked += menu.name }
        override fun digit(digit: String) { invoked += digit }
        override fun backspace() { invoked += "delete" }
        override fun back() { invoked += "back" }
        override fun editDraft() { invoked += "edit" }
        override fun call(): String { invoked += "call"; return "DIALING" }
        override fun hangup(): String { invoked += "hangup"; return "HANGUP" }
        override fun sendSms(): String { invoked += "sendSms"; return "SMS_SENDING" }
    }
}
