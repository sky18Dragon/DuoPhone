package com.duophone.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.duophone.app.DuoPhoneApplication
import com.duophone.app.MainActivity
import com.duophone.app.R
import com.duophone.app.device.AndroidUsbSerialTransport
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.domain.ModemConnectionState
import com.duophone.app.domain.ModemController
import com.duophone.app.domain.ModemUiState
import com.duophone.app.domain.SmsServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service maintaining the QDC507 USB connection, AT command channel,
 * and asynchronous URC monitoring across Activity backgrounding.
 */
class ModemService : Service() {

    companion object {
        private const val CHANNEL_ID = "duo_modem_service"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.duophone.app.service.START"
        const val ACTION_CONNECT = "com.duophone.app.service.CONNECT"

        fun start(context: Context, triggerConnect: Boolean = false) {
            val intent = Intent(context, ModemService::class.java).apply {
                action = if (triggerConnect) ACTION_CONNECT else ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: ModemController
    private lateinit var smsController: SmsServiceController
    private var lastConnectionState: ModemConnectionState = ModemConnectionState.DISCONNECTED
    private var receiversRegistered = false

    private val usbSystemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> controller.onUsbDeviceAttached()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> controller.onUsbDeviceDetached()
            }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AndroidUsbSerialTransport.ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                controller.onUsbPermissionResult(granted)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as DuoPhoneApplication
        controller = app.modemController
        smsController = app.smsController

        createNotificationChannel()
        promoteToForeground(controller.state.value)
        registerUsbReceivers()

        serviceScope.launch {
            controller.state.collect { state ->
                updateNotification(state)
                if (state.connectionState != lastConnectionState) {
                    val prev = lastConnectionState
                    lastConnectionState = state.connectionState
                    if (state.connectionState == ModemConnectionState.READY && prev != ModemConnectionState.READY) {
                        serviceScope.launch(Dispatchers.IO) { smsController.syncFromModem(notifyNew = false) }
                    } else if (state.connectionState == ModemConnectionState.DISCONNECTED) {
                        smsController.onModemDisconnected()
                    }
                }
            }
        }
        DuoLogger.i(LogCategory.SERVICE, "service_create", "ModemService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground(controller.state.value)
        when (intent?.action) {
            ACTION_CONNECT -> {
                serviceScope.launch { controller.connect() }
            }
            else -> {
                if (!controller.atEngine.isRunning) {
                    serviceScope.launch { controller.connect() }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DuoLogger.i(LogCategory.SERVICE, "service_destroy", "ModemService stopping")
        if (receiversRegistered) {
            runCatching { unregisterReceiver(usbSystemReceiver) }
            runCatching { unregisterReceiver(usbPermissionReceiver) }
            receiversRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun registerUsbReceivers() {
        if (receiversRegistered) return
        val systemFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val permissionFilter = IntentFilter(AndroidUsbSerialTransport.ACTION_USB_PERMISSION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbSystemReceiver, systemFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(usbPermissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbSystemReceiver, systemFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, permissionFilter)
        }
        receiversRegistered = true
        DuoLogger.i(LogCategory.USB, "receivers_registered", "USB attach/detach and permission receivers active")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.service_channel_desc)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun promoteToForeground(state: ModemUiState) {
        val notification = buildNotification(state)
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        result.onFailure { err ->
            DuoLogger.e(
                LogCategory.SERVICE,
                "foreground_failed",
                "Failed to promote ModemService to foreground",
                throwable = err,
            )
        }
    }

    private fun updateNotification(state: ModemUiState) {
        val mgr = getSystemService(NotificationManager::class.java)
        runCatching { mgr.notify(NOTIFICATION_ID, buildNotification(state)) }
    }

    private fun buildNotification(state: ModemUiState): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Duo Phone · ${state.connectionState.label}")
            .setContentText(state.statusDetail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }
}
