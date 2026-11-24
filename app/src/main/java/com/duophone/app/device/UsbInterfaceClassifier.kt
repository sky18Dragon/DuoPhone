package com.duophone.app.device

/**
 * Pure-Kotlin USB interface classifier & safe AT candidate ranker.
 *
 * Design constraints (derived from QDC507 hardware research):
 * 1. Never claim ADB (0xFF/0x42/0x01), USB Audio Class (0x01), Mass Storage (0x08),
 *    CDC Control/Data (0x02/0x0A), Wireless (0xE0), or Hub (0x09) as AT ports.
 * 2. Prioritize standard QDC507 AT/Modem interfaces (bInterfaceNumber 2, then 3).
 * 3. Avoid probing Diag IF0 (bInterfaceNumber 0) during normal probing because
 *    probing Diag after a modem reboot can stall synchronous USB reads.
 */
object UsbInterfaceClassifier {
    private const val CLASS_AUDIO = 0x01
    private const val CLASS_CDC_CONTROL = 0x02
    private const val CLASS_MASS_STORAGE = 0x08
    private const val CLASS_HUB = 0x09
    private const val CLASS_CDC_DATA = 0x0A
    private const val CLASS_WIRELESS = 0xE0
    private const val CLASS_VENDOR_SPECIFIC = 0xFF

    private const val ADB_SUBCLASS = 0x42
    private const val ADB_PROTOCOL = 0x01

    const val PREFERRED_AT_INTERFACE_ID = 2
    const val SECONDARY_AT_INTERFACE_ID = 3
    const val DIAG_INTERFACE_ID = 0

    data class RawInterface(
        val index: Int,
        val interfaceId: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
        val endpointCount: Int,
        val bulkInAddress: Int?,
        val bulkOutAddress: Int?,
        val hasInterruptIn: Boolean = false,
    )

    fun classify(raw: RawInterface, activeAtInterfaceId: Int = -1): UsbInterfaceDescriptorInfo {
        val hasBulkPair = raw.bulkInAddress != null && raw.bulkOutAddress != null
        val isAdb = raw.interfaceClass == CLASS_VENDOR_SPECIFIC &&
            raw.interfaceSubclass == ADB_SUBCLASS &&
            raw.interfaceProtocol == ADB_PROTOCOL

        val role = when {
            isAdb -> UsbInterfaceRole.ADB
            raw.interfaceClass == CLASS_AUDIO -> UsbInterfaceRole.USB_AUDIO
            raw.interfaceClass == CLASS_CDC_CONTROL -> UsbInterfaceRole.CDC_CONTROL
            raw.interfaceClass == CLASS_CDC_DATA -> UsbInterfaceRole.CDC_DATA
            raw.interfaceClass == CLASS_MASS_STORAGE -> UsbInterfaceRole.MASS_STORAGE
            raw.interfaceClass == CLASS_WIRELESS -> UsbInterfaceRole.WIRELESS
            raw.interfaceClass == CLASS_VENDOR_SPECIFIC &&
                raw.interfaceId == DIAG_INTERFACE_ID &&
                raw.interfaceSubclass == 0xFF &&
                raw.interfaceProtocol == 0xFF -> UsbInterfaceRole.DIAG_SUSPECT
            raw.interfaceClass == CLASS_VENDOR_SPECIFIC && hasBulkPair -> UsbInterfaceRole.AT_CANDIDATE
            raw.interfaceClass == CLASS_VENDOR_SPECIFIC -> UsbInterfaceRole.VENDOR_OTHER
            else -> UsbInterfaceRole.UNKNOWN
        }

        val isSafeAtCandidate = when (role) {
            UsbInterfaceRole.AT_CANDIDATE -> true
            // CDC ACM/non-networking serial fallback only if subclass is ACM (0x02) and has bulk pair
            UsbInterfaceRole.CDC_CONTROL -> false
            UsbInterfaceRole.ADB,
            UsbInterfaceRole.USB_AUDIO,
            UsbInterfaceRole.CDC_DATA,
            UsbInterfaceRole.MASS_STORAGE,
            UsbInterfaceRole.WIRELESS,
            UsbInterfaceRole.DIAG_SUSPECT,
            UsbInterfaceRole.VENDOR_OTHER,
            UsbInterfaceRole.UNKNOWN,
            -> false
        }

        return UsbInterfaceDescriptorInfo(
            index = raw.index,
            interfaceId = raw.interfaceId,
            interfaceClass = raw.interfaceClass,
            interfaceSubclass = raw.interfaceSubclass,
            interfaceProtocol = raw.interfaceProtocol,
            endpointCount = raw.endpointCount,
            bulkInAddress = raw.bulkInAddress,
            bulkOutAddress = raw.bulkOutAddress,
            hasInterruptIn = raw.hasInterruptIn,
            role = role,
            isSafeAtCandidate = isSafeAtCandidate,
            isActiveAtPort = raw.interfaceId == activeAtInterfaceId && activeAtInterfaceId >= 0,
        )
    }

    /**
     * Ranks safe AT probe candidates in descending order of reliability:
     * 1) IF2 (standard QDC507/Quectel AT port)
     * 2) IF3 (standard Modem port, also accepts AT)
     * 3) Other vendor-specific serial interfaces with interrupt IN (serial ports usually have 3 eps: intr IN + bulk IN/OUT)
     * 4) Other non-IF0 vendor-specific bulk IN+OUT interfaces
     */
    fun rankAtCandidates(interfaces: List<UsbInterfaceDescriptorInfo>): List<UsbInterfaceDescriptorInfo> {
        return interfaces
            .filter { it.isSafeAtCandidate && it.interfaceId != DIAG_INTERFACE_ID }
            .sortedWith(
                compareBy<UsbInterfaceDescriptorInfo>(
                    { candidatePriority(it) },
                    { it.interfaceId },
                ),
            )
    }

    private fun candidatePriority(info: UsbInterfaceDescriptorInfo): Int = when {
        info.interfaceId == PREFERRED_AT_INTERFACE_ID -> 0
        info.interfaceId == SECONDARY_AT_INTERFACE_ID -> 1
        info.hasInterruptIn && info.interfaceSubclass == 0x00 -> 2
        info.interfaceSubclass == 0x00 -> 3
        else -> 4
    }
}
