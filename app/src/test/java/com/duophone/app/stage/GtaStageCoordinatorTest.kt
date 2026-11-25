package com.duophone.app.stage

import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogLevel
import com.duophone.app.diagnostics.LogSink
import com.duophone.app.domain.CallUiPhase
import com.duophone.app.domain.CallUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GtaStageCoordinatorTest {

    @Before
    fun silenceLogs() {
        DuoLogger.sink = object : LogSink {
            override fun emit(level: LogLevel, tag: String, line: String, throwable: Throwable?) = Unit
        }
    }

    private class InMemoryStageConfigStore(
        initialConfig: GtaStageConfig = GtaStageConfig.default(),
        private val hasOpeningVideo: Boolean = false,
    ) : StageConfigStore {
        var config: GtaStageConfig = initialConfig
        var soundEnabled: Boolean = true
        var hapticEnabled: Boolean = true

        override fun loadConfig(): GtaStageConfig = config
        override fun saveConfig(config: GtaStageConfig) {
            this.config = config
        }

        override fun resetConfig(): GtaStageConfig {
            config = GtaStageConfig.default()
            return config
        }

        override fun rescanAssets(assetConfig: GtaAssetConfig): ResolvedStageAssets =
            ResolvedStageAssets(
                assetsDirPath = "/tmp/test_gta_assets",
                openingVideoUri = if (hasOpeningVideo) "file:///tmp/opening.mp4" else null,
            )

        override fun loadSoundEnabled(): Boolean = soundEnabled
        override fun saveSoundEnabled(enabled: Boolean) {
            soundEnabled = enabled
        }

        override fun loadHapticEnabled(): Boolean = hapticEnabled
        override fun saveHapticEnabled(enabled: Boolean) {
            hapticEnabled = enabled
        }
    }

    @Test
    fun `opening transitions cleanly to phone dialing and menu switches stay synced`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callState = MutableStateFlow(CallUiState())
        val store = InMemoryStageConfigStore(hasOpeningVideo = true)
        val coordinator = GtaStageCoordinator(
            callStateFlow = callState,
            configStore = store,
            ioDispatcher = dispatcher,
        )

        assertEquals(OpeningPhase.PLAYING_VIDEO, coordinator.state.value.openingPhase)
        assertEquals(AppScreenState.Opening, coordinator.state.value.screenState)

        coordinator.onOpeningFinished()
        assertEquals(OpeningPhase.IDLE_READY, coordinator.state.value.openingPhase)
        assertEquals(AppScreenState.PhoneDialing, coordinator.state.value.screenState)

        val eventJob = async(UnconfinedTestDispatcher(testScheduler)) { coordinator.events.take(2).toList() }
        coordinator.selectMenu(DeckMenu.SMS)
        assertEquals(AppScreenState.SmsChat, coordinator.state.value.screenState)

        coordinator.selectMenu(DeckMenu.SETTINGS)
        assertEquals(AppScreenState.Settings, coordinator.state.value.screenState)

        val emitted = eventJob.await()
        assertEquals(InteractionEvent.MenuSwitched(DeckMenu.PHONE, DeckMenu.SMS), emitted[0])
        assertEquals(InteractionEvent.MenuSwitched(DeckMenu.SMS, DeckMenu.SETTINGS), emitted[1])

        coordinator.release()
    }

    @Test
    fun `dialpad keys update dial buffer and emit matching key events`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callState = MutableStateFlow(CallUiState())
        val coordinator = GtaStageCoordinator(
            callStateFlow = callState,
            configStore = InMemoryStageConfigStore(),
            clockMs = { 1000L },
            ioDispatcher = dispatcher,
        )
        coordinator.skipOpening()

        val eventsJob = async(UnconfinedTestDispatcher(testScheduler)) { coordinator.events.take(4).toList() }
        coordinator.onDialKeyPressed("1")
        coordinator.onDialKeyPressed("0")
        coordinator.onDialKeyPressed("#")
        coordinator.onDialBackspace()

        assertEquals("10", coordinator.state.value.dialBuffer)
        val events = eventsJob.await()
        assertEquals(VirtualKeyId.DIGIT_1, (events[0] as InteractionEvent.KeyPressed).keyId)
        assertEquals(VirtualKeyId.DIGIT_0, (events[1] as InteractionEvent.KeyPressed).keyId)
        assertEquals(VirtualKeyId.HASH, (events[2] as InteractionEvent.KeyPressed).keyId)
        assertEquals(VirtualKeyId.BACKSPACE, (events[3] as InteractionEvent.KeyPressed).keyId)

        coordinator.onDialClear()
        assertEquals("", coordinator.state.value.dialBuffer)
        coordinator.release()
    }

    @Test
    fun `real call phase drives top screen state transitions and restores dialer on hangup`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callState = MutableStateFlow(CallUiState())
        val coordinator = GtaStageCoordinator(
            callStateFlow = callState,
            configStore = InMemoryStageConfigStore(),
            ioDispatcher = dispatcher,
        )
        coordinator.skipOpening()
        coordinator.selectMenu(DeckMenu.SMS)
        advanceUntilIdle()

        val eventsJob = async(UnconfinedTestDispatcher(testScheduler)) { coordinator.events.take(3).toList() }

        callState.value = CallUiState(phase = CallUiPhase.DIALING, remoteNumber = "10086")
        advanceUntilIdle()
        assertEquals(AppScreenState.CallDialing, coordinator.state.value.screenState)

        callState.value = CallUiState(phase = CallUiPhase.ACTIVE, remoteNumber = "10086", connectedAtMs = 1L)
        advanceUntilIdle()
        assertEquals(AppScreenState.InCall, coordinator.state.value.screenState)

        callState.value = CallUiState(phase = CallUiPhase.IDLE)
        advanceUntilIdle()
        assertEquals(AppScreenState.PhoneDialing, coordinator.state.value.screenState)
        assertEquals(DeckMenu.PHONE, coordinator.state.value.activeMenu)

        val events = eventsJob.await()
        assertTrue(events[0] is InteractionEvent.CallStarted)
        assertTrue(events[1] is InteractionEvent.CallConnected)
        assertTrue(events[2] is InteractionEvent.CallEnded)

        coordinator.release()
    }

    @Test
    fun `sms typing and incoming sms synchronize draft text and notification banner`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callState = MutableStateFlow(CallUiState())
        val coordinator = GtaStageCoordinator(
            callStateFlow = callState,
            configStore = InMemoryStageConfigStore(),
            clockMs = { 5000L },
            ioDispatcher = dispatcher,
        )
        coordinator.skipOpening()
        coordinator.startNewSms("+8613800138000")

        coordinator.updateSmsDraftBody("Hi")
        assertEquals("Hi", coordinator.state.value.smsDraftBody)
        assertEquals(AppScreenState.SmsChat, coordinator.state.value.screenState)

        coordinator.onSmsSent("+8613800138000", "Hi")
        assertEquals("", coordinator.state.value.smsDraftBody)
        assertEquals("+8613800138000", coordinator.state.value.selectedSmsAddress)

        coordinator.onIncomingSms(
            address = "10086",
            displayName = "China Mobile",
            body = "验证码 998811",
            otpCode = "998811",
            timestampMs = 6000L,
        )
        assertNotNull(coordinator.state.value.incomingSmsBanner)
        assertEquals("998811", coordinator.state.value.incomingSmsBanner?.otpCode)

        coordinator.dismissSmsBanner()
        assertNull(coordinator.state.value.incomingSmsBanner)
        coordinator.release()
    }

    @Test
    fun `stage config json roundtrip and calibration clamping work accurately`() {
        val custom = GtaStageConfig(
            assets = GtaAssetConfig(openingVideoFile = "custom_open.mp4"),
            phoneOverlay = PhoneOverlayConfig(
                leftFraction = 0.61f,
                topFraction = 0.18f,
                widthFraction = 0.27f,
                heightFraction = 0.70f,
                rotationX = 6f,
                rotationY = -12f,
                rotationZ = -3f,
                breathingEnabled = false,
                quadCorners = QuadCorners(
                    tlX = 0.437534f, tlY = 0.168934f,
                    trX = 0.615719f, trY = 0.183950f,
                    brX = 0.566395f, brY = 0.808944f,
                    blX = 0.383826f, blY = 0.764908f,
                ),
            ),
        )
        val parsed = GtaStageConfig.fromJson(custom.toJson())
        assertEquals("custom_open.mp4", parsed.assets.openingVideoFile)
        assertEquals(0.61f, parsed.phoneOverlay.leftFraction, 0.001f)
        assertEquals(-12f, parsed.phoneOverlay.rotationY, 0.001f)
        assertEquals(false, parsed.phoneOverlay.breathingEnabled)
        assertNotNull(parsed.phoneOverlay.quadCorners)
        assertEquals(0.437534f, parsed.phoneOverlay.quadCorners!!.tlX, 0.0001f)
        assertEquals(0.808944f, parsed.phoneOverlay.quadCorners!!.brY, 0.0001f)

        val clamped = PhoneOverlayConfig(leftFraction = 2.5f, widthFraction = 0.01f, rotationX = 99f).coerced()
        assertEquals(0.85f, clamped.leftFraction, 0.001f)
        assertEquals(0.12f, clamped.widthFraction, 0.001f)
        assertEquals(35f, clamped.rotationX, 0.001f)
    }

    @Test
    fun `manual slider calibration clears quadCorners while resetStageConfig restores it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callState = MutableStateFlow(CallUiState())
        val initialQuad = QuadCorners(0.43f, 0.16f, 0.62f, 0.18f, 0.56f, 0.81f, 0.38f, 0.77f)
        val store = InMemoryStageConfigStore(
            initialConfig = GtaStageConfig(
                phoneOverlay = PhoneOverlayConfig(quadCorners = initialQuad),
            ),
        )
        val coordinator = GtaStageCoordinator(
            callStateFlow = callState,
            configStore = store,
            ioDispatcher = dispatcher,
        )

        assertNotNull(coordinator.state.value.stageConfig.phoneOverlay.quadCorners)
        coordinator.updatePhoneOverlay { it.copy(leftFraction = 0.45f) }
        assertNull(coordinator.state.value.stageConfig.phoneOverlay.quadCorners)

        coordinator.resetStageConfig()
        assertEquals(GtaStageConfig.default(), coordinator.state.value.stageConfig)
        coordinator.release()
    }

    @Test
    fun `theme mode updates and resolved assets select day or night variants properly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callState = MutableStateFlow(CallUiState())
        val coordinator = GtaStageCoordinator(
            callStateFlow = callState,
            configStore = InMemoryStageConfigStore(),
            ioDispatcher = dispatcher,
        )
        assertEquals(StageThemeMode.SYSTEM, coordinator.state.value.themeMode)
        coordinator.setThemeMode(StageThemeMode.DARK)
        assertEquals(StageThemeMode.DARK, coordinator.state.value.themeMode)
        coordinator.setThemeMode(StageThemeMode.LIGHT)
        assertEquals(StageThemeMode.LIGHT, coordinator.state.value.themeMode)

        val assets = ResolvedStageAssets(
            openingLightVideoUri = "file:///light.mp4",
            openingDarkVideoUri = "file:///dark.mp4",
            idleLightImagePath = AssetSource.ExternalFile("/light.png"),
            idleDarkImagePath = AssetSource.ExternalFile("/dark.png"),
        )
        assertEquals("file:///light.mp4", assets.openingVideoFor(isDark = false))
        assertEquals("file:///dark.mp4", assets.openingVideoFor(isDark = true))
        assertEquals(AssetSource.ExternalFile("/light.png"), assets.idleImageFor(isDark = false))
        assertEquals(AssetSource.ExternalFile("/dark.png"), assets.idleImageFor(isDark = true))

        coordinator.release()
    }
}
