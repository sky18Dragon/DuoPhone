package com.duophone.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAtParserTest {

    @Test
    fun normalizesDialNumbersAndRejectsInvalidInput() {
        assertEquals("+8613800138000", CallAtParser.normalizeDialNumber("+86 138-0013-8000"))
        assertEquals("10086", CallAtParser.normalizeDialNumber("(10086)"))
        assertEquals("*#06#", CallAtParser.normalizeDialNumber("*#06#"))
        assertNull(CallAtParser.normalizeDialNumber("10086\r\nATH"))
        assertNull(CallAtParser.normalizeDialNumber("abc123"))
        assertNull(CallAtParser.normalizeDialNumber("+++"))
    }

    @Test
    fun validatesAndFormatsDtmfCommands() {
        assertEquals("AT+VTS=\"5\"", CallAtParser.dtmfCommand("5"))
        assertEquals("AT+VTS=\"*\"", CallAtParser.dtmfCommand("*"))
        assertEquals("AT+VTS=\"#\"", CallAtParser.dtmfCommand("#"))
        assertNull(CallAtParser.dtmfCommand("A"))
        assertNull(CallAtParser.dtmfCommand("12"))
    }

    @Test
    fun parsesMultiLineClccAndCeerResponses() {
        val rawClcc = """
            +CLCC: 1,0,0,0,0,"+8613800138000",145
            +CLCC: 2,1,4,1,0,"10000",129
            OK
        """.trimIndent()
        val voiceCalls = CallAtParser.parseClccResponse(rawClcc)
        // Entry 2 has mode=1 (data/fax), so only voice call index=1 is returned
        assertEquals(1, voiceCalls.size)
        assertEquals(1, voiceCalls[0].index)
        assertEquals(ClccCallStatus.ACTIVE, voiceCalls[0].status)
        assertEquals("+8613800138000", voiceCalls[0].number)
        assertTrue(voiceCalls[0].isVoice)

        val ceer = CallAtParser.parseCeerResponse("+CEER: User busy\r\nOK")
        assertEquals("User busy", ceer)
    }
}
