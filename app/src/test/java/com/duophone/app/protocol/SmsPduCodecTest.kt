package com.duophone.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsPduCodecTest {

    @Test
    fun encodesEnglishSingleAndLongGsm7bitSms() {
        val single = SmsPduCodec.encodeSubmit("+8613800138000", "Hello DuoPhone 123!", concatenationRef = 0x12)
        assertEquals(1, single.size)
        assertEquals(SmsEncoding.GSM_7BIT, single[0].encoding)
        assertEquals(single[0].pduHex.length / 2 - 1, single[0].tpduLength)

        val longEnglish = "A".repeat(170)
        val multi = SmsPduCodec.encodeSubmit("10086", longEnglish, concatenationRef = 0x34)
        assertEquals(2, multi.size)
        assertEquals(SmsEncoding.GSM_7BIT, multi[0].encoding)
        assertEquals(1, multi[0].sequence)
        assertEquals(2, multi[1].sequence)
        assertEquals(2, multi[0].total)
    }

    @Test
    fun encodesChineseSingleAndLongUcs2AndPreservesEmojiSurrogatePairs() {
        val chineseShort = SmsPduCodec.encodeSubmit("+8613800138000", "你好，大疆4G掌机测试！", concatenationRef = 0x55)
        assertEquals(1, chineseShort.size)
        assertEquals(SmsEncoding.UCS2, chineseShort[0].encoding)

        // 66 Chinese chars + 1 emoji (2 UTF-16 code units) = 68 code units -> exceeds 67 boundary cleanly
        val longChinese = "测".repeat(66) + "\uD83D\uDE80" + "试".repeat(20)
        val multi = SmsPduCodec.encodeSubmit("+8613800138000", longChinese, concatenationRef = 0x77)
        assertEquals(2, multi.size)
        assertEquals(SmsEncoding.UCS2, multi[0].encoding)
        // First segment user data length should be 6 (UDH) + 66*2 = 138 bytes (emoji moved cleanly to segment 2)
        val pduBytes = multi[0].pduHex.chunked(2).map { it.toInt(16) }
        val udlIdx = 1 + 1 + 1 + 1 + 1 + 7 + 1 + 1 // SMSC(1) + FO(1) + MR(1) + DALen(1) + DAType(1) + DA(7) + PID(1) + DCS(1)
        assertEquals(6 + 66 * 2, pduBytes[udlIdx])
    }

    @Test
    fun decodesStandardGsm7AndUcs2DeliverPdusAndReassemblesMultipart() {
        // Known 3GPP GSM 7-bit SMS-DELIVER: "hellohello" from +46708251358
        val gsm7Deliver = "07917283010010F5040BC87238880900F10000993092516195800AE8329BFD4697D9EC37"
        val decodedGsm7 = SmsPduCodec.decodeDeliver(gsm7Deliver)
        assertEquals("hellohello", decodedGsm7.body)
        assertEquals(SmsEncoding.GSM_7BIT, decodedGsm7.encoding)
        assertNotNull(decodedGsm7.timestampMs)

        // Construct 2-part UCS2 SMS-DELIVER PDUs (out of order!) and verify multipart reassembly
        // Part 2: "测试通过" (6D4B 8BD5 901A 8FC7)
        val pduPart2 = buildDeliverUcs2Multipart(
            senderDigits = "8613800138000",
            ref = 0x2A,
            total = 2,
            seq = 2,
            text = "验证码 619204 请勿泄露",
        )
        val pduPart1 = buildDeliverUcs2Multipart(
            senderDigits = "8613800138000",
            ref = 0x2A,
            total = 2,
            seq = 1,
            text = "【DuoPhone】中文长短信重组",
        )

        val cmglRaw = """
            +CMGL: 5,0,,38
            $pduPart2
            +CMGL: 4,0,,38
            $pduPart1
            OK
        """.trimIndent()

        val stored = SmsPduCodec.parseCmglPduResponse(cmglRaw)
        assertEquals(2, stored.size)

        val assembled = SmsPduCodec.assembleMultipart(stored)
        assertEquals(1, assembled.size)
        assertEquals("+8613800138000", assembled[0].sender)
        assertEquals("【DuoPhone】中文长短信重组验证码 619204 请勿泄露", assembled[0].body)
        assertEquals("619204", assembled[0].otpCode)
        assertEquals(listOf(4, 5), assembled[0].modemIndices)
        assertFalse(assembled[0].isIncompleteMultipart)
        assertFalse(assembled[0].isRead)
    }

    @Test
    fun extractsVerificationCodesAccuratelyWithoutFalsePositives() {
        assertEquals("482910", VerificationCodeExtractor.extract("【测试平台】您的登录验证码是 482910，5分钟内有效。"))
        assertEquals("7391", VerificationCodeExtractor.extract("Your verification code is 7391."))
        assertNull(VerificationCodeExtractor.extract("今天下午 1430 在 10086 门口集合"))
        assertTrue(SmsPduCodec.normalizePhoneNumber("+86 138-0013-8000") == "+8613800138000")
        assertNull(SmsPduCodec.normalizePhoneNumber("abc123"))
    }

    private fun buildDeliverUcs2Multipart(
        senderDigits: String,
        ref: Int,
        total: Int,
        seq: Int,
        text: String,
    ): String {
        val sb = StringBuilder()
        sb.append("00") // SMSC length 0
        sb.append("44") // SMS-DELIVER (0x04) + UDHI (0x40)
        sb.append("%02X".format(senderDigits.length))
        sb.append("91") // International TON/NPI
        var i = 0
        while (i < senderDigits.length) {
            val lo = senderDigits[i] - '0'
            val hi = if (i + 1 < senderDigits.length) senderDigits[i + 1] - '0' else 0x0F
            sb.append("%02X".format((hi shl 4) or lo))
            i += 2
        }
        sb.append("00") // PID
        sb.append("08") // DCS = UCS2
        sb.append("62114261530023") // SCTS: 2026-11-24 16:35:00 +08:00
        val ucs2Bytes = text.toCharArray().flatMap { ch -> listOf((ch.code shr 8) and 0xFF, ch.code and 0xFF) }
        val udh = listOf(0x05, 0x00, 0x03, ref and 0xFF, total and 0xFF, seq and 0xFF)
        val udl = udh.size + ucs2Bytes.size
        sb.append("%02X".format(udl))
        (udh + ucs2Bytes).forEach { sb.append("%02X".format(it)) }
        return sb.toString()
    }
}
