package com.duophone.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.domain.CallUiPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

interface GtaUiSoundEffects {
    fun playKeyClick(digit: String?, callPhase: CallUiPhase)
    fun playMenuSwitch(callPhase: CallUiPhase)
    fun playCallTrigger(callPhase: CallUiPhase)
    fun playSmsSent(callPhase: CallUiPhase)
    fun playSmsReceived(callPhase: CallUiPhase)
    fun triggerHaptic(light: Boolean = true)
}

/**
 * Low-latency GTA-styled UI sound synthesizer and haptic feedback controller.
 * Strictly routes audio away from USB UAC devices and stays silent while an active VoLTE call
 * is in progress so QDC507 two-way audio is never disturbed.
 */
class GtaUiSoundController(
    private val context: Context,
    private val isSoundEnabled: () -> Boolean,
    private val isHapticEnabled: () -> Boolean,
) : GtaUiSoundEffects {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun playKeyClick(digit: String?, callPhase: CallUiPhase) {
        if (!canPlayUiSound(callPhase)) return
        val freqHz = when (digit) {
            "1" -> 697.0
            "2" -> 770.0
            "3" -> 852.0
            "4" -> 941.0
            "5" -> 1046.0
            "6" -> 1174.0
            "7" -> 1209.0
            "8" -> 1336.0
            "9" -> 1477.0
            "0" -> 1100.0
            "*" -> 620.0
            "#" -> 1560.0
            else -> 1260.0
        }
        playSynthesizedChime(
            tones = listOf(ToneStep(freqHz = freqHz, durationMs = 28, amplitude = 0.24)),
        )
    }

    override fun playMenuSwitch(callPhase: CallUiPhase) {
        if (!canPlayUiSound(callPhase)) return
        playSynthesizedChime(
            tones = listOf(
                ToneStep(freqHz = 740.0, durationMs = 26, amplitude = 0.20),
                ToneStep(freqHz = 1120.0, durationMs = 36, amplitude = 0.22),
            ),
        )
    }

    override fun playCallTrigger(callPhase: CallUiPhase) {
        if (!canPlayUiSound(callPhase)) return
        playSynthesizedChime(
            tones = listOf(
                ToneStep(freqHz = 880.0, durationMs = 45, amplitude = 0.25),
                ToneStep(freqHz = 1320.0, durationMs = 65, amplitude = 0.28),
            ),
        )
    }

    override fun playSmsSent(callPhase: CallUiPhase) {
        if (!canPlayUiSound(callPhase)) return
        playSynthesizedChime(
            tones = listOf(
                ToneStep(freqHz = 987.0, durationMs = 36, amplitude = 0.22),
                ToneStep(freqHz = 1480.0, durationMs = 56, amplitude = 0.26),
            ),
        )
    }

    override fun playSmsReceived(callPhase: CallUiPhase) {
        if (!canPlayUiSound(callPhase)) return
        playSynthesizedChime(
            tones = listOf(
                ToneStep(freqHz = 1318.0, durationMs = 55, amplitude = 0.28),
                ToneStep(freqHz = 0.0, durationMs = 25, amplitude = 0.0),
                ToneStep(freqHz = 1568.0, durationMs = 75, amplitude = 0.30),
            ),
        )
    }

    override fun triggerHaptic(light: Boolean) {
        if (!isHapticEnabled()) return
        runCatching {
            val vibrator = resolveVibrator() ?: return
            if (!vibrator.hasVibrator()) return
            val duration = if (light) 16L else 28L
            val amp = if (light) 95 else 155
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amp))
        }.onFailure { err ->
            DuoLogger.d(LogCategory.AUDIO, "ui_haptic_skip", err.message.orEmpty())
        }
    }

    private fun canPlayUiSound(callPhase: CallUiPhase): Boolean =
        isSoundEnabled() && callPhase != CallUiPhase.ACTIVE

    private data class ToneStep(
        val freqHz: Double,
        val durationMs: Int,
        val amplitude: Double,
    )

    private fun playSynthesizedChime(tones: List<ToneStep>) {
        scope.launch {
            runCatching {
                val sampleRate = 24_000
                val totalSamples = tones.sumOf { (sampleRate * it.durationMs) / 1000 }
                if (totalSamples <= 0) return@runCatching
                val pcm = ShortArray(totalSamples)
                var offset = 0
                for (step in tones) {
                    val count = (sampleRate * step.durationMs) / 1000
                    for (i in 0 until count) {
                        val tNorm = i.toDouble() / count.coerceAtLeast(1)
                        val attack = (i / (sampleRate * 0.004)).coerceIn(0.0, 1.0)
                        val decay = exp(-3.5 * tNorm)
                        val sample = if (step.freqHz <= 0.0) {
                            0.0
                        } else {
                            sin(2.0 * PI * step.freqHz * i / sampleRate) * step.amplitude * attack * decay
                        }
                        pcm[offset + i] = (sample * Short.MAX_VALUE).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                    }
                    offset += count
                }

                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val bufferBytes = pcm.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        selectNonUsbOutputDevice()?.let { track.setPreferredDevice(it) }
                    }
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    val durationMs = tones.sumOf { it.durationMs }.toLong() + 30L
                    Thread.sleep(durationMs)
                } finally {
                    runCatching { track.stop() }
                    runCatching { track.release() }
                }
            }
        }
    }

    private fun selectNonUsbOutputDevice(): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { dev ->
            dev.type != AudioDeviceInfo.TYPE_USB_DEVICE &&
                dev.type != AudioDeviceInfo.TYPE_USB_HEADSET
        }
        return outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        } ?: outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        } ?: outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: outputs.firstOrNull()
    }

    private fun resolveVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
