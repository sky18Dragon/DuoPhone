package com.duophone.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.duophone.app.DuoPhoneApplication
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles quick-action notification buttons (Answer/Reject/Hangup/SMS Direct Reply/Copy OTP)
 * so users can handle calls and messages without interrupting foreground games.
 */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ANSWER_CALL = "com.duophone.app.action.ANSWER_CALL"
        const val ACTION_REJECT_CALL = "com.duophone.app.action.REJECT_CALL"
        const val ACTION_HANGUP_CALL = "com.duophone.app.action.HANGUP_CALL"
        const val ACTION_REPLY_SMS = "com.duophone.app.action.REPLY_SMS"
        const val ACTION_COPY_OTP = "com.duophone.app.action.COPY_OTP"

        const val KEY_SMS_REPLY = "key_sms_reply"
        const val EXTRA_SMS_ADDRESS = "extra_sms_address"
        const val EXTRA_OTP_CODE = "extra_otp_code"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? DuoPhoneApplication ?: return
        val action = intent.action ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)

        when (action) {
            ACTION_ANSWER_CALL -> {
                val pending = goAsync()
                DuoLogger.i(LogCategory.CALL, "notif_action_answer", "Answering call from notification")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        app.callController.answer()
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_REJECT_CALL, ACTION_HANGUP_CALL -> {
                val pending = goAsync()
                DuoLogger.i(LogCategory.CALL, "notif_action_hangup", "Hanging up/rejecting call from notification ($action)")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        app.callController.hangupOrReject()
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_REPLY_SMS -> {
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_SMS_REPLY)
                    ?.toString()
                    ?.trim()
                val address = intent.getStringExtra(EXTRA_SMS_ADDRESS)?.trim()
                if (reply.isNullOrBlank() || address.isNullOrBlank()) return
                val pending = goAsync()
                DuoLogger.i(LogCategory.SMS, "notif_action_reply", "Sending direct notification reply")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val res = app.smsController.sendSms(address, reply)
                        if (res.success && notifId >= 0) {
                            runCatching {
                                context.getSystemService(NotificationManager::class.java).cancel(notifId)
                            }
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_COPY_OTP -> {
                val otp = intent.getStringExtra(EXTRA_OTP_CODE)?.trim()
                if (!otp.isNullOrBlank()) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("OTP", otp))
                    DuoLogger.i(LogCategory.SMS, "notif_action_copy_otp", "Copied OTP from notification")
                }
                if (notifId >= 0) {
                    runCatching {
                        context.getSystemService(NotificationManager::class.java).cancel(notifId)
                    }
                }
            }
        }
    }
}
