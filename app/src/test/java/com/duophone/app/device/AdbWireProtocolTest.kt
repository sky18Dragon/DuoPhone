package com.duophone.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbWireProtocolTest {
    @Test
    fun `packet header encoding round trips and rejects invalid magic or checksum`() {
        val pkt = AdbWireProtocol.openPacket(localId = 42, service = "shell:uname -r")
        val (headerBytes, payload) = AdbWireProtocol.encodePacket(pkt, includeChecksum = true)
        assertEquals(AdbConstants.HEADER_SIZE, headerBytes.size)

        val decoded = AdbWireProtocol.decodeHeader(headerBytes)
        assertNotNull(decoded)
        assertEquals(AdbConstants.CMD_OPEN, decoded!!.command)
        assertEquals(42, decoded.arg0)
        assertEquals(0, decoded.arg1)
        assertEquals(payload.size, decoded.dataLength)
        assertTrue(AdbWireProtocol.verifyPayload(decoded, payload, requireChecksum = true))

        // Corrupt magic
        val badMagic = headerBytes.copyOf()
        badMagic[20] = (badMagic[20].toInt() xor 0xFF).toByte()
        assertNull(AdbWireProtocol.decodeHeader(badMagic))

        // Corrupt payload byte breaks checksum
        val badPayload = payload.copyOf()
        badPayload[0] = (badPayload[0].toInt() xor 0x01).toByte()
        assertFalse(AdbWireProtocol.verifyPayload(decoded, badPayload, requireChecksum = true))
        assertTrue(AdbWireProtocol.verifyPayload(decoded, badPayload, requireChecksum = false))
    }

    @Test
    fun `shell marker wraps single quotes and parses exit code cleanly`() {
        val wrapped = AdbShellMarker.wrapCommand("echo 'hi' && ls /dev/snd")
        assertTrue(wrapped.contains("sh -c 'echo '\"'\"'hi'\"'\"' && ls /dev/snd'"))
        assertTrue(wrapped.contains(AdbShellMarker.DEFAULT_MARKER))

        val ok = AdbShellMarker.parseOutput("3.18.44-perf\r\ncontrolC0\r\n__DUO_ADB_EXIT__:0\r\n")
        assertEquals(0, ok.exitCode)
        assertEquals("3.18.44-perf\ncontrolC0", ok.stdout)

        val fail = AdbShellMarker.parseOutput("insmod: failed\n__DUO_ADB_EXIT__:127\n")
        assertEquals(127, fail.exitCode)
        assertEquals("insmod: failed", fail.stdout)

        val missing = AdbShellMarker.parseOutput("partial output without marker")
        assertEquals(-1, missing.exitCode)
        assertEquals("partial output without marker", missing.stdout)
    }

    @Test
    fun `sync codec encodes SEND DATA DONE and decodes OKAY or FAIL`() {
        val send = AdbSyncCodec.encodeSendRequest("/tmp/duophone-voice/test.bin", 0b111_101_101)
        assertEquals("SEND", String(send, 0, 4, Charsets.US_ASCII))
        val sendLen = ByteBuffer.wrap(send, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals("/tmp/duophone-voice/test.bin,33261", String(send, 8, sendLen, Charsets.UTF_8))

        val data = AdbSyncCodec.encodeDataChunk(byteArrayOf(1, 2, 3, 4), offset = 1, length = 2)
        assertEquals("DATA", String(data, 0, 4, Charsets.US_ASCII))
        assertEquals(2, ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(2, data[8].toInt())
        assertEquals(3, data[9].toInt())

        val okBytes = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("OKAY".toByteArray(Charsets.US_ASCII))
            .putInt(0)
            .array()
        assertEquals(AdbSyncResponse.Okay, AdbSyncCodec.decodeResponse(okBytes))

        val msg = "read-only file system".toByteArray(Charsets.UTF_8)
        val failBytes = ByteBuffer.allocate(8 + msg.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("FAIL".toByteArray(Charsets.US_ASCII))
            .putInt(msg.size)
            .put(msg)
            .array()
        assertEquals(AdbSyncResponse.Fail("read-only file system"), AdbSyncCodec.decodeResponse(failBytes))
    }
}
