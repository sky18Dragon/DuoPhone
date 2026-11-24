package com.duophone.app.device

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

interface AdbShellRunner {
    suspend fun isAdbInterfacePresent(): Boolean
    suspend fun runShell(command: String, timeoutMs: Long = 10_000L): Result<AdbShellResult>
    suspend fun pushFile(
        localBytes: ByteArray,
        remotePath: String,
        modeBits: Int = 0b111_101_101,
        timeoutMs: Long = 20_000L,
    ): Result<Unit>
}

class AndroidUsbAdbClient(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AdbShellRunner {
    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val operationMutex = Mutex()
    private val streamIdCounter = AtomicInteger(10)

    override suspend fun isAdbInterfacePresent(): Boolean = withContext(ioDispatcher) {
        findTargetAdbEndpoint() != null
    }

    override suspend fun runShell(command: String, timeoutMs: Long): Result<AdbShellResult> =
        operationMutex.withLock {
            withContext(ioDispatcher) {
                runCatching {
                    withAdbSession(timeoutMs) { session ->
                        val localId = streamIdCounter.incrementAndGet()
                        val wrapped = AdbShellMarker.wrapCommand(command)
                        session.sendPacket(AdbWireProtocol.openPacket(localId, "shell:$wrapped"))
                        val remoteId = session.awaitOkay(localId)
                        val output = ByteArrayOutputStream()
                        val deadline = System.currentTimeMillis() + timeoutMs
                        while (true) {
                            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(100L).toInt()
                            val packet = session.readPacket(remaining)
                                ?: throw IOException("ADB shell read timeout waiting for stream output")
                            when (packet.command) {
                                AdbConstants.CMD_WRTE -> {
                                    if (packet.arg1 == localId) {
                                        if (packet.payload.isNotEmpty()) {
                                            output.write(packet.payload)
                                        }
                                        session.sendPacket(AdbWireProtocol.okayPacket(localId, remoteId))
                                    }
                                }
                                AdbConstants.CMD_CLSE -> {
                                    if (packet.arg1 == localId) {
                                        session.sendPacket(AdbWireProtocol.closePacket(localId, remoteId))
                                        break
                                    }
                                }
                                AdbConstants.CMD_OKAY -> {
                                    // Additional flow-control ack; ignore while draining stdout.
                                }
                                else -> {
                                    DuoLogger.d(
                                        LogCategory.USB,
                                        "adb_shell_ignored_pkt",
                                        "Ignored ADB packet ${AdbWireProtocol.commandName(packet.command)}",
                                    )
                                }
                            }
                            if (System.currentTimeMillis() > deadline) {
                                throw IOException("ADB shell exceeded ${timeoutMs}ms timeout")
                            }
                        }
                        val raw = output.toString(Charsets.UTF_8.name())
                        AdbShellMarker.parseOutput(raw)
                    }
                }.onFailure { err ->
                    DuoLogger.w(LogCategory.USB, "adb_shell_fail", "ADB shell failed: ${err.message}")
                }
            }
        }

    override suspend fun pushFile(
        localBytes: ByteArray,
        remotePath: String,
        modeBits: Int,
        timeoutMs: Long,
    ): Result<Unit> = operationMutex.withLock {
        withContext(ioDispatcher) {
            runCatching {
                withAdbSession(timeoutMs) { session ->
                    val localId = streamIdCounter.incrementAndGet()
                    session.sendPacket(AdbWireProtocol.openPacket(localId, "sync:"))
                    val remoteId = session.awaitOkay(localId)

                    session.sendStreamBytes(
                        localId = localId,
                        remoteId = remoteId,
                        data = AdbSyncCodec.encodeSendRequest(remotePath, modeBits),
                    )

                    var offset = 0
                    while (offset < localBytes.size) {
                        val sliceLen = minOf(AdbConstants.SYNC_DATA_MAX, localBytes.size - offset)
                        val chunkFrame = AdbSyncCodec.encodeDataChunk(localBytes, offset, sliceLen)
                        session.sendStreamBytes(localId, remoteId, chunkFrame)
                        offset += sliceLen
                    }

                    val mtimeSec = (System.currentTimeMillis() / 1000L).toInt()
                    session.sendStreamBytes(localId, remoteId, AdbSyncCodec.encodeDone(mtimeSec))

                    val replyBuffer = ByteArrayOutputStream()
                    val deadline = System.currentTimeMillis() + timeoutMs
                    var syncReply: AdbSyncResponse? = null
                    while (syncReply == null) {
                        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(100L).toInt()
                        val packet = session.readPacket(remaining)
                            ?: throw IOException("ADB sync timeout waiting for DONE confirmation")
                        when (packet.command) {
                            AdbConstants.CMD_WRTE -> {
                                if (packet.arg1 == localId) {
                                    replyBuffer.write(packet.payload)
                                    session.sendPacket(AdbWireProtocol.okayPacket(localId, remoteId))
                                    syncReply = AdbSyncCodec.decodeResponse(replyBuffer.toByteArray())
                                }
                            }
                            AdbConstants.CMD_CLSE -> {
                                throw IOException("ADB sync stream closed prematurely by device")
                            }
                        }
                        if (System.currentTimeMillis() > deadline) {
                            throw IOException("ADB sync exceeded ${timeoutMs}ms timeout")
                        }
                    }

                    when (syncReply) {
                        is AdbSyncResponse.Okay -> {
                            runCatching {
                                session.sendStreamBytes(localId, remoteId, AdbSyncCodec.encodeQuit())
                                session.sendPacket(AdbWireProtocol.closePacket(localId, remoteId))
                            }
                            DuoLogger.i(
                                LogCategory.USB,
                                "adb_sync_push",
                                "Pushed ${localBytes.size} bytes to $remotePath via USB ADB",
                            )
                        }
                        is AdbSyncResponse.Fail -> {
                            throw IOException("ADB sync FAIL for $remotePath: ${syncReply.message}")
                        }
                        is AdbSyncResponse.Other -> {
                            throw IOException("Unexpected ADB sync response: ${syncReply.tag}")
                        }
                    }
                }
            }.onFailure { err ->
                DuoLogger.w(LogCategory.USB, "adb_push_fail", "ADB push failed: ${err.message}")
            }
        }
    }

    private fun findTargetAdbEndpoint(): AdbUsbTarget? {
        val manager = usbManager
        val candidate = manager.deviceList.values.firstOrNull { dev ->
            SupportedModemProfile.match(dev.vendorId, dev.productId) != null
        } ?: return null

        for (i in 0 until candidate.interfaceCount) {
            val intf = candidate.getInterface(i)
            if (intf.interfaceClass != AdbConstants.ADB_USB_CLASS ||
                intf.interfaceSubclass != AdbConstants.ADB_USB_SUBCLASS ||
                intf.interfaceProtocol != AdbConstants.ADB_USB_PROTOCOL
            ) {
                continue
            }
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && bulkIn == null) {
                        bulkIn = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT && bulkOut == null) {
                        bulkOut = ep
                    }
                }
            }
            if (bulkIn != null && bulkOut != null) {
                return AdbUsbTarget(candidate, intf, bulkIn, bulkOut)
            }
        }
        return null
    }

    private fun <T> withAdbSession(timeoutMs: Long, block: (AdbUsbSession) -> T): T {
        val target = findTargetAdbEndpoint()
            ?: throw IllegalStateException("QDC507 USB ADB interface (FF/42/01) is not attached")
        if (!usbManager.hasPermission(target.device)) {
            throw SecurityException("USB permission required before opening QDC507 ADB interface")
        }
        val connection = usbManager.openDevice(target.device)
            ?: throw IOException("Failed to open USB device connection for ADB")
        val claimed = connection.claimInterface(target.usbInterface, true)
        if (!claimed) {
            connection.close()
            throw IOException("Failed to claim USB ADB interface #${target.usbInterface.id}")
        }
        return try {
            val session = AdbUsbSession(
                connection = connection,
                bulkIn = target.bulkIn,
                bulkOut = target.bulkOut,
                defaultTimeoutMs = timeoutMs.coerceIn(1_000L, 20_000L).toInt(),
            )
            session.handshake()
            block(session)
        } finally {
            runCatching { connection.releaseInterface(target.usbInterface) }
            runCatching { connection.close() }
        }
    }

    private data class AdbUsbTarget(
        val device: UsbDevice,
        val usbInterface: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    private class AdbUsbSession(
        private val connection: UsbDeviceConnection,
        private val bulkIn: UsbEndpoint,
        private val bulkOut: UsbEndpoint,
        private val defaultTimeoutMs: Int,
    ) {
        private var peerMaxPayload = AdbConstants.MAX_PAYLOAD
        private var useChecksum = true

        fun handshake() {
            sendPacket(AdbWireProtocol.cnxnPacket())
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                val pkt = readPacket(2_500) ?: continue
                when (pkt.command) {
                    AdbConstants.CMD_CNXN -> {
                        peerMaxPayload = pkt.arg1.coerceIn(256, AdbConstants.MAX_PAYLOAD)
                        useChecksum = (pkt.arg0 == AdbConstants.VERSION)
                        return
                    }
                    AdbConstants.CMD_AUTH -> {
                        throw SecurityException(
                            "QDC507 adbd requested AUTH challenge; automatic RSA signing is not configured",
                        )
                    }
                }
            }
            throw IOException("Timed out waiting for QDC507 ADB CNXN handshake")
        }

        fun sendStreamBytes(localId: Int, remoteId: Int, data: ByteArray) {
            var offset = 0
            while (offset < data.size) {
                val chunkLen = minOf(peerMaxPayload, data.size - offset)
                val slice = data.copyOfRange(offset, offset + chunkLen)
                sendPacket(AdbWireProtocol.writePacket(localId, remoteId, slice))
                awaitOkay(localId)
                offset += chunkLen
            }
        }

        fun awaitOkay(localId: Int): Int {
            val deadline = System.currentTimeMillis() + defaultTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(100L).toInt()
                val pkt = readPacket(remaining) ?: continue
                when (pkt.command) {
                    AdbConstants.CMD_OKAY -> {
                        if (pkt.arg1 == localId) return pkt.arg0
                    }
                    AdbConstants.CMD_CLSE -> {
                        if (pkt.arg1 == localId) {
                            throw IOException("ADB stream $localId closed by device before OKAY")
                        }
                    }
                }
            }
            throw IOException("Timed out waiting for ADB OKAY on stream $localId")
        }

        fun sendPacket(packet: AdbPacket) {
            val (header, payload) = AdbWireProtocol.encodePacket(packet, includeChecksum = useChecksum)
            writeExact(header)
            if (payload.isNotEmpty()) {
                writeExact(payload)
            }
        }

        fun readPacket(timeoutMs: Int): AdbPacket? {
            val headerBytes = readExact(AdbConstants.HEADER_SIZE, timeoutMs) ?: return null
            val header = AdbWireProtocol.decodeHeader(headerBytes)
                ?: throw IOException("Corrupt ADB header received")
            val payload = if (header.dataLength > 0) {
                readExact(header.dataLength, timeoutMs)
                    ?: throw IOException("Truncated ADB payload (expected ${header.dataLength} bytes)")
            } else {
                ByteArray(0)
            }
            if (!AdbWireProtocol.verifyPayload(header, payload, requireChecksum = useChecksum)) {
                throw IOException("ADB payload checksum mismatch for ${AdbWireProtocol.commandName(header.command)}")
            }
            return AdbPacket(header.command, header.arg0, header.arg1, payload)
        }

        private fun writeExact(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val len = minOf(peerMaxPayload.coerceAtLeast(64), bytes.size - offset)
                val chunk = if (offset == 0 && len == bytes.size) bytes else bytes.copyOfRange(offset, offset + len)
                val written = connection.bulkTransfer(bulkOut, chunk, chunk.size, defaultTimeoutMs)
                if (written <= 0) {
                    throw IOException("USB ADB bulk OUT failed at offset=$offset (rc=$written)")
                }
                offset += written
            }
        }

        private fun readExact(expectedBytes: Int, timeoutMs: Int): ByteArray? {
            val out = ByteArray(expectedBytes)
            val maxPacket = maxOf(64, bulkIn.maxPacketSize)
            val scratch = ByteArray(maxPacket)
            var offset = 0
            val deadline = System.currentTimeMillis() + timeoutMs
            while (offset < expectedBytes) {
                val remainingMs = (deadline - System.currentTimeMillis()).toInt()
                if (remainingMs <= 0) {
                    return if (offset == 0) null else throw IOException("ADB USB read partial timeout ($offset/$expectedBytes)")
                }
                val readLen = connection.bulkTransfer(
                    bulkIn,
                    scratch,
                    minOf(scratch.size, expectedBytes - offset),
                    remainingMs,
                )
                if (readLen < 0) {
                    return if (offset == 0) null else throw IOException("ADB USB bulk IN error (rc=$readLen)")
                }
                if (readLen == 0) {
                    // Zero-length USB packet: retry until deadline.
                    continue
                }
                System.arraycopy(scratch, 0, out, offset, readLen)
                offset += readLen
            }
            return out
        }
    }
}
