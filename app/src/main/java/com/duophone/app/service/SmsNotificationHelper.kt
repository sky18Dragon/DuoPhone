package com.duophone.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.duophone.app.MainActivity
import com.duophone.app.R
import com.duophone.app.data.SmsRecord
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.stage.TopScreenPhoneMasker

object SmsNotificationHelper {
    private const val CHANNEL_SMS = "duo_sms_incoming"
    private const val CHANNEL_CALL = "duo_call_incoming"
    private const val CHANNEL_CALL_ONGOING = "duo_call_ongoing"
    private const val CALL_NOTIF_ID = 2001
    private const val ONGOING_CALL_NOTIF_ID = 2002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        val smsChannel = NotificationChannel(
            CHANNEL_SMS,
            "新短信通知",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "外置 4G 模块收到新短信时的实时提醒与快捷回复"
        }
        val callChannel = NotificationChannel(
            CHANNEL_CALL,
            "来电提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "外置 4G 模块来电及游戏期间悬浮接听/拒接提醒"
        }
        val ongoingChannel = NotificationChannel(
            CHANNEL_CALL_ONGOING,
            "通话进行中",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "外置 4G 模块通话中的常驻挂断控制栏"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(smsChannel)
        mgr.createNotificationChannel(callChannel)
        mgr.createNotificationChannel(ongoingChannel)
    }

    fun showIncomingSms(
        context: Context,
        sms: SmsRecord,
        displayName: String? = null,
    ) {
        ensureChannels(context)
        val notifId = 3000 + (sms.dedupKey.hashCode() and 0x7FFF)
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
        val maskedAddr = TopScreenPhoneMasker.mask(sms.address)
        val senderLabel = if (!displayName.isNullOrBlank()) "$displayName ($maskedAddr)" else maskedAddr
        val title = if (!sms.otpCode.isNullOrBlank()) {
            "新短信 · 验证码 ${sms.otpCode} · $senderLabel"
        } else {
            "新短信 · $senderLabel"
        }

        val replyRemoteInput = RemoteInput.Builder(CallActionReceiver.KEY_SMS_REPLY)
            .setLabel("快捷回复 ${displayName ?: maskedAddr}")
            .build()
        val replyIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_REPLY_SMS
            putExtra(CallActionReceiver.EXTRA_SMS_ADDRESS, sms.address)
            putExtra(CallActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val replyFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPi = PendingIntent.getBroadcast(context, notifId, replyIntent, replyFlags)
        val replyAction = NotificationCompat.Action.Builder(0, "快捷回复", replyPi)
            .addRemoteInput(replyRemoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_SMS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(sms.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sms.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(replyAction)

        if (!sms.otpCode.isNullOrBlank()) {
            val otpIntent = Intent(context, CallActionReceiver::class.java).apply {
                action = CallActionReceiver.ACTION_COPY_OTP
                putExtra(CallActionReceiver.EXTRA_OTP_CODE, sms.otpCode)
                putExtra(CallActionReceiver.EXTRA_NOTIF_ID, notifId)
            }
            val otpPi = PendingIntent.getBroadcast(
                context,
                notifId + 1,
                otpIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "复制验证码", otpPi)
        }

        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr.notify(notifId, builder.build())
        }.onFailure { err ->
            DuoLogger.w(LogCategory.SMS, "sms_notif_failed", err.message.orEmpty())
        }
    }

    fun showIncomingCall(
        context: Context,
        number: String,
        displayName: String? = null,
    ) {
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
        val answerIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_ANSWER_CALL
        }
        val answerPi = PendingIntent.getBroadcast(
            context,
            CALL_NOTIF_ID + 10,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val rejectIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_REJECT_CALL
        }
        val rejectPi = PendingIntent.getBroadcast(
            context,
            CALL_NOTIF_ID + 11,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val maskedNumber = TopScreenPhoneMasker.mask(number)
        val callerText = when {
            !displayName.isNullOrBlank() && number.isNotBlank() -> "$displayName · $maskedNumber"
            !displayName.isNullOrBlank() -> displayName
            number.isNotBlank() -> maskedNumber
            else -> "未知号码"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("4G 模块来电")
            .setContentText(callerText)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .addAction(0, "接听", answerPi)
            .addAction(0, "拒接", rejectPi)
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

    fun showOngoingCall(
        context: Context,
        number: String,
        displayName: String? = null,
    ) {
        ensureChannels(context)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "CALL")
        }
        val pi = PendingIntent.getActivity(
            context,
            ONGOING_CALL_NOTIF_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangupIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_HANGUP_CALL
        }
        val hangupPi = PendingIntent.getBroadcast(
            context,
            ONGOING_CALL_NOTIF_ID + 10,
            hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val maskedNumber = TopScreenPhoneMasker.mask(number)
        val callerText = when {
            !displayName.isNullOrBlank() && number.isNotBlank() -> "$displayName · $maskedNumber"
            !displayName.isNullOrBlank() -> displayName
            number.isNotBlank() -> maskedNumber
            else -> "未知号码"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_CALL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("正在 4G VoLTE 通话")
            .setContentText(callerText)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .addAction(0, "挂断", hangupPi)
            .build()

        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr.notify(ONGOING_CALL_NOTIF_ID, notification)
        }.onFailure { err ->
            DuoLogger.w(LogCategory.CALL, "ongoing_notif_failed", err.message.orEmpty())
        }
    }

    fun cancelOngoingCall(context: Context) {
        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr.cancel(ONGOING_CALL_NOTIF_ID)
        }
    }
}
