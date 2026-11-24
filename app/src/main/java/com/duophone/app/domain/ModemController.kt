package com.duophone.app.domain

import com.duophone.app.device.UsbDeviceSummary
import com.duophone.app.device.UsbSerialTransport
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.protocol.AtConsoleRecord
import com.duophone.app.protocol.AtEngine
import com.duophone.app.protocol.AtResponseParser
import com.duophone.app.protocol.AtResult
import com.duophone.app.protocol.AtStatus
import com.duophone.app.protocol.ModemIdentity
import com.duophone.app.protocol.NetworkRegState
import com.duophone.app.protocol.RegistrationSnapshot
import com.duophone.app.protocol.SignalReading
import com.duophone.app.protocol.SimState
import com.duophone.app.protocol.UrcEvent
import com.duophone.app.protocol.UsbConfigInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates the USB modem lifecycle, connection state machine, AT initialization,
 * telemetry queries, URC updates, and automatic recovery.
 */
class ModemController(
    private val transport: UsbSerialTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestUsbPermission: () -> Boolean = { false },
    private val findAttachedSummary: () -> UsbDeviceSummary? = { transport.currentDevice },
    private val hasUsbPermission: () -> Boolean = { true },
    private val autoReconnectEnabled: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lifecycleMutex = Mutex()
    private var reconnectJob: Job? = null

    val atEngine: AtEngine = AtEngine(
        transport = transport,
        ioDispatcher = ioDispatcher,
        onTransportLost = { err -> handleTransportLost(err) },
    )

    private val _state = MutableStateFlow(ModemUiState())
    val state: StateFlow<ModemUiState> = _state.asStateFlow()

    init {
        scope.launch {
            atEngine.urcEvents.collect { event ->
                handleUrc(event)
            }
        }
    }

    fun onUsbDeviceAttached() {
        DuoLogger.i(LogCategory.USB, "usb_attached", "USB attach event received", stateTag())
        reconnectJob?.cancel()
        scope.launch { connect() }
    }

    fun onUsbPermissionResult(granted: Boolean) {
        DuoLogger.i(LogCategory.USB, "usb_permission", "granted=$granted", stateTag())
        if (granted) {
            scope.launch { connect() }
        } else {
            transition(
                ModemConnectionState.ERROR,
                "用户拒绝了 USB 访问权限，请点击重新连接并授予权限。",
            )
        }
    }

    fun onUsbDeviceDetached() {
        DuoLogger.w(LogCategory.USB, "usb_detached", "USB detach event received", stateTag())
        reconnectJob?.cancel()
        reconnectJob = null
        atEngine.stopReader()
        transport.close()
        _state.update {
            it.copy(
                connectionState = ModemConnectionState.DISCONNECTED,
                statusDetail = "4G 模块已断开。若非主动拔出，请检查 OTG 线缆或供电能力。",
                usbDevice = null,
                activeAtInterfaceId = -1,
                simState = SimState.UNKNOWN,
                signal = SignalReading(),
                registration = RegistrationSnapshot(),
                isBusy = false,
                reconnectAttempt = 0,
            )
        }
    }

    suspend fun connect(): Boolean = lifecycleMutex.withLock {
        reconnectJob?.cancel()
        reconnectJob = null

        val attached = findAttachedSummary()
        if (attached == null) {
            transition(
                ModemConnectionState.DISCONNECTED,
                "未检测到大疆第一代 4G 模块 (2CA3:4006 / 2C7C:0125)。",
            )
            return@withLock false
        }
        if (!attached.isSupported) {
            _state.update {
                it.copy(
                    connectionState = ModemConnectionState.UNSUPPORTED_DEVICE,
                    statusDetail = "检测到不支持的 USB 设备：${attached.vidPidHex}",
                    usbDevice = attached,
                )
            }
            return@withLock false
        }

        _state.update {
            it.copy(
                connectionState = ModemConnectionState.USB_DETECTED,
                statusDetail = "已检测到 ${attached.profile?.displayName} (${attached.vidPidHex})",
                usbDevice = attached,
                isBusy = true,
            )
        }

        if (!hasUsbPermission()) {
            transition(ModemConnectionState.PERMISSION_REQUIRED, "正在申请 USB 设备权限...")
            _state.update { it.copy(isBusy = false) }
            requestUsbPermission()
            return@withLock false
        }

        transition(ModemConnectionState.CONNECTING, "正在安全探测 AT 串口...")
        val probedSummary = try {
            transport.openAndProbe { step ->
                _state.update { it.copy(statusDetail = step) }
            }
        } catch (e: SecurityException) {
            transition(ModemConnectionState.PERMISSION_REQUIRED, e.message ?: "需要 USB 权限")
            _state.update { it.copy(isBusy = false) }
            requestUsbPermission()
            return@withLock false
        } catch (e: Exception) {
            DuoLogger.e(LogCategory.USB, "open_probe_failed", e.message ?: "探测失败", stateTag(), throwable = e)
            transition(ModemConnectionState.ERROR, e.message ?: "打开或探测 USB AT 接口失败")
            _state.update { it.copy(isBusy = false) }
            return@withLock false
        }

        atEngine.startReader()
        _state.update {
            it.copy(
                usbDevice = probedSummary,
                activeAtInterfaceId = transport.activeInterfaceId,
            )
        }

        // Verify AT handshake & disable echo
        val atOk = atEngine.execute("AT", timeoutMs = 2000, maxRetries = 2)
        if (!atOk.isOk) {
            DuoLogger.e(LogCategory.AT, "handshake_failed", "AT handshake failed: ${atOk.status}", stateTag())
            atEngine.stopReader()
            transport.close()
            transition(ModemConnectionState.ERROR, "AT 握手失败：${atOk.errorDetail ?: atOk.status}")
            _state.update { it.copy(isBusy = false) }
            return@withLock false
        }

        atEngine.execute("ATE0", timeoutMs = 2000, maxRetries = 1)
        transition(ModemConnectionState.AT_READY, "AT 握手成功 (IF#${transport.activeInterfaceId})，正在读取模块信息...")

        refreshTelemetryInternal(includeIdentityAndConfig = true)
        _state.update { it.copy(isBusy = false, reconnectAttempt = 0) }
        return@withLock _state.value.connectionState.isHealthy ||
            _state.value.connectionState == ModemConnectionState.SIM_NOT_READY ||
            _state.value.connectionState == ModemConnectionState.NETWORK_UNAVAILABLE
    }

    suspend fun refreshTelemetry(includeIdentityAndConfig: Boolean = false) = lifecycleMutex.withLock {
        if (!transport.isOpen) return@withLock
        _state.update { it.copy(isBusy = true) }
        try {
            refreshTelemetryInternal(includeIdentityAndConfig)
        } finally {
            _state.update { it.copy(isBusy = false) }
        }
    }

    suspend fun runRawAtCommand(rawCmd: String): AtResult {
        val cmd = rawCmd.trim()
        if (cmd.isEmpty()) {
            return AtResult(cmd, AtStatus.ERROR, errorDetail = "指令不能为空")
        }
        val result = atEngine.execute(cmd, timeoutMs = 15_000L, maxRetries = 0)
        val record = AtConsoleRecord(
            timestampMs = System.currentTimeMillis(),
            command = cmd,
            response = result.raw.ifBlank { result.errorDetail ?: result.status.name },
            status = result.status,
            durationMs = result.durationMs,
        )
        _state.update {
            it.copy(consoleRecords = (it.consoleRecords + record).takeLast(80))
        }
        return result
    }

    fun clearConsole() {
        _state.update { it.copy(consoleRecords = emptyList()) }
    }

    fun release() {
        reconnectJob?.cancel()
        atEngine.release()
        transport.close()
        scope.cancel()
    }

    private suspend fun refreshTelemetryInternal(includeIdentityAndConfig: Boolean) {
        if (includeIdentityAndConfig) {
            val ati = atEngine.execute("ATI", timeoutMs = 3000, maxRetries = 1)
            val cgmi = atEngine.execute("AT+CGMI", timeoutMs = 3000, maxRetries = 1)
            val cgmm = atEngine.execute("AT+CGMM", timeoutMs = 3000, maxRetries = 1)
            val cgmr = atEngine.execute("AT+CGMR", timeoutMs = 3000, maxRetries = 1)
            val cgsn = atEngine.execute("AT+CGSN", timeoutMs = 3000, maxRetries = 1)

            val identity = ModemIdentity(
                manufacturer = AtResponseParser.parseSingleValueLine(cgmi, "+CGMI:"),
                model = AtResponseParser.parseSingleValueLine(cgmm, "+CGMM:"),
                revision = AtResponseParser.parseSingleValueLine(cgmr, "+CGMR:")
                    .ifBlank { AtResponseParser.parseAtiRevision(ati) },
                imei = AtResponseParser.parseImei(cgsn),
            )

            // Read-only USB & voice capability probes (safe, non-destructive)
            val usbnetRes = atEngine.execute("AT+QCFG=\"usbnet\"", timeoutMs = 3000, maxRetries = 1)
            val usbcfgRes = atEngine.execute("AT+QCFG=\"usbcfg\"", timeoutMs = 3000, maxRetries = 1)
            val imsRes = atEngine.execute("AT+QCFG=\"ims\"", timeoutMs = 3000, maxRetries = 1)
            val qpcmvRes = atEngine.execute("AT+QPCMV=?", timeoutMs = 3000, maxRetries = 1)

            val usbConfig = UsbConfigInfo(
                usbnetMode = AtResponseParser.parseQcfgTailInt(usbnetRes),
                usbcfgRaw = AtResponseParser.parseQcfgLine(usbcfgRes),
                imsRaw = AtResponseParser.parseQcfgLine(imsRes),
                qpcmvSupportRaw = qpcmvRes.payloadLines.firstOrNull().orEmpty(),
            )

            _state.update { it.copy(identity = identity, usbConfig = usbConfig) }
            DuoLogger.i(
                LogCategory.MODEM,
                "identity",
                "mfr=${identity.manufacturer} model=${identity.model} rev=${identity.revision} " +
                    "imei=${identity.imei} usbnet=${usbConfig.usbnetMode} qpcmv=${usbConfig.qpcmvSupportRaw}",
                stateTag(),
            )
        }

        val cpin = atEngine.execute("AT+CPIN?", timeoutMs = 3000, maxRetries = 1)
        val simState = AtResponseParser.parseCpin(cpin)
        _state.update { it.copy(simState = simState) }

        if (simState != SimState.READY) {
            transition(
                ModemConnectionState.SIM_NOT_READY,
                "AT 就绪，但 SIM 状态异常：${simState.label}",
            )
            _state.update { it.copy(lastRefreshMs = System.currentTimeMillis()) }
            return
        }

        transition(ModemConnectionState.SIM_READY, "SIM 卡已就绪，正在查询网络注册与信号...")

        val csq = atEngine.execute("AT+CSQ", timeoutMs = 3000, maxRetries = 1)
        val cops = atEngine.execute("AT+COPS?", timeoutMs = 5000, maxRetries = 1)
        val creg = atEngine.execute("AT+CREG?", timeoutMs = 3000, maxRetries = 1)
        val cgreg = atEngine.execute("AT+CGREG?", timeoutMs = 3000, maxRetries = 1)
        val cereg = atEngine.execute("AT+CEREG?", timeoutMs = 3000, maxRetries = 1)

        val signal = AtResponseParser.parseCsq(csq)
        val operator = AtResponseParser.parseCops(cops)
        val reg = RegistrationSnapshot(
            creg = AtResponseParser.parseReg(creg),
            cgreg = AtResponseParser.parseReg(cgreg),
            cereg = AtResponseParser.parseReg(cereg),
        )

        _state.update {
            it.copy(
                signal = signal,
                operator = operator,
                registration = reg,
                lastRefreshMs = System.currentTimeMillis(),
            )
        }

        if (reg.isRegistered) {
            transition(ModemConnectionState.NETWORK_REGISTERED, "已注册至 ${operator.name.ifBlank { "蜂窝网络" }}")
            transition(
                ModemConnectionState.READY,
                buildString {
                    append(operator.name.ifBlank { "已注册网络" })
                    if (operator.accessTechLabel.isNotBlank()) append(" · ").append(operator.accessTechLabel)
                    if (signal.dbm != null) append(" · ${signal.dbm} dBm")
                    append(" · AT IF#${transport.activeInterfaceId}")
                },
            )
        } else {
            transition(
                ModemConnectionState.NETWORK_UNAVAILABLE,
                "SIM 已就绪，蜂窝网络状态：${reg.primaryState.label}",
            )
        }
    }

    private fun handleUrc(event: UrcEvent) {
        _state.update { current ->
            val updatedUrcs = (listOf(event.rawLine) + current.recentUrcs).take(30)
            when (event) {
                is UrcEvent.NetworkRegistrationChanged -> {
                    val newState = NetworkRegState.fromCode(event.stat)
                    val newReg = when (event.domain) {
                        "CREG" -> current.registration.copy(creg = newState)
                        "CGREG" -> current.registration.copy(cgreg = newState)
                        "CEREG" -> current.registration.copy(cereg = newState)
                        else -> current.registration
                    }
                    current.copy(registration = newReg, recentUrcs = updatedUrcs)
                }
                is UrcEvent.SignalChanged -> {
                    val dbm = if (event.rssi in 0..31) -113 + 2 * event.rssi else null
                    val bars = when {
                        event.rssi >= 99 -> 0
                        event.rssi <= 5 -> 1
                        event.rssi <= 11 -> 2
                        event.rssi <= 17 -> 3
                        event.rssi <= 23 -> 4
                        else -> 5
                    }
                    current.copy(
                        signal = SignalReading(event.rssi, event.ber, dbm, bars),
                        recentUrcs = updatedUrcs,
                    )
                }
                else -> current.copy(recentUrcs = updatedUrcs)
            }
        }
    }

    private fun handleTransportLost(err: Throwable) {
        transport.close()
        if (!autoReconnectEnabled) {
            transition(ModemConnectionState.DISCONNECTED, "USB 链路中断：${err.message.orEmpty()}")
            return
        }
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val delays = listOf(1500L, 3000L, 5000L, 10_000L)
            for ((idx, waitMs) in delays.withIndex()) {
                val attempt = idx + 1
                _state.update {
                    it.copy(
                        connectionState = ModemConnectionState.RECOVERING,
                        statusDetail = "USB 链路中断，${waitMs / 1000}s 后尝试第 $attempt 次恢复...",
                        reconnectAttempt = attempt,
                    )
                }
                DuoLogger.w(
                    LogCategory.USB,
                    "recover_schedule",
                    "attempt=$attempt delayMs=$waitMs reason=${err.message}",
                    stateTag(),
                )
                delay(waitMs)
                if (findAttachedSummary() == null) {
                    transition(ModemConnectionState.DISCONNECTED, "模块未连接，等待重新插入...")
                    return@launch
                }
                if (connect()) {
                    DuoLogger.i(LogCategory.USB, "recover_success", "Reconnected on attempt=$attempt", stateTag())
                    return@launch
                }
            }
            transition(ModemConnectionState.ERROR, "自动恢复失败，请重新插拔模块或点击手动重连。")
        }
    }

    private fun transition(newState: ModemConnectionState, detail: String) {
        val prev = _state.value.connectionState
        if (prev != newState) {
            DuoLogger.i(LogCategory.MODEM, "state_transition", "$prev -> $newState ($detail)", newState.name)
        }
        _state.update { it.copy(connectionState = newState, statusDetail = detail) }
    }

    private fun stateTag(): String = _state.value.connectionState.name
}
