package com.duophone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duophone.app.data.CallDirection
import com.duophone.app.data.CallLogRecord
import com.duophone.app.data.CallRecordOutcome
import com.duophone.app.domain.CallUiPhase
import com.duophone.app.domain.CallUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DIALPAD_ROWS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("*", "0", "#"),
)

@Composable
fun CallTabScreen(
    callState: CallUiState,
    callLogs: List<CallLogRecord>,
    modemReady: Boolean,
    onDial: (String) -> Unit,
    onAnswer: () -> Unit,
    onHangupOrReject: () -> Unit,
    onSendDtmf: (String) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSimulateIncoming: () -> Unit,
    onSimulateOutgoing: (String) -> Unit,
    onDeleteCallLog: (Long) -> Unit,
    onClearCallLogs: () -> Unit,
) {
    var dialInput by rememberSaveable { mutableStateOf("") }
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Left pane: Active Call Card OR Dialpad
        Column(
            modifier = Modifier
                .weight(0.48f)
                .fillMaxSize()
                .background(DuoSurface, RoundedCornerShape(12.dp))
                .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (callState.phase != CallUiPhase.IDLE) {
                ActiveCallPanel(
                    callState = callState,
                    onAnswer = onAnswer,
                    onHangupOrReject = onHangupOrReject,
                    onSendDtmf = onSendDtmf,
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker,
                )
            } else {
                Text(
                    text = "拨号键盘 (Phase 3 呼叫控制)",
                    color = DuoAccent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                if (!callState.lastError.isNullOrBlank()) {
                    Text(
                        text = callState.lastError,
                        color = DuoRed,
                        fontSize = 12.sp,
                    )
                }

                OutlinedTextField(
                    value = dialInput,
                    onValueChange = { dialInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("输入电话号码") },
                )

                DIALPAD_ROWS.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { digit ->
                            OutlinedButton(
                                onClick = { dialInput += digit },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                            ) {
                                Text(digit, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { if (dialInput.isEmpty()) dialInput = "+" else dialInput = dialInput.dropLast(1) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (dialInput.isEmpty()) "+号" else "退格", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { onDial(dialInput) },
                        enabled = modemReady && dialInput.isNotBlank(),
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = DuoGreen),
                    ) {
                        Text("拨出 (ATD)", color = Color(0xFF04121F), fontWeight = FontWeight.Bold)
                    }
                }

                if (!modemReady) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = onSimulateIncoming,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("模拟呼入", fontSize = 11.sp, color = DuoAmber)
                        }
                        OutlinedButton(
                            onClick = { onSimulateOutgoing(dialInput.ifBlank { "+8610086" }) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("模拟拨号", fontSize = 11.sp, color = DuoAmber)
                        }
                    }
                }
            }
        }

        // Right pane: Call History
        Column(
            modifier = Modifier
                .weight(0.52f)
                .fillMaxSize()
                .background(DuoSurface, RoundedCornerShape(12.dp))
                .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "通话记录 (${callLogs.size})",
                    color = DuoAccent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                if (callLogs.isNotEmpty()) {
                    OutlinedButton(onClick = onClearCallLogs) {
                        Text("清空", fontSize = 11.sp)
                    }
                }
            }

            if (callLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无通话记录。\n可点击左侧「模拟呼入 / 模拟拨号」验证接听、挂断与记录落盘。",
                        color = DuoTextSecondary,
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(callLogs, key = { it.id }) { log ->
                        val badgeColor = when (log.outcome) {
                            CallRecordOutcome.ANSWERED, CallRecordOutcome.OUTGOING_CONNECTED -> DuoGreen
                            CallRecordOutcome.MISSED, CallRecordOutcome.REJECTED -> DuoAmber
                            CallRecordOutcome.OUTGOING_FAILED -> DuoRed
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(10.dp))
                                .clickable { dialInput = log.number },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1629)),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "${if (log.direction == CallDirection.INCOMING) "↙ " else "↗ "}${log.number}",
                                        color = DuoTextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        text = log.outcome.label,
                                        color = badgeColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "${timeFmt.format(Date(log.startedAtMs))} · ${formatDuration(log.durationSec)}" +
                                            if (log.endReason.isNotBlank()) " · ${log.endReason}" else "",
                                        color = DuoTextSecondary,
                                        fontSize = 10.sp,
                                    )
                                    Text(
                                        text = "删除",
                                        color = DuoRed,
                                        fontSize = 10.sp,
                                        modifier = Modifier.clickable { onDeleteCallLog(log.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveCallPanel(
    callState: CallUiState,
    onAnswer: () -> Unit,
    onHangupOrReject: () -> Unit,
    onSendDtmf: (String) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (callState.isSimulated) "[SIMULATED] ${callState.phase.label}" else callState.phase.label,
            color = if (callState.phase == CallUiPhase.ACTIVE) DuoGreen else DuoAmber,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
        Text(
            text = callState.remoteNumber.ifBlank { "未知号码" },
            color = DuoTextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        if (callState.phase == CallUiPhase.ACTIVE) {
            Text(
                text = "通话计时 ${formatDuration(callState.elapsedSec)}" +
                    if (callState.dtmfHistory.isNotEmpty()) " · DTMF: ${callState.dtmfHistory}" else "",
                color = DuoCyan,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Text(
            text = callState.audioWarning,
            color = DuoAmber,
            fontSize = 11.sp,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (callState.phase == CallUiPhase.INCOMING) {
                Button(
                    onClick = onAnswer,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = DuoGreen),
                ) {
                    Text("接听 (A)", color = Color(0xFF04121F), fontWeight = FontWeight.Bold)
                }
            }
            Button(
                onClick = onHangupOrReject,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = DuoRed),
            ) {
                Text(
                    text = if (callState.phase == CallUiPhase.INCOMING) "拒接 (B)" else "挂断 (B)",
                    color = Color(0xFF04121F),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (callState.phase == CallUiPhase.ACTIVE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onToggleMute, modifier = Modifier.weight(1f)) {
                    Text(if (callState.isMuted) "已静音 (X)" else "静音 (X)", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onToggleSpeaker, modifier = Modifier.weight(1f)) {
                    Text(if (callState.isSpeakerOn) "扬声器开 (Y)" else "扬声器关 (Y)", fontSize = 12.sp)
                }
            }

            Text("DTMF 按键音 (AT+VTS)", color = DuoTextSecondary, fontSize = 11.sp)
            DIALPAD_ROWS.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    row.forEach { tone ->
                        OutlinedButton(
                            onClick = { onSendDtmf(tone) },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                        ) {
                            Text(tone, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

fun formatDuration(sec: Long): String {
    val m = sec / 60L
    val s = sec % 60L
    return "%02d:%02d".format(m, s)
}
