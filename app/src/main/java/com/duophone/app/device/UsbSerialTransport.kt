package com.duophone.app.device

import java.io.IOException

/**
 * Transport abstraction for USB bulk serial AT I/O.
 * Decouples protocol/state-machine logic from Android UsbManager for deterministic unit testing.
 */
interface UsbSerialTransport {
    val isOpen: Boolean
    val activeInterfaceId: Int
    val currentDevice: UsbDeviceSummary?

    /**
     * Opens the target USB device, safely probes candidate AT interfaces, and leaves
     * the winning AT interface claimed. Throws IOException on failure.
     */
    suspend fun openAndProbe(onProgress: (String) -> Unit = {}): UsbDeviceSummary

    /** Writes raw bytes to the bulk OUT endpoint. Returns number of bytes written. */
    @Throws(IOException::class)
    suspend fun write(data: ByteArray, timeoutMs: Int = 1500): Int

    /**
     * Reads up to [buffer.size] bytes from the bulk IN endpoint.
     * Returns >0 bytes read, 0 on benign timeout, or throws IOException on hard disconnect/error.
     */
    @Throws(IOException::class)
    suspend fun read(buffer: ByteArray, timeoutMs: Int = 250): Int

    /** Sends CDC SET_CONTROL_LINE_STATE (DTR/RTS). */
    suspend fun setControlLineState(dtr: Boolean, rts: Boolean): Boolean

    /** Closes endpoints, releases interface, and closes device connection. */
    fun close()
}
