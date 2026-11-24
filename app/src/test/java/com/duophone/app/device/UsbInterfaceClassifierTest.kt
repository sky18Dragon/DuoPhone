package com.duophone.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbInterfaceClassifierTest {

    @Test
    fun matchesKnownQdc507AndQuectelProfiles() {
        assertEquals(SupportedModemProfile.DJI_QDC507, SupportedModemProfile.match(0x2CA3, 0x4006))
        assertEquals(SupportedModemProfile.QUECTEL_COMPAT, SupportedModemProfile.match(0x2C7C, 0x0125))
        assertNull(SupportedModemProfile.match(0x18D1, 0x4EE7))
    }

    @Test
    fun excludesAdbUacCdcStorageAndDiagFromAtCandidates() {
        val rawInterfaces = listOf(
            // IF0: Diag (FF/FF/FF)
            UsbInterfaceClassifier.RawInterface(0, 0, 0xFF, 0xFF, 0xFF, 2, 0x81, 0x01),
            // IF1: NMEA / raw PCM serial (FF/00/00)
            UsbInterfaceClassifier.RawInterface(1, 1, 0xFF, 0x00, 0x00, 2, 0x82, 0x02),
            // IF2: Standard AT port (FF/00/00, 3 ep incl interrupt)
            UsbInterfaceClassifier.RawInterface(2, 2, 0xFF, 0x00, 0x00, 3, 0x84, 0x03, hasInterruptIn = true),
            // IF3: Modem port (FF/00/00, 3 ep incl interrupt)
            UsbInterfaceClassifier.RawInterface(3, 3, 0xFF, 0x00, 0x00, 3, 0x86, 0x04, hasInterruptIn = true),
            // IF4: CDC ECM Control
            UsbInterfaceClassifier.RawInterface(4, 4, 0x02, 0x06, 0x00, 1, null, null, hasInterruptIn = true),
            // IF5: CDC Data
            UsbInterfaceClassifier.RawInterface(5, 5, 0x0A, 0x00, 0x00, 2, 0x88, 0x05),
            // IF6: ADB (FF/42/01) - MUST NEVER be claimed as AT
            UsbInterfaceClassifier.RawInterface(6, 6, 0xFF, 0x42, 0x01, 2, 0x8A, 0x06),
            // IF7: UAC Audio Control
            UsbInterfaceClassifier.RawInterface(7, 7, 0x01, 0x01, 0x00, 0, null, null),
            // IF8: UAC Audio Streaming
            UsbInterfaceClassifier.RawInterface(8, 8, 0x01, 0x02, 0x00, 1, 0x8B, null),
            // IF9: Mass Storage
            UsbInterfaceClassifier.RawInterface(9, 9, 0x08, 0x06, 0x50, 2, 0x8C, 0x07),
        )

        val classified = rawInterfaces.map { UsbInterfaceClassifier.classify(it) }
        val byId = classified.associateBy { it.interfaceId }

        assertEquals(UsbInterfaceRole.DIAG_SUSPECT, byId[0]!!.role)
        assertFalse(byId[0]!!.isSafeAtCandidate)

        assertEquals(UsbInterfaceRole.ADB, byId[6]!!.role)
        assertFalse(byId[6]!!.isSafeAtCandidate)

        assertEquals(UsbInterfaceRole.USB_AUDIO, byId[7]!!.role)
        assertFalse(byId[7]!!.isSafeAtCandidate)

        assertEquals(UsbInterfaceRole.MASS_STORAGE, byId[9]!!.role)
        assertFalse(byId[9]!!.isSafeAtCandidate)

        val ranked = UsbInterfaceClassifier.rankAtCandidates(classified)
        assertEquals(listOf(2, 3, 1), ranked.map { it.interfaceId })
        assertTrue(ranked.none { it.role == UsbInterfaceRole.ADB || it.role == UsbInterfaceRole.DIAG_SUSPECT })
        assertNotNull(byId[2])
    }
}
