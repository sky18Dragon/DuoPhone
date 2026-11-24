package com.duophone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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

internal val DIALPAD_ROWS = listOf(
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Left pane: Active Call Card OR Compact Landscape Dialer
        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxSize()
                .background(DuoSurface, RoundedCornerShape(12.dp))
                .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "拨号键盘 (Phase 3)",
                        color = DuoAccent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    if (!callState.lastError.isNullOrBlank()) {
                        Text(
                            text = callState.lastError,
                            color = DuoRed,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                CompactLandscapeDialer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    dialInput = dialInput,
                    onDialInputChange = { dialInput = it },
                    modemReady = modemReady,
                    placeholder = "点击数字键或输入号码",
                    onDial = { onDial(dialInput) },
                )

                if (!modemReady) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = onSimulateIncoming,
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("模拟呼入", fontSize = 11.sp, color = DuoAmber)
                        }
                        OutlinedButton(
                            onClick = { onSimulateOutgoing(dialInput.ifBlank { "+8610086" }) },
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp),
                            contentPadding = PaddingValues(0.dp),
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
                .weight(0.45f)
                .fillMaxSize()
                .background(DuoSurface, RoundedCornerShape(12.dp))
                .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                    fontSize = 13.sp,
                )
                if (callLogs.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClearCallLogs,
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("清空", fontSize = 11.sp)
                    }
                }
            }

            if (callLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无通话记录。\n输入号码后点击右侧绿色「拨出」按钮发起呼叫。",
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
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "${if (log.direction == CallDirection.INCOMING) "↙ " else "↗ "}${log.number}",
                                        color = DuoTextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        text = log.outcome.label,
                                        color = badgeColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "${timeFmt.format(Date(log.startedAtMs))} · ${formatDuration(log.durationSec)}" +
                                            if (log.endReason.isNotBlank()) " · ${log.endReason}" else "",
                                        color = DuoTextSecondary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "删除",
                                        color = DuoRed,
                                        fontSize = 10.sp,
                                        modifier = Modifier
                                            .padding(start = 6.dp)
                                            .clickable { onDeleteCallLog(log.id) },
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

/**
 * Shared landscape-optimized dialer for both top and bottom Retroid Pocket Duo screens:
 * keeps the number bar compact (`40.dp`) and places a prominent green `拨出` button alongside
 * the 4x3 keypad so `* 0 #` and `拨出` never clip on short displays.
 */
@Composable
internal fun CompactLandscapeDialer(
    modifier: Modifier,
    dialInput: String,
    onDialInputChange: (String) -> Unit,
    modemReady: Boolean,
    placeholder: String,
    onDial: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(Color(0xFF0A0F1D), RoundedCornerShape(8.dp))
                .border(1.dp, DuoAccent.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (dialInput.isEmpty()) {
                Text(
                    text = placeholder,
                    color = DuoTextSecondary,
                    fontSize = 13.sp,
                )
            }
            BasicTextField(
                value = dialInput,
                onValueChange = onDialInputChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = DuoTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(DuoAccent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (modemReady && dialInput.isNotBlank()) onDial()
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(0.72f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DIALPAD_ROWS.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        row.forEach { digit ->
                            OutlinedButton(
                                onClick = { onDialInputChange(dialInput + digit) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(digit, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(0.28f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val updated = if (dialInput.isEmpty()) "+" else dialInput.dropLast(1)
                        onDialInputChange(updated)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.26f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(if (dialInput.isEmpty()) "+号" else "退格", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = { onDialInputChange("") },
                    enabled = dialInput.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.22f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("清空", fontSize = 12.sp)
                }

                Button(
                    onClick = onDial,
                    enabled = modemReady && dialInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.52f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DuoGreen,
                        disabledContainerColor = Color(0xFF1E3A34),
                    ),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "拨出",
                            color = if (modemReady && dialInput.isNotBlank()) Color(0xFF04121F) else DuoTextSecondary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "ATD",
                            color = if (modemReady && dialInput.isNotBlank()) Color(0xFF04121F) else DuoTextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompactDtmfGrid(
    modifier: Modifier,
    onSendDtmf: (String) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("DTMF 按键音 (AT+VTS)", color = DuoTextSecondary, fontSize = 10.sp)
        DIALPAD_ROWS.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { tone ->
                    OutlinedButton(
                        onClick = { onSendDtmf(tone) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(tone, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
    if (callState.phase == CallUiPhase.ACTIVE) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(0.50f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (callState.isSimulated) "[SIMULATED] ${callState.phase.label}" else callState.phase.label,
                    color = DuoGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    text = callState.remoteNumber.ifBlank { "未知号码" },
                    color = DuoTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "计时 ${formatDuration(callState.elapsedSec)}" +
                        if (callState.dtmfHistory.isNotEmpty()) " · ${callState.dtmfHistory}" else "",
                    color = DuoCyan,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(if (callState.isMuted) "已静音(X)" else "静音(X)", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(if (callState.isSpeakerOn) "扬声开(Y)" else "扬声关(Y)", fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onHangupOrReject,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DuoRed),
                ) {
                    Text("挂断 (B)", color = Color(0xFF04121F), fontWeight = FontWeight.Bold)
                }
            }

            CompactDtmfGrid(
                modifier = Modifier
                    .weight(0.50f)
                    .fillMaxHeight(),
                onSendDtmf = onSendDtmf,
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (callState.isSimulated) "[SIMULATED] ${callState.phase.label}" else callState.phase.label,
                color = DuoAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                text = callState.remoteNumber.ifBlank { "未知号码" },
                color = DuoTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
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
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DuoGreen),
                    ) {
                        Text("接听 (A)", color = Color(0xFF04121F), fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = onHangupOrReject,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DuoRed),
                ) {
                    Text(
                        text = if (callState.phase == CallUiPhase.INCOMING) "拒接 (B)" else "挂断 (B)",
                        color = Color(0xFF04121F),
                        fontWeight = FontWeight.Bold,
                    )
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
