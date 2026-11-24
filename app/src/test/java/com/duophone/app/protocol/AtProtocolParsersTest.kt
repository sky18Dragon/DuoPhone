package com.duophone.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtProtocolParsersTest {

    @Test
    fun commandPolicyBlocksRetryOnSideEffectCommands() {
        assertFalse(AtCommandPolicy.isRetryable("ATD+8613800138000;"))
        assertFalse(AtCommandPolicy.isRetryable("AT+CMGS=24"))
        assertFalse(AtCommandPolicy.isRetryable("AT+VTS=\"5\""))
        assertFalse(AtCommandPolicy.isRetryable("AT+CFUN=1,1"))
        assertFalse(AtCommandPolicy.isRetryable("AT+QCFG=\"usbnet\",1"))
        assertFalse(AtCommandPolicy.isRetryable("AT+CMGD=1"))

        assertTrue(AtCommandPolicy.isRetryable("AT"))
        assertTrue(AtCommandPolicy.isRetryable("AT+CSQ"))
        assertTrue(AtCommandPolicy.isRetryable("AT+CEREG?"))
        assertTrue(AtCommandPolicy.isRetryable("AT+QCFG=\"usbnet\""))
        assertTrue(AtCommandPolicy.isRetryable("AT+QPCMV=?"))
    }

    @Test
    fun lineStreamFramerHandlesSplitPacketsAndSmsPrompt() {
        val framer = LineStreamFramer()
        assertEquals(emptyList<String>(), framer.append("+CM"))
        assertEquals(listOf("+CMTI: \"ME\",12"), framer.append("TI: \"ME\",12\r\n> "))
        assertTrue(framer.hasPrompt())
        framer.consumePrompt()
        assertFalse(framer.hasPrompt())
    }

    @Test
    fun urcParserDistinguishesQueryReplyFromUnsolicitedRegistration() {
        // Multi-field CEREG reply while command in flight must NOT be stolen as URC
        assertNull(UrcParser.parse("+CEREG: 0,1", hasInFlightCommand = true))

        // Single-field unsolicited CEREG URC MUST be parsed even while command in flight
        val urc = UrcParser.parse("+CEREG: 5", hasInFlightCommand = true)
        assertTrue(urc is UrcEvent.NetworkRegistrationChanged)
        assertEquals(5, (urc as UrcEvent.NetworkRegistrationChanged).stat)

        // Incoming call & SMS URCs
        assertTrue(UrcParser.parse("RING", hasInFlightCommand = true) is UrcEvent.Ring)
        val clip = UrcParser.parse("+CLIP: \"+8613800138000\",145,\"\",0,\"\",0", hasInFlightCommand = true)
        assertTrue(clip is UrcEvent.CallerId)
        assertEquals("+8613800138000", (clip as UrcEvent.CallerId).number)

        val cmti = UrcParser.parse("+CMTI: \"ME\",7", hasInFlightCommand = true)
        assertTrue(cmti is UrcEvent.NewSmsIndicator)
        assertEquals("ME", (cmti as UrcEvent.NewSmsIndicator).storage)
        assertEquals(7, cmti.index)

        val ended = UrcParser.parse("NO CARRIER", hasInFlightCommand = false)
        assertEquals(CallEndReason.REMOTE_HANGUP, (ended as UrcEvent.CallEnded).reason)
    }

    @Test
    fun parsesCsqCopsCpinRegAndQcfgAccurately() {
        val csq = AtResponseParser.parseCsq(AtResult("AT+CSQ", AtStatus.OK, listOf("+CSQ: 24,99", "OK"), "+CSQ: 24,99\nOK"))
        assertEquals(24, csq.rssi)
        assertEquals(-65, csq.dbm)
        assertEquals(5, csq.bars)

        val noSignal = AtResponseParser.parseCsq(AtResult("AT+CSQ", AtStatus.OK, listOf("+CSQ: 99,99", "OK"), "+CSQ: 99,99\nOK"))
        assertNull(noSignal.dbm)
        assertEquals(0, noSignal.bars)

        val cops = AtResponseParser.parseCops(
            AtResult("AT+COPS?", AtStatus.OK, listOf("+COPS: 0,0,\"CHN-CT\",7", "OK"), "+COPS: 0,0,\"CHN-CT\",7\nOK"),
        )
        assertEquals("CHN-CT", cops.name)
        assertEquals("4G (LTE)", cops.accessTechLabel)

        val ucs2Carrier = AtResponseParser.decodeMaybeUcs2("4E2D56FD75354FE1")
        assertEquals("中国电信", ucs2Carrier)

        val cpin = AtResponseParser.parseCpin(AtResult("AT+CPIN?", AtStatus.OK, listOf("+CPIN: READY", "OK"), "+CPIN: READY\nOK"))
        assertEquals(SimState.READY, cpin)

        val reg = AtResponseParser.parseReg(AtResult("AT+CEREG?", AtStatus.OK, listOf("+CEREG: 0,1", "OK"), "+CEREG: 0,1\nOK"))
        assertEquals(NetworkRegState.REGISTERED_HOME, reg)
        assertTrue(reg.isRegistered)

        val usbnet = AtResponseParser.parseQcfgTailInt(
            AtResult("AT+QCFG=\"usbnet\"", AtStatus.OK, listOf("+QCFG: \"usbnet\",1", "OK"), "+QCFG: \"usbnet\",1\nOK"),
        )
        assertNotNull(usbnet)
        assertEquals(1, usbnet)
    }
}
