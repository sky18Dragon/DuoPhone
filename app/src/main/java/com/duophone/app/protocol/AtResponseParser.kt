package com.duophone.app.protocol

enum class SimState(val label: String) {
    UNKNOWN("未知"),
    READY("就绪 (READY)"),
    SIM_PIN("需要 PIN"),
    SIM_PUK("需要 PUK"),
    NOT_INSERTED("未插卡"),
    ERROR("SIM 异常"),
}

enum class NetworkRegState(val code: Int, val label: String) {
    NOT_REGISTERED(0, "未注册"),
    REGISTERED_HOME(1, "已注册（归属网）"),
    SEARCHING(2, "搜网中"),
    DENIED(3, "注册被拒"),
    UNKNOWN(4, "未知"),
    REGISTERED_ROAMING(5, "已注册（漫游）"),
    ;

    val isRegistered: Boolean get() = this == REGISTERED_HOME || this == REGISTERED_ROAMING

    companion object {
        fun fromCode(code: Int): NetworkRegState =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class SignalReading(
    val rssi: Int = 99,
    val ber: Int = 99,
    val dbm: Int? = null,
    val bars: Int = 0,
)

data class OperatorReading(
    val mode: Int? = null,
    val name: String = "",
    val accessTechCode: Int? = null,
) {
    val accessTechLabel: String
        get() = when (accessTechCode) {
            0, 1, 3 -> "2G (GSM/EDGE)"
            2, 4, 5, 6 -> "3G (UMTS/HSPA)"
            7 -> "4G (LTE)"
            8 -> "4G (LTE-M)"
            9 -> "NB-IoT"
            11, 12 -> "5G (NR)"
            else -> ""
        }
}

data class RegistrationSnapshot(
    val creg: NetworkRegState = NetworkRegState.UNKNOWN,
    val cgreg: NetworkRegState = NetworkRegState.UNKNOWN,
    val cereg: NetworkRegState = NetworkRegState.UNKNOWN,
) {
    val isRegistered: Boolean get() = cereg.isRegistered || creg.isRegistered || cgreg.isRegistered
    val primaryState: NetworkRegState
        get() = when {
            cereg.isRegistered -> cereg
            creg.isRegistered -> creg
            cgreg.isRegistered -> cgreg
            cereg != NetworkRegState.UNKNOWN -> cereg
            creg != NetworkRegState.UNKNOWN -> creg
            else -> cgreg
        }
}

data class ModemIdentity(
    val manufacturer: String = "",
    val model: String = "",
    val revision: String = "",
    val imei: String = "",
) {
    val isQdc507: Boolean
        get() = revision.contains("QDC507", ignoreCase = true) ||
            model.contains("QDC507", ignoreCase = true)
}

data class UsbConfigInfo(
    val usbnetMode: Int? = null,
    val usbcfgRaw: String = "",
    val imsRaw: String = "",
    val qpcmvSupportRaw: String = "",
) {
    val usbnetLabel: String
        get() = when (usbnetMode) {
            0 -> "0 · QMI / RMNET"
            1 -> "1 · CDC-ECM"
            2 -> "2 · MBIM"
            3 -> "3 · RNDIS"
            null -> "未知"
            else -> "$usbnetMode · 自定义"
        }
}

object AtResponseParser {
    private val csqRegex = Regex("""\+CSQ:\s*(\d+)\s*,\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val copsRegex = Regex("""\+COPS:\s*(\d+)(?:\s*,\s*(\d+)\s*,\s*"([^"]*)"(?:\s*,\s*(\d+))?)?""", RegexOption.IGNORE_CASE)
    private val regRegex = Regex("""\+(?:CREG|CGREG|CEREG):\s*(\d+)\s*,\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val qcfgIntTailRegex = Regex("""(\d+)\s*$""")

    fun parseCpin(result: AtResult): SimState {
        val text = result.raw.uppercase()
        return when {
            text.contains("+CPIN: READY") -> SimState.READY
            text.contains("+CPIN: SIM PIN") -> SimState.SIM_PIN
            text.contains("+CPIN: SIM PUK") -> SimState.SIM_PUK
            text.contains("SIM NOT INSERTED") || text.contains("CME ERROR: 10") -> SimState.NOT_INSERTED
            !result.isOk -> SimState.ERROR
            else -> SimState.UNKNOWN
        }
    }

    fun parseCsq(result: AtResult): SignalReading {
        val m = csqRegex.find(result.raw) ?: return SignalReading()
        val rssi = m.groupValues[1].toIntOrNull() ?: 99
        val ber = m.groupValues[2].toIntOrNull() ?: 99
        if (rssi >= 99 || rssi < 0) return SignalReading(rssi = rssi, ber = ber, dbm = null, bars = 0)
        val dbm = -113 + 2 * rssi
        val bars = when {
            rssi <= 5 -> 1
            rssi <= 11 -> 2
            rssi <= 17 -> 3
            rssi <= 23 -> 4
            else -> 5
        }
        return SignalReading(rssi = rssi, ber = ber, dbm = dbm, bars = bars)
    }

    fun parseCops(result: AtResult): OperatorReading {
        val m = copsRegex.find(result.raw) ?: return OperatorReading()
        val mode = m.groupValues[1].toIntOrNull()
        val rawName = m.groupValues.getOrNull(3).orEmpty()
        val act = m.groupValues.getOrNull(4)?.toIntOrNull()
        return OperatorReading(
            mode = mode,
            name = decodeMaybeUcs2(rawName),
            accessTechCode = act,
        )
    }

    fun parseReg(result: AtResult): NetworkRegState {
        val m = regRegex.find(result.raw) ?: return NetworkRegState.UNKNOWN
        val stat = m.groupValues[2].toIntOrNull() ?: return NetworkRegState.UNKNOWN
        return NetworkRegState.fromCode(stat)
    }

    fun parseImei(result: AtResult): String {
        return result.payloadLines
            .map { it.trim() }
            .firstOrNull { it.length in 14..17 && it.all(Char::isDigit) }
            .orEmpty()
    }

    fun parseSingleValueLine(result: AtResult, stripPrefix: String? = null): String {
        val line = result.payloadLines.firstOrNull()?.trim().orEmpty()
        if (stripPrefix != null && line.startsWith(stripPrefix, ignoreCase = true)) {
            return line.substringAfter(":").trim()
        }
        return line
    }

    fun parseAtiRevision(result: AtResult): String {
        val lines = result.payloadLines.map { it.trim() }
        lines.firstOrNull { it.startsWith("Revision:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return lines.firstOrNull { it.contains("QDC507", ignoreCase = true) }
            ?: lines.lastOrNull().orEmpty()
    }

    fun parseQcfgTailInt(result: AtResult): Int? {
        val line = result.payloadLines.firstOrNull { it.contains("+QCFG:", ignoreCase = true) } ?: return null
        return qcfgIntTailRegex.find(line.trim())?.groupValues?.get(1)?.toIntOrNull()
    }

    fun parseQcfgLine(result: AtResult): String =
        result.payloadLines.firstOrNull { it.contains("+QCFG:", ignoreCase = true) }?.trim().orEmpty()

    fun decodeMaybeUcs2(s: String): String {
        val t = s.trim()
        if (t.length < 4 || t.length % 4 != 0) return s
        if (!t.all { it in "0123456789ABCDEFabcdef" }) return s
        return runCatching {
            val decoded = t.chunked(4).map { it.toInt(16).toChar() }.joinToString("")
            if (decoded.any { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }) s else decoded
        }.getOrDefault(s)
    }
}
