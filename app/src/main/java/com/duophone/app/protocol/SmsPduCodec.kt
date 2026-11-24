package com.duophone.app.protocol

import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

enum class SmsEncoding {
    GSM_7BIT,
    UCS2,
    EIGHT_BIT,
}

data class SmsSubmitSegment(
    val pduHex: String,
    val tpduLength: Int,
    val sequence: Int,
    val total: Int,
    val encoding: SmsEncoding,
)

data class SmsConcatInfo(
    val reference: Int,
    val referenceBits: Int,
    val total: Int,
    val sequence: Int,
)

data class DecodedDeliverPdu(
    val sender: String,
    val body: String,
    val timestampMs: Long?,
    val concatenation: SmsConcatInfo?,
    val dataCodingScheme: Int,
    val encoding: SmsEncoding,
    val rawPduHex: String,
)

data class StoredModemPdu(
    val index: Int,
    val status: Int, // 0=REC UNREAD, 1=REC READ, 2=STO UNSENT, 3=STO SENT
    val rawPduHex: String,
    val storage: String = "ME",
)

data class AssembledSms(
    val dedupKey: String,
    val sender: String,
    val body: String,
    val timestampMs: Long,
    val isRead: Boolean,
    val modemIndices: List<Int>,
    val storage: String,
    val otpCode: String?,
    val isIncompleteMultipart: Boolean = false,
)

object VerificationCodeExtractor {
    private val hints = listOf(
        "验证码", "校验码", "动态码", "识别码", "确认码", "口令", "密码", "安全码",
        "code", "otp", "verification", "passcode", "pin",
    )
    private val digitsRegex = Regex("""(?<![0-9])([0-9]{4,8})(?![0-9])""")

    fun extract(body: String): String? {
        val lower = body.lowercase()
        if (hints.none { lower.contains(it) }) return null
        val matches = digitsRegex.findAll(body).map { it.groupValues[1] }.toList()
        if (matches.isEmpty()) return null
        return matches.firstOrNull { it.length == 6 }
            ?: matches.firstOrNull { it.length == 4 }
            ?: matches.first()
    }
}

/**
 * 3GPP TS 23.040 SMS PDU encoder (SMS-SUBMIT) and decoder (SMS-DELIVER) with full
 * GSM 7-bit (basic + extension), UCS2 (UTF-16BE with surrogate-safe splitting),
 * alphanumeric sender, SCTS timezone parsing, and multipart UDH reassembly.
 */
object SmsPduCodec {

    private val gsmBasicTable: CharArray = charArrayOf(
        '@', '£', '$', '¥', 'è', 'é', 'ù', 'ì', 'ò', 'Ç', '\n', 'Ø', 'ø', '\r', 'Å', 'å',
        'Δ', '_', 'Φ', 'Γ', 'Λ', 'Ω', 'Π', 'Ψ', 'Σ', 'Θ', 'Ξ', '\u001B', 'Æ', 'æ', 'ß', 'É',
        ' ', '!', '"', '#', '¤', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', ';', '<', '=', '>', '?',
        '¡', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
        'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'Ä', 'Ö', 'Ñ', 'Ü', '§',
        '¿', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o',
        'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'ä', 'ö', 'ñ', 'ü', 'à',
    )

    private val gsmExtToChar: Map<Int, Char> = mapOf(
        0x0A to '\u000C',
        0x14 to '^',
        0x28 to '{',
        0x29 to '}',
        0x2F to '\\',
        0x3C to '[',
        0x3D to '~',
        0x3E to ']',
        0x40 to '|',
        0x65 to '€',
    )

    private val charToGsmBasic: Map<Char, Int> = buildMap {
        gsmBasicTable.forEachIndexed { idx, ch ->
            if (idx != 0x1B) put(ch, idx)
        }
    }

    private val charToGsmExt: Map<Char, Int> = gsmExtToChar.entries.associate { (k, v) -> v to k }

    fun normalizePhoneNumber(raw: String): String? {
        if (raw.any { it == '\r' || it == '\n' }) return null
        val compact = raw.filter { it != ' ' && it != '-' && it != '(' && it != ')' }
        if (compact.isEmpty() || compact.length > 21) return null
        for (i in compact.indices) {
            val c = compact[i]
            if (c in '0'..'9') continue
            if (c == '+' && i == 0) continue
            return null
        }
        val digitCount = compact.count { it in '0'..'9' }
        if (digitCount !in 1..20) return null
        return compact
    }

    /**
     * Encodes an SMS-SUBMIT into one or more 3GPP PDU segments.
     * Automatically selects GSM 7-bit when all characters fit GSM alphabet; otherwise UCS2.
     */
    fun encodeSubmit(
        destination: String,
        body: String,
        concatenationRef: Int = (System.currentTimeMillis() and 0xFF).toInt(),
    ): List<SmsSubmitSegment> {
        val normDest = normalizePhoneNumber(destination)
            ?: throw IllegalArgumentException("无效收件号码：$destination")
        require(body.isNotEmpty()) { "短信内容不能为空" }

        val gsmSeptets = encodeToGsm7SeptetsOrNull(body)
        return if (gsmSeptets != null) {
            encodeSubmitGsm7(normDest, gsmSeptets, concatenationRef and 0xFF)
        } else {
            encodeSubmitUcs2(normDest, body, concatenationRef and 0xFF)
        }
    }

    private fun encodeSubmitGsm7(
        normDest: String,
        septets: List<Int>,
        ref: Int,
    ): List<SmsSubmitSegment> {
        val chunks: List<List<Int>> = if (septets.size <= 160) {
            listOf(septets)
        } else {
            splitGsm7Septets(septets, maxSeptetsPerPart = 153)
        }
        require(chunks.size <= 255) { "短信过长，分片数超过 255" }

        val isIntl = normDest.startsWith("+")
        val digits = if (isIntl) normDest.drop(1) else normDest
        val addrBytes = encodeSemiOctetAddress(digits)
        val total = chunks.size

        return chunks.mapIndexed { idx, partSeptets ->
            val seq = idx + 1
            val udBytes: ByteArray
            val udlSeptets: Int
            if (total == 1) {
                udBytes = packGsm7Septets(partSeptets, fillBits = 0)
                udlSeptets = partSeptets.size
            } else {
                // 6-byte UDH (48 bits) -> ceil(48/7) = 7 septets -> 1 fill bit
                val udh = byteArrayOf(0x05, 0x00, 0x03, ref.toByte(), total.toByte(), seq.toByte())
                val packedBody = packGsm7Septets(partSeptets, fillBits = 1)
                udBytes = udh + packedBody
                udlSeptets = 7 + partSeptets.size
            }

            val bytes = ArrayList<Byte>(16 + addrBytes.size + udBytes.size)
            bytes.add(0x00) // Use SIM default SMSC
            bytes.add(if (total > 1) 0x41 else 0x01) // SMS-SUBMIT (+ UDHI if multipart)
            bytes.add(0x00) // TP-MR
            bytes.add(digits.length.toByte())
            bytes.add(if (isIntl) 0x91.toByte() else 0x81.toByte())
            addrBytes.forEach { bytes.add(it) }
            bytes.add(0x00) // TP-PID
            bytes.add(0x00) // TP-DCS: GSM 7-bit
            bytes.add(udlSeptets.toByte())
            udBytes.forEach { bytes.add(it) }

            SmsSubmitSegment(
                pduHex = bytesToHex(bytes),
                tpduLength = bytes.size - 1,
                sequence = seq,
                total = total,
                encoding = SmsEncoding.GSM_7BIT,
            )
        }
    }

    private fun encodeSubmitUcs2(
        normDest: String,
        body: String,
        ref: Int,
    ): List<SmsSubmitSegment> {
        val units = body.toCharArray()
        val chunks = if (units.size <= 70) {
            listOf(units)
        } else {
            splitUtf16Safely(units, maxUnits = 67)
        }
        require(chunks.size <= 255) { "短信过长，分片数超过 255" }

        val isIntl = normDest.startsWith("+")
        val digits = if (isIntl) normDest.drop(1) else normDest
        val addrBytes = encodeSemiOctetAddress(digits)
        val total = chunks.size

        return chunks.mapIndexed { idx, chunk ->
            val seq = idx + 1
            val ud = ArrayList<Byte>(6 + chunk.size * 2)
            if (total > 1) {
                ud.add(0x05)
                ud.add(0x00)
                ud.add(0x03)
                ud.add(ref.toByte())
                ud.add(total.toByte())
                ud.add(seq.toByte())
            }
            for (ch in chunk) {
                val code = ch.code
                ud.add(((code shr 8) and 0xFF).toByte())
                ud.add((code and 0xFF).toByte())
            }

            val bytes = ArrayList<Byte>(16 + addrBytes.size + ud.size)
            bytes.add(0x00) // SIM default SMSC
            bytes.add(if (total > 1) 0x41 else 0x01)
            bytes.add(0x00) // TP-MR
            bytes.add(digits.length.toByte())
            bytes.add(if (isIntl) 0x91.toByte() else 0x81.toByte())
            addrBytes.forEach { bytes.add(it) }
            bytes.add(0x00) // TP-PID
            bytes.add(0x08) // TP-DCS: UCS2
            bytes.add(ud.size.toByte())
            ud.forEach { bytes.add(it) }

            SmsSubmitSegment(
                pduHex = bytesToHex(bytes),
                tpduLength = bytes.size - 1,
                sequence = seq,
                total = total,
                encoding = SmsEncoding.UCS2,
            )
        }
    }

    /**
     * Decodes a 3GPP SMS-DELIVER PDU (hex string).
     */
    fun decodeDeliver(rawPduHex: String): DecodedDeliverPdu {
        val cleanHex = rawPduHex.trim().uppercase()
        val bytes = hexToBytes(cleanHex)
        require(bytes.isNotEmpty()) { "无效的十六进制 PDU" }

        var cursor = 0
        val smscLen = bytes[cursor++].toInt() and 0xFF
        require(cursor + smscLen <= bytes.size) { "PDU 在 SMSC 字段处截断" }
        cursor += smscLen

        require(cursor < bytes.size) { "PDU 在 TPDU 首字节处截断" }
        val firstOctet = bytes[cursor++].toInt() and 0xFF
        val mti = firstOctet and 0x03
        require(mti == 0) { "仅支持 SMS-DELIVER (MTI=0)，当前 MTI=$mti" }
        val hasUdh = (firstOctet and 0x40) != 0

        require(cursor + 2 <= bytes.size) { "PDU 在发件人头处截断" }
        val senderLen = bytes[cursor++].toInt() and 0xFF
        val senderType = bytes[cursor++].toInt() and 0xFF
        val senderOctets = (senderLen + 1) / 2
        require(cursor + senderOctets <= bytes.size) { "PDU 在发件人地址处截断" }
        val senderBytes = bytes.copyOfRange(cursor, cursor + senderOctets)
        cursor += senderOctets

        val isAlphanumeric = (senderType and 0x70) == 0x50
        val sender = if (isAlphanumeric) {
            decodeGsm7(senderBytes, septetCount = (senderLen * 4) / 7, bitOffset = 0)
        } else {
            decodeSemiOctetAddress(senderBytes, senderLen, senderType)
        }

        require(cursor + 10 <= bytes.size) { "PDU 在 PID/DCS/SCTS/UDL 处截断" }
        cursor++ // TP-PID
        val dcs = bytes[cursor++].toInt() and 0xFF
        val sctsBytes = bytes.copyOfRange(cursor, cursor + 7)
        cursor += 7
        val timestampMs = decodeScts(sctsBytes)
        val udl = bytes[cursor++].toInt() and 0xFF

        val encoding = detectEncoding(dcs)
        val expectedOctets = when (encoding) {
            SmsEncoding.GSM_7BIT -> (udl * 7 + 7) / 8
            SmsEncoding.EIGHT_BIT, SmsEncoding.UCS2 -> udl
        }
        require(cursor + expectedOctets <= bytes.size) { "PDU 在用户数据处截断" }
        val udBytes = bytes.copyOfRange(cursor, cursor + expectedOctets)

        var udhTotalOctets = 0
        var concat: SmsConcatInfo? = null
        if (hasUdh) {
            require(udBytes.isNotEmpty()) { "UDH 为空" }
            val udhl = udBytes[0].toInt() and 0xFF
            require(udhl + 1 <= udBytes.size) { "UDH 长度超出用户数据" }
            udhTotalOctets = udhl + 1
            concat = parseConcatenationUdh(udBytes, udhl)
        }

        val body = when (encoding) {
            SmsEncoding.GSM_7BIT -> {
                val headerSeptets = if (hasUdh) (udhTotalOctets * 8 + 6) / 7 else 0
                val textSeptets = (udl - headerSeptets).coerceAtLeast(0)
                decodeGsm7(udBytes, septetCount = textSeptets, bitOffset = headerSeptets * 7)
            }
            SmsEncoding.UCS2 -> {
                val payloadLen = udBytes.size - udhTotalOctets
                val evenLen = payloadLen - (payloadLen % 2)
                val sb = StringBuilder(evenLen / 2)
                var i = udhTotalOctets
                while (i + 1 < udhTotalOctets + evenLen) {
                    val hi = udBytes[i].toInt() and 0xFF
                    val lo = udBytes[i + 1].toInt() and 0xFF
                    sb.append(((hi shl 8) or lo).toChar())
                    i += 2
                }
                sb.toString()
            }
            SmsEncoding.EIGHT_BIT -> {
                val payload = udBytes.copyOfRange(udhTotalOctets, udBytes.size)
                if (payload.isEmpty()) "" else "[二进制短信] ${bytesToHex(payload.toList())}"
            }
        }

        return DecodedDeliverPdu(
            sender = sender.ifBlank { "未知号码" },
            body = body,
            timestampMs = timestampMs,
            concatenation = concat,
            dataCodingScheme = dcs,
            encoding = encoding,
            rawPduHex = cleanHex,
        )
    }

    /**
     * Parses `AT+CMGL=4` response into raw [StoredModemPdu] entries.
     */
    fun parseCmglPduResponse(raw: String, storage: String = "ME"): List<StoredModemPdu> {
        val lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val out = mutableListOf<StoredModemPdu>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("+CMGL:", ignoreCase = true)) {
                val fields = line.substringAfter(":").split(",").map { it.trim() }
                val index = fields.getOrNull(0)?.toIntOrNull()
                val status = fields.getOrNull(1)?.toIntOrNull() ?: 0
                val pduLine = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (index != null && pduLine.isNotEmpty() && pduLine.all { it in "0123456789ABCDEFabcdef" }) {
                    out.add(StoredModemPdu(index = index, status = status, rawPduHex = pduLine.uppercase(), storage = storage))
                    i += 2
                    continue
                }
            }
            i++
        }
        return out
    }

    /**
     * Parses `AT+CMGR=<idx>` PDU response into a single [StoredModemPdu].
     */
    fun parseCmgrPduResponse(index: Int, raw: String, storage: String = "ME"): StoredModemPdu? {
        val lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("+CMGR:", ignoreCase = true)) {
                val fields = line.substringAfter(":").split(",").map { it.trim() }
                val status = fields.getOrNull(0)?.toIntOrNull() ?: 0
                val pduLine = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (pduLine.isNotEmpty() && pduLine.all { it in "0123456789ABCDEFabcdef" }) {
                    return StoredModemPdu(index = index, status = status, rawPduHex = pduLine.uppercase(), storage = storage)
                }
            }
        }
        return null
    }

    /**
     * Reassembles single and concatenated multipart PDUs into deduplicated [AssembledSms] items.
     */
    fun assembleMultipart(
        storedPdus: List<StoredModemPdu>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<AssembledSms> {
        data class Part(val stored: StoredModemPdu, val decoded: DecodedDeliverPdu)
        data class GroupKey(
            val sender: String,
            val reference: Int,
            val refBits: Int,
            val total: Int,
            val dcs: Int,
        )

        val singles = mutableListOf<Part>()
        val groups = LinkedHashMap<GroupKey, MutableList<Part>>()

        for (stored in storedPdus) {
            val decoded = runCatching { decodeDeliver(stored.rawPduHex) }.getOrNull() ?: continue
            val part = Part(stored, decoded)
            val concat = decoded.concatenation
            if (concat != null && concat.total > 1 && concat.sequence in 1..concat.total) {
                val key = GroupKey(decoded.sender, concat.reference, concat.referenceBits, concat.total, decoded.dataCodingScheme)
                groups.getOrPut(key) { mutableListOf() }.add(part)
            } else {
                singles.add(part)
            }
        }

        val result = mutableListOf<AssembledSms>()
        for (part in singles) {
            val body = part.decoded.body
            result.add(
                AssembledSms(
                    dedupKey = sha256(listOf(part.decoded.rawPduHex)),
                    sender = part.decoded.sender,
                    body = body,
                    timestampMs = part.decoded.timestampMs ?: nowMs,
                    isRead = part.stored.status == 1,
                    modemIndices = listOf(part.stored.index),
                    storage = part.stored.storage,
                    otpCode = VerificationCodeExtractor.extract(body),
                ),
            )
        }

        val maxWindowMs = 12L * 60L * 60L * 1000L
        for ((key, parts) in groups) {
            val sorted = parts.sortedWith(compareBy({ it.decoded.timestampMs ?: nowMs }, { it.stored.index }))
            val clusters = mutableListOf<MutableList<Part>>()
            for (part in sorted) {
                val seq = part.decoded.concatenation!!.sequence
                val partTs = part.decoded.timestampMs ?: nowMs
                val target = clusters.firstOrNull { cluster ->
                    cluster.none { it.decoded.concatenation!!.sequence == seq } &&
                        abs(partTs - (cluster.maxOfOrNull { it.decoded.timestampMs ?: nowMs } ?: nowMs)) <= maxWindowMs
                }
                if (target != null) target.add(part) else clusters.add(mutableListOf(part))
            }

            for (cluster in clusters) {
                val ordered = cluster.sortedBy { it.decoded.concatenation!!.sequence }
                val complete = ordered.size == key.total &&
                    ordered.map { it.decoded.concatenation!!.sequence } == (1..key.total).toList()
                val joinedBody = if (complete) {
                    ordered.joinToString("") { it.decoded.body }
                } else {
                    ordered.joinToString("") { it.decoded.body } +
                        " [分片 ${ordered.size}/${key.total}]"
                }
                result.add(
                    AssembledSms(
                        dedupKey = sha256(ordered.map { it.decoded.rawPduHex }),
                        sender = key.sender,
                        body = joinedBody,
                        timestampMs = ordered.minOfOrNull { it.decoded.timestampMs ?: nowMs } ?: nowMs,
                        isRead = ordered.all { it.stored.status == 1 },
                        modemIndices = ordered.map { it.stored.index },
                        storage = ordered.first().stored.storage,
                        otpCode = VerificationCodeExtractor.extract(joinedBody),
                        isIncompleteMultipart = !complete,
                    ),
                )
            }
        }

        return result.sortedByDescending { it.timestampMs }
    }

    private fun encodeToGsm7SeptetsOrNull(text: String): List<Int>? {
        val out = ArrayList<Int>(text.length)
        for (ch in text) {
            val basic = charToGsmBasic[ch]
            if (basic != null) {
                out.add(basic)
                continue
            }
            val ext = charToGsmExt[ch]
            if (ext != null) {
                out.add(0x1B)
                out.add(ext)
                continue
            }
            return null
        }
        return out
    }

    private fun splitGsm7Septets(septets: List<Int>, maxSeptetsPerPart: Int): List<List<Int>> {
        val parts = mutableListOf<List<Int>>()
        var start = 0
        while (start < septets.size) {
            var end = minOf(start + maxSeptetsPerPart, septets.size)
            // Do not split an escape sequence (0x1B + ext) across segment boundaries
            if (end < septets.size && end > start && septets[end - 1] == 0x1B) {
                end--
            }
            parts.add(septets.subList(start, end))
            start = end
        }
        return parts
    }

    private fun packGsm7Septets(septets: List<Int>, fillBits: Int): ByteArray {
        val totalBits = fillBits + septets.size * 7
        val byteLen = (totalBits + 7) / 8
        val out = ByteArray(byteLen)
        var bitPos = fillBits
        for (septet in septets) {
            for (b in 0 until 7) {
                if (((septet shr b) and 1) != 0) {
                    val byteIdx = bitPos / 8
                    val bitIdx = bitPos % 8
                    out[byteIdx] = (out[byteIdx].toInt() or (1 shl bitIdx)).toByte()
                }
                bitPos++
            }
        }
        return out
    }

    private fun decodeGsm7(bytes: ByteArray, septetCount: Int, bitOffset: Int): String {
        val sb = StringBuilder(septetCount)
        var escaped = false
        for (i in 0 until septetCount) {
            val startBit = bitOffset + i * 7
            var value = 0
            for (b in 0 until 7) {
                val bit = startBit + b
                val byteIdx = bit / 8
                if (byteIdx >= bytes.size) break
                val bitIdx = bit % 8
                if (((bytes[byteIdx].toInt() shr bitIdx) and 1) != 0) {
                    value = value or (1 shl b)
                }
            }
            if (escaped) {
                escaped = false
                sb.append(gsmExtToChar[value] ?: ' ')
            } else if (value == 0x1B) {
                escaped = true
            } else if (value in gsmBasicTable.indices) {
                sb.append(gsmBasicTable[value])
            }
        }
        return sb.toString()
    }

    private fun splitUtf16Safely(units: CharArray, maxUnits: Int): List<CharArray> {
        val out = mutableListOf<CharArray>()
        var start = 0
        while (start < units.size) {
            var end = minOf(start + maxUnits, units.size)
            if (end < units.size && end > start &&
                Character.isHighSurrogate(units[end - 1]) &&
                Character.isLowSurrogate(units[end])
            ) {
                end--
            }
            out.add(units.copyOfRange(start, end))
            start = end
        }
        return out
    }

    private fun encodeSemiOctetAddress(digits: String): ByteArray {
        val len = (digits.length + 1) / 2
        val out = ByteArray(len)
        var i = 0
        var outIdx = 0
        while (i < digits.length) {
            val low = digits[i] - '0'
            val high = if (i + 1 < digits.length) digits[i + 1] - '0' else 0x0F
            out[outIdx++] = ((high shl 4) or low).toByte()
            i += 2
        }
        return out
    }

    private fun decodeSemiOctetAddress(bytes: ByteArray, digitCount: Int, type: Int): String {
        val sb = StringBuilder(digitCount + 1)
        if ((type and 0x70) == 0x10) sb.append('+')
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val low = v and 0x0F
            val high = (v shr 4) and 0x0F
            if (low <= 9 && sb.length < digitCount + (if (sb.startsWith("+")) 1 else 0)) {
                sb.append(('0'.code + low).toChar())
            }
            if (high <= 9 && sb.length < digitCount + (if (sb.startsWith("+")) 1 else 0)) {
                sb.append(('0'.code + high).toChar())
            }
        }
        return sb.toString()
    }

    private fun detectEncoding(dcs: Int): SmsEncoding {
        val highNibble = (dcs shr 4) and 0x0F
        return when {
            dcs == 0x00 || (highNibble in 0x00..0x03 && ((dcs shr 2) and 0x03) == 0) -> SmsEncoding.GSM_7BIT
            highNibble in 0x00..0x03 && ((dcs shr 2) and 0x03) == 2 -> SmsEncoding.UCS2
            highNibble in 0x00..0x03 && ((dcs shr 2) and 0x03) == 1 -> SmsEncoding.EIGHT_BIT
            highNibble == 0x0F && (dcs and 0x04) == 0 -> SmsEncoding.GSM_7BIT
            highNibble == 0x0F -> SmsEncoding.EIGHT_BIT
            else -> SmsEncoding.UCS2
        }
    }

    private fun parseConcatenationUdh(udBytes: ByteArray, udhl: Int): SmsConcatInfo? {
        var pos = 1
        val end = 1 + udhl
        while (pos + 2 <= end && pos + 2 <= udBytes.size) {
            val iei = udBytes[pos++].toInt() and 0xFF
            val iedl = udBytes[pos++].toInt() and 0xFF
            if (pos + iedl > end || pos + iedl > udBytes.size) break
            if (iei == 0x00 && iedl == 3) {
                val ref = udBytes[pos].toInt() and 0xFF
                val total = udBytes[pos + 1].toInt() and 0xFF
                val seq = udBytes[pos + 2].toInt() and 0xFF
                return SmsConcatInfo(ref, 8, total, seq)
            }
            if (iei == 0x08 && iedl == 4) {
                val ref = ((udBytes[pos].toInt() and 0xFF) shl 8) or (udBytes[pos + 1].toInt() and 0xFF)
                val total = udBytes[pos + 2].toInt() and 0xFF
                val seq = udBytes[pos + 3].toInt() and 0xFF
                return SmsConcatInfo(ref, 16, total, seq)
            }
            pos += iedl
        }
        return null
    }

    private fun decodeScts(bytes: ByteArray): Long? {
        if (bytes.size != 7) return null
        fun bcd(b: Byte): Int {
            val v = b.toInt() and 0xFF
            return (v and 0x0F) * 10 + ((v shr 4) and 0x0F)
        }
        val year = 2000 + bcd(bytes[0])
        val month = bcd(bytes[1])
        val day = bcd(bytes[2])
        val hour = bcd(bytes[3])
        val minute = bcd(bytes[4])
        val second = bcd(bytes[5])
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return null
        }

        val tzRaw = bytes[6].toInt() and 0xFF
        val negative = (tzRaw and 0x08) != 0
        val tzLow = tzRaw and 0x07
        val tzHigh = (tzRaw shr 4) and 0x0F
        val quarters = tzLow * 10 + tzHigh
        val offsetMillis = (if (negative) -1 else 1) * quarters * 15 * 60 * 1000

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis - offsetMillis
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "PDU 十六进制长度必须为偶数" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "PDU 含非法十六进制字符" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: List<Byte>): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append("%02X".format(b.toInt() and 0xFF))
        }
    }

    private fun sha256(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it.toByteArray(Charsets.UTF_8)) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
