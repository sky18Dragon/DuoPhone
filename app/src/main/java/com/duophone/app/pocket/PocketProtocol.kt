package com.duophone.app.pocket

import com.duophone.app.domain.CallUiPhase
import com.duophone.app.domain.CallUiState
import com.duophone.app.domain.ModemUiState
import com.duophone.app.stage.DeckMenu
import com.duophone.app.stage.GtaStageState
import com.duophone.app.stage.TopScreenPhoneMasker
import org.json.JSONObject
import org.json.JSONTokener

const val POCKET_PROTOCOL_VERSION = 1

enum class PocketDisplayRole { TOP, BOTTOM }

/** UI projection for PocketJS display: no raw identity, Binder, or mutable core object. */
data class PocketSnapshot(
    val role: PocketDisplayRole,
    val connection: String,
    val signalBars: Int,
    val sim: String,
    val call: String,
    val page: String,
    val screen: String,
    val dialDisplay: String,
    val draftLength: Int,
    val unreadCount: Int,
    val loading: Boolean,
    val smsTarget: String = "",
    val smsDraftPreview: String = "",
) {
    fun json(kind: String = "state"): String {
        require(kind == "initialize" || kind == "state")
        return JSONObject().put("version", POCKET_PROTOCOL_VERSION).put("kind", kind)
            .put("role", role.name).put("connection", connection).put("signalBars", signalBars)
            .put("sim", sim).put("call", call).put("page", page).put("screen", screen)
            .put("dialDisplay", dialDisplay).put("draftLength", draftLength)
            .put("unreadCount", unreadCount).put("loading", loading)
            .put("smsTarget", smsTarget).put("smsDraftPreview", smsDraftPreview)
            .put("safeMode", false).toString()
    }
}

object PocketStateMapper {
    fun map(role: PocketDisplayRole, stage: GtaStageState, modem: ModemUiState,
            call: CallUiState, unreadCount: Int): PocketSnapshot {
        val rawDialOrRemote = if (call.phase != CallUiPhase.IDLE && call.remoteNumber.isNotBlank()) {
            call.remoteNumber
        } else {
            stage.dialBuffer
        }
        val rawSmsTarget = (stage.selectedSmsAddress?.takeIf { it.isNotBlank() } ?: stage.smsDraftNumber).trim()
        val normalizedDraft = stage.smsDraftBody.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        val smsDraftPreview = when {
            stage.smsDraftBody.isEmpty() -> ""
            normalizedDraft.isNotEmpty() && normalizedDraft.all { it.code in 32..126 } -> normalizedDraft.take(80)
            else -> "Draft: ${stage.smsDraftBody.length} chars"
        }
        return PocketSnapshot(
            role = role,
            connection = modem.connectionState.name,
            signalBars = modem.signal.bars.coerceIn(0, 5),
            sim = modem.simState.name,
            call = call.phase.name,
            page = stage.activeMenu.name,
            screen = stage.screenState.name,
            dialDisplay = TopScreenPhoneMasker.mask(rawDialOrRemote),
            draftLength = stage.smsDraftBody.length,
            unreadCount = unreadCount.coerceAtLeast(0),
            loading = modem.isBusy,
            smsTarget = TopScreenPhoneMasker.mask(rawSmsTarget),
            smsDraftPreview = smsDraftPreview,
        )
    }
}

sealed interface PocketUiIntent {
    data class Navigate(val menu: DeckMenu) : PocketUiIntent
    data class DialDigit(val digit: String) : PocketUiIntent
    data object Backspace : PocketUiIntent
    data object Back : PocketUiIntent
    data object RequestCall : PocketUiIntent
    data object RequestHangup : PocketUiIntent
    data object RequestSendSms : PocketUiIntent
    data object EditDraft : PocketUiIntent
}

object PocketProtocol {
    fun parse(raw: String): PocketUiIntent? = runCatching {
        require(raw.length <= 4096)
        // Flat closed schema; reject duplicates, trailing data and lenient JSON coercions
        // consistently on Android's JSONTokener and the JVM test implementation.
        val reader = JSONTokener(raw)
        val fields = mutableMapOf<String, Any>()
        require(reader.nextClean() == '{')
        while (true) {
            require(reader.nextClean() == '"')
            val key = reader.nextString('"')
            require(key !in fields && reader.nextClean() == ':')
            fields[key] = when (reader.nextClean()) {
                '"' -> reader.nextString('"')
                '1' -> 1
                else -> error("unsupported scalar")
            }
            when (reader.nextClean()) {
                '}' -> break
                ',' -> Unit
                else -> error("invalid separator")
            }
        }
        require(reader.nextClean() == '\u0000')
        require(fields["version"] == POCKET_PROTOCOL_VERSION)
        val type = fields["type"] as String
        val expected = when (type) { "navigate" -> setOf("version", "type", "page")
            "digit" -> setOf("version", "type", "digit")
            else -> setOf("version", "type") }
        require(fields.keys == expected)
        when (type) {
            "navigate" -> PocketUiIntent.Navigate(DeckMenu.valueOf(fields["page"] as String))
            "digit" -> (fields["digit"] as String).let {
                require(it.length == 1 && it[0] in "0123456789*#")
                PocketUiIntent.DialDigit(it)
            }
            "backspace" -> PocketUiIntent.Backspace
            "back" -> PocketUiIntent.Back
            "call" -> PocketUiIntent.RequestCall
            "hangup" -> PocketUiIntent.RequestHangup
            "sendSms" -> PocketUiIntent.RequestSendSms
            "editDraft" -> PocketUiIntent.EditDraft
            else -> error("unknown intent")
        }
    }.getOrNull()

    fun event(code: String): String = JSONObject().put("version", POCKET_PROTOCOL_VERSION)
        .put("kind", "event").put("code", code).toString()
}

/** Dispatches validated PocketJS UI intents to Kotlin business targets. */
class PocketIntentDispatcher(private val target: Target) {
    interface Target {
        fun navigate(menu: DeckMenu)
        fun digit(digit: String)
        fun backspace()
        fun back()
        fun editDraft()
        fun call(): String
        fun hangup(): String
        fun sendSms(): String
    }
    fun dispatch(intent: PocketUiIntent): String = when (intent) {
        is PocketUiIntent.Navigate -> { target.navigate(intent.menu); "NAVIGATED" }
        is PocketUiIntent.DialDigit -> { target.digit(intent.digit); "DIGIT_UPDATED" }
        PocketUiIntent.Backspace -> { target.backspace(); "DIAL_UPDATED" }
        PocketUiIntent.Back -> { target.back(); "BACK" }
        PocketUiIntent.EditDraft -> { target.editDraft(); "IME_NATIVE_FALLBACK" }
        PocketUiIntent.RequestCall -> target.call()
        PocketUiIntent.RequestHangup -> target.hangup()
        PocketUiIntent.RequestSendSms -> target.sendSms()
    }
}
