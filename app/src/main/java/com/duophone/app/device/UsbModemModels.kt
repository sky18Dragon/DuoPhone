package com.duophone.app.device

/**
 * Known USB VID/PID identities for DJI Cellular Dongle Gen 1 (QDC507 / MDM9207).
 */
enum class SupportedModemProfile(
    val vendorId: Int,
    val productId: Int,
    val displayName: String,
) {
    DJI_QDC507(0x2CA3, 0x4006, "DJI Cellular Dongle Gen 1 (QDC507)"),
    QUECTEL_COMPAT(0x2C7C, 0x0125, "QDC507 / Quectel EC25 Compatible"),
    ;

    companion object {
        fun match(vendorId: Int, productId: Int): SupportedModemProfile? =
            entries.firstOrNull { it.vendorId == vendorId && it.productId == productId }
    }
}

enum class UsbInterfaceRole(val label: String) {
    AT_CANDIDATE("串口候选 (Vendor Bulk)"),
    DIAG_SUSPECT("诊断口 (IF0 避让)"),
    ADB("模块 ADB (FF/42/01 保护)"),
    USB_AUDIO("USB 音频 (UAC)"),
    CDC_CONTROL("CDC 网络控制 (ECM/MBIM/RNDIS)"),
    CDC_DATA("CDC 网络数据"),
    MASS_STORAGE("大容量存储"),
    WIRELESS("无线控制 (RNDIS)"),
    VENDOR_OTHER("厂商接口"),
    UNKNOWN("其他接口"),
}

data class UsbInterfaceDescriptorInfo(
    val index: Int,
    val interfaceId: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpointCount: Int,
    val bulkInAddress: Int?,
    val bulkOutAddress: Int?,
    val hasInterruptIn: Boolean = false,
    val role: UsbInterfaceRole,
    val isSafeAtCandidate: Boolean,
    val isActiveAtPort: Boolean = false,
) {
    val summary: String
        get() = buildString {
            append("IF#").append(interfaceId)
            append(" [%02X/%02X/%02X] ".format(interfaceClass, interfaceSubclass, interfaceProtocol))
            append(endpointCount).append("ep")
            if (bulkInAddress != null) append(" IN=0x%02X".format(bulkInAddress))
            if (bulkOutAddress != null) append(" OUT=0x%02X".format(bulkOutAddress))
        }
}

data class UsbDeviceSummary(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String,
    val manufacturerName: String,
    val profile: SupportedModemProfile?,
    val interfaces: List<UsbInterfaceDescriptorInfo>,
) {
    val vidPidHex: String get() = "%04X:%04X".format(vendorId, productId)
    val isSupported: Boolean get() = profile != null
    val hasAdbInterface: Boolean get() = interfaces.any { it.role == UsbInterfaceRole.ADB }
    val hasUacInterface: Boolean get() = interfaces.any { it.role == UsbInterfaceRole.USB_AUDIO }
}
