package com.duophone.app.domain

import com.duophone.app.data.CallDirection
import com.duophone.app.data.CallLogRecord
import com.duophone.app.data.CallLogStore
import com.duophone.app.data.CallRecordOutcome
import com.duophone.app.device.UsbDeviceSummary
import com.duophone.app.device.UsbSerialTransport
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogLevel
import com.duophone.app.diagnostics.LogSink
import com.duophone.app.protocol.AtEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private class InMemoryCallLogStore : CallLogStore {
    private val items = mutableListOf<CallLogRecord>()
    private var nextId = 1L
    private val _logs = MutableStateFlow<List<CallLogRecord>>(emptyList())
    override val logs: StateFlow<List<CallLogRecord>> = _logs.asStateFlow()

    override suspend fun reload() {
        _logs.value = items.sortedByDescending { it.startedAtMs }
    }

    override suspend fun insert(record: CallLogRecord): Long {
        val id = nextId++
        items.add(record.copy(id = id))
        reload()
        return id
    }

    override suspend fun deleteById(id: Long) {
        items.removeAll { it.id == id }
        reload()
    }

    override suspend fun clearAll() {
        items.clear()
        reload()
    }
}

private class MockCallUsbTransport(
    private val responder: (String) -> List<String>,
) : UsbSerialTransport {
    private val rx = LinkedBlockingQueue<ByteArray>()
    val commands = mutableListOf<String>()
    override var isOpen: Boolean = true
    override var activeInterfaceId: Int = 2
    override var currentDevice: UsbDeviceSummary? = null

    fun injectUrc(text: String) {
        rx.offer(text.toByteArray(Charsets.ISO_8859_1))
    }

    override suspend fun openAndProbe(onProgress: (String) -> Unit): UsbDeviceSummary = error("unused")

    override suspend fun write(data: ByteArray, timeoutMs: Int): Int {
        if (!isOpen) throw IOException("closed")
        val cmd = String(data, Charsets.ISO_8859_1)
        commands.add(cmd)
        responder(cmd).forEach { injectUrc(it) }
        return data.size
    }

    override suspend fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val next = rx.poll(timeoutMs.toLong().coerceAtLeast(10L), TimeUnit.MILLISECONDS) ?: return 0
        val count = minOf(next.size, buffer.size)
        System.arraycopy(next, 0, buffer, 0, count)
        return count
    }

    override suspend fun setControlLineState(dtr: Boolean, rts: Boolean): Boolean = true
    override fun close() {
        isOpen = false
    }
}

class CallControllerTest {

    @Before
    fun silenceLog() {
        DuoLogger.sink = object : LogSink {
            override fun emit(level: LogLevel, tag: String, line: String, throwable: Throwable?) = Unit
        }
    }

    @Test
    fun outgoingDialTransitionsThroughClccAndRemoteHangupPersistsHistory() = runBlocking {
        var callActive = false
        val transport = MockCallUsbTransport { raw ->
            val cmd = raw.trim()
            when {
                cmd.startsWith("ATD") -> {
                    callActive = true
                    listOf("OK\r\n")
                }
                cmd == "AT+CLCC" -> {
                    if (callActive) listOf("+CLCC: 1,0,0,0,0,\"10086\",129\r\nOK\r\n")
                    else listOf("OK\r\n")
                }
                cmd.startsWith("AT+VTS=") -> listOf("OK\r\n")
                else -> listOf("OK\r\n")
            }
        }
        val engine = AtEngine(transport, ioDispatcher = Dispatchers.IO)
        engine.startReader()
        val store = InMemoryCallLogStore()
        val controller = CallController(engine, store, clccPollIntervalMs = 100L)

        val err = controller.dial("10086")
        assertNull(err)
        delay(200)
        assertEquals(CallUiPhase.ACTIVE, controller.state.value.phase)

        assertNull(controller.sendDtmf("1"))
        assertTrue(transport.commands.any { it.trim() == "AT+VTS=\"1\"" })

        // Simulate remote hangup via NO CARRIER URC
        callActive = false
        transport.injectUrc("NO CARRIER\r\n")
        delay(200)

        assertEquals(CallUiPhase.IDLE, controller.state.value.phase)
        assertEquals(1, store.logs.value.size)
        assertEquals(CallRecordOutcome.OUTGOING_CONNECTED, store.logs.value[0].outcome)
        assertEquals(CallDirection.OUTGOING, store.logs.value[0].direction)

        controller.release()
        engine.release()
    }

    @Test
    fun incomingRingClipAnswerAndHangupPlusRejectFlow() = runBlocking {
        var alertNumber: String? = null
        var dismissed = 0
        val transport = MockCallUsbTransport { raw ->
            when (raw.trim()) {
                "AT+CLCC" -> listOf("+CLCC: 1,1,4,0,0,\"+8613800138000\",145\r\nOK\r\n")
                else -> listOf("OK\r\n")
            }
        }
        val engine = AtEngine(transport, ioDispatcher = Dispatchers.IO)
        engine.startReader()
        val store = InMemoryCallLogStore()
        val controller = CallController(
            atEngine = engine,
            callLogStore = store,
            onIncomingCallAlert = { alertNumber = it },
            onIncomingCallDismiss = { dismissed++ },
            clccPollIntervalMs = 200L,
        )

        // Incoming RING + CLIP -> Answer -> Hangup
        transport.injectUrc("RING\r\n+CLIP: \"+8613800138000\",145\r\n")
        delay(150)
        assertEquals(CallUiPhase.INCOMING, controller.state.value.phase)
        assertEquals("+8613800138000", alertNumber)

        assertNull(controller.answer())
        assertEquals(CallUiPhase.ACTIVE, controller.state.value.phase)
        assertTrue(dismissed >= 1)

        assertNull(controller.hangupOrReject())
        assertEquals(CallUiPhase.IDLE, controller.state.value.phase)
        assertEquals(CallRecordOutcome.ANSWERED, store.logs.value.first().outcome)

        // Second incoming call -> Reject directly
        transport.injectUrc("RING\r\n+CLIP: \"10010\",129\r\n")
        delay(150)
        assertEquals(CallUiPhase.INCOMING, controller.state.value.phase)
        assertNull(controller.hangupOrReject())
        assertEquals(CallRecordOutcome.REJECTED, store.logs.value.first().outcome)

        controller.release()
        engine.release()
    }

    @Test
    fun dialErrorQueriesCeerWithoutRetryingAtd() = runBlocking {
        val transport = MockCallUsbTransport { raw ->
            val cmd = raw.trim()
            when {
                cmd.startsWith("ATD") -> listOf("ERROR\r\n")
                cmd == "AT+CEER" -> listOf("+CEER: Call barred\r\nOK\r\n")
                else -> listOf("OK\r\n")
            }
        }
        val engine = AtEngine(transport, ioDispatcher = Dispatchers.IO)
        engine.startReader()
        val store = InMemoryCallLogStore()
        val controller = CallController(engine, store)

        val err = controller.dial("10086")
        assertNotNull(err)
        assertTrue(err!!.contains("Call barred"))
        assertEquals(1, transport.commands.count { it.trim().startsWith("ATD") })
        assertEquals(CallRecordOutcome.OUTGOING_FAILED, store.logs.value.first().outcome)

        controller.release()
        engine.release()
    }
}
