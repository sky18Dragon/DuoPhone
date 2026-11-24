package com.duophone.app.data

enum class SmsDirection { INCOMING, OUTGOING }

enum class SmsDeliveryStatus(val label: String) {
    RECEIVED("已接收"),
    SENDING("发送中"),
    SENT("已发送"),
    FAILED("发送失败"),
}

data class SmsRecord(
    val id: Long = 0L,
    val dedupKey: String,
    val address: String,
    val direction: SmsDirection,
    val body: String,
    val timestampMs: Long,
    val isRead: Boolean,
    val status: SmsDeliveryStatus,
    val otpCode: String? = null,
    val modemIndices: List<Int> = emptyList(),
)

data class SmsThreadSummary(
    val address: String,
    val latestBody: String,
    val latestTimestampMs: Long,
    val unreadCount: Int,
    val latestOtp: String?,
    val messageCount: Int,
)

enum class CallDirection { INCOMING, OUTGOING }

enum class CallRecordOutcome(val label: String) {
    ANSWERED("已接听"),
    MISSED("未接来电"),
    REJECTED("已拒接"),
    OUTGOING_CONNECTED("已接通"),
    OUTGOING_FAILED("未接通"),
}

data class CallLogRecord(
    val id: Long = 0L,
    val number: String,
    val direction: CallDirection,
    val outcome: CallRecordOutcome,
    val startedAtMs: Long,
    val durationSec: Long,
    val endReason: String = "",
)
