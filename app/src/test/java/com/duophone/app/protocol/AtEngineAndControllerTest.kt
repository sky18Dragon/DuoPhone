package com.duophone.app.protocol

import com.duophone.app.device.SupportedModemProfile
import com.duophone.app.device.UsbDeviceSummary
import com.duophone.app.device.UsbInterfaceDescriptorInfo
import com.duophone.app.device.UsbInterfaceRole
import com.duophone.app.device.UsbSerialTransport
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogLevel
import com.duophone.app.diagnostics.LogSink
import com.duophone.app.domain.ModemConnectionState
import com.duophone.app.domain.ModemController
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private class FakeUsbSerialTransport(
    private val responder: (String) -> List<String>,
) : UsbSerialTransport {
    private val rxQueue = LinkedBlockingQueue<ByteArray>()
    val sentCommands = mutableListOf<String>()

    @Volatile
    override var isOpen: Boolean = false

    @Volatile
    override var activeInterfaceId: Int = -1

    override var currentDevice: UsbDeviceSummary? = sampleDeviceSummary()

    fun injectIncoming(text: String) {
        rxQueue.offer(text.toByteArray(Charsets.ISO_8859_1))
    }

    override suspend fun openAndProbe(onProgress: (String) -> Unit): UsbDeviceSummary {
        onProgress("Mock probe IF#2")
        isOpen = true
        activeInterfaceId = 2
        return currentDevice!!
    }

    override suspend fun write(data: ByteArray, timeoutMs: Int): Int {
        if (!isOpen) throw IOException("Closed")
        val text = String(data, Charsets.ISO_8859_1)
        sentCommands.add(text)
        responder(text).forEach { chunk -> injectIncoming(chunk) }
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

    companion object {
        fun sampleDeviceSummary(supported: Boolean = true): UsbDeviceSummary = UsbDeviceSummary(
            deviceName = "/dev/bus/usb/001/002",
            vendorId = if (supported) 0x2CA3 else 0x1234,
            productId = if (supported) 0x4006 else 0x5678,
            productName = "Baiwang",
            manufacturerName = "DJI",
            profile = if (supported) SupportedModemProfile.DJI_QDC507 else null,
            interfaces = listOf(
                UsbInterfaceDescriptorInfo(2, 2, 0xFF, 0x00, 0x00, 3, 0x84, 0x03, true, UsbInterfaceRole.AT_CANDIDATE, true, true),
                UsbInterfaceDescriptorInfo(6, 6, 0xFF, 0x42, 0x01, 2, 0x8A, 0x06, false, UsbInterfaceRole.ADB, false, false),
            ),
        )
    }
}

class AtEngineAndControllerTest {

    @Before
    fun silenceAndroidLog() {
        DuoLogger.sink = object : LogSink {
            override fun emit(level: LogLevel, tag: String, line: String, throwable: Throwable?) = Unit
        }
    }

    @Test
    fun atEngineDemuxesFragmentedUrcDuringInFlightCommand() = runBlocking {
        val transport = FakeUsbSerialTransport { raw ->
            if (raw.startsWith("AT+CSQ")) {
                // Inject a split +CMTI URC right in the middle of the +CSQ reply
                listOf("+CM", "TI: \"ME\",9\r\n+CSQ: 22,99\r\n", "OK\r\n")
            } else {
                listOf("OK\r\n")
            }
        }
        transport.openAndProbe()
        val engine = AtEngine(transport, ioDispatcher = Dispatchers.IO)
        engine.startReader()

        var receivedUrc: UrcEvent? = null
        val urcCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            receivedUrc = withTimeout(3000) { engine.urcEvents.first() }
        }

        val res = engine.execute("AT+CSQ", timeoutMs = 3000)
        urcCollector.join()

        assertEquals(AtStatus.OK, res.status)
        assertEquals(listOf("+CSQ: 22,99"), res.payloadLines)
        assertTrue(receivedUrc is UrcEvent.NewSmsIndicator)
        assertEquals(9, (receivedUrc as UrcEvent.NewSmsIndicator).index)

        engine.release()
    }

    @Test
    fun atEngineHandlesSmsPromptAndPayloadSubmission() = runBlocking {
        val transport = FakeUsbSerialTransport { raw ->
            when {
                raw.startsWith("AT+CMGS=18") -> listOf("\r\n> ")
                raw.endsWith("\u001A") -> listOf("\r\n+CMGS: 42\r\nOK\r\n")
                else -> listOf("OK\r\n")
            }
        }
        transport.openAndProbe()
        val engine = AtEngine(transport, ioDispatcher = Dispatchers.IO)
        engine.startReader()

        val promptRes = engine.execute("AT+CMGS=18", timeoutMs = 2000, expectPrompt = true)
        assertEquals(AtStatus.PROMPT_READY, promptRes.status)

        val submitRes = engine.sendPromptPayload("0001000B91683108100005F00008044F60597D", timeoutMs = 2000)
        assertEquals(AtStatus.OK, submitRes.status)
        assertTrue(submitRes.raw.contains("+CMGS: 42"))

        engine.release()
    }

    @Test
    fun atEngineNeverAutoRetriesNonIdempotentCommands() = runBlocking {
        val transport = FakeUsbSerialTransport { raw ->
            if (raw.startsWith("ATD")) listOf("ERROR\r\n") else listOf("OK\r\n")
        }
        transport.openAndProbe()
        val engine = AtEngine(transport, ioDispatcher = Dispatchers.IO)
        engine.startReader()

        val res = engine.execute("ATD10086;", timeoutMs = 2000, maxRetries = 2)
        assertEquals(AtStatus.ERROR, res.status)
        assertEquals(1, transport.sentCommands.count { it.startsWith("ATD") })

        engine.release()
    }

    @Test
    fun controllerReachesReadyStateWithSimulatedQdc507() = runBlocking {
        val transport = FakeUsbSerialTransport { raw ->
            val cmd = raw.trim()
            when {
                cmd == "AT" || cmd == "ATE0" -> listOf("OK\r\n")
                cmd == "ATI" -> listOf("Quectel\r\nEG25\r\nRevision: QDC507GLEFM21\r\nOK\r\n")
                cmd == "AT+CGMI" -> listOf("Quectel\r\nOK\r\n")
                cmd == "AT+CGMM" -> listOf("EG25\r\nOK\r\n")
                cmd == "AT+CGMR" -> listOf("QDC507GLEFM21\r\nOK\r\n")
                cmd == "AT+CGSN" -> listOf("861234567890123\r\nOK\r\n")
                cmd == "AT+CPIN?" -> listOf("+CPIN: READY\r\nOK\r\n")
                cmd == "AT+CSQ" -> listOf("+CSQ: 25,99\r\nOK\r\n")
                cmd == "AT+COPS?" -> listOf("+COPS: 0,0,\"CHINA MOBILE\",7\r\nOK\r\n")
                cmd == "AT+CREG?" -> listOf("+CREG: 0,1\r\nOK\r\n")
                cmd == "AT+CGREG?" -> listOf("+CGREG: 0,1\r\nOK\r\n")
                cmd == "AT+CEREG?" -> listOf("+CEREG: 0,1\r\nOK\r\n")
                cmd == "AT+QCFG=\"usbnet\"" -> listOf("+QCFG: \"usbnet\",0\r\nOK\r\n")
                cmd == "AT+QCFG=\"usbcfg\"" -> listOf("+QCFG: \"usbcfg\",0x2CA3,0x4006,1,1,1,1,1,1,0\r\nOK\r\n")
                cmd == "AT+QCFG=\"ims\"" -> listOf("+QCFG: \"ims\",1,1\r\nOK\r\n")
                cmd == "AT+QPCMV=?" -> listOf("+QPCMV: (0,1),(0-2)\r\nOK\r\n")
                else -> listOf("OK\r\n")
            }
        }

        val controller = ModemController(
            transport = transport,
            ioDispatcher = Dispatchers.IO,
            autoReconnectEnabled = false,
        )

        val ok = controller.connect()
        assertTrue(ok)
        val s = controller.state.value
        assertEquals(ModemConnectionState.READY, s.connectionState)
        assertEquals(2, s.activeAtInterfaceId)
        assertTrue(s.identity.isQdc507)
        assertEquals(SimState.READY, s.simState)
        assertEquals("CHINA MOBILE", s.operator.name)
        assertEquals(-63, s.signal.dbm)
        assertEquals(5, s.signal.bars)
        assertTrue(s.registration.isRegistered)
        assertEquals(0, s.usbConfig.usbnetMode)

        controller.onUsbDeviceDetached()
        assertEquals(ModemConnectionState.DISCONNECTED, controller.state.value.connectionState)
        assertFalse(transport.isOpen)

        controller.release()
    }

    @Test
    fun controllerTransitionsToPermissionRequiredAndSimNotReady() = runBlocking {
        val transport = FakeUsbSerialTransport { raw ->
            when (raw.trim()) {
                "AT+CPIN?" -> listOf("+CME ERROR: 10\r\n")
                else -> listOf("OK\r\n")
            }
        }

        var permissionRequested = false
        val controllerNoPerm = ModemController(
            transport = transport,
            ioDispatcher = Dispatchers.IO,
            hasUsbPermission = { false },
            requestUsbPermission = { permissionRequested = true; true },
            autoReconnectEnabled = false,
        )
        assertFalse(controllerNoPerm.connect())
        assertTrue(permissionRequested)
        assertEquals(ModemConnectionState.PERMISSION_REQUIRED, controllerNoPerm.state.value.connectionState)
        controllerNoPerm.release()

        val controllerNoSim = ModemController(
            transport = transport,
            ioDispatcher = Dispatchers.IO,
            hasUsbPermission = { true },
            autoReconnectEnabled = false,
        )
        controllerNoSim.connect()
        assertEquals(ModemConnectionState.SIM_NOT_READY, controllerNoSim.state.value.connectionState)
        assertEquals(SimState.NOT_INSERTED, controllerNoSim.state.value.simState)
        controllerNoSim.release()
    }
}
