package com.duophone.app.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogCategory(val tag: String) {
    USB("DUO_USB"),
    AT("DUO_AT"),
    URC("DUO_URC"),
    SMS("DUO_SMS"),
    CALL("DUO_CALL"),
    AUDIO("DUO_AUDIO"),
    MODEM("DUO_MODEM"),
    NETWORK("DUO_NETWORK"),
    POWER("DUO_POWER"),
    DISPLAY("DUO_DISPLAY"),
    INPUT("DUO_INPUT"),
    SERVICE("DUO_SERVICE"),
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timestampMs: Long,
    val category: LogCategory,
    val level: LogLevel,
    val state: String,
    val operation: String,
    val message: String,
    val durationMs: Long? = null,
) {
    fun formatLine(): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
        val dur = durationMs?.let { " (${it}ms)" } ?: ""
        val st = if (state.isNotBlank()) " [$state]" else ""
        return "$time ${level.name.first()}/${category.tag}$st $operation: $message$dur"
    }
}

/**
 * Redacts PII (phone numbers, IMEI/IMSI/ICCID, verification codes, SMS bodies) by default
 * so diagnostic logs can be safely inspected and shared without leaking user data.
 */
object PiiRedactor {
    private val internationalPhone = Regex("""\+\d{7,15}""")
    private val longNumericId = Regex("""(?<![0-9A-Fa-f])(\d{14,20})(?![0-9A-Fa-f])""")
    private val phoneCandidate = Regex("""(?<![0-9])(1[3-9]\d{9}|\d{7,13})(?![0-9])""")
    private val otpHint = Regex(
        """(验证码|校验码|动态码|识别码|确认码|code|otp|passcode)([^0-9]{0,12})(\d{4,8})""",
        RegexOption.IGNORE_CASE,
    )
    private val dialCommand = Regex("""(ATD)([+\d*#,]+)(;?)""", RegexOption.IGNORE_CASE)
    private val quotedPhone = Regex("""("\+?\d{5,16}")""")

    fun redact(raw: String): String {
        if (raw.isBlank()) return raw
        var out = raw
        out = dialCommand.replace(out) { m ->
            "${m.groupValues[1]}${maskDigits(m.groupValues[2])}${m.groupValues[3]}"
        }
        out = otpHint.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[OTP:${m.groupValues[3].length}]"
        }
        out = internationalPhone.replace(out) { m -> maskDigits(m.value) }
        out = longNumericId.replace(out) { m ->
            val v = m.groupValues[1]
            "${v.take(4)}******${v.takeLast(4)}"
        }
        out = quotedPhone.replace(out) { m ->
            val inner = m.value.trim('"')
            "\"${maskDigits(inner)}\""
        }
        out = phoneCandidate.replace(out) { m -> maskDigits(m.groupValues[1]) }
        return out
    }

    fun maskIdentifier(value: String): String {
        val clean = value.trim()
        if (clean.length <= 6) return "***"
        return "${clean.take(3)}***${clean.takeLast(3)}"
    }

    private fun maskDigits(value: String): String {
        if (value.length <= 4) return "****"
        return "${value.take(3)}****${value.takeLast(2)}"
    }
}

interface LogSink {
    fun emit(level: LogLevel, tag: String, line: String, throwable: Throwable? = null)
}

object AndroidLogSink : LogSink {
    override fun emit(level: LogLevel, tag: String, line: String, throwable: Throwable?) {
        runCatching {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, line, throwable)
                LogLevel.INFO -> Log.i(tag, line, throwable)
                LogLevel.WARN -> Log.w(tag, line, throwable)
                LogLevel.ERROR -> Log.e(tag, line, throwable)
            }
        }
    }
}

class FileAndAndroidLogSink(private val logFile: File) : LogSink {
    private val fileLock = Any()
    private val maxBytes = 256 * 1024L

    override fun emit(level: LogLevel, tag: String, line: String, throwable: Throwable?) {
        AndroidLogSink.emit(level, tag, line, throwable)
        synchronized(fileLock) {
            runCatching {
                logFile.parentFile?.mkdirs()
                if (logFile.exists() && logFile.length() > maxBytes) {
                    val tail = logFile.readLines().takeLast(200).joinToString("\n") + "\n"
                    logFile.writeText(tail)
                }
                logFile.appendText(line + "\n")
            }
        }
    }
}

object DuoLogger {
    private const val MAX_ENTRIES = 500
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Volatile
    var sink: LogSink = AndroidLogSink

    @Volatile
    var redactEnabled: Boolean = true

    fun log(
        category: LogCategory,
        level: LogLevel,
        operation: String,
        message: String,
        state: String = "",
        durationMs: Long? = null,
        throwable: Throwable? = null,
    ) {
        val safeMessage = if (redactEnabled) PiiRedactor.redact(message) else message
        val fullMessage = if (throwable != null) {
            "$safeMessage | ex=${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
        } else {
            safeMessage
        }
        val entry = LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = category,
            level = level,
            state = state,
            operation = operation,
            message = fullMessage,
            durationMs = durationMs,
        )
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
        sink.emit(level, category.tag, entry.formatLine(), throwable)
    }

    fun d(category: LogCategory, operation: String, message: String, state: String = "", durationMs: Long? = null) =
        log(category, LogLevel.DEBUG, operation, message, state, durationMs)

    fun i(category: LogCategory, operation: String, message: String, state: String = "", durationMs: Long? = null) =
        log(category, LogLevel.INFO, operation, message, state, durationMs)

    fun w(category: LogCategory, operation: String, message: String, state: String = "", durationMs: Long? = null) =
        log(category, LogLevel.WARN, operation, message, state, durationMs)

    fun e(
        category: LogCategory,
        operation: String,
        message: String,
        state: String = "",
        durationMs: Long? = null,
        throwable: Throwable? = null,
    ) = log(category, LogLevel.ERROR, operation, message, state, durationMs, throwable)

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }
}
