package com.duophone.app.audio

data class AvailableLocalDevices(
    val hasBluetooth: Boolean = false,
    val hasWiredHeadset: Boolean = false,
    val hasEarpiece: Boolean = false,
)

/**
 * Pure JVM-testable policy for local voice audio routing across Speaker, Earpiece,
 * 3.5mm Wired Headset, and Bluetooth Headset.
 */
object AudioRoutePolicy {

    fun availableRoutes(devices: AvailableLocalDevices): Set<LocalAudioRoute> {
        val routes = linkedSetOf(LocalAudioRoute.SPEAKER)
        if (devices.hasEarpiece) {
            routes.add(LocalAudioRoute.EARPIECE_OR_DEFAULT)
        }
        if (devices.hasWiredHeadset) {
            routes.add(LocalAudioRoute.WIRED_HEADSET)
        }
        if (devices.hasBluetooth) {
            routes.add(LocalAudioRoute.BLUETOOTH)
        }
        return routes
    }

    fun selectPreferredRoute(
        devices: AvailableLocalDevices,
        userOverride: LocalAudioRoute?,
    ): LocalAudioRoute {
        val available = availableRoutes(devices)
        if (userOverride != null && userOverride in available) {
            return userOverride
        }
        return when {
            devices.hasBluetooth -> LocalAudioRoute.BLUETOOTH
            devices.hasWiredHeadset -> LocalAudioRoute.WIRED_HEADSET
            else -> LocalAudioRoute.SPEAKER
        }
    }

    fun resolveNextRouteOnDeviceChange(
        currentRoute: LocalAudioRoute,
        previous: AvailableLocalDevices,
        current: AvailableLocalDevices,
    ): LocalAudioRoute {
        if (!previous.hasBluetooth && current.hasBluetooth) {
            return LocalAudioRoute.BLUETOOTH
        }
        if (!previous.hasWiredHeadset && current.hasWiredHeadset && !current.hasBluetooth) {
            return LocalAudioRoute.WIRED_HEADSET
        }
        if (currentRoute == LocalAudioRoute.BLUETOOTH && !current.hasBluetooth) {
            return if (current.hasWiredHeadset) LocalAudioRoute.WIRED_HEADSET else LocalAudioRoute.SPEAKER
        }
        if (currentRoute == LocalAudioRoute.WIRED_HEADSET && !current.hasWiredHeadset) {
            return if (current.hasBluetooth) LocalAudioRoute.BLUETOOTH else LocalAudioRoute.SPEAKER
        }
        val available = availableRoutes(current)
        return if (currentRoute in available) currentRoute else selectPreferredRoute(current, null)
    }

    /**
     * Speakerphone NLP ducking is only needed on speakerphone to attenuate loudspeaker-to-mic bleed.
     * Bluetooth and 3.5mm wired headsets keep unrestricted full-duplex speech gain.
     */
    fun shouldEnableSpeakerEchoDucking(route: LocalAudioRoute): Boolean =
        route == LocalAudioRoute.SPEAKER
}
