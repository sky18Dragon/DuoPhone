package com.duophone.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.duophone.app.MainActivity
import com.duophone.app.R
import com.duophone.app.data.SmsRecord
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory

object SmsNotificationHelper {
    private const val CHANNEL_SMS = "duo_sms_incoming"
    private const val CHANNEL_CALL = "duo_call_incoming"
    private const val CALL_NOTIF_ID = 2001

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        val smsChannel = NotificationChannel(
            CHANNEL_SMS,
            "新短信通知",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "外置 4G 模块收到新短信时的实时提醒"
        }
        val callChannel = NotificationChannel(
            CHANNEL_CALL,
            "来电提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "外置 4G 模块来电及游戏期间悬浮来电提醒"
        }
        mgr.createNotificationChannel(smsChannel)
        mgr.createNotificationChannel(callChannel)
    }

    fun showIncomingSms(context: Context, sms: SmsRecord) {
        ensureChannels(context)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "SMS")
            putExtra("sms_address", sms.address)
        }
        val pi = PendingIntent.getActivity(
            context,
            (sms.timestampMs and 0xFFFF).toInt(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (!sms.otpCode.isNullOrBlank()) {
            "新短信 · 验证码 ${sms.otpCode} (${sms.address})"
        } else {
            "新短信 · ${sms.address}"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_SMS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(sms.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sms.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr.notify(3000 + (sms.dedupKey.hashCode() and 0x7FFF), notification)
        }.onFailure { err ->
            DuoLogger.w(LogCategory.SMS, "sms_notif_failed", err.message.orEmpty())
        }
    }

    fun showIncomingCall(context: Context, number: String) {
        ensureChannels(context)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "CALL")
        }
        val pi = PendingIntent.getActivity(
            context,
            CALL_NOTIF_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("4G 模块来电")
            .setContentText(number.ifBlank { "未知号码" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()

        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr.notify(CALL_NOTIF_ID, notification)
        }.onFailure { err ->
            DuoLogger.w(LogCategory.CALL, "call_notif_failed", err.message.orEmpty())
        }
    }

    fun cancelIncomingCall(context: Context) {
        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr.cancel(CALL_NOTIF_ID)
        }
    }
}
