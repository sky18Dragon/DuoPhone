package com.duophone.app.stage

import com.duophone.app.audio.GtaUiSoundEffects
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.domain.CallUiPhase
import com.duophone.app.domain.CallUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single Source of Truth synchronizing Retroid Pocket Duo's top visual stage (MainActivity / Display#0)
 * and bottom handheld control deck (SecondaryDeckActivity / Display#2).
 */
class GtaStageCoordinator(
    private val callStateFlow: StateFlow<CallUiState>,
    private val configStore: StageConfigStore? = null,
    private val soundEffects: GtaUiSoundEffects? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    ioDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var bannerDismissJob: Job? = null
    private var smsKeyCycleIndex = 0
    private var lastCallPhase: CallUiPhase = callStateFlow.value.phase

    private val _state: MutableStateFlow<GtaStageState>
    val state: StateFlow<GtaStageState>

    private val _events = MutableSharedFlow<InteractionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<InteractionEvent> = _events.asSharedFlow()

    init {
        val loadedConfig = configStore?.loadConfig() ?: GtaStageConfig.default()
        val resolvedAssets = configStore?.rescanAssets(loadedConfig.assets) ?: ResolvedStageAssets()
        val initialOpening = if (resolvedAssets.hasAnyOpeningVideo()) {
            OpeningPhase.PLAYING_VIDEO
        } else {
            OpeningPhase.FALLBACK_ANIMATION
        }
        val initialSound = configStore?.loadSoundEnabled() ?: true
        val initialHaptic = configStore?.loadHapticEnabled() ?: true
        val initialTheme = configStore?.loadThemeMode() ?: StageThemeMode.SYSTEM

        _state = MutableStateFlow(
            GtaStageState(
                openingPhase = initialOpening,
                activeMenu = DeckMenu.PHONE,
                screenState = resolveScreenState(
                    openingPhase = initialOpening,
                    activeMenu = DeckMenu.PHONE,
                    callPhase = callStateFlow.value.phase,
                ),
                themeMode = initialTheme,
                stageConfig = loadedConfig,
                resolvedAssets = resolvedAssets,
                soundEnabled = initialSound,
                hapticEnabled = initialHaptic,
            ),
        )
        state = _state.asStateFlow()

        scope.launch {
            callStateFlow.collect { callState ->
                handleCallStateChange(callState)
            }
        }
    }

    fun onOpeningFinished() {
        _state.update { cur ->
            if (cur.openingPhase == OpeningPhase.IDLE_READY) return@update cur
            cur.copy(
                openingPhase = OpeningPhase.IDLE_READY,
                screenState = resolveScreenState(
                    openingPhase = OpeningPhase.IDLE_READY,
                    activeMenu = cur.activeMenu,
                    callPhase = callStateFlow.value.phase,
                ),
            )
        }
        DuoLogger.i(LogCategory.DISPLAY, "gta_opening_done", "Transitioned to IDLE_READY")
    }

    fun skipOpening() {
        onOpeningFinished()
    }

    fun replayOpening() {
        val nextOpening = if (_state.value.resolvedAssets.hasAnyOpeningVideo()) {
            OpeningPhase.PLAYING_VIDEO
        } else {
            OpeningPhase.FALLBACK_ANIMATION
        }
        _state.update { cur ->
            cur.copy(
                openingPhase = nextOpening,
                screenState = resolveScreenState(
                    openingPhase = nextOpening,
                    activeMenu = cur.activeMenu,
                    callPhase = callStateFlow.value.phase,
                ),
            )
        }
        _events.tryEmit(InteractionEvent.ReplayOpening)
    }

    fun selectMenu(menu: DeckMenu) {
        val prev = _state.value.activeMenu
        if (prev != menu) {
            soundEffects?.playMenuSwitch(callStateFlow.value.phase)
            soundEffects?.triggerHaptic(light = true)
            _events.tryEmit(InteractionEvent.MenuSwitched(from = prev, to = menu))
        }
        _state.update { cur ->
            val nextOpening = cur.openingPhase
            cur.copy(
                activeMenu = menu,
                screenState = resolveScreenState(
                    openingPhase = nextOpening,
                    activeMenu = menu,
                    callPhase = callStateFlow.value.phase,
                ),
            )
        }
    }

    fun onDialKeyPressed(digit: String) {
        if (digit.isEmpty()) return
        val callPhase = callStateFlow.value.phase
        soundEffects?.playKeyClick(digit, callPhase)
        soundEffects?.triggerHaptic(light = true)
        _state.update { cur ->
            val nextBuffer = if (cur.dialBuffer.length < 32) cur.dialBuffer + digit else cur.dialBuffer
            cur.copy(
                activeMenu = DeckMenu.PHONE,
                dialBuffer = nextBuffer,
                screenState = resolveScreenState(cur.openingPhase, DeckMenu.PHONE, callPhase),
            )
        }
        val keyId = VirtualKeyId.fromDialChar(digit) ?: VirtualKeyId.DIGIT_0
        _events.tryEmit(InteractionEvent.KeyPressed(keyId = keyId, label = digit, timestampMs = clockMs()))
    }

    fun onDialBackspace() {
        val callPhase = callStateFlow.value.phase
        soundEffects?.playKeyClick(null, callPhase)
        soundEffects?.triggerHaptic(light = true)
        _state.update { cur ->
            val updated = if (cur.dialBuffer.isEmpty()) "+" else cur.dialBuffer.dropLast(1)
            cur.copy(dialBuffer = updated)
        }
        _events.tryEmit(InteractionEvent.KeyPressed(keyId = VirtualKeyId.BACKSPACE, label = "DEL", timestampMs = clockMs()))
    }

    fun onDialClear() {
        soundEffects?.playKeyClick(null, callStateFlow.value.phase)
        soundEffects?.triggerHaptic(light = true)
        _state.update { it.copy(dialBuffer = "") }
        _events.tryEmit(InteractionEvent.KeyPressed(keyId = VirtualKeyId.CLEAR, label = "CLR", timestampMs = clockMs()))
    }

    fun setDialBuffer(number: String) {
        _state.update { cur ->
            cur.copy(dialBuffer = number.take(32))
        }
    }

    fun onDialTrigger(number: String) {
        val callPhase = callStateFlow.value.phase
        soundEffects?.playCallTrigger(callPhase)
        soundEffects?.triggerHaptic(light = false)
        if (number.isNotBlank()) {
            _state.update { it.copy(dialBuffer = number) }
        }
        _events.tryEmit(InteractionEvent.KeyPressed(keyId = VirtualKeyId.CALL, label = "CALL", timestampMs = clockMs()))
    }

    fun onDtmfKeyPressed(digit: String) {
        soundEffects?.playKeyClick(digit, callStateFlow.value.phase)
        soundEffects?.triggerHaptic(light = true)
        val keyId = VirtualKeyId.fromDialChar(digit) ?: VirtualKeyId.DIGIT_0
        _events.tryEmit(InteractionEvent.KeyPressed(keyId = keyId, label = digit, timestampMs = clockMs()))
    }

    fun onAnswerOrHangupVisualFeedback(isAnswer: Boolean) {
        soundEffects?.triggerHaptic(light = false)
        val keyId = if (isAnswer) VirtualKeyId.ANSWER else VirtualKeyId.HANGUP
        _events.tryEmit(
            InteractionEvent.KeyPressed(
                keyId = keyId,
                label = if (isAnswer) "ANS" else "END",
                timestampMs = clockMs(),
            ),
        )
    }

    fun selectSmsThread(address: String?) {
        _state.update { cur ->
            cur.copy(
                activeMenu = DeckMenu.SMS,
                isComposingNewSms = false,
                selectedSmsAddress = address,
                screenState = resolveScreenState(cur.openingPhase, DeckMenu.SMS, callStateFlow.value.phase),
            )
        }
    }

    fun startNewSms(prefillNumber: String = "") {
        _state.update { cur ->
            cur.copy(
                activeMenu = DeckMenu.SMS,
                isComposingNewSms = true,
                selectedSmsAddress = null,
                smsDraftNumber = prefillNumber,
                screenState = resolveScreenState(cur.openingPhase, DeckMenu.SMS, callStateFlow.value.phase),
            )
        }
    }

    fun updateSmsDraftNumber(number: String) {
        _state.update { it.copy(smsDraftNumber = number) }
    }

    fun updateSmsDraftBody(newBody: String) {
        val prevBody = _state.value.smsDraftBody
        val addedChar = if (newBody.length > prevBody.length) newBody.lastOrNull() else null
        _state.update { cur ->
            cur.copy(
                activeMenu = DeckMenu.SMS,
                smsDraftBody = newBody,
                screenState = resolveScreenState(cur.openingPhase, DeckMenu.SMS, callStateFlow.value.phase),
            )
        }
        val now = clockMs()
        _events.tryEmit(InteractionEvent.TextChanged(text = newBody, addedChar = addedChar, timestampMs = now))
        if (newBody != prevBody) {
            soundEffects?.playKeyClick(null, callStateFlow.value.phase)
            val keyId = when (smsKeyCycleIndex++ % 3) {
                0 -> VirtualKeyId.SMS_KEY_LEFT
                1 -> VirtualKeyId.SMS_KEY_CENTER
                else -> VirtualKeyId.SMS_KEY_RIGHT
            }
            _events.tryEmit(
                InteractionEvent.KeyPressed(
                    keyId = keyId,
                    label = addedChar?.toString() ?: "KEY",
                    timestampMs = now,
                ),
            )
        }
    }

    fun onSmsSent(address: String, body: String) {
        val callPhase = callStateFlow.value.phase
        soundEffects?.playSmsSent(callPhase)
        soundEffects?.triggerHaptic(light = false)
        _state.update { cur ->
            cur.copy(
                activeMenu = DeckMenu.SMS,
                isComposingNewSms = false,
                selectedSmsAddress = address,
                smsDraftBody = "",
                smsDraftNumber = if (cur.isComposingNewSms) "" else cur.smsDraftNumber,
                screenState = resolveScreenState(cur.openingPhase, DeckMenu.SMS, callPhase),
            )
        }
        val now = clockMs()
        _events.tryEmit(InteractionEvent.KeyPressed(keyId = VirtualKeyId.SMS_SEND, label = "SEND", timestampMs = now))
        _events.tryEmit(InteractionEvent.SmsSent(address = address, body = body))
    }

    fun onIncomingSms(
        address: String,
        displayName: String?,
        body: String,
        otpCode: String?,
        timestampMs: Long,
    ) {
        soundEffects?.playSmsReceived(callStateFlow.value.phase)
        val preview = body.replace('\n', ' ').take(48)
        val notice = SmsBannerNotice(
            address = address,
            displayName = displayName,
            preview = preview,
            otpCode = otpCode,
            timestampMs = timestampMs,
        )
        _state.update { it.copy(incomingSmsBanner = notice) }
        _events.tryEmit(InteractionEvent.SmsReceived(notice))
        bannerDismissJob?.cancel()
        bannerDismissJob = scope.launch {
            delay(6_000L)
            _state.update { cur ->
                if (cur.incomingSmsBanner?.timestampMs == timestampMs) {
                    cur.copy(incomingSmsBanner = null)
                } else {
                    cur
                }
            }
        }
    }

    fun dismissSmsBanner() {
        bannerDismissJob?.cancel()
        _state.update { it.copy(incomingSmsBanner = null) }
    }

    fun updatePhoneOverlay(transform: (PhoneOverlayConfig) -> PhoneOverlayConfig) {
        _state.update { cur ->
            val updatedOverlay = transform(cur.stageConfig.phoneOverlay)
                .copy(quadCorners = null)
                .coerced()
            val updatedConfig = cur.stageConfig.copy(phoneOverlay = updatedOverlay)
            configStore?.saveConfig(updatedConfig)
            cur.copy(stageConfig = updatedConfig)
        }
    }

    fun resetStageConfig() {
        val resetConfig = configStore?.resetConfig() ?: GtaStageConfig.default()
        val assets = configStore?.rescanAssets(resetConfig.assets) ?: _state.value.resolvedAssets
        _state.update {
            it.copy(
                stageConfig = resetConfig,
                resolvedAssets = assets,
            )
        }
    }

    fun rescanAssets() {
        val loadedConfig = configStore?.loadConfig() ?: _state.value.stageConfig
        val assets = configStore?.rescanAssets(loadedConfig.assets) ?: _state.value.resolvedAssets
        _state.update { cur ->
            cur.copy(
                stageConfig = loadedConfig,
                resolvedAssets = assets,
            )
        }
    }

    fun setCalibrationGridVisible(visible: Boolean) {
        _state.update { it.copy(isCalibrationGridVisible = visible) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        configStore?.saveSoundEnabled(enabled)
        _state.update { it.copy(soundEnabled = enabled) }
    }

    fun setHapticEnabled(enabled: Boolean) {
        configStore?.saveHapticEnabled(enabled)
        _state.update { it.copy(hapticEnabled = enabled) }
    }

    fun setShowLegacyDiagnostics(show: Boolean) {
        _state.update { it.copy(showLegacyDiagnostics = show) }
    }

    fun setThemeMode(mode: StageThemeMode) {
        configStore?.saveThemeMode(mode)
        _state.update { it.copy(themeMode = mode) }
    }

    fun navigateFromNotification(tab: String?, smsAddress: String?) {
        when (tab) {
            "CALL" -> {
                _state.update { cur ->
                    cur.copy(
                        openingPhase = OpeningPhase.IDLE_READY,
                        activeMenu = DeckMenu.PHONE,
                        screenState = resolveScreenState(OpeningPhase.IDLE_READY, DeckMenu.PHONE, callStateFlow.value.phase),
                    )
                }
            }
            "SMS" -> {
                _state.update { cur ->
                    cur.copy(
                        openingPhase = OpeningPhase.IDLE_READY,
                        activeMenu = DeckMenu.SMS,
                        isComposingNewSms = false,
                        selectedSmsAddress = smsAddress ?: cur.selectedSmsAddress,
                        screenState = resolveScreenState(OpeningPhase.IDLE_READY, DeckMenu.SMS, callStateFlow.value.phase),
                    )
                }
            }
        }
    }

    private fun handleCallStateChange(callState: CallUiState) {
        val prevPhase = lastCallPhase
        val nextPhase = callState.phase
        lastCallPhase = nextPhase

        if (prevPhase == CallUiPhase.IDLE && (nextPhase == CallUiPhase.DIALING || nextPhase == CallUiPhase.ALERTING)) {
            _events.tryEmit(InteractionEvent.CallStarted(callState.remoteNumber))
        } else if (prevPhase != CallUiPhase.ACTIVE && nextPhase == CallUiPhase.ACTIVE) {
            _events.tryEmit(InteractionEvent.CallConnected)
        } else if (prevPhase != CallUiPhase.IDLE && nextPhase == CallUiPhase.IDLE) {
            _events.tryEmit(InteractionEvent.CallEnded)
        }

        _state.update { cur ->
            val forceOpeningDone = if (nextPhase != CallUiPhase.IDLE && cur.openingPhase != OpeningPhase.IDLE_READY) {
                OpeningPhase.IDLE_READY
            } else {
                cur.openingPhase
            }
            val nextMenu = if (prevPhase != CallUiPhase.IDLE && nextPhase == CallUiPhase.IDLE) {
                DeckMenu.PHONE
            } else {
                cur.activeMenu
            }
            cur.copy(
                openingPhase = forceOpeningDone,
                activeMenu = nextMenu,
                screenState = resolveScreenState(forceOpeningDone, nextMenu, nextPhase),
            )
        }
    }

    private fun resolveScreenState(
        openingPhase: OpeningPhase,
        activeMenu: DeckMenu,
        callPhase: CallUiPhase,
    ): AppScreenState {
        return when (callPhase) {
            CallUiPhase.DIALING, CallUiPhase.ALERTING, CallUiPhase.ENDING -> AppScreenState.CallDialing
            CallUiPhase.INCOMING -> AppScreenState.CallIncoming
            CallUiPhase.ACTIVE -> AppScreenState.InCall
            CallUiPhase.IDLE -> {
                if (openingPhase != OpeningPhase.IDLE_READY) {
                    AppScreenState.Opening
                } else {
                    when (activeMenu) {
                        DeckMenu.PHONE -> AppScreenState.PhoneDialing
                        DeckMenu.SMS -> AppScreenState.SmsChat
                        DeckMenu.SETTINGS -> AppScreenState.Settings
                    }
                }
            }
        }
    }

    fun release() {
        bannerDismissJob?.cancel()
        scope.cancel()
    }
}
