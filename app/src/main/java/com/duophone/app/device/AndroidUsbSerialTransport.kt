package com.duophone.app.device

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

class AndroidUsbSerialTransport(
    private val context: Context,
) : UsbSerialTransport {

    companion object {
        const val ACTION_USB_PERMISSION = "com.duophone.app.action.USB_PERMISSION"
        private const val CDC_REQ_TYPE_HOST_TO_IFACE = 0x21
        private const val CDC_SET_CONTROL_LINE_STATE = 0x22
        private const val CONSECUTIVE_ERROR_THRESHOLD = 5
    }

    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile
    private var connection: UsbDeviceConnection? = null

    @Volatile
    private var claimedInterface: UsbInterface? = null

    @Volatile
    private var endpointIn: UsbEndpoint? = null

    @Volatile
    private var endpointOut: UsbEndpoint? = null

    @Volatile
    private var _activeInterfaceId: Int = -1

    @Volatile
    private var _currentDevice: UsbDeviceSummary? = null

    private var consecutiveReadErrors = 0

    override val isOpen: Boolean
        get() = connection != null && endpointIn != null && endpointOut != null

    override val activeInterfaceId: Int
        get() = _activeInterfaceId

    override val currentDevice: UsbDeviceSummary?
        get() = _currentDevice

    fun findSupportedDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { dev ->
            SupportedModemProfile.match(dev.vendorId, dev.productId) != null
        }

    fun listAllDevices(): List<UsbDeviceSummary> =
        usbManager.deviceList.values.map { describeDevice(it) }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        DuoLogger.i(
            LogCategory.USB,
            "request_permission",
            "Requesting USB permission for %04X:%04X (%s)".format(
                device.vendorId,
                device.productId,
                device.deviceName,
            ),
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    fun describeDevice(device: UsbDevice, activeIfId: Int = _activeInterfaceId): UsbDeviceSummary {
        val rawInterfaces = (0 until device.interfaceCount).map { idx ->
            val intf = device.getInterface(idx)
            var bulkIn: Int? = null
            var bulkOut: Int? = null
            var hasIntrIn = false
            for (epIdx in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(epIdx)
                when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                        if (ep.direction == UsbConstants.USB_DIR_IN && bulkIn == null) {
                            bulkIn = ep.address
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT && bulkOut == null) {
                            bulkOut = ep.address
                        }
                    }
                    UsbConstants.USB_ENDPOINT_XFER_INT -> {
                        if (ep.direction == UsbConstants.USB_DIR_IN) hasIntrIn = true
                    }
                }
            }
            UsbInterfaceClassifier.RawInterface(
                index = idx,
                interfaceId = intf.id,
                interfaceClass = intf.interfaceClass,
                interfaceSubclass = intf.interfaceSubclass,
                interfaceProtocol = intf.interfaceProtocol,
                endpointCount = intf.endpointCount,
                bulkInAddress = bulkIn,
                bulkOutAddress = bulkOut,
                hasInterruptIn = hasIntrIn,
            )
        }
        val classified = rawInterfaces.map { UsbInterfaceClassifier.classify(it, activeIfId) }
        return UsbDeviceSummary(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            productName = device.productName.orEmpty(),
            manufacturerName = device.manufacturerName.orEmpty(),
            profile = SupportedModemProfile.match(device.vendorId, device.productId),
            interfaces = classified,
        )
    }

    override suspend fun openAndProbe(onProgress: (String) -> Unit): UsbDeviceSummary =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            close()
            delay(150)

            val device = findSupportedDevice()
                ?: throw IOException("未检测到支持的 4G 模块 (2CA3:4006 / 2C7C:0125)")
            if (!hasPermission(device)) {
                throw SecurityException("尚未授予 USB 设备访问权限")
            }

            val summary = describeDevice(device, activeIfId = -1)
            val candidates = UsbInterfaceClassifier.rankAtCandidates(summary.interfaces)
            DuoLogger.i(
                LogCategory.USB,
                "probe_start",
                "device=${summary.vidPidHex} ifaces=${summary.interfaces.size} " +
                    "safeCandidates=${candidates.map { it.interfaceId }} " +
                    "adb=${summary.hasAdbInterface} uac=${summary.hasUacInterface}",
            )

            if (candidates.isEmpty()) {
                throw IOException("当前 USB 描述符中无可用的安全 AT 串口候选接口")
            }

            val conn = usbManager.openDevice(device)
                ?: throw IOException("UsbManager.openDevice 返回 null（权限可能已失效）")

            try {
                // Pass 1: fast probe on ranked safe candidates
                for (cand in candidates) {
                    onProgress("快速探测 IF#${cand.interfaceId}...")
                    if (tryProbeInterface(conn, device, cand, thorough = false)) {
                        val connectedSummary = describeDevice(device, activeIfId = cand.interfaceId)
                        _currentDevice = connectedSummary
                        DuoLogger.i(
                            LogCategory.USB,
                            "probe_success",
                            "AT port locked on IF#${cand.interfaceId}",
                            durationMs = System.currentTimeMillis() - startMs,
                        )
                        return@withContext connectedSummary
                    }
                }

                // Pass 2 & Pass 3 (cold-boot delayed retry)
                repeat(2) { pass ->
                    if (pass == 1) {
                        onProgress("等待模块就绪 (3s) 后重试探测...")
                        delay(3000)
                    }
                    for (cand in candidates) {
                        onProgress("深度探测 IF#${cand.interfaceId}...")
                        if (tryProbeInterface(conn, device, cand, thorough = true)) {
                            val connectedSummary = describeDevice(device, activeIfId = cand.interfaceId)
                            _currentDevice = connectedSummary
                            DuoLogger.i(
                                LogCategory.USB,
                                "probe_success",
                                "AT port locked on IF#${cand.interfaceId} (pass=${pass + 2})",
                                durationMs = System.currentTimeMillis() - startMs,
                            )
                            return@withContext connectedSummary
                        }
                    }
                }

                conn.close()
                throw IOException(
                    "已遍历 ${candidates.size} 个安全候选接口 (${candidates.map { it.interfaceId }})，均未收到 AT OK 响应",
                )
            } catch (e: Exception) {
                if (connection == null) {
                    runCatching { conn.close() }
                }
                throw e
            }
        }

    private suspend fun tryProbeInterface(
        conn: UsbDeviceConnection,
        device: UsbDevice,
        candidate: UsbInterfaceDescriptorInfo,
        thorough: Boolean,
    ): Boolean {
        val intf = device.getInterface(candidate.index)
        if (!conn.claimInterface(intf, true)) {
            DuoLogger.w(LogCategory.USB, "probe_claim_failed", "claimInterface IF#${candidate.interfaceId} failed")
            return false
        }

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (k in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(k)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
        }
        if (epIn == null || epOut == null) {
            runCatching { conn.releaseInterface(intf) }
            return false
        }

        val dtrModes = if (thorough) listOf(true, false) else listOf(true)
        val commands = if (thorough) listOf("AT\r\n", "AT\r", "ATI\r\n") else listOf("AT\r\n")
        val readBuf = ByteArray(1024)

        for (dtr in dtrModes) {
            val lineVal = if (dtr) 0x03 else 0x00
            conn.controlTransfer(
                CDC_REQ_TYPE_HOST_TO_IFACE,
                CDC_SET_CONTROL_LINE_STATE,
                lineVal,
                intf.id,
                null,
                0,
                500,
            )
            // Drain any stale bytes
            while (conn.bulkTransfer(epIn, readBuf, readBuf.size, 60) > 0) {
                // discard
            }

            for (cmd in commands) {
                val payload = cmd.toByteArray(Charsets.US_ASCII)
                val written = conn.bulkTransfer(epOut, payload, payload.size, 800)
                if (written <= 0) continue

                val deadline = System.currentTimeMillis() + if (thorough) 1600 else 1000
                val acc = StringBuilder()
                while (System.currentTimeMillis() < deadline) {
                    val n = conn.bulkTransfer(epIn, readBuf, readBuf.size, 150)
                    if (n > 0) {
                        acc.append(String(readBuf, 0, n, Charsets.ISO_8859_1))
                        val text = acc.toString()
                        if (text.contains("OK") || text.contains("Quectel") || text.contains("QDC507")) {
                            connection = conn
                            claimedInterface = intf
                            endpointIn = epIn
                            endpointOut = epOut
                            _activeInterfaceId = intf.id
                            consecutiveReadErrors = 0
                            return true
                        }
                    } else {
                        delay(25)
                    }
                }
            }
        }

        runCatching { conn.releaseInterface(intf) }
        return false
    }

    override suspend fun write(data: ByteArray, timeoutMs: Int): Int = withContext(Dispatchers.IO) {
        val c = connection ?: throw IOException("USB 连接未打开")
        val ep = endpointOut ?: throw IOException("Bulk OUT 端点未就绪")
        val written = c.bulkTransfer(ep, data, data.size, timeoutMs)
        if (written < 0) {
            throw IOException("USB Bulk OUT 写入失败 (ret=$written)")
        }
        written
    }

    override suspend fun read(buffer: ByteArray, timeoutMs: Int): Int = withContext(Dispatchers.IO) {
        val c = connection ?: throw IOException("USB 连接未打开")
        val ep = endpointIn ?: throw IOException("Bulk IN 端点未就绪")
        val n = c.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
        if (n >= 0) {
            consecutiveReadErrors = 0
            return@withContext n
        }
        // On Android UsbDeviceConnection, bulkTransfer returns -1 on timeout as well as disconnect.
        // Distinguish timeout from hard disconnect by verifying the device is still enumerated.
        val devName = _currentDevice?.deviceName
        if (devName != null && !usbManager.deviceList.containsKey(devName)) {
            throw IOException("USB 设备已从总线移除")
        }
        consecutiveReadErrors++
        if (consecutiveReadErrors >= CONSECUTIVE_ERROR_THRESHOLD && findSupportedDevice() == null) {
            throw IOException("USB 设备读取异常且设备不再存在")
        }
        0
    }

    override suspend fun setControlLineState(dtr: Boolean, rts: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val c = connection ?: return@withContext false
            val ifaceId = _activeInterfaceId
            if (ifaceId < 0) return@withContext false
            val value = (if (dtr) 0x01 else 0x00) or (if (rts) 0x02 else 0x00)
            c.controlTransfer(
                CDC_REQ_TYPE_HOST_TO_IFACE,
                CDC_SET_CONTROL_LINE_STATE,
                value,
                ifaceId,
                null,
                0,
                800,
            ) >= 0
        }

    override fun close() {
        val c = connection
        val intf = claimedInterface
        connection = null
        claimedInterface = null
        endpointIn = null
        endpointOut = null
        _activeInterfaceId = -1
        consecutiveReadErrors = 0
        if (c != null) {
            if (intf != null) {
                runCatching { c.releaseInterface(intf) }
            }
            runCatching { c.close() }
            DuoLogger.i(LogCategory.USB, "close", "Released USB interface and closed connection")
        }
    }
}
