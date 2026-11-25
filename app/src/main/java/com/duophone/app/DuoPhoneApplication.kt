package com.duophone.app

import android.app.Application
import com.duophone.app.audio.AndroidAudioBridge
import com.duophone.app.audio.CallRingtoneController
import com.duophone.app.audio.GtaUiSoundController
import com.duophone.app.data.CallLogRepository
import com.duophone.app.data.ContactRepository
import com.duophone.app.data.DuoDatabaseHelper
import com.duophone.app.data.SmsRepository
import com.duophone.app.device.AndroidUsbAdbClient
import com.duophone.app.device.AndroidUsbSerialTransport
import com.duophone.app.device.Qdc507ConfigManager
import com.duophone.app.device.Qdc507VoiceRuntime
import com.duophone.app.device.VoiceRuntimeStage
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.FileAndAndroidLogSink
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.domain.CallController
import com.duophone.app.domain.CallUiPhase
import com.duophone.app.domain.ModemController
import com.duophone.app.domain.SmsServiceController
import com.duophone.app.service.SmsNotificationHelper
import com.duophone.app.stage.GtaConfigRepository
import com.duophone.app.stage.GtaStageCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class DuoPhoneApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var dbHelper: DuoDatabaseHelper
        private set

    lateinit var smsRepository: SmsRepository
        private set

    lateinit var callLogRepository: CallLogRepository
        private set

    lateinit var contactRepository: ContactRepository
        private set

    lateinit var usbTransport: AndroidUsbSerialTransport
        private set

    lateinit var adbClient: AndroidUsbAdbClient
        private set

    lateinit var voiceRuntime: Qdc507VoiceRuntime
        private set

    lateinit var audioBridge: AndroidAudioBridge
        private set

    lateinit var ringtoneController: CallRingtoneController
        private set

    lateinit var modemController: ModemController
        private set

    lateinit var smsController: SmsServiceController
        private set

    lateinit var callController: CallController
        private set

    lateinit var configManager: Qdc507ConfigManager
        private set

    lateinit var gtaConfigRepository: GtaConfigRepository
        private set

    lateinit var gtaSoundController: GtaUiSoundController
        private set

    lateinit var gtaStageCoordinator: GtaStageCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        DuoLogger.sink = FileAndAndroidLogSink(File(filesDir, "logs/duophone-latest.log"))
        SmsNotificationHelper.ensureChannels(this)
        dbHelper = DuoDatabaseHelper(this)
        smsRepository = SmsRepository(dbHelper)
        callLogRepository = CallLogRepository(dbHelper)
        contactRepository = ContactRepository(this, dbHelper)
        usbTransport = AndroidUsbSerialTransport(this)
        adbClient = AndroidUsbAdbClient(this)
        val voiceDir = File(filesDir, "voice_runtime").apply { mkdirs() }
        voiceRuntime = Qdc507VoiceRuntime(adbClient, voiceDir)
        audioBridge = AndroidAudioBridge(this)
        ringtoneController = CallRingtoneController(this)

        modemController = ModemController(
            transport = usbTransport,
            requestUsbPermission = {
                val dev = usbTransport.findSupportedDevice()
                if (dev != null) {
                    usbTransport.requestPermission(dev)
                    true
                } else {
                    false
                }
            },
            findAttachedSummary = {
                usbTransport.findSupportedDevice()?.let { usbTransport.describeDevice(it) }
                    ?: usbTransport.listAllDevices().firstOrNull()
            },
            hasUsbPermission = {
                val dev = usbTransport.findSupportedDevice()
                dev != null && usbTransport.hasPermission(dev)
            },
            keepaliveIntervalMs = 30_000L,
            isKeepalivePaused = {
                ::callController.isInitialized && callController.state.value.phase != CallUiPhase.IDLE
            },
        )
        smsController = SmsServiceController(
            atEngine = modemController.atEngine,
            repository = smsRepository,
            onNewIncomingSms = { sms ->
                val name = contactRepository.resolveDisplayName(sms.address)
                SmsNotificationHelper.showIncomingSms(this, sms, displayName = name)
                if (::gtaStageCoordinator.isInitialized) {
                    gtaStageCoordinator.onIncomingSms(
                        address = sms.address,
                        displayName = name,
                        body = sms.body,
                        otpCode = sms.otpCode,
                        timestampMs = sms.timestampMs,
                    )
                }
            },
        )
        callController = CallController(
            atEngine = modemController.atEngine,
            callLogStore = callLogRepository,
            onIncomingCallAlert = { num ->
                ringtoneController.startRinging()
                val name = contactRepository.resolveDisplayName(num)
                SmsNotificationHelper.showIncomingCall(this, num, displayName = name)
            },
            onIncomingCallDismiss = {
                ringtoneController.stopRinging()
                SmsNotificationHelper.cancelIncomingCall(this)
            },
            onMuteChanged = { muted -> audioBridge.setMuted(muted) },
            onSpeakerChanged = { speaker -> audioBridge.setSpeakerEnabled(speaker) },
            onRealCallActive = {
                ringtoneController.stopRinging()
                val uacComplete = audioBridge.refreshDiagnostics().uacPair.isComplete
                if (uacComplete && audioBridge.checkRecordPermission()) {
                    var snap = voiceRuntime.state.value
                    if (snap.stage == VoiceRuntimeStage.UNKNOWN || snap.stage == VoiceRuntimeStage.ADB_UNAVAILABLE) {
                        voiceRuntime.probeReadOnly(uacDetected = true)
                        snap = voiceRuntime.state.value
                    }
                    // Auto-recover /tmp/duophone-voice if QDC507 power-cycled or re-plugged since boot.
                    val allAssetsValid = snap.localAssets.isNotEmpty() && snap.localAssets.all { it.valid }
                    if ((!snap.aprLoaded || !snap.voiceLoaded) && snap.adbDetected && allAssetsValid) {
                        DuoLogger.i(
                            LogCategory.MODEM,
                            "voice_auto_prepare",
                            "Preparing /tmp/duophone-voice automatically on active call",
                        )
                        voiceRuntime.prepareReversibleRuntime(uacDetected = true)
                        snap = voiceRuntime.state.value
                    }
                    // Always start a fresh mavo-pcm-bridge --voice-route-session on ACTIVE VoLTE call
                    // so hw:0,4 attaches to the live DSP voice path (stops any stale pre-call PID first).
                    if (snap.aprLoaded && snap.voiceLoaded ||
                        snap.stage == VoiceRuntimeStage.PREPARED ||
                        snap.stage == VoiceRuntimeStage.ROUTE_ACTIVE
                    ) {
                        voiceRuntime.startVoiceRoute()
                        delay(300L)
                        audioBridge.refreshDiagnostics()
                        if (!audioBridge.state.value.isRunning) {
                            audioBridge.start()
                        }
                    }
                }
            },
            onRealCallEnded = {
                ringtoneController.stopRinging()
                audioBridge.stop()
                if (voiceRuntime.state.value.bridgePid != null) {
                    voiceRuntime.stopVoiceRoute()
                }
            },
        )
        val backupDir = File(filesDir, "backups").apply { mkdirs() }
        configManager = Qdc507ConfigManager(modemController.atEngine, backupDir)
        gtaConfigRepository = GtaConfigRepository(this)
        gtaSoundController = GtaUiSoundController(
            context = this,
            isSoundEnabled = {
                !::gtaStageCoordinator.isInitialized || gtaStageCoordinator.state.value.soundEnabled
            },
            isHapticEnabled = {
                !::gtaStageCoordinator.isInitialized || gtaStageCoordinator.state.value.hapticEnabled
            },
        )
        gtaStageCoordinator = GtaStageCoordinator(
            callStateFlow = callController.state,
            configStore = gtaConfigRepository,
            soundEffects = gtaSoundController,
        )
        appScope.launch { contactRepository.reload() }
        DuoLogger.i(LogCategory.SERVICE, "app_create", "DuoPhoneApplication initialized")
    }
}
