package com.duophone.app.device

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AdbConstants {
    const val VERSION = 0x01000000
    const val VERSION_NO_CHECKSUM = 0x01000001
    const val MAX_PAYLOAD = 4096
    const val SYNC_DATA_MAX = 64 * 1024
    const val HEADER_SIZE = 24

    const val CMD_SYNC = 0x434E5953 // "SYNC"
    const val CMD_CNXN = 0x4E584E43 // "CNXN"
    const val CMD_OPEN = 0x4E45504F // "OPEN"
    const val CMD_OKAY = 0x59414B4F // "OKAY"
    const val CMD_CLSE = 0x45534C43 // "CLSE"
    const val CMD_WRTE = 0x45545257 // "WRTE"
    const val CMD_AUTH = 0x48545541 // "AUTH"

    const val ADB_USB_CLASS = 0xFF
    const val ADB_USB_SUBCLASS = 0x42
    const val ADB_USB_PROTOCOL = 0x01

    const val SYNC_ID_SEND = "SEND"
    const val SYNC_ID_DATA = "DATA"
    const val SYNC_ID_DONE = "DONE"
    const val SYNC_ID_OKAY = "OKAY"
    const val SYNC_ID_FAIL = "FAIL"
    const val SYNC_ID_QUIT = "QUIT"
}

data class AdbHeader(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataChecksum: Int,
    val magic: Int,
) {
    fun isMagicValid(): Boolean = magic == (command xor -1)
}

data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbPacket) return false
        return command == other.command &&
            arg0 == other.arg0 &&
            arg1 == other.arg1 &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

data class AdbShellResult(
    val exitCode: Int,
    val stdout: String,
    val rawOutput: String,
)

sealed interface AdbSyncResponse {
    data object Okay : AdbSyncResponse
    data class Fail(val message: String) : AdbSyncResponse
    data class Other(val tag: String, val value: Int) : AdbSyncResponse
}

object AdbWireProtocol {
    fun payloadChecksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) {
            sum += (b.toInt() and 0xFF)
        }
        return sum
    }

    fun encodePacket(packet: AdbPacket, includeChecksum: Boolean = true): Pair<ByteArray, ByteArray> {
        val header = ByteBuffer.allocate(AdbConstants.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(packet.command)
            .putInt(packet.arg0)
            .putInt(packet.arg1)
            .putInt(packet.payload.size)
            .putInt(if (includeChecksum) payloadChecksum(packet.payload) else 0)
            .putInt(packet.command xor -1)
            .array()
        return header to packet.payload
    }

    fun decodeHeader(bytes: ByteArray): AdbHeader? {
        if (bytes.size != AdbConstants.HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val header = AdbHeader(
            command = buf.int,
            arg0 = buf.int,
            arg1 = buf.int,
            dataLength = buf.int,
            dataChecksum = buf.int,
            magic = buf.int,
        )
        return if (header.isMagicValid() && header.dataLength >= 0) header else null
    }

    fun verifyPayload(header: AdbHeader, payload: ByteArray, requireChecksum: Boolean = true): Boolean {
        if (payload.size != header.dataLength) return false
        if (!requireChecksum) return true
        return payloadChecksum(payload) == header.dataChecksum
    }

    fun nullTerminatedUtf8(text: String): ByteArray {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        return utf8 + byteArrayOf(0)
    }

    fun cnxnPacket(
        version: Int = AdbConstants.VERSION,
        maxPayload: Int = AdbConstants.MAX_PAYLOAD,
        banner: String = "host::duophone",
    ): AdbPacket = AdbPacket(
        command = AdbConstants.CMD_CNXN,
        arg0 = version,
        arg1 = maxPayload,
        payload = nullTerminatedUtf8(banner),
    )

    fun openPacket(localId: Int, service: String): AdbPacket = AdbPacket(
        command = AdbConstants.CMD_OPEN,
        arg0 = localId,
        arg1 = 0,
        payload = nullTerminatedUtf8(service),
    )

    fun okayPacket(localId: Int, remoteId: Int): AdbPacket = AdbPacket(
        command = AdbConstants.CMD_OKAY,
        arg0 = localId,
        arg1 = remoteId,
    )

    fun writePacket(localId: Int, remoteId: Int, payload: ByteArray): AdbPacket = AdbPacket(
        command = AdbConstants.CMD_WRTE,
        arg0 = localId,
        arg1 = remoteId,
        payload = payload,
    )

    fun closePacket(localId: Int, remoteId: Int): AdbPacket = AdbPacket(
        command = AdbConstants.CMD_CLSE,
        arg0 = localId,
        arg1 = remoteId,
    )

    fun commandName(command: Int): String = when (command) {
        AdbConstants.CMD_SYNC -> "SYNC"
        AdbConstants.CMD_CNXN -> "CNXN"
        AdbConstants.CMD_OPEN -> "OPEN"
        AdbConstants.CMD_OKAY -> "OKAY"
        AdbConstants.CMD_CLSE -> "CLSE"
        AdbConstants.CMD_WRTE -> "WRTE"
        AdbConstants.CMD_AUTH -> "AUTH"
        else -> "0x${command.toUInt().toString(16)}"
    }
}

object AdbShellMarker {
    const val DEFAULT_MARKER = "__DUO_ADB_EXIT__:"

    fun wrapCommand(command: String, marker: String = DEFAULT_MARKER): String {
        val singleQuoted = "'" + command.replace("'", "'\"'\"'") + "'"
        return "sh -c $singleQuoted; __duo_rc=$?; printf '\\n${marker}%d\\n' \"\$__duo_rc\""
    }

    fun parseOutput(rawOutput: String, marker: String = DEFAULT_MARKER): AdbShellResult {
        val normalized = rawOutput.replace("\r\n", "\n")
        val markerIdx = normalized.lastIndexOf(marker)
        if (markerIdx < 0) {
            return AdbShellResult(
                exitCode = -1,
                stdout = normalized.trimEnd('\n'),
                rawOutput = rawOutput,
            )
        }
        val afterMarker = normalized.substring(markerIdx + marker.length)
        val firstLine = afterMarker.lineSequence().firstOrNull().orEmpty().trim()
        val exitCode = firstLine.toIntOrNull() ?: -1
        val stdout = normalized.substring(0, markerIdx).trimEnd('\n')
        return AdbShellResult(
            exitCode = exitCode,
            stdout = stdout,
            rawOutput = rawOutput,
        )
    }
}

object AdbSyncCodec {
    private const val S_IFREG = 0x8000

    fun encodeSendRequest(remotePath: String, modeBits: Int = 0b111_101_101): ByteArray {
        val fullMode = if ((modeBits and 0xF000) == 0) (S_IFREG or modeBits) else modeBits
        val spec = "$remotePath,$fullMode".toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(8 + spec.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(AdbConstants.SYNC_ID_SEND.toByteArray(Charsets.US_ASCII))
            .putInt(spec.size)
            .put(spec)
            .array()
    }

    fun encodeDataChunk(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "Invalid chunk slice offset=$offset length=$length total=${data.size}"
        }
        return ByteBuffer.allocate(8 + length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(AdbConstants.SYNC_ID_DATA.toByteArray(Charsets.US_ASCII))
            .putInt(length)
            .put(data, offset, length)
            .array()
    }

    fun encodeDone(mtimeSeconds: Int): ByteArray =
        ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(AdbConstants.SYNC_ID_DONE.toByteArray(Charsets.US_ASCII))
            .putInt(mtimeSeconds)
            .array()

    fun encodeQuit(): ByteArray =
        ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(AdbConstants.SYNC_ID_QUIT.toByteArray(Charsets.US_ASCII))
            .putInt(0)
            .array()

    fun decodeResponse(bytes: ByteArray): AdbSyncResponse? {
        if (bytes.size < 8) return null
        val tag = String(bytes, 0, 4, Charsets.US_ASCII)
        val value = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return when (tag) {
            AdbConstants.SYNC_ID_OKAY -> AdbSyncResponse.Okay
            AdbConstants.SYNC_ID_FAIL -> {
                val msgLen = value.coerceIn(0, bytes.size - 8)
                val msg = String(bytes, 8, msgLen, Charsets.UTF_8)
                AdbSyncResponse.Fail(msg)
            }
            else -> AdbSyncResponse.Other(tag, value)
        }
    }
}
