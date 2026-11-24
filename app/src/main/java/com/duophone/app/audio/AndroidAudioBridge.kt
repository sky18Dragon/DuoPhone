package com.duophone.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class LocalAudioRoute(val label: String) {
    EARPIECE_OR_DEFAULT("听筒/默认"),
    SPEAKER("扬声器"),
    WIRED_HEADSET("有线耳机"),
    BLUETOOTH("蓝牙耳机"),
}

data class UacDevicePair(
    val inputId: Int? = null,
    val inputName: String? = null,
    val outputId: Int? = null,
    val outputName: String? = null,
    val sampleRates: List<Int> = emptyList(),
) {
    val isComplete: Boolean get() = inputId != null && outputId != null
}

data class AudioBridgeState(
    val isRunning: Boolean = false,
    val hasRecordPermission: Boolean = false,
    val uacPair: UacDevicePair = UacDevicePair(),
    val localRoute: LocalAudioRoute = LocalAudioRoute.SPEAKER,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val uplinkRmsDbfs: Double = -96.0,
    val downlinkRmsDbfs: Double = -96.0,
    val uplinkZeroChunks: Int = 0,
    val downlinkZeroChunks: Int = 0,
    val androidCaptureConcurrencyWarning: Boolean = false,
    val lastError: String? = null,
    val lastSummary: String = "音频桥接未启动",
)

class AndroidAudioBridge(
    private val context: Context,
) {
    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uplinkJob: Job? = null
    private var downlinkJob: Job? = null

    private val _state = MutableStateFlow(
        AudioBridgeState(hasRecordPermission = checkRecordPermission()),
    )
    val state: StateFlow<AudioBridgeState> = _state.asStateFlow()

    @Volatile
    private var mutedFlag: Boolean = false

    fun checkRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun refreshDiagnostics(): AudioBridgeState {
        val perm = checkRecordPermission()
        val uac = findUacDevicePair()
        val next = _state.value.copy(
            hasRecordPermission = perm,
            uacPair = uac.summary,
        )
        _state.value = next
        return next
    }

    fun setMuted(muted: Boolean) {
        mutedFlag = muted
        _state.value = _state.value.copy(
            isMuted = muted,
            androidCaptureConcurrencyWarning = if (muted) false else _state.value.androidCaptureConcurrencyWarning,
        )
        DuoLogger.i(LogCategory.AUDIO, "bridge_mute", "muted=$muted")
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        val route = if (enabled) LocalAudioRoute.SPEAKER else LocalAudioRoute.EARPIECE_OR_DEFAULT
        applyLocalAudioRoute(route)
    }

    fun applyLocalAudioRoute(route: LocalAudioRoute) {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                applyCommunicationDeviceApi31(route)
            } else {
                applyLegacyAudioRoute(route)
            }
            _state.value = _state.value.copy(
                localRoute = route,
                isSpeakerOn = route == LocalAudioRoute.SPEAKER,
            )
            DuoLogger.i(LogCategory.AUDIO, "audio_route", "Applied local audio route: ${route.name}")
        }.onFailure { err ->
            DuoLogger.w(LogCategory.AUDIO, "audio_route_fail", "Route ${route.name} failed: ${err.message}")
            _state.value = _state.value.copy(lastError = "切换音频路由失败: ${err.message}")
        }
    }

    suspend fun start(): Result<AudioBridgeState> {
        if (_state.value.isRunning) return Result.success(_state.value)
        val hasPerm = checkRecordPermission()
        if (!hasPerm) {
            val msg = "缺少 RECORD_AUDIO 麦克风权限，无法启动双向语音桥接"
            val updated = _state.value.copy(
                hasRecordPermission = false,
                lastError = msg,
                lastSummary = msg,
            )
            _state.value = updated
            return Result.failure(SecurityException(msg))
        }

        val resolved = findUacDevicePair()
        if (resolved.input == null || resolved.output == null) {
            val msg = "Android AudioManager 未检测到完整 QDC507 USB UAC 输入/输出设备 (in=${resolved.summary.inputName}, out=${resolved.summary.outputName})"
            val updated = _state.value.copy(
                hasRecordPermission = true,
                uacPair = resolved.summary,
                lastError = msg,
                lastSummary = "UAC 音频设备未就绪",
            )
            _state.value = updated
            DuoLogger.w(LogCategory.AUDIO, "uac_missing", msg)
            return Result.failure(IllegalStateException(msg))
        }

        val targetRoute = if (_state.value.isSpeakerOn) LocalAudioRoute.SPEAKER else _state.value.localRoute
        applyLocalAudioRoute(targetRoute)

        val uacInRate = resolved.input.sampleRates.firstOrNull { it in SUPPORTED_USB_RATES } ?: DEFAULT_UAC_RATE_HZ
        val uacOutRate = resolved.output.sampleRates.firstOrNull { it in SUPPORTED_USB_RATES } ?: DEFAULT_UAC_RATE_HZ
        val localInputDevice = findLocalInputDevice(targetRoute)
        val localOutputDevice = findLocalOutputDevice(targetRoute)

        _state.value = _state.value.copy(
            isRunning = true,
            hasRecordPermission = true,
            uacPair = resolved.summary,
            uplinkZeroChunks = 0,
            downlinkZeroChunks = 0,
            androidCaptureConcurrencyWarning = false,
            lastError = null,
            lastSummary = "双向 UAC 音频桥接运行中 (uacIn=${uacInRate}Hz, uacOut=${uacOutRate}Hz, localOut=${localOutputDevice?.productName ?: targetRoute.label})",
        )

        uplinkJob = scope.launch {
            runUplinkLoop(
                localInDevice = localInputDevice,
                uacOutDevice = resolved.output,
                uacOutRateHz = uacOutRate,
            )
        }
        downlinkJob = scope.launch {
            runDownlinkLoop(
                uacInDevice = resolved.input,
                uacInRateHz = uacInRate,
                localOutDevice = localOutputDevice,
            )
        }

        DuoLogger.i(LogCategory.AUDIO, "bridge_started", _state.value.lastSummary)
        return Result.success(_state.value)
    }

    suspend fun stop() {
        val up = uplinkJob
        val down = downlinkJob
        uplinkJob = null
        downlinkJob = null
        up?.cancelAndJoin()
        down?.cancelAndJoin()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                run {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.stopBluetoothSco()
                }
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }

        _state.value = _state.value.copy(
            isRunning = false,
            lastSummary = "音频桥接已停止",
        )
        DuoLogger.i(LogCategory.AUDIO, "bridge_stopped", "Stopped bidirectional UAC bridge")
    }

    @SuppressLint("MissingPermission")
    private suspend fun CoroutineScope.runUplinkLoop(
        localInDevice: AudioDeviceInfo?,
        uacOutDevice: AudioDeviceInfo,
        uacOutRateHz: Int,
    ) {
        val localRate = LOCAL_CAPTURE_RATE_HZ
        val frameSamples = (localRate * FRAME_MS) / 1000
        val minRecBuf = AudioRecord.getMinBufferSize(
            localRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(frameSamples * 4 * 2)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                localRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minRecBuf,
            )
        } catch (t: Throwable) {
            reportLoopError("创建本地麦克风 AudioRecord 失败: ${t.message}")
            return
        }
        if (localInDevice != null) {
            record.preferredDevice = localInDevice
        }

        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        if (AcousticEchoCanceler.isAvailable()) {
            aec = runCatching {
                AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = runCatching {
                NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }

        val minTrackBuf = AudioTrack.getMinBufferSize(
            uacOutRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(((uacOutRateHz * FRAME_MS) / 1000) * 4 * 2)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(uacOutRateHz)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minTrackBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            aec?.release()
            ns?.release()
            record.release()
            reportLoopError("创建 UAC 上行 AudioTrack 失败: ${t.message}")
            return
        }

        track.preferredDevice = uacOutDevice
        track.setVolume(AudioTrack.getMaxVolume())
        val resampler = StatefulLinearResampler(localRate, uacOutRateHz)
        val buffer = ShortArray(frameSamples)

        try {
            record.startRecording()
            track.play()
            var zeroChunks = 0
            var frames = 0
            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val stats = PcmLevelMeter.measure(buffer, read)
                if (mutedFlag) {
                    buffer.fill(0, 0, read)
                    zeroChunks = 0
                } else if (stats.allZero) {
                    zeroChunks++
                } else {
                    zeroChunks = 0
                }

                val outSamples = resampler.process(buffer, read)
                if (outSamples.isNotEmpty()) {
                    track.write(outSamples, 0, outSamples.size)
                }

                if (frames == 0 || frames % METER_LOG_INTERVAL_FRAMES == 0) {
                    val routedIn = record.routedDevice
                    val routedOut = track.routedDevice
                    DuoLogger.i(
                        LogCategory.AUDIO,
                        "uplink_meter",
                        "frame=$frames rms=${"%.1f".format(stats.rmsDbfs)} zero=$zeroChunks " +
                            "in=${routedIn?.type}:${routedIn?.productName} out=${routedOut?.type}:${routedOut?.productName}",
                    )
                }
                frames++

                _state.value = _state.value.copy(
                    uplinkRmsDbfs = if (mutedFlag) -96.0 else stats.rmsDbfs,
                    uplinkZeroChunks = zeroChunks,
                    androidCaptureConcurrencyWarning = !mutedFlag && zeroChunks >= ZERO_STREAM_WARN_THRESHOLD,
                )
            }
        } catch (t: Throwable) {
            if (isActive) {
                reportLoopError("上行语音循环异常: ${t.message}")
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun CoroutineScope.runDownlinkLoop(
        uacInDevice: AudioDeviceInfo,
        uacInRateHz: Int,
        localOutDevice: AudioDeviceInfo?,
    ) {
        val localRate = LOCAL_PLAYBACK_RATE_HZ
        val frameSamples = ((uacInRateHz * FRAME_MS) / 1000).coerceAtLeast(160)
        val minRecBuf = AudioRecord.getMinBufferSize(
            uacInRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(frameSamples * 4 * 2)

        val uacRecord = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(uacInRateHz)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minRecBuf)
                .build()
        } catch (t: Throwable) {
            reportLoopError("创建 UAC 下行 AudioRecord 失败: ${t.message}")
            return
        }
        uacRecord.preferredDevice = uacInDevice

        val minTrackBuf = AudioTrack.getMinBufferSize(
            localRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(((localRate * FRAME_MS) / 1000) * 4 * 2)

        val localTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(localRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minTrackBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            uacRecord.release()
            reportLoopError("创建本地下行 AudioTrack 失败: ${t.message}")
            return
        }
        if (localOutDevice != null) {
            localTrack.preferredDevice = localOutDevice
        }
        localTrack.setVolume(AudioTrack.getMaxVolume())

        val resampler = StatefulLinearResampler(uacInRateHz, localRate)
        val buffer = ShortArray(frameSamples)

        try {
            uacRecord.startRecording()
            localTrack.play()
            var zeroChunks = 0
            var frames = 0
            while (isActive) {
                val read = uacRecord.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val stats = PcmLevelMeter.measure(buffer, read)
                zeroChunks = if (stats.allZero) zeroChunks + 1 else 0

                val outSamples = resampler.process(buffer, read)
                if (outSamples.isNotEmpty()) {
                    localTrack.write(outSamples, 0, outSamples.size)
                }

                if (frames == 0 || frames % METER_LOG_INTERVAL_FRAMES == 0) {
                    val routedIn = uacRecord.routedDevice
                    val routedOut = localTrack.routedDevice
                    DuoLogger.i(
                        LogCategory.AUDIO,
                        "downlink_meter",
                        "frame=$frames rms=${"%.1f".format(stats.rmsDbfs)} zero=$zeroChunks " +
                            "in=${routedIn?.type}:${routedIn?.productName} out=${routedOut?.type}:${routedOut?.productName}",
                    )
                }
                frames++

                _state.value = _state.value.copy(
                    downlinkRmsDbfs = stats.rmsDbfs,
                    downlinkZeroChunks = zeroChunks,
                )
            }
        } catch (t: Throwable) {
            if (isActive) {
                reportLoopError("下行语音循环异常: ${t.message}")
            }
        } finally {
            runCatching { uacRecord.stop() }
            runCatching { uacRecord.release() }
            runCatching { localTrack.stop() }
            runCatching { localTrack.release() }
        }
    }

    private fun reportLoopError(message: String) {
        DuoLogger.e(LogCategory.AUDIO, "bridge_loop_error", message)
        _state.value = _state.value.copy(lastError = message)
    }

    private fun applyCommunicationDeviceApi31(route: LocalAudioRoute) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val devices = audioManager.availableCommunicationDevices
        val wantedTypes = when (route) {
            LocalAudioRoute.SPEAKER -> listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            LocalAudioRoute.BLUETOOTH -> listOf(
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_HEARING_AID,
            )
            LocalAudioRoute.WIRED_HEADSET -> listOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            )
            LocalAudioRoute.EARPIECE_OR_DEFAULT -> listOf(
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            )
        }
        val match = devices.firstOrNull { it.type in wantedTypes }
        if (match != null) {
            audioManager.setCommunicationDevice(match)
        } else {
            audioManager.clearCommunicationDevice()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyAudioRoute(route: LocalAudioRoute) {
        when (route) {
            LocalAudioRoute.SPEAKER -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = true
            }
            LocalAudioRoute.BLUETOOTH -> {
                audioManager.isSpeakerphoneOn = false
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
            LocalAudioRoute.WIRED_HEADSET,
            LocalAudioRoute.EARPIECE_OR_DEFAULT -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
        }
    }

    private data class ResolvedUac(
        val input: AudioDeviceInfo?,
        val output: AudioDeviceInfo?,
        val summary: UacDevicePair,
    )

    private fun findUacDevicePair(): ResolvedUac {
        val all = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
        val usbInputs = all.filter { dev ->
            dev.isSource && (dev.type == AudioDeviceInfo.TYPE_USB_DEVICE || dev.type == AudioDeviceInfo.TYPE_USB_HEADSET)
        }
        val usbOutputs = all.filter { dev ->
            dev.isSink && (dev.type == AudioDeviceInfo.TYPE_USB_DEVICE || dev.type == AudioDeviceInfo.TYPE_USB_HEADSET)
        }
        val selectedIn = usbInputs.maxByOrNull { scoreUsbAudioDevice(it) }
        val selectedOut = usbOutputs.maxByOrNull { scoreUsbAudioDevice(it) }
        val rates = ((selectedIn?.sampleRates?.toList().orEmpty()) + (selectedOut?.sampleRates?.toList().orEmpty()))
            .distinct()
            .sorted()

        return ResolvedUac(
            input = selectedIn,
            output = selectedOut,
            summary = UacDevicePair(
                inputId = selectedIn?.id,
                inputName = selectedIn?.productName?.toString(),
                outputId = selectedOut?.id,
                outputName = selectedOut?.productName?.toString(),
                sampleRates = rates,
            ),
        )
    }

    private fun findLocalInputDevice(route: LocalAudioRoute): AudioDeviceInfo? {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { dev ->
                dev.type != AudioDeviceInfo.TYPE_USB_DEVICE &&
                    dev.type != AudioDeviceInfo.TYPE_USB_HEADSET
            }
        return when (route) {
            LocalAudioRoute.BLUETOOTH -> inputs.firstOrNull { dev ->
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            } ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            LocalAudioRoute.WIRED_HEADSET -> inputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            } ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            LocalAudioRoute.SPEAKER,
            LocalAudioRoute.EARPIECE_OR_DEFAULT ->
                inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    ?: inputs.firstOrNull()
        }
    }

    private fun findLocalOutputDevice(route: LocalAudioRoute): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { dev ->
                dev.type != AudioDeviceInfo.TYPE_USB_DEVICE &&
                    dev.type != AudioDeviceInfo.TYPE_USB_HEADSET
            }
        return when (route) {
            LocalAudioRoute.BLUETOOTH -> outputs.firstOrNull { dev ->
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            } ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            LocalAudioRoute.WIRED_HEADSET -> outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            } ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            LocalAudioRoute.SPEAKER ->
                outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?: outputs.firstOrNull()
            LocalAudioRoute.EARPIECE_OR_DEFAULT ->
                outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?: outputs.firstOrNull()
        }
    }

    private fun scoreUsbAudioDevice(info: AudioDeviceInfo): Int {
        val name = info.productName?.toString().orEmpty().uppercase()
        var score = if (info.type == AudioDeviceInfo.TYPE_USB_DEVICE) 10 else 5
        if (name.contains("DJI") || name.contains("QDC") || name.contains("MDM")) score += 50
        if (info.sampleRates.contains(8000) || info.sampleRates.contains(16000)) score += 10
        return score
    }

    companion object {
        private const val LOCAL_CAPTURE_RATE_HZ = 16_000
        private const val LOCAL_PLAYBACK_RATE_HZ = 16_000
        private const val DEFAULT_UAC_RATE_HZ = 8_000
        private const val FRAME_MS = 20
        private const val ZERO_STREAM_WARN_THRESHOLD = 25
        private const val METER_LOG_INTERVAL_FRAMES = 100
        private val SUPPORTED_USB_RATES = setOf(8_000, 16_000, 44_100, 48_000)
    }
}
