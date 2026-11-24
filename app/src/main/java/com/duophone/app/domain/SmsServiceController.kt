package com.duophone.app.domain

import com.duophone.app.data.SmsDeliveryStatus
import com.duophone.app.data.SmsDirection
import com.duophone.app.data.SmsRecord
import com.duophone.app.data.SmsStore
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.protocol.AtEngine
import com.duophone.app.protocol.AtStatus
import com.duophone.app.protocol.SmsPduCodec
import com.duophone.app.protocol.UrcEvent
import com.duophone.app.protocol.VerificationCodeExtractor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class SmsSendResult(
    val success: Boolean,
    val segmentsSent: Int,
    val totalSegments: Int,
    val errorMessage: String? = null,
)

/**
 * Manages PDU-mode SMS configuration, inbox sync, real-time +CMTI reception,
 * long-SMS sending (without auto-retry), and local SQLite persistence.
 */
class SmsServiceController(
    private val atEngine: AtEngine,
    private val repository: SmsStore,
    private val onNewIncomingSms: (SmsRecord) -> Unit = {},
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val syncMutex = Mutex()
    private var pduConfigured = false

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncStatus = MutableStateFlow("尚未同步")
    val lastSyncStatus: StateFlow<String> = _lastSyncStatus.asStateFlow()

    val messages: StateFlow<List<SmsRecord>> = repository.messages

    init {
        scope.launch { repository.reload() }
        scope.launch {
            atEngine.urcEvents.collect { event ->
                if (event is UrcEvent.NewSmsIndicator) {
                    DuoLogger.i(LogCategory.SMS, "cmti_received", "storage=${event.storage} idx=${event.index}")
                    syncFromModem(notifyNew = true)
                }
            }
        }
    }

    fun onModemDisconnected() {
        pduConfigured = false
    }

    suspend fun ensurePduMode(): Boolean {
        if (!atEngine.isRunning) return false
        if (pduConfigured) return true
        val cmgf = atEngine.execute("AT+CMGF=0", timeoutMs = 3000, maxRetries = 1)
        atEngine.execute("AT+CNMI=2,1,0,0,0", timeoutMs = 3000, maxRetries = 1)
        pduConfigured = cmgf.isOk
        DuoLogger.i(LogCategory.SMS, "configure_pdu", "AT+CMGF=0 ok=${cmgf.isOk}")
        return pduConfigured
    }

    suspend fun syncFromModem(notifyNew: Boolean = false): Int = syncMutex.withLock {
        if (!atEngine.isRunning) return@withLock 0
        _isSyncing.value = true
        try {
            if (!ensurePduMode()) {
                _lastSyncStatus.value = "PDU 模式初始化失败"
                return@withLock 0
            }
            val res = atEngine.execute("AT+CMGL=4", timeoutMs = 20_000L, maxRetries = 1)
            if (!res.isOk) {
                _lastSyncStatus.value = "读取短信失败 (${res.status})"
                return@withLock 0
            }
            val rawPdus = SmsPduCodec.parseCmglPduResponse(res.raw, storage = "ME")
            val assembled = SmsPduCodec.assembleMultipart(rawPdus)
            var newlyInserted = 0
            for (sms in assembled) {
                val record = SmsRecord(
                    dedupKey = sms.dedupKey,
                    address = sms.sender,
                    direction = SmsDirection.INCOMING,
                    body = sms.body,
                    timestampMs = sms.timestampMs,
                    isRead = sms.isRead,
                    status = SmsDeliveryStatus.RECEIVED,
                    otpCode = sms.otpCode,
                    modemIndices = sms.modemIndices,
                )
                val inserted = repository.insertIfAbsent(record)
                if (inserted) {
                    newlyInserted++
                    if (notifyNew && !record.isRead) {
                        onNewIncomingSms(record)
                    }
                }
            }
            _lastSyncStatus.value = "已同步 ${assembled.size} 条（新增 $newlyInserted 条）"
            DuoLogger.i(LogCategory.SMS, "sync_complete", "rawPdus=${rawPdus.size} assembled=${assembled.size} new=$newlyInserted")
            newlyInserted
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Encodes and transmits an SMS (supporting English, Chinese, and multipart long SMS).
     * Strictly NEVER auto-retries AT+CMGS to prevent duplicate carrier charges/messages.
     */
    suspend fun sendSms(destination: String, body: String): SmsSendResult {
        val normDest = SmsPduCodec.normalizePhoneNumber(destination)
            ?: return SmsSendResult(false, 0, 0, "号码格式无效，仅允许数字和开头 +")
        if (body.isBlank()) {
            return SmsSendResult(false, 0, 0, "短信内容不能为空")
        }
        if (!atEngine.isRunning) {
            return SmsSendResult(false, 0, 0, "4G 模块未连接，无法发送真实短信")
        }
        if (!ensurePduMode()) {
            return SmsSendResult(false, 0, 0, "无法切换至 PDU 短信模式")
        }

        val segments = runCatching { SmsPduCodec.encodeSubmit(normDest, body) }.getOrElse { err ->
            return SmsSendResult(false, 0, 0, err.message ?: "PDU 编码失败")
        }

        val localId = repository.insertAndGetId(
            SmsRecord(
                dedupKey = "out_${UUID.randomUUID()}",
                address = normDest,
                direction = SmsDirection.OUTGOING,
                body = body,
                timestampMs = System.currentTimeMillis(),
                isRead = true,
                status = SmsDeliveryStatus.SENDING,
                otpCode = VerificationCodeExtractor.extract(body),
            ),
        )

        var sentCount = 0
        for (seg in segments) {
            DuoLogger.i(
                LogCategory.SMS,
                "send_segment",
                "part=${seg.sequence}/${seg.total} enc=${seg.encoding} tpduLen=${seg.tpduLength}",
            )
            // Never auto-retry AT+CMGS (maxRetries = 0 enforced both here and in AtCommandPolicy)
            val prompt = atEngine.execute(
                command = "AT+CMGS=${seg.tpduLength}",
                timeoutMs = 8_000L,
                maxRetries = 0,
                expectPrompt = true,
            )
            if (prompt.status != AtStatus.PROMPT_READY) {
                atEngine.abortPrompt()
                repository.updateStatus(localId, SmsDeliveryStatus.FAILED)
                val detail = "分片 ${seg.sequence}/${seg.total} 未收到 > 提示符 (${prompt.errorDetail ?: prompt.status})"
                DuoLogger.e(LogCategory.SMS, "send_prompt_failed", detail)
                return SmsSendResult(false, sentCount, segments.size, detail)
            }

            val submit = atEngine.sendPromptPayload(seg.pduHex, timeoutMs = 60_000L)
            if (!submit.isOk) {
                repository.updateStatus(localId, SmsDeliveryStatus.FAILED)
                val detail = "分片 ${seg.sequence}/${seg.total} 发送失败 (${submit.errorDetail ?: submit.status})"
                DuoLogger.e(LogCategory.SMS, "send_payload_failed", detail)
                return SmsSendResult(false, sentCount, segments.size, detail)
            }
            sentCount++
        }

        repository.updateStatus(localId, SmsDeliveryStatus.SENT)
        DuoLogger.i(LogCategory.SMS, "send_success", "Sent $sentCount/${segments.size} segments")
        return SmsSendResult(true, sentCount, segments.size)
    }

    /**
     * Inserts a clearly-labeled simulated SMS directly into the local SQLite database for
     * on-device App UI/notification verification when no QDC507 dongle is connected.
     */
    suspend fun injectSimulatedIncomingSms(sender: String, body: String) {
        val now = System.currentTimeMillis()
        val record = SmsRecord(
            dedupKey = "sim_${UUID.randomUUID()}",
            address = sender,
            direction = SmsDirection.INCOMING,
            body = "[SIMULATED] $body",
            timestampMs = now,
            isRead = false,
            status = SmsDeliveryStatus.RECEIVED,
            otpCode = VerificationCodeExtractor.extract(body),
        )
        if (repository.insertIfAbsent(record)) {
            onNewIncomingSms(record)
            DuoLogger.i(LogCategory.SMS, "sim_incoming", "Injected simulated SMS into local store")
        }
    }

    suspend fun markThreadRead(address: String) = repository.markThreadRead(address)

    suspend fun deleteLocalMessage(id: Long) = repository.deleteById(id)

    fun release() {
        scope.cancel()
    }
}
