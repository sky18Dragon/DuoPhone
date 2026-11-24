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
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.duophone.app.DuoPhoneApplication
import com.duophone.app.MainActivity
import com.duophone.app.R
import com.duophone.app.audio.AndroidAudioBridge
import com.duophone.app.data.ContactRepository
import com.duophone.app.device.AndroidUsbSerialTransport
import com.duophone.app.device.Qdc507VoiceRuntime
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.domain.CallController
import com.duophone.app.domain.CallUiPhase
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
 * active-call WakeLock, screen/lid display power diagnostics, and URC recovery.
 */
class ModemService : Service() {

    companion object {
        private const val CHANNEL_ID = "duo_modem_service"
        private const val NOTIFICATION_ID = 1001
        private const val CALL_WAKELOCK_TAG = "duophone:ActiveVoiceCall"
        private const val CALL_WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L

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
    private lateinit var callController: CallController
    private lateinit var contactRepository: ContactRepository
    private lateinit var voiceRuntime: Qdc507VoiceRuntime
    private lateinit var audioBridge: AndroidAudioBridge
    private var lastConnectionState: ModemConnectionState = ModemConnectionState.DISCONNECTED
    private var receiversRegistered = false
    private var displayListenerRegistered = false
    private var callWakeLock: PowerManager.WakeLock? = null

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

    private val screenPowerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val modemState = controller.state.value.connectionState
            val usbAttached = (application as DuoPhoneApplication).usbTransport.findSupportedDevice() != null
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    DuoLogger.i(
                        LogCategory.POWER,
                        "screen_off",
                        "modem=$modemState usbAttached=$usbAttached wakeLockHeld=${callWakeLock?.isHeld == true}",
                    )
                }
                Intent.ACTION_SCREEN_ON -> {
                    DuoLogger.i(
                        LogCategory.POWER,
                        "screen_on",
                        "modem=$modemState usbAttached=$usbAttached",
                    )
                    if (usbAttached && (modemState == ModemConnectionState.DISCONNECTED || modemState == ModemConnectionState.ERROR)) {
                        serviceScope.launch { controller.connect() }
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    DuoLogger.d(
                        LogCategory.POWER,
                        "user_present",
                        "modem=$modemState usbAttached=$usbAttached",
                    )
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            logDisplayEvent("display_added", displayId)
        }

        override fun onDisplayRemoved(displayId: Int) {
            logDisplayEvent("display_removed", displayId)
        }

        override fun onDisplayChanged(displayId: Int) {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val disp = dm.getDisplay(displayId)
            DuoLogger.d(
                LogCategory.DISPLAY,
                "display_changed",
                "displayId=$displayId state=${disp?.state} modem=${controller.state.value.connectionState}",
            )
        }

        private fun logDisplayEvent(event: String, displayId: Int) {
            val usbAttached = (application as DuoPhoneApplication).usbTransport.findSupportedDevice() != null
            DuoLogger.i(
                LogCategory.DISPLAY,
                event,
                "displayId=$displayId modem=${controller.state.value.connectionState} usbAttached=$usbAttached",
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as DuoPhoneApplication
        controller = app.modemController
        smsController = app.smsController
        callController = app.callController
        contactRepository = app.contactRepository
        voiceRuntime = app.voiceRuntime
        audioBridge = app.audioBridge

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        callWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CALL_WAKELOCK_TAG).apply {
            setReferenceCounted(false)
        }

        createNotificationChannel()
        promoteToForeground(controller.state.value)
        registerReceiversAndListeners()

        serviceScope.launch {
            controller.state.collect { state ->
                updateNotification(state)
                if (state.connectionState != lastConnectionState) {
                    val prev = lastConnectionState
                    lastConnectionState = state.connectionState
                    if (state.connectionState == ModemConnectionState.READY && prev != ModemConnectionState.READY) {
                        serviceScope.launch(Dispatchers.IO) {
                            smsController.syncFromModem(notifyNew = false)
                            callController.ensureCallControlReady()
                            val hasUac = state.usbDevice?.hasUacInterface == true
                            if (state.usbDevice?.hasAdbInterface == true) {
                                voiceRuntime.probeReadOnly(uacDetected = hasUac)
                            }
                            audioBridge.refreshDiagnostics()
                        }
                    } else if (state.connectionState == ModemConnectionState.DISCONNECTED) {
                        smsController.onModemDisconnected()
                        callController.onModemDisconnected()
                    }
                }
            }
        }

        serviceScope.launch {
            callController.state.collect { callState ->
                updateCallWakeLock(active = callState.phase != CallUiPhase.IDLE)
                if (callState.phase == CallUiPhase.ACTIVE) {
                    val name = contactRepository.resolveDisplayName(callState.remoteNumber)
                    SmsNotificationHelper.showOngoingCall(
                        context = this@ModemService,
                        number = callState.remoteNumber,
                        displayName = name,
                    )
                } else {
                    SmsNotificationHelper.cancelOngoingCall(this@ModemService)
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
        updateCallWakeLock(active = false)
        SmsNotificationHelper.cancelOngoingCall(this)
        if (receiversRegistered) {
            runCatching { unregisterReceiver(usbSystemReceiver) }
            runCatching { unregisterReceiver(usbPermissionReceiver) }
            runCatching { unregisterReceiver(screenPowerReceiver) }
            receiversRegistered = false
        }
        if (displayListenerRegistered) {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            runCatching { dm.unregisterDisplayListener(displayListener) }
            displayListenerRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateCallWakeLock(active: Boolean) {
        val wl = callWakeLock ?: return
        runCatching {
            if (active && !wl.isHeld) {
                wl.acquire(CALL_WAKELOCK_TIMEOUT_MS)
                DuoLogger.i(LogCategory.POWER, "wakelock_acquire", "Acquired ActiveVoiceCall PartialWakeLock")
            } else if (!active && wl.isHeld) {
                wl.release()
                DuoLogger.i(LogCategory.POWER, "wakelock_release", "Released ActiveVoiceCall PartialWakeLock")
            }
        }.onFailure { err ->
            DuoLogger.w(LogCategory.POWER, "wakelock_error", err.message.orEmpty())
        }
    }

    private fun registerReceiversAndListeners() {
        if (!receiversRegistered) {
            val systemFilter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            val permissionFilter = IntentFilter(AndroidUsbSerialTransport.ACTION_USB_PERMISSION)
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbSystemReceiver, systemFilter, Context.RECEIVER_EXPORTED)
                registerReceiver(usbPermissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(screenPowerReceiver, screenFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(usbSystemReceiver, systemFilter)
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(usbPermissionReceiver, permissionFilter)
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenPowerReceiver, screenFilter)
            }
            receiversRegistered = true
            DuoLogger.i(LogCategory.USB, "receivers_registered", "USB, permission, and screen/power receivers active")
        }

        if (!displayListenerRegistered) {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.registerDisplayListener(displayListener, null)
            displayListenerRegistered = true
        }
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
