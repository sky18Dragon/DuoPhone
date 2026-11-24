package com.duophone.app.device

import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogLevel
import com.duophone.app.diagnostics.LogSink
import com.duophone.app.diagnostics.PiiRedactor
import com.duophone.app.protocol.AtEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class Qdc507ConfigManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun silenceLog() {
        DuoLogger.sink = object : LogSink {
            override fun emit(level: LogLevel, tag: String, line: String, throwable: Throwable?) = Unit
        }
    }

    @Test
    fun `md5 crypt and qadbkey deriver match reference vector and reject invalid challenges`() {
        val full = Md5Crypt.crypt("SH_adb_quectel", "12345678")
        assertEquals("\$1\$12345678\$0jXKXQwSwMxYoegx0S.I.1", full)
        assertEquals("0jXKXQwSwMxYoeg", QadbKeyDeriver.deriveLegacyKey("12345678"))
        assertNull(QadbKeyDeriver.deriveLegacyKey("not8digits"))
        assertEquals("87654321", QadbKeyDeriver.parseChallenge("+QADBKEY: 87654321\r\nOK"))
    }

    @Test
    fun `usbcfg parser preserves original VID PID and redactor masks qadbkey`() {
        val parsed = UsbCfgTuple.parse("+QCFG: \"usbcfg\",0x2CA3,0x4006,1,1,1,1,1,0,0")
        assertNotNull(parsed)
        val enabled = parsed!!.withAdbAndUac(adbEnabled = true, uacEnabled = true)
        assertEquals("AT+QCFG=\"usbcfg\",0x2CA3,0x4006,1,1,1,1,1,1,1", enabled.toWriteCommand())

        val redacted = PiiRedactor.redact("AT+QADBKEY=\"0jXKXQwSwMxYoeg\"")
        assertFalse(redacted.contains("0jXKXQwSwMxYoeg"))
        assertTrue(redacted.contains("AT+QADBKEY=\"[REDACTED]\""))
    }

    @Test
    fun `config manager blocks write without backup or during active voice call and succeeds after backup`() = runBlocking {
        val dir = tempFolder.newFolder("backups")
        var currentUsbCfg = "+QCFG: \"usbcfg\",0x2CA3,0x4006,1,1,1,1,1,0,0"
        var clccOutput = ""
        val txLog = mutableListOf<String>()
        val transport = ScriptedConfigTransport { cmd ->
            txLog.add(cmd)
            when {
                cmd == "AT+CGSN" -> "863200001112223\r\nOK\r\n"
                cmd == "AT+CGMR" -> "QDC507GLEFM21\r\nOK\r\n"
                cmd == "AT+QCFG=\"usbcfg\"" -> "$currentUsbCfg\r\nOK\r\n"
                cmd == "AT+QCFG=\"usbnet\"" -> "+QCFG: \"usbnet\",0\r\nOK\r\n"
                cmd == "AT+QCFG=\"ims\"" -> "+QCFG: \"ims\",0,1\r\nOK\r\n"
                cmd == "AT+QPCMV=?" -> "+QPCMV: (0,1),(0-2)\r\nOK\r\n"
                cmd == "AT+CLCC" -> "${clccOutput}OK\r\n"
                cmd == "AT+QADBKEY?" -> "+QADBKEY: 12345678\r\nOK\r\n"
                cmd == "AT+QADBKEY=\"0jXKXQwSwMxYoeg\"" -> "OK\r\n"
                cmd.startsWith("AT+QCFG=\"usbcfg\",") -> {
                    currentUsbCfg = "+QCFG: " + cmd.removePrefix("AT+QCFG=")
                    "OK\r\n"
                }
                cmd == "AT+CFUN=1,1" -> "OK\r\n"
                else -> "OK\r\n"
            }
        }
        val engine = AtEngine(transport, ioDispatcher = Dispatchers.IO)
        engine.startReader()
        try {
            val mgr = Qdc507ConfigManager(engine, dir)

            // 1. Blocked before backup
            val preBackup = mgr.enableAdbAndUacWithReboot()
            assertTrue(preBackup.isFailure)
            assertFalse(txLog.any { it.startsWith("AT+CFUN=1,1") })

            // 2. Backup succeeds and persists file
            val rec = mgr.backupCurrentConfig().getOrThrow()
            assertEquals("0x2CA3", rec.usbCfg.vidHex)
            assertTrue(mgr.state.value.hasMatchingBackup)

            // 3. Blocked during active voice call
            clccOutput = "+CLCC: 1,0,0,0,0,\"10086\",129\r\n"
            val inCall = mgr.enableAdbAndUacWithReboot()
            assertTrue(inCall.isFailure)

            // 4. Succeeds when call idle
            clccOutput = ""
            txLog.clear()
            val enableRes = mgr.enableAdbAndUacWithReboot()
            assertTrue(enableRes.isSuccess)
            assertTrue(txLog.contains("AT+QADBKEY=\"0jXKXQwSwMxYoeg\""))
            assertTrue(txLog.contains("AT+QCFG=\"usbcfg\",0x2CA3,0x4006,1,1,1,1,1,1,1"))
            assertTrue(txLog.contains("AT+CFUN=1,1"))

            // 5. Restore original config preserves VID/PID and reverts bits to 0,0
            txLog.clear()
            val restoreRes = mgr.restoreOriginalUsbCfgWithReboot()
            assertTrue(restoreRes.isSuccess)
            assertTrue(txLog.contains("AT+QCFG=\"usbcfg\",0x2CA3,0x4006,1,1,1,1,1,0,0"))
            assertTrue(txLog.contains("AT+CFUN=1,1"))
        } finally {
            engine.release()
        }
    }

    private class ScriptedConfigTransport(
        private val responder: (String) -> String,
    ) : UsbSerialTransport {
        private val rx = LinkedBlockingQueue<ByteArray>()
        override var isOpen: Boolean = true
        override val activeInterfaceId: Int = 2
        override val currentDevice: UsbDeviceSummary? = null

        override suspend fun openAndProbe(onProgress: (String) -> Unit): UsbDeviceSummary = error("unused")

        override suspend fun write(data: ByteArray, timeoutMs: Int): Int {
            if (!isOpen) throw IOException("closed")
            val cmd = String(data, Charsets.UTF_8).trim()
            val resp = responder(cmd)
            rx.offer(resp.toByteArray(Charsets.UTF_8))
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
}
