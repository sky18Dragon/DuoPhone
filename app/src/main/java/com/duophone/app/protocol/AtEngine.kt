package com.duophone.app.protocol

import com.duophone.app.device.UsbSerialTransport
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Serialized AT command engine with continuous background USB reader and real-time URC demuxing.
 *
 * Guarantees:
 * - Only one AT transaction executes at a time via [commandMutex].
 * - Incoming bulk bytes are line-framed continuously; URC lines are dispatched on [urcEvents]
 *   without corrupting in-flight AT responses.
 * - Non-idempotent commands (dial, SMS send, DTMF, reset, NV write) are never auto-retried.
 */
class AtEngine(
    private val transport: UsbSerialTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onTransportLost: (Throwable) -> Unit = {},
) {
    private class ActiveRequest(
        val command: String,
        val expectPrompt: Boolean,
        val startMs: Long,
        val lines: MutableList<String> = mutableListOf(),
        val deferred: CompletableDeferred<AtResult> = CompletableDeferred(),
    )

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val commandMutex = Mutex()
    private val stateLock = Any()

    private val framer = LineStreamFramer()
    private var readerJob: Job? = null
    private var activeRequest: ActiveRequest? = null

    private val _urcEvents = MutableSharedFlow<UrcEvent>(extraBufferCapacity = 64)
    val urcEvents: SharedFlow<UrcEvent> = _urcEvents.asSharedFlow()

    val isRunning: Boolean
        get() = readerJob?.isActive == true && transport.isOpen

    fun startReader() {
        stopReader()
        synchronized(stateLock) {
            framer.reset()
        }
        readerJob = scope.launch {
            val buffer = ByteArray(4096)
            DuoLogger.i(LogCategory.AT, "reader_start", "AT reader loop started")
            while (isActive && transport.isOpen) {
                val count = try {
                    transport.read(buffer, timeoutMs = 250)
                } catch (e: IOException) {
                    DuoLogger.e(LogCategory.AT, "reader_io_error", "Transport read failed", throwable = e)
                    failActiveRequest(AtStatus.TRANSPORT_ERROR, e.message ?: "USB IO error")
                    onTransportLost(e)
                    break
                } catch (e: Exception) {
                    DuoLogger.e(LogCategory.AT, "reader_unexpected_error", "Unexpected read error", throwable = e)
                    failActiveRequest(AtStatus.TRANSPORT_ERROR, e.message ?: "Read error")
                    onTransportLost(e)
                    break
                }

                if (count <= 0) continue
                val chunk = String(buffer, 0, count, Charsets.ISO_8859_1)
                processIncomingChunk(chunk)
            }
            DuoLogger.i(LogCategory.AT, "reader_stop", "AT reader loop stopped")
        }
    }

    fun stopReader() {
        readerJob?.cancel()
        readerJob = null
        failActiveRequest(AtStatus.TRANSPORT_ERROR, "Reader stopped")
        synchronized(stateLock) {
            framer.reset()
        }
    }

    fun release() {
        stopReader()
        scope.cancel()
    }

    suspend fun execute(
        command: String,
        timeoutMs: Long = 5000L,
        maxRetries: Int = 0,
        expectPrompt: Boolean = false,
    ): AtResult {
        val allowedRetries = if (AtCommandPolicy.isRetryable(command)) maxRetries.coerceIn(0, 2) else 0
        var lastResult: AtResult? = null
        for (attempt in 0..allowedRetries) {
            val result = executeOnce(command, timeoutMs, expectPrompt)
            lastResult = result
            if (result.isOk || result.status == AtStatus.TRANSPORT_ERROR) {
                return result
            }
            if (attempt < allowedRetries) {
                DuoLogger.w(
                    LogCategory.AT,
                    "retry",
                    "Retrying idempotent cmd='${command.trim()}' attempt=${attempt + 2}/${allowedRetries + 1} " +
                        "prevStatus=${result.status}",
                )
                delay(150)
            }
        }
        return lastResult ?: AtResult(command, AtStatus.ERROR, errorDetail = "No attempt executed")
    }

    /**
     * Sends raw payload + Ctrl+Z (0x1A) after receiving '>' prompt (used by SMS PDU submission).
     * Never auto-retried.
     */
    suspend fun sendPromptPayload(
        payload: String,
        timeoutMs: Long = 60_000L,
    ): AtResult = commandMutex.withLock {
        if (!transport.isOpen) {
            return@withLock AtResult("<SMS_PAYLOAD>", AtStatus.TRANSPORT_ERROR, errorDetail = "USB 未连接")
        }
        val req = ActiveRequest(
            command = "<SMS_PAYLOAD>",
            expectPrompt = false,
            startMs = System.currentTimeMillis(),
        )
        synchronized(stateLock) {
            activeRequest = req
        }
        try {
            val bytes = payload.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x1A)
            transport.write(bytes, timeoutMs = 3000)
            val completed = withTimeoutOrNull(timeoutMs) { req.deferred.await() }
            completed ?: AtResult(
                command = "<SMS_PAYLOAD>",
                status = AtStatus.TIMEOUT,
                lines = synchronized(stateLock) { req.lines.toList() },
                raw = synchronized(stateLock) { req.lines.joinToString("\n") },
                errorDetail = "发送超时 (${timeoutMs}ms)",
                durationMs = System.currentTimeMillis() - req.startMs,
            )
        } catch (e: IOException) {
            AtResult(
                command = "<SMS_PAYLOAD>",
                status = AtStatus.TRANSPORT_ERROR,
                errorDetail = e.message,
                durationMs = System.currentTimeMillis() - req.startMs,
            )
        } finally {
            synchronized(stateLock) {
                if (activeRequest === req) activeRequest = null
            }
        }
    }

    suspend fun abortPrompt() = commandMutex.withLock {
        if (transport.isOpen) {
            runCatching { transport.write(byteArrayOf(0x1B), timeoutMs = 800) }
        }
    }

    private suspend fun executeOnce(
        command: String,
        timeoutMs: Long,
        expectPrompt: Boolean,
    ): AtResult = commandMutex.withLock {
        val cleanCmd = command.trim()
        val startMs = System.currentTimeMillis()
        if (!transport.isOpen) {
            return@withLock AtResult(cleanCmd, AtStatus.TRANSPORT_ERROR, errorDetail = "USB 未连接")
        }
        if (readerJob?.isActive != true) {
            startReader()
        }

        val req = ActiveRequest(
            command = cleanCmd,
            expectPrompt = expectPrompt,
            startMs = startMs,
        )
        synchronized(stateLock) {
            activeRequest = req
        }

        DuoLogger.d(LogCategory.AT, "tx", "-> $cleanCmd")

        try {
            val wireBytes = (cleanCmd + "\r\n").toByteArray(Charsets.ISO_8859_1)
            transport.write(wireBytes, timeoutMs = 1500)

            val completed = withTimeoutOrNull(timeoutMs) { req.deferred.await() }
            val result = completed ?: run {
                val snapshot = synchronized(stateLock) { req.lines.toList() }
                AtResult(
                    command = cleanCmd,
                    status = AtStatus.TIMEOUT,
                    lines = snapshot,
                    raw = snapshot.joinToString("\n"),
                    errorDetail = "AT 指令超时 (${timeoutMs}ms)",
                    durationMs = System.currentTimeMillis() - startMs,
                )
            }

            DuoLogger.d(
                LogCategory.AT,
                "rx",
                "<- $cleanCmd | status=${result.status} lines=${result.lines.size} " +
                    "preview=${result.payloadLines.take(2).joinToString(" / ")}",
                durationMs = result.durationMs,
            )
            result
        } catch (e: IOException) {
            DuoLogger.e(LogCategory.AT, "tx_error", "Write failed for $cleanCmd", throwable = e)
            onTransportLost(e)
            AtResult(
                command = cleanCmd,
                status = AtStatus.TRANSPORT_ERROR,
                errorDetail = e.message ?: "USB 写入失败",
                durationMs = System.currentTimeMillis() - startMs,
            )
        } finally {
            synchronized(stateLock) {
                if (activeRequest === req) activeRequest = null
            }
        }
    }

    private fun processIncomingChunk(chunk: String) {
        val linesToDispatch: List<String>
        val promptMatchedRequest: ActiveRequest?

        synchronized(stateLock) {
            linesToDispatch = framer.append(chunk)
            val req = activeRequest
            promptMatchedRequest = if (req != null && req.expectPrompt && framer.hasPrompt()) {
                framer.consumePrompt()
                req
            } else {
                null
            }
        }

        for (rawLine in linesToDispatch) {
            handleCompleteLine(rawLine)
        }

        if (promptMatchedRequest != null) {
            val duration = System.currentTimeMillis() - promptMatchedRequest.startMs
            val snapshot = synchronized(stateLock) { promptMatchedRequest.lines.toList() }
            promptMatchedRequest.deferred.complete(
                AtResult(
                    command = promptMatchedRequest.command,
                    status = AtStatus.PROMPT_READY,
                    lines = snapshot,
                    raw = snapshot.joinToString("\n"),
                    durationMs = duration,
                ),
            )
        }
    }

    private fun handleCompleteLine(rawLine: String) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return

        val req = synchronized(stateLock) { activeRequest }
        val urc = UrcParser.parse(trimmed, hasInFlightCommand = req != null)
        if (urc != null) {
            DuoLogger.i(LogCategory.URC, "event", "${urc.javaClass.simpleName}: ${urc.rawLine}")
            _urcEvents.tryEmit(urc)
            return
        }

        if (req == null) {
            DuoLogger.d(LogCategory.AT, "unsolicited_other", trimmed)
            return
        }

        // Ignore echo of the command itself
        if (trimmed.equals(req.command, ignoreCase = true)) return

        val upper = trimmed.uppercase()
        val terminalStatus: AtStatus? = when {
            upper == "OK" -> AtStatus.OK
            upper == "ERROR" -> AtStatus.ERROR
            upper.startsWith("+CME ERROR:") -> AtStatus.CME_ERROR
            upper.startsWith("+CMS ERROR:") -> AtStatus.CMS_ERROR
            else -> null
        }

        val completedLines = synchronized(stateLock) {
            req.lines.add(trimmed)
            if (terminalStatus != null) req.lines.toList() else null
        }

        if (terminalStatus != null && completedLines != null) {
            val duration = System.currentTimeMillis() - req.startMs
            val errorDetail = if (terminalStatus != AtStatus.OK) trimmed else null
            req.deferred.complete(
                AtResult(
                    command = req.command,
                    status = terminalStatus,
                    lines = completedLines,
                    raw = completedLines.joinToString("\n"),
                    errorDetail = errorDetail,
                    durationMs = duration,
                ),
            )
        }
    }

    private fun failActiveRequest(status: AtStatus, reason: String) {
        val req = synchronized(stateLock) {
            val current = activeRequest
            activeRequest = null
            current
        } ?: return
        val lines = synchronized(stateLock) { req.lines.toList() }
        req.deferred.complete(
            AtResult(
                command = req.command,
                status = status,
                lines = lines,
                raw = lines.joinToString("\n"),
                errorDetail = reason,
                durationMs = System.currentTimeMillis() - req.startMs,
            ),
        )
    }
}
