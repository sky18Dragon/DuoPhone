package com.duophone.app.domain

import com.duophone.app.device.UsbDeviceSummary
import com.duophone.app.protocol.AtConsoleRecord
import com.duophone.app.protocol.ModemIdentity
import com.duophone.app.protocol.OperatorReading
import com.duophone.app.protocol.RegistrationSnapshot
import com.duophone.app.protocol.SignalReading
import com.duophone.app.protocol.SimState
import com.duophone.app.protocol.UsbConfigInfo

enum class ModemConnectionState(val label: String, val isHealthy: Boolean = false) {
    DISCONNECTED("未连接"),
    USB_DETECTED("已发现 USB 模块"),
    PERMISSION_REQUIRED("需要 USB 授权"),
    CONNECTING("正在探测并连接 AT 口"),
    AT_READY("AT 通信已就绪"),
    SIM_READY("SIM 卡已就绪"),
    NETWORK_REGISTERED("蜂窝网络已注册"),
    READY("通信终端已就绪", isHealthy = true),

    // Abnormal states
    ERROR("连接异常"),
    RECOVERING("异常恢复重连中"),
    UNSUPPORTED_DEVICE("不支持的 USB 设备"),
    SIM_NOT_READY("SIM 未就绪或未插卡"),
    NETWORK_UNAVAILABLE("蜂窝网络不可用"),
}

data class ModemUiState(
    val connectionState: ModemConnectionState = ModemConnectionState.DISCONNECTED,
    val statusDetail: String = "请通过 USB Type-C OTG 连接大疆第一代 4G 模块 (2CA3:4006)",
    val usbDevice: UsbDeviceSummary? = null,
    val activeAtInterfaceId: Int = -1,
    val identity: ModemIdentity = ModemIdentity(),
    val simState: SimState = SimState.UNKNOWN,
    val signal: SignalReading = SignalReading(),
    val operator: OperatorReading = OperatorReading(),
    val registration: RegistrationSnapshot = RegistrationSnapshot(),
    val usbConfig: UsbConfigInfo = UsbConfigInfo(),
    val lastRefreshMs: Long = 0L,
    val isBusy: Boolean = false,
    val reconnectAttempt: Int = 0,
    val recentUrcs: List<String> = emptyList(),
    val consoleRecords: List<AtConsoleRecord> = emptyList(),
)
