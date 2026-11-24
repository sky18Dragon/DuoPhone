package com.duophone.app.protocol

enum class AtStatus {
    OK,
    ERROR,
    CME_ERROR,
    CMS_ERROR,
    TIMEOUT,
    TRANSPORT_ERROR,
    PROMPT_READY,
}

data class AtResult(
    val command: String,
    val status: AtStatus,
    val lines: List<String> = emptyList(),
    val raw: String = "",
    val errorDetail: String? = null,
    val durationMs: Long = 0L,
) {
    val isOk: Boolean get() = status == AtStatus.OK || status == AtStatus.PROMPT_READY
    val payloadLines: List<String>
        get() = lines.filterNot { line ->
            val u = line.trim().uppercase()
            u.isEmpty() || u == "OK" || u == "ERROR" || u == command.trim().uppercase()
        }
}

/**
 * Prevents dangerous automatic retries on side-effecting AT commands (dialing, sending SMS,
 * DTMF tones, deleting SMS, resetting modem, writing NV config).
 */
object AtCommandPolicy {
    private val nonRetryablePrefixes = listOf(
        "ATD",
        "ATA",
        "ATH",
        "AT+CHUP",
        "AT+CMGS",
        "AT+CMGW",
        "AT+CMGD",
        "AT+VTS",
        "AT+CFUN=1,1",
        "AT+CFUN=0",
        "AT+QPOWD",
    )

    fun isRetryable(command: String): Boolean {
        val norm = command.trim().uppercase()
        if (nonRetryablePrefixes.any { norm.startsWith(it) }) return false
        // Write forms of AT+QCFG="key",<value> must never auto-retry
        if (norm.startsWith("AT+QCFG=") && norm.contains(",")) return false
        return true
    }
}

data class AtConsoleRecord(
    val timestampMs: Long,
    val command: String,
    val response: String,
    val status: AtStatus,
    val durationMs: Long,
)
