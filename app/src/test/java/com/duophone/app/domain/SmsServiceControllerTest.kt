package com.duophone.app.domain

import com.duophone.app.data.SmsDeliveryStatus
import com.duophone.app.data.SmsRecord
import com.duophone.app.data.SmsStore
import com.duophone.app.device.UsbDeviceSummary
import com.duophone.app.device.UsbSerialTransport
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogLevel
import com.duophone.app.diagnostics.LogSink
import com.duophone.app.protocol.AtEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private class InMemorySmsStore : SmsStore {
    private val items = mutableListOf<SmsRecord>()
    private var nextId = 1L
    private val _messages = MutableStateFlow<List<SmsRecord>>(emptyList())
    override val messages: StateFlow<List<SmsRecord>> = _messages.asStateFlow()

    override suspend fun reload() {
        _messages.value = items.sortedByDescending { it.timestampMs }
    }

    override suspend fun insertIfAbsent(record: SmsRecord): Boolean {
        if (items.any { it.dedupKey == record.dedupKey }) return false
        items.add(record.copy(id = nextId++))
        reload()
        return true
    }

    override suspend fun insertAndGetId(record: SmsRecord): Long {
        val id = nextId++
        items.add(record.copy(id = id))
        reload()
        return id
    }

    override suspend fun updateStatus(id: Long, status: SmsDeliveryStatus) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(status = status)
            reload()
        }
    }

    override suspend fun markThreadRead(address: String) {
        items.forEachIndexed { idx, rec ->
            if (rec.address == address && !rec.isRead) {
                items[idx] = rec.copy(isRead = true)
            }
        }
        reload()
    }

    override suspend fun deleteById(id: Long) {
        items.removeAll { it.id == id }
        reload()
    }
}

private class MockSmsUsbTransport(
    private val onWrite: (String) -> List<String>,
) : UsbSerialTransport {
    private val rx = LinkedBlockingQueue<ByteArray>()
    override var isOpen: Boolean = true
    override var activeInterfaceId: Int = 2
    override var currentDevice: UsbDeviceSummary? = null

    override suspend fun openAndProbe(onProgress: (String) -> Unit): UsbDeviceSummary {
        error("Not used")
    }

    override suspend fun write(data: ByteArray, timeoutMs: Int): Int {
        if (!isOpen) throw IOException("Closed")
        val text = String(data, Charsets.ISO_8859_1)
        onWrite(text).forEach { rx.offer(it.toByteArray(Charsets.ISO_8859_1)) }
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

class SmsServiceControllerTest {

    @Before
    fun silenceLog() {
        DuoLogger.sink = object : LogSink {
            override fun emit(level: LogLevel, tag: String, line: String, throwable: Throwable?) = Unit
        }
    }

    @Test
    fun syncsMultipartSmsFromModemAndDeduplicatesRepeatSync() = runBlocking {
        // 2-part UCS2 SMS-DELIVER PDUs
        val part1 = buildDeliverUcs2("8613800138000", 0x11, 2, 1, "长短信第一段")
        val part2 = buildDeliverUcs2("8613800138000", 0x11, 2, 2, "验证码 884210")

        val transport = MockSmsUsbTransport { raw ->
            when {
                raw.startsWith("AT+CMGL=4") -> listOf("+CMGL: 1,0,,30\r\n$part1\r\n+CMGL: 2,0,,30\r\n$part2\r\nOK\r\n")
                else -> listOf("OK\r\n")
            }
        }
        val engine = AtEngine(transport, ioDispatcher = Dispatchers.IO)
        engine.startReader()

        val store = InMemorySmsStore()
        var notified = 0
        val controller = SmsServiceController(
            atEngine = engine,
            repository = store,
            onNewIncomingSms = { notified++ },
            ioDispatcher = Dispatchers.IO,
        )

        val firstAdded = controller.syncFromModem(notifyNew = true)
        assertEquals(1, firstAdded)
        assertEquals(1, notified)
        assertEquals(1, store.messages.value.size)
        assertEquals("长短信第一段验证码 884210", store.messages.value[0].body)
        assertEquals("884210", store.messages.value[0].otpCode)

        // Second sync with same PDUs must deduplicate cleanly
        val secondAdded = controller.syncFromModem(notifyNew = true)
        assertEquals(0, secondAdded)
        assertEquals(1, notified)
        assertEquals(1, store.messages.value.size)

        controller.release()
        engine.release()
    }

    @Test
    fun sendsMultipartChineseSmsAndNeverAutoRetriesCmgsFailure() = runBlocking {
        val commands = mutableListOf<String>()
        var failSecondPrompt = false
        var cmgsCount = 0

        val transport = MockSmsUsbTransport { raw ->
            commands.add(raw)
            when {
                raw.startsWith("AT+CMGS=") -> {
                    cmgsCount++
                    if (failSecondPrompt && cmgsCount == 2) listOf("ERROR\r\n") else listOf("\r\n> ")
                }
                raw.endsWith("\u001A") -> listOf("\r\n+CMGS: $cmgsCount\r\nOK\r\n")
                else -> listOf("OK\r\n")
            }
        }
        val engine = AtEngine(transport, ioDispatcher = Dispatchers.IO)
        engine.startReader()

        val store = InMemorySmsStore()
        val controller = SmsServiceController(engine, store, ioDispatcher = Dispatchers.IO)

        // 80 Chinese chars -> 2 UCS2 segments
        val longChinese = "测试".repeat(40)
        val okRes = controller.sendSms("+8613800138000", longChinese)
        assertTrue(okRes.success)
        assertEquals(2, okRes.segmentsSent)
        assertEquals(2, okRes.totalSegments)
        assertEquals(SmsDeliveryStatus.SENT, store.messages.value.first().status)

        // Now fail on prompt of second segment and verify NO automatic retry occurs on AT+CMGS
        failSecondPrompt = true
        cmgsCount = 0
        commands.clear()
        val failRes = controller.sendSms("+8613800138000", longChinese)
        assertFalse(failRes.success)
        assertEquals(1, failRes.segmentsSent)
        assertEquals(2, commands.count { it.startsWith("AT+CMGS=") })
        assertEquals(SmsDeliveryStatus.FAILED, store.messages.value.first().status)

        controller.release()
        engine.release()
    }

    private fun buildDeliverUcs2(
        senderDigits: String,
        ref: Int,
        total: Int,
        seq: Int,
        text: String,
    ): String {
        val sb = StringBuilder()
        sb.append("00").append("44").append("%02X".format(senderDigits.length)).append("91")
        var i = 0
        while (i < senderDigits.length) {
            val lo = senderDigits[i] - '0'
            val hi = if (i + 1 < senderDigits.length) senderDigits[i + 1] - '0' else 0x0F
            sb.append("%02X".format((hi shl 4) or lo))
            i += 2
        }
        sb.append("00").append("08").append("62114281000023")
        val ucs2 = text.toCharArray().flatMap { ch -> listOf((ch.code shr 8) and 0xFF, ch.code and 0xFF) }
        val udh = listOf(0x05, 0x00, 0x03, ref and 0xFF, total and 0xFF, seq and 0xFF)
        sb.append("%02X".format(udh.size + ucs2.size))
        (udh + ucs2).forEach { sb.append("%02X".format(it)) }
        return sb.toString()
    }
}
