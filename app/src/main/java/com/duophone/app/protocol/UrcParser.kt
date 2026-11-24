package com.duophone.app.protocol

enum class CallEndReason {
    REMOTE_HANGUP,
    BUSY,
    NO_ANSWER,
    NO_DIAL_TONE,
}

sealed interface UrcEvent {
    val rawLine: String

    data class Ring(override val rawLine: String) : UrcEvent
    data class CallerId(val number: String, val type: Int?, override val rawLine: String) : UrcEvent
    data class NewSmsIndicator(val storage: String, val index: Int, override val rawLine: String) : UrcEvent
    data class DirectSmsHeader(val header: String, override val rawLine: String) : UrcEvent
    data class CallConnected(override val rawLine: String) : UrcEvent
    data class CallEnded(val reason: CallEndReason, override val rawLine: String) : UrcEvent
    data class NetworkRegistrationChanged(
        val domain: String,
        val stat: Int,
        override val rawLine: String,
    ) : UrcEvent
    data class SignalChanged(val rssi: Int, val ber: Int, override val rawLine: String) : UrcEvent
    data class SimStateChanged(val state: String, override val rawLine: String) : UrcEvent
}

object UrcParser {
    private val cmtiRegex = Regex("""^\+CMTI:\s*"([^"]+)"\s*,\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val clipRegex = Regex("""^\+CLIP:\s*"([^"]*)"(?:\s*,\s*(\d+))?""", RegexOption.IGNORE_CASE)
    private val regUrcRegex = Regex("""^\+(CREG|CGREG|CEREG):\s*(\d+)(?:\s*,.*)?$""", RegexOption.IGNORE_CASE)
    private val qindCsqRegex = Regex("""^\+QIND:\s*"csq"\s*,\s*(\d+)\s*,\s*(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Classifies a complete trimmed line as an unsolicited URC event.
     *
     * @param hasInFlightCommand when true, multi-field query responses like `+CEREG: 0,1`
     *   or `+CPIN: READY` are kept for the active AT command instead of being stolen as URCs.
     */
    fun parse(rawLine: String, hasInFlightCommand: Boolean): UrcEvent? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        val upper = line.uppercase()

        return when {
            upper == "RING" || upper.startsWith("+CRING:") -> UrcEvent.Ring(line)
            upper.startsWith("+CLIP:") -> {
                val m = clipRegex.find(line)
                val number = m?.groupValues?.getOrNull(1).orEmpty()
                val type = m?.groupValues?.getOrNull(2)?.toIntOrNull()
                UrcEvent.CallerId(number, type, line)
            }
            upper.startsWith("+CMTI:") -> {
                val m = cmtiRegex.find(line) ?: return null
                val mem = m.groupValues[1]
                val idx = m.groupValues[2].toIntOrNull() ?: return null
                UrcEvent.NewSmsIndicator(mem, idx, line)
            }
            upper.startsWith("+CMT:") -> UrcEvent.DirectSmsHeader(line, line)
            upper == "NO CARRIER" -> UrcEvent.CallEnded(CallEndReason.REMOTE_HANGUP, line)
            upper == "BUSY" -> UrcEvent.CallEnded(CallEndReason.BUSY, line)
            upper == "NO ANSWER" -> UrcEvent.CallEnded(CallEndReason.NO_ANSWER, line)
            upper == "NO DIALTONE" || upper == "NO DIAL TONE" -> UrcEvent.CallEnded(CallEndReason.NO_DIAL_TONE, line)
            upper == "CONNECT" || upper == "MO CONNECTED" -> UrcEvent.CallConnected(line)
            upper.startsWith("+QIND:") -> {
                val m = qindCsqRegex.find(line) ?: return null
                val rssi = m.groupValues[1].toIntOrNull() ?: return null
                val ber = m.groupValues[2].toIntOrNull() ?: 99
                UrcEvent.SignalChanged(rssi, ber, line)
            }
            !hasInFlightCommand && upper.startsWith("+CPIN:") -> {
                UrcEvent.SimStateChanged(line.substringAfter(":").trim(), line)
            }
            upper.startsWith("+CREG:") || upper.startsWith("+CGREG:") || upper.startsWith("+CEREG:") -> {
                // Query reply format is "+CEREG: <n>,<stat>[,...]"; URC format when <n>=1 is "+CEREG: <stat>".
                // When a command is in flight, only treat unambiguous single-field lines as URC.
                val payload = line.substringAfter(":").trim()
                val parts = payload.split(",").map { it.trim() }
                if (hasInFlightCommand && parts.size >= 2) {
                    null
                } else {
                    val m = regUrcRegex.find(line) ?: return null
                    val domain = m.groupValues[1].uppercase()
                    val stat = if (parts.size >= 2) {
                        parts[1].toIntOrNull() ?: return null
                    } else {
                        parts[0].toIntOrNull() ?: return null
                    }
                    UrcEvent.NetworkRegistrationChanged(domain, stat, line)
                }
            }
            else -> null
        }
    }
}

/**
 * Incremental line framer for arbitrary USB bulk packet boundaries.
 */
class LineStreamFramer(private val maxPendingChars: Int = 8192) {
    private val pending = StringBuilder()

    fun append(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        pending.append(chunk)
        val out = mutableListOf<String>()
        var idx = 0
        while (idx < pending.length) {
            val ch = pending[idx]
            if (ch == '\r' || ch == '\n') {
                val line = pending.substring(0, idx)
                // Consume optional paired \n after \r
                val nextIdx = if (ch == '\r' && idx + 1 < pending.length && pending[idx + 1] == '\n') {
                    idx + 2
                } else {
                    idx + 1
                }
                pending.delete(0, nextIdx)
                idx = 0
                if (line.isNotBlank()) {
                    out.add(line)
                }
            } else {
                idx++
            }
        }
        if (pending.length > maxPendingChars) {
            pending.delete(0, pending.length - maxPendingChars / 2)
        }
        return out
    }

    fun hasPrompt(): Boolean = pending.contains(">")

    fun consumePrompt() {
        val pos = pending.indexOf('>')
        if (pos >= 0) {
            var end = pos + 1
            if (end < pending.length && pending[end] == ' ') end++
            pending.delete(0, end)
        }
    }

    fun reset() {
        pending.setLength(0)
    }
}
