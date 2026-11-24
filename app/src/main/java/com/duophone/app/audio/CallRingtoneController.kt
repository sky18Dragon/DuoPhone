package com.duophone.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory

/**
 * Plays incoming call ringtone and vibration strictly on non-USB local devices
 * (Bluetooth / 3.5mm headset / built-in speaker), preventing QDC507 UAC from hijacking ringtone output.
 */
class CallRingtoneController(
    private val context: Context,
) {
    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var isRinging = false

    fun startRinging() {
        synchronized(lock) {
            if (isRinging) return
            isRinging = true

            val localOut = selectNonUsbOutputDevice()
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            if (ringtoneUri != null) {
                runCatching {
                    val player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build(),
                        )
                        setDataSource(context, ringtoneUri)
                        isLooping = true
                        prepare()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && localOut != null) {
                            setPreferredDevice(localOut)
                        }
                        start()
                    }
                    mediaPlayer = player
                }.onFailure { err ->
                    DuoLogger.w(LogCategory.AUDIO, "ringtone_play_fail", err.message.orEmpty())
                }
            }

            runCatching {
                val vibrator = resolveVibrator()
                if (vibrator?.hasVibrator() == true) {
                    val pattern = longArrayOf(0L, 400L, 600L)
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
                }
            }.onFailure { err ->
                DuoLogger.w(LogCategory.AUDIO, "ringtone_vibrate_fail", err.message.orEmpty())
            }

            DuoLogger.i(
                LogCategory.AUDIO,
                "ringtone_start",
                "out=${localOut?.type}:${localOut?.productName ?: "default"}",
            )
        }
    }

    fun stopRinging() {
        synchronized(lock) {
            val wasRinging = isRinging || mediaPlayer != null
            isRinging = false
            mediaPlayer?.let { player ->
                runCatching {
                    if (player.isPlaying) player.stop()
                }
                runCatching { player.release() }
            }
            mediaPlayer = null

            runCatching {
                resolveVibrator()?.cancel()
            }

            if (wasRinging) {
                DuoLogger.i(LogCategory.AUDIO, "ringtone_stop", "Stopped incoming ringtone & vibration")
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
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        } ?: outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
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
