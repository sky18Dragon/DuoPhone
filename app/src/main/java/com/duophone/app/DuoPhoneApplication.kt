package com.duophone.app

import android.app.Application
import com.duophone.app.device.AndroidUsbSerialTransport
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.FileAndAndroidLogSink
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.domain.ModemController
import java.io.File

class DuoPhoneApplication : Application() {
    lateinit var usbTransport: AndroidUsbSerialTransport
        private set

    lateinit var modemController: ModemController
        private set

    override fun onCreate() {
        super.onCreate()
        DuoLogger.sink = FileAndAndroidLogSink(File(filesDir, "logs/duophone-latest.log"))
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
        DuoLogger.i(LogCategory.SERVICE, "app_create", "DuoPhoneApplication initialized")
    }
}
