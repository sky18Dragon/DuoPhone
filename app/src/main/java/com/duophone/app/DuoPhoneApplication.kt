package com.duophone.app

import android.app.Application
import com.duophone.app.data.CallLogRepository
import com.duophone.app.data.DuoDatabaseHelper
import com.duophone.app.data.SmsRepository
import com.duophone.app.device.AndroidUsbSerialTransport
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.FileAndAndroidLogSink
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.domain.CallController
import com.duophone.app.domain.ModemController
import com.duophone.app.domain.SmsServiceController
import com.duophone.app.service.SmsNotificationHelper
import java.io.File

class DuoPhoneApplication : Application() {
    lateinit var dbHelper: DuoDatabaseHelper
        private set

    lateinit var smsRepository: SmsRepository
        private set

    lateinit var callLogRepository: CallLogRepository
        private set

    lateinit var usbTransport: AndroidUsbSerialTransport
        private set

    lateinit var modemController: ModemController
        private set

    lateinit var smsController: SmsServiceController
        private set

    lateinit var callController: CallController
        private set

    override fun onCreate() {
        super.onCreate()
        DuoLogger.sink = FileAndAndroidLogSink(File(filesDir, "logs/duophone-latest.log"))
        SmsNotificationHelper.ensureChannels(this)
        dbHelper = DuoDatabaseHelper(this)
        smsRepository = SmsRepository(dbHelper)
        callLogRepository = CallLogRepository(dbHelper)
        usbTransport = AndroidUsbSerialTransport(this)
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
        )
        smsController = SmsServiceController(
            atEngine = modemController.atEngine,
            repository = smsRepository,
            onNewIncomingSms = { sms -> SmsNotificationHelper.showIncomingSms(this, sms) },
        )
        callController = CallController(
            atEngine = modemController.atEngine,
            callLogStore = callLogRepository,
            onIncomingCallAlert = { num -> SmsNotificationHelper.showIncomingCall(this, num) },
            onIncomingCallDismiss = { SmsNotificationHelper.cancelIncomingCall(this) },
        )
        DuoLogger.i(LogCategory.SERVICE, "app_create", "DuoPhoneApplication initialized")
    }
}
