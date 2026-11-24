package com.duophone.app.domain

import com.duophone.app.device.SupportedModemProfile
import com.duophone.app.device.UsbDeviceSummary
import com.duophone.app.device.UsbInterfaceDescriptorInfo
import com.duophone.app.device.UsbInterfaceRole
import com.duophone.app.device.UsbSerialTransport
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogLevel
import com.duophone.app.diagnostics.LogSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private class KeepaliveFakeTransport : UsbSerialTransport {
    private val rxQueue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    var failAtPing: Boolean = false

    @Volatile
    var reconnectSucceeds: Boolean = true

    val atPingCount = AtomicInteger(0)

    @Volatile
    override var isOpen: Boolean = false

    @Volatile
    override var activeInterfaceId: Int = -1

    override var currentDevice: UsbDeviceSummary? = UsbDeviceSummary(
        deviceName = "/dev/bus/usb/001/002",
        vendorId = 0x2CA3,
        productId = 0x4006,
        productName = "Baiwang",
        manufacturerName = "DJI",
        profile = SupportedModemProfile.DJI_QDC507,
        interfaces = listOf(
            UsbInterfaceDescriptorInfo(2, 2, 0xFF, 0x00, 0x00, 3, 0x84, 0x03, true, UsbInterfaceRole.AT_CANDIDATE, true, true),
        ),
    )

    override suspend fun openAndProbe(onProgress: (String) -> Unit): UsbDeviceSummary {
        if (!reconnectSucceeds && isOpen) throw IOException("Reconnect blocked")
        isOpen = true
        activeInterfaceId = 2
        return currentDevice!!
    }

    override suspend fun write(data: ByteArray, timeoutMs: Int): Int {
        if (!isOpen) throw IOException("Closed")
        val cmd = String(data, Charsets.ISO_8859_1).trim()
        val reply = when {
            cmd == "AT" -> {
                atPingCount.incrementAndGet()
                if (failAtPing) "ERROR\r\n" else "OK\r\n"
            }
            cmd == "AT+CPIN?" -> "+CPIN: READY\r\nOK\r\n"
            cmd == "AT+CSQ" -> "+CSQ: 22,99\r\nOK\r\n"
            cmd == "AT+COPS?" -> "+COPS: 0,0,\"China Broadnet\",7\r\nOK\r\n"
            cmd == "AT+CEREG?" -> "+CEREG: 0,1\r\nOK\r\n"
            cmd == "AT+CREG?" -> "+CREG: 0,1\r\nOK\r\n"
            cmd == "AT+CGREG?" -> "+CGREG: 0,1\r\nOK\r\n"
            else -> "OK\r\n"
        }
        rxQueue.offer(reply.toByteArray(Charsets.ISO_8859_1))
        return data.size
    }

    override suspend fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (!isOpen) throw IOException("Closed")
        val next = rxQueue.poll(timeoutMs.toLong().coerceAtLeast(10L), TimeUnit.MILLISECONDS) ?: return 0
        val count = minOf(next.size, buffer.size)
        System.arraycopy(next, 0, buffer, 0, count)
        return count
    }

    override suspend fun setControlLineState(dtr: Boolean, rts: Boolean): Boolean = isOpen

    override fun close() {
        isOpen = false
        activeInterfaceId = -1
        rxQueue.clear()
    }
}

class ModemKeepaliveTest {

    @Before
    fun silenceLog() {
        DuoLogger.sink = object : LogSink {
            override fun emit(level: LogLevel, tag: String, line: String, throwable: Throwable?) = Unit
        }
    }

    @Test
    fun keepalivePausesWhenPredicateTrueAndTriggersRecoveringOnConsecutiveFailures() = runBlocking {
        val transport = KeepaliveFakeTransport()
        val paused = AtomicBoolean(true)
        val controller = ModemController(
            transport = transport,
            ioDispatcher = Dispatchers.IO,
            keepaliveIntervalMs = 40L,
            isKeepalivePaused = { paused.get() },
        )

        assertTrue(controller.connect())
        assertEquals(ModemConnectionState.READY, controller.state.value.connectionState)
        val pingsAfterConnect = transport.atPingCount.get()

        // While paused (e.g., active voice call), no keepalive pings should run.
        delay(180L)
        assertEquals(pingsAfterConnect, transport.atPingCount.get())

        // Unpause and fail keepalive AT pings -> 2 consecutive misses trigger RECOVERING.
        transport.failAtPing = true
        paused.set(false)
        var sawRecovering = false
        repeat(40) {
            if (controller.state.value.connectionState == ModemConnectionState.RECOVERING) {
                sawRecovering = true
                return@repeat
            }
            delay(50L)
        }
        assertTrue("Expected keepalive failures to trigger RECOVERING", sawRecovering)

        controller.release()
    }
}
