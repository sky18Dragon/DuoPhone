package com.duophone.app.domain

import com.duophone.app.data.CallDirection
import com.duophone.app.data.CallLogRecord
import com.duophone.app.data.CallLogStore
import com.duophone.app.data.CallRecordOutcome
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.protocol.AtEngine
import com.duophone.app.protocol.CallAtParser
import com.duophone.app.protocol.CallEndReason
import com.duophone.app.protocol.ClccCallStatus
import com.duophone.app.protocol.UrcEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CallUiPhase(val label: String) {
    IDLE("空闲"),
    DIALING("正在拨号"),
    ALERTING("对方振铃中"),
    INCOMING("来电振铃"),
    ACTIVE("通话已接通"),
    ENDING("正在挂断"),
}

data class CallUiState(
    val phase: CallUiPhase = CallUiPhase.IDLE,
    val remoteNumber: String = "",
    val isIncoming: Boolean = false,
    val isSimulated: Boolean = false,
    val startedAtMs: Long = 0L,
    val connectedAtMs: Long = 0L,
    val elapsedSec: Long = 0L,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val lastError: String? = null,
    val audioWarning: String = "请使用【诊断】页 Phase 4 控制卡独立验收 QDC507 双向语音桥接",
    val dtmfHistory: String = "",
)

/**
 * Manages Phase 3 voice call signaling (ATD/ATA/ATH/+CLCC/+CLIP/+CEER/+VTS),
 * call history persistence, and clearly-labeled local UI simulation when no modem is connected.
 *
 * Strictly decoupled from Phase 4 two-way audio verification while exposing optional audio hooks.
 */
class CallController(
    private val atEngine: AtEngine,
    private val callLogStore: CallLogStore,
    private val onIncomingCallAlert: (String) -> Unit = {},
    private val onIncomingCallDismiss: () -> Unit = {},
    private val onMuteChanged: (Boolean) -> Unit = {},
    private val onSpeakerChanged: (Boolean) -> Unit = {},
    private val onRealCallActive: suspend () -> Unit = {},
    private val onRealCallEnded: suspend () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clccPollIntervalMs: Long = 1200L,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val callMutex = Mutex()
    private var clipConfigured = false
    private var clccPollJob: Job? = null
    private var timerJob: Job? = null
    private var realAudioHookActive = false

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    val callLogs: StateFlow<List<CallLogRecord>> = callLogStore.logs

    init {
        scope.launch { callLogStore.reload() }
        scope.launch {
            atEngine.urcEvents.collect { event ->
                handleUrc(event)
            }
        }
    }

    fun onModemDisconnected() {
        clipConfigured = false
        clccPollJob?.cancel()
        timerJob?.cancel()
        if (!_state.value.isSimulated && _state.value.phase != CallUiPhase.IDLE) {
            onIncomingCallDismiss()
            scope.launch {
                finalizeCallRecord(
                    forcedOutcome = null,
                    reason = "USB 断开",
                )
            }
        }
    }

    suspend fun ensureCallControlReady(): Boolean {
        if (!atEngine.isRunning) return false
        if (clipConfigured) return true
        val res = atEngine.execute("AT+CLIP=1", timeoutMs = 3000, maxRetries = 1)
        clipConfigured = res.isOk
        DuoLogger.i(LogCategory.CALL, "configure_clip", "AT+CLIP=1 ok=${res.isOk}")
        return clipConfigured
    }

    suspend fun dial(rawNumber: String): String? = callMutex.withLock {
        val number = CallAtParser.normalizeDialNumber(rawNumber)
            ?: return@withLock "无效号码：仅允许数字、*、# 和开头的 +"
        if (_state.value.phase != CallUiPhase.IDLE) {
            return@withLock "当前已有通话在进行中"
        }
        if (!atEngine.isRunning) {
            return@withLock "4G 模块未连接，请使用「模拟拨号」测试界面或连接 QDC507"
        }

        ensureCallControlReady()
        val now = System.currentTimeMillis()
        _state.value = CallUiState(
            phase = CallUiPhase.DIALING,
            remoteNumber = number,
            isIncoming = false,
            isSimulated = false,
            startedAtMs = now,
        )

        DuoLogger.i(LogCategory.CALL, "dial", "Dialing ATD$number;")
        // Never auto-retry ATD
        val res = atEngine.execute("ATD$number;", timeoutMs = 15_000L, maxRetries = 0)
        if (!res.isOk) {
            val ceer = queryCeer()
            val detail = buildString {
                append("拨号失败 (${res.errorDetail ?: res.status})")
                if (ceer.isNotBlank()) append(" · ").append(ceer)
            }
            DuoLogger.e(LogCategory.CALL, "dial_failed", detail)
            callLogStore.insert(
                CallLogRecord(
                    number = number,
                    direction = CallDirection.OUTGOING,
                    outcome = CallRecordOutcome.OUTGOING_FAILED,
                    startedAtMs = now,
                    durationSec = 0L,
                    endReason = ceer.ifBlank { res.status.name },
                ),
            )
            _state.value = CallUiState(lastError = detail)
            return@withLock detail
        }

        startClccPolling()
        null
    }

    suspend fun answer(): String? = callMutex.withLock {
        val cur = _state.value
        if (cur.phase != CallUiPhase.INCOMING) {
            return@withLock "当前没有可接听的来电"
        }
        if (cur.isSimulated) {
            onIncomingCallDismiss()
            val now = System.currentTimeMillis()
            _state.update {
                it.copy(
                    phase = CallUiPhase.ACTIVE,
                    connectedAtMs = now,
                    elapsedSec = 0L,
                    lastError = null,
                )
            }
            startElapsedTimer()
            DuoLogger.i(LogCategory.CALL, "sim_answer", "Answered simulated incoming call")
            return@withLock null
        }

        if (!atEngine.isRunning) return@withLock "4G 模块未连接"
        onIncomingCallDismiss()
        val res = atEngine.execute("ATA", timeoutMs = 8000L, maxRetries = 0)
        if (!res.isOk) {
            val detail = "接听失败 (${res.errorDetail ?: res.status})"
            _state.update { it.copy(lastError = detail) }
            return@withLock detail
        }
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                phase = CallUiPhase.ACTIVE,
                connectedAtMs = if (it.connectedAtMs == 0L) now else it.connectedAtMs,
                lastError = null,
            )
        }
        startElapsedTimer()
        startClccPolling()
        null
    }

    suspend fun hangupOrReject(): String? = callMutex.withLock {
        val cur = _state.value
        if (cur.phase == CallUiPhase.IDLE) return@withLock null
        val wasIncoming = cur.phase == CallUiPhase.INCOMING

        if (cur.isSimulated) {
            onIncomingCallDismiss()
            val outcome = when {
                wasIncoming -> CallRecordOutcome.REJECTED
                cur.connectedAtMs > 0L && cur.isIncoming -> CallRecordOutcome.ANSWERED
                cur.connectedAtMs > 0L -> CallRecordOutcome.OUTGOING_CONNECTED
                else -> CallRecordOutcome.OUTGOING_FAILED
            }
            finalizeCallRecord(forcedOutcome = outcome, reason = "[SIMULATED]")
            DuoLogger.i(LogCategory.CALL, "sim_hangup", "Ended simulated call outcome=$outcome")
            return@withLock null
        }

        if (!atEngine.isRunning) {
            onIncomingCallDismiss()
            finalizeCallRecord(forcedOutcome = null, reason = "USB 未连接")
            return@withLock null
        }

        _state.update { it.copy(phase = CallUiPhase.ENDING) }
        onIncomingCallDismiss()
        var res = atEngine.execute("ATH", timeoutMs = 5000L, maxRetries = 0)
        if (!res.isOk) {
            res = atEngine.execute("AT+CHUP", timeoutMs = 5000L, maxRetries = 0)
        }
        val outcome = when {
            wasIncoming -> CallRecordOutcome.REJECTED
            cur.connectedAtMs > 0L && cur.isIncoming -> CallRecordOutcome.ANSWERED
            cur.connectedAtMs > 0L -> CallRecordOutcome.OUTGOING_CONNECTED
            else -> CallRecordOutcome.OUTGOING_FAILED
        }
        finalizeCallRecord(
            forcedOutcome = outcome,
            reason = if (res.isOk) "本端挂断" else "挂断异常 (${res.status})",
        )
        if (!res.isOk) "挂断指令返回异常 (${res.status})" else null
    }

    suspend fun sendDtmf(digit: String): String? {
        val cur = _state.value
        if (cur.phase != CallUiPhase.ACTIVE) return "仅在通话接通后可发送按键音"
        val cmd = CallAtParser.dtmfCommand(digit) ?: return "无效 DTMF 按键：$digit"
        if (cur.isSimulated) {
            _state.update { it.copy(dtmfHistory = (it.dtmfHistory + digit).takeLast(24)) }
            return null
        }
        if (!atEngine.isRunning) return "4G 模块未连接"
        val res = atEngine.execute(cmd, timeoutMs = 3000L, maxRetries = 0)
        if (!res.isOk) return "DTMF 发送失败 (${res.status})"
        _state.update { it.copy(dtmfHistory = (it.dtmfHistory + digit).takeLast(24)) }
        return null
    }

    fun toggleMute() {
        val next = !_state.value.isMuted
        _state.update { it.copy(isMuted = next) }
        onMuteChanged(next)
        DuoLogger.i(LogCategory.AUDIO, "call_mute", "muted=$next")
    }

    fun toggleSpeaker() {
        val next = !_state.value.isSpeakerOn
        _state.update { it.copy(isSpeakerOn = next) }
        onSpeakerChanged(next)
        DuoLogger.i(LogCategory.AUDIO, "call_speaker", "speaker=$next")
    }

    fun simulateIncomingCall(number: String = "+8613800138000") {
        if (atEngine.isRunning || _state.value.phase != CallUiPhase.IDLE) return
        val now = System.currentTimeMillis()
        _state.value = CallUiState(
            phase = CallUiPhase.INCOMING,
            remoteNumber = number,
            isIncoming = true,
            isSimulated = true,
            startedAtMs = now,
        )
        onIncomingCallAlert(number)
        DuoLogger.i(LogCategory.CALL, "sim_incoming", "Started simulated incoming call")
    }

    fun simulateOutgoingCall(rawNumber: String) {
        if (atEngine.isRunning || _state.value.phase != CallUiPhase.IDLE) return
        val number = CallAtParser.normalizeDialNumber(rawNumber) ?: "+8610086"
        val now = System.currentTimeMillis()
        _state.value = CallUiState(
            phase = CallUiPhase.DIALING,
            remoteNumber = number,
            isIncoming = false,
            isSimulated = true,
            startedAtMs = now,
        )
        scope.launch {
            delay(1000L)
            if (_state.value.isSimulated && _state.value.phase == CallUiPhase.DIALING) {
                _state.update { it.copy(phase = CallUiPhase.ALERTING) }
            }
            delay(1200L)
            if (_state.value.isSimulated && _state.value.phase == CallUiPhase.ALERTING) {
                _state.update {
                    it.copy(
                        phase = CallUiPhase.ACTIVE,
                        connectedAtMs = System.currentTimeMillis(),
                    )
                }
                startElapsedTimer()
            }
        }
        DuoLogger.i(LogCategory.CALL, "sim_outgoing", "Started simulated outgoing call")
    }

    suspend fun deleteCallLog(id: Long) = callLogStore.deleteById(id)
    suspend fun clearCallLogs() = callLogStore.clearAll()

    fun clearError() {
        _state.update { it.copy(lastError = null) }
    }

    fun release() {
        clccPollJob?.cancel()
        timerJob?.cancel()
        scope.cancel()
    }

    private suspend fun handleUrc(event: UrcEvent) {
        when (event) {
            is UrcEvent.Ring -> {
                var shouldAlert = false
                callMutex.withLock {
                    if (_state.value.phase == CallUiPhase.IDLE) {
                        _state.value = CallUiState(
                            phase = CallUiPhase.INCOMING,
                            remoteNumber = "",
                            isIncoming = true,
                            isSimulated = false,
                            startedAtMs = System.currentTimeMillis(),
                        )
                        shouldAlert = true
                        startClccPolling()
                    }
                }
                if (shouldAlert) {
                    onIncomingCallAlert(_state.value.remoteNumber)
                }
            }
            is UrcEvent.CallerId -> {
                var refreshNumber: String? = null
                callMutex.withLock {
                    if (_state.value.phase == CallUiPhase.INCOMING && event.number.isNotBlank()) {
                        _state.update { it.copy(remoteNumber = event.number) }
                        refreshNumber = event.number
                    }
                }
                if (refreshNumber != null) {
                    onIncomingCallAlert(refreshNumber)
                }
            }
            is UrcEvent.CallConnected -> {
                callMutex.withLock {
                    if (_state.value.phase in listOf(CallUiPhase.DIALING, CallUiPhase.ALERTING, CallUiPhase.INCOMING)) {
                        val now = System.currentTimeMillis()
                        _state.update {
                            it.copy(
                                phase = CallUiPhase.ACTIVE,
                                connectedAtMs = if (it.connectedAtMs == 0L) now else it.connectedAtMs,
                            )
                        }
                        onIncomingCallDismiss()
                        startElapsedTimer()
                    }
                }
            }
            is UrcEvent.CallEnded -> {
                callMutex.withLock {
                    val cur = _state.value
                    if (cur.phase == CallUiPhase.IDLE || cur.isSimulated) return@withLock
                    onIncomingCallDismiss()
                    val reasonText = when (event.reason) {
                        CallEndReason.REMOTE_HANGUP -> "对端挂断 (NO CARRIER)"
                        CallEndReason.BUSY -> "对方忙线 (BUSY)"
                        CallEndReason.NO_ANSWER -> "无人应答 (NO ANSWER)"
                        CallEndReason.NO_DIAL_TONE -> "无拨号音 (NO DIALTONE)"
                    }
                    val ceer = if (cur.connectedAtMs == 0L) queryCeer() else ""
                    val fullReason = if (ceer.isNotBlank()) "$reasonText · $ceer" else reasonText
                    val outcome = when {
                        cur.connectedAtMs > 0L && cur.isIncoming -> CallRecordOutcome.ANSWERED
                        cur.connectedAtMs > 0L -> CallRecordOutcome.OUTGOING_CONNECTED
                        cur.isIncoming -> CallRecordOutcome.MISSED
                        else -> CallRecordOutcome.OUTGOING_FAILED
                    }
                    finalizeCallRecord(forcedOutcome = outcome, reason = fullReason)
                }
            }
            else -> Unit
        }
    }

    private fun startClccPolling() {
        clccPollJob?.cancel()
        clccPollJob = scope.launch {
            while (isActive && _state.value.phase != CallUiPhase.IDLE && !_state.value.isSimulated) {
                val res = atEngine.execute("AT+CLCC", timeoutMs = 2500L, maxRetries = 0)
                if (res.isOk) {
                    val entries = CallAtParser.parseClccResponse(res.raw)
                    callMutex.withLock {
                        val cur = _state.value
                        if (cur.phase == CallUiPhase.IDLE || cur.isSimulated) return@withLock
                        val primary = entries.firstOrNull()
                        if (primary == null) {
                            if (cur.phase != CallUiPhase.IDLE) {
                                onIncomingCallDismiss()
                                val outcome = when {
                                    cur.connectedAtMs > 0L && cur.isIncoming -> CallRecordOutcome.ANSWERED
                                    cur.connectedAtMs > 0L -> CallRecordOutcome.OUTGOING_CONNECTED
                                    cur.isIncoming -> CallRecordOutcome.MISSED
                                    else -> CallRecordOutcome.OUTGOING_FAILED
                                }
                                finalizeCallRecord(forcedOutcome = outcome, reason = "通话结束")
                            }
                        } else {
                            val rawPhase = when (primary.status) {
                                ClccCallStatus.ACTIVE, ClccCallStatus.HELD -> CallUiPhase.ACTIVE
                                ClccCallStatus.DIALING -> CallUiPhase.DIALING
                                ClccCallStatus.ALERTING -> CallUiPhase.ALERTING
                                ClccCallStatus.INCOMING, ClccCallStatus.WAITING -> CallUiPhase.INCOMING
                                ClccCallStatus.DISCONNECTED -> CallUiPhase.ENDING
                            }
                            // Once a call has connected (e.g. immediately after ATA OK), never regress
                            // backward on a stale in-flight +CLCC sample until DISCONNECTED/empty.
                            val mappedPhase = if (cur.connectedAtMs > 0L && rawPhase != CallUiPhase.ENDING) {
                                CallUiPhase.ACTIVE
                            } else {
                                rawPhase
                            }
                            val connectedAt = if (mappedPhase == CallUiPhase.ACTIVE && cur.connectedAtMs == 0L) {
                                System.currentTimeMillis()
                            } else {
                                cur.connectedAtMs
                            }
                            _state.update {
                                it.copy(
                                    phase = mappedPhase,
                                    remoteNumber = primary.number.ifBlank { it.remoteNumber },
                                    connectedAtMs = connectedAt,
                                )
                            }
                            if (mappedPhase == CallUiPhase.ACTIVE) {
                                onIncomingCallDismiss()
                                startElapsedTimer()
                            }
                        }
                    }
                }
                delay(clccPollIntervalMs)
            }
        }
    }

    private fun startElapsedTimer() {
        if (!_state.value.isSimulated && !realAudioHookActive) {
            realAudioHookActive = true
            scope.launch { runCatching { onRealCallActive() } }
        }
        if (timerJob?.isActive == true) return
        timerJob = scope.launch {
            while (isActive && _state.value.phase == CallUiPhase.ACTIVE) {
                val start = _state.value.connectedAtMs
                if (start > 0L) {
                    val sec = ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0L)
                    _state.update { it.copy(elapsedSec = sec) }
                }
                delay(1000L)
            }
        }
    }

    private suspend fun queryCeer(): String {
        if (!atEngine.isRunning) return ""
        val res = atEngine.execute("AT+CEER", timeoutMs = 2500L, maxRetries = 0)
        return if (res.isOk) CallAtParser.parseCeerResponse(res.raw) else ""
    }

    private suspend fun finalizeCallRecord(forcedOutcome: CallRecordOutcome?, reason: String) {
        clccPollJob?.cancel()
        clccPollJob = null
        timerJob?.cancel()
        timerJob = null
        if (realAudioHookActive) {
            realAudioHookActive = false
            scope.launch { runCatching { onRealCallEnded() } }
        }

        val cur = _state.value
        val now = System.currentTimeMillis()
        val duration = if (cur.connectedAtMs > 0L) {
            ((now - cur.connectedAtMs) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        val outcome = forcedOutcome ?: when {
            duration > 0L && cur.isIncoming -> CallRecordOutcome.ANSWERED
            duration > 0L -> CallRecordOutcome.OUTGOING_CONNECTED
            cur.isIncoming -> CallRecordOutcome.MISSED
            else -> CallRecordOutcome.OUTGOING_FAILED
        }
        if (cur.startedAtMs > 0L) {
            callLogStore.insert(
                CallLogRecord(
                    number = cur.remoteNumber.ifBlank { "未知号码" },
                    direction = if (cur.isIncoming) CallDirection.INCOMING else CallDirection.OUTGOING,
                    outcome = outcome,
                    startedAtMs = cur.startedAtMs,
                    durationSec = duration,
                    endReason = reason,
                ),
            )
        }
        val err = if (outcome == CallRecordOutcome.OUTGOING_FAILED && reason.isNotBlank() && reason != "[SIMULATED]") {
            reason
        } else {
            null
        }
        _state.value = CallUiState(lastError = err)
    }
}
