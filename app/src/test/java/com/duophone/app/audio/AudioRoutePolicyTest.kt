package com.duophone.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutePolicyTest {

    @Test
    fun selectPreferredRouteRespectsPriorityAndValidOverride() {
        assertEquals(
            LocalAudioRoute.SPEAKER,
            AudioRoutePolicy.selectPreferredRoute(AvailableLocalDevices(), null),
        )
        assertEquals(
            LocalAudioRoute.WIRED_HEADSET,
            AudioRoutePolicy.selectPreferredRoute(AvailableLocalDevices(hasWiredHeadset = true), null),
        )
        assertEquals(
            LocalAudioRoute.BLUETOOTH,
            AudioRoutePolicy.selectPreferredRoute(
                AvailableLocalDevices(hasBluetooth = true, hasWiredHeadset = true),
                null,
            ),
        )
        // Explicit user speaker override stays honored while Bluetooth is attached
        assertEquals(
            LocalAudioRoute.SPEAKER,
            AudioRoutePolicy.selectPreferredRoute(
                AvailableLocalDevices(hasBluetooth = true),
                LocalAudioRoute.SPEAKER,
            ),
        )
        // Unavailable override falls back to best available route
        assertEquals(
            LocalAudioRoute.WIRED_HEADSET,
            AudioRoutePolicy.selectPreferredRoute(
                AvailableLocalDevices(hasWiredHeadset = true),
                LocalAudioRoute.BLUETOOTH,
            ),
        )
    }

    @Test
    fun resolveNextRouteSwitchesAutomaticallyOnHeadsetHotplug() {
        val btOnly = AvailableLocalDevices(hasBluetooth = true)
        val wiredOnly = AvailableLocalDevices(hasWiredHeadset = true)
        val both = AvailableLocalDevices(hasBluetooth = true, hasWiredHeadset = true)
        val none = AvailableLocalDevices()

        // Plugging wired headset switches away from speaker
        assertEquals(
            LocalAudioRoute.WIRED_HEADSET,
            AudioRoutePolicy.resolveNextRouteOnDeviceChange(LocalAudioRoute.SPEAKER, none, wiredOnly),
        )
        // Plugging Bluetooth upgrades from wired headset to Bluetooth
        assertEquals(
            LocalAudioRoute.BLUETOOTH,
            AudioRoutePolicy.resolveNextRouteOnDeviceChange(LocalAudioRoute.WIRED_HEADSET, wiredOnly, both),
        )
        // Unplugging Bluetooth while wired remains falls back to wired
        assertEquals(
            LocalAudioRoute.WIRED_HEADSET,
            AudioRoutePolicy.resolveNextRouteOnDeviceChange(LocalAudioRoute.BLUETOOTH, both, wiredOnly),
        )
        // Unplugging last headset returns cleanly to speaker
        assertEquals(
            LocalAudioRoute.SPEAKER,
            AudioRoutePolicy.resolveNextRouteOnDeviceChange(LocalAudioRoute.BLUETOOTH, btOnly, none),
        )
    }

    @Test
    fun echoDuckingIsEnabledOnlyOnSpeakerphone() {
        assertTrue(AudioRoutePolicy.shouldEnableSpeakerEchoDucking(LocalAudioRoute.SPEAKER))
        assertFalse(AudioRoutePolicy.shouldEnableSpeakerEchoDucking(LocalAudioRoute.WIRED_HEADSET))
        assertFalse(AudioRoutePolicy.shouldEnableSpeakerEchoDucking(LocalAudioRoute.BLUETOOTH))
        assertFalse(AudioRoutePolicy.shouldEnableSpeakerEchoDucking(LocalAudioRoute.EARPIECE_OR_DEFAULT))
    }
}
