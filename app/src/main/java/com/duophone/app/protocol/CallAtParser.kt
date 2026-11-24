package com.duophone.app.protocol

enum class ClccCallStatus(val code: Int, val label: String) {
    ACTIVE(0, "通话中"),
    HELD(1, "保持"),
    DIALING(2, "拨号中"),
    ALERTING(3, "振铃中"),
    INCOMING(4, "来电振铃"),
    WAITING(5, "呼叫等待"),
    DISCONNECTED(6, "已结束"),
    ;

    companion object {
        fun fromCode(code: Int): ClccCallStatus? = entries.firstOrNull { it.code == code }
    }
}

data class ModemCallEntry(
    val index: Int,
    val isIncoming: Boolean,
    val status: ClccCallStatus,
    val isVoice: Boolean,
    val isMultiparty: Boolean,
    val number: String,
)

object CallAtParser {
    private const val MAX_DIAL_LENGTH = 32

    fun normalizeDialNumber(raw: String): String? {
        if (raw.any { it == '\r' || it == '\n' }) return null
        val compact = raw.filter { it != ' ' && it != '-' && it != '(' && it != ')' }
        if (compact.isEmpty() || compact.length > MAX_DIAL_LENGTH) return null
        for (i in compact.indices) {
            val ch = compact[i]
            if (ch in '0'..'9' || ch == '*' || ch == '#') continue
            if (ch == '+' && i == 0) continue
            return null
        }
        if (compact.none { it in '0'..'9' }) return null
        return compact
    }

    fun normalizeDtmfTone(tone: String): String? {
        if (tone.length != 1) return null
        val ch = tone[0]
        return if (ch in '0'..'9' || ch == '*' || ch == '#') tone else null
    }

    fun dtmfCommand(tone: String): String? {
        val valid = normalizeDtmfTone(tone) ?: return null
        return "AT+VTS=\"$valid\""
    }

    fun parseClccLine(rawLine: String): ModemCallEntry? {
        val line = rawLine.trim()
        if (!line.startsWith("+CLCC:", ignoreCase = true)) return null
        val payload = line.substringAfter(":").trim()
        val fields = splitCsv(payload)
        if (fields.size < 5) return null

        val index = fields[0].toIntOrNull() ?: return null
        val dir = fields[1].toIntOrNull() ?: return null
        if (dir != 0 && dir != 1) return null
        val statusCode = fields[2].toIntOrNull() ?: return null
        val status = ClccCallStatus.fromCode(statusCode) ?: return null
        val mode = fields[3].toIntOrNull() ?: return null
        val mpty = fields[4].toIntOrNull() ?: return null
        val number = fields.getOrNull(5)?.let(::unquote).orEmpty()

        return ModemCallEntry(
            index = index,
            isIncoming = dir == 1,
            status = status,
            isVoice = mode == 0,
            isMultiparty = mpty != 0,
            number = number,
        )
    }

    fun parseClccResponse(raw: String): List<ModemCallEntry> {
        return raw.replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")
            .mapNotNull(::parseClccLine)
            .filter { it.isVoice }
    }

    fun parseCeerResponse(raw: String): String {
        val line = raw.replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")
            .map { it.trim() }
            .firstOrNull { it.startsWith("+CEER:", ignoreCase = true) }
            ?: return ""
        return line.substringAfter(":").trim()
    }

    private fun splitCsv(value: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in value) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    sb.append(ch)
                }
                ch == ',' && !inQuotes -> {
                    fields.add(sb.toString().trim())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
        }
        fields.add(sb.toString().trim())
        return fields
    }

    private fun unquote(value: String): String {
        val t = value.trim()
        return if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) {
            t.substring(1, t.length - 1)
        } else {
            t
        }
    }
}
