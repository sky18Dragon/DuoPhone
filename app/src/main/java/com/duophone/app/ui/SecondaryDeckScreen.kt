package com.duophone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duophone.app.domain.CallUiPhase
import com.duophone.app.domain.CallUiState
import com.duophone.app.domain.ModemUiState

enum class DeckMode(val title: String) {
    DIALER("下屏拨号盘"),
    QUICK_SMS("快捷短信"),
    QUICK_AT("AT 快捷板"),
}

private val DECK_DIALPAD = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("*", "0", "#"),
)

private val QUICK_AT_PRESETS = listOf(
    "AT",
    "AT+CSQ",
    "AT+CPIN?",
    "AT+COPS?",
    "AT+CEREG?",
    "AT+CLCC",
)

@Composable
fun SecondaryDeckScreen(
    displayId: Int,
    modemState: ModemUiState,
    callState: CallUiState,
    snackbarHostState: SnackbarHostState,
    onDial: (String) -> Unit,
    onAnswer: () -> Unit,
    onHangupOrReject: () -> Unit,
    onSendDtmf: (String) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSendSms: (String, String) -> Unit,
    onRunAt: (String) -> Unit,
    onSimulateIncomingCall: () -> Unit,
    onCloseDeck: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(DeckMode.DIALER) }
    var dialInput by rememberSaveable { mutableStateOf("") }
    var smsTarget by rememberSaveable { mutableStateOf("") }
    var smsText by rememberSaveable { mutableStateOf("") }
    val modemReady = modemState.activeAtInterfaceId >= 0

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DuoBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DuoSurface)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "DUO DECK · 下屏控制台 (Display #$displayId)",
                            color = DuoCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = "${modemState.connectionState.label} · 运营商: ${modemState.operator.name.ifBlank { "未连接" }}",
                            color = DuoTextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    OutlinedButton(onClick = onCloseDeck) {
                        Text("关闭下屏", fontSize = 11.sp)
                    }
                }

                if (callState.phase == CallUiPhase.IDLE) {
                    TabRow(
                        selectedTabIndex = mode.ordinal,
                        containerColor = DuoSurface,
                        contentColor = DuoAccent,
                    ) {
                        DeckMode.entries.forEach { entry ->
                            Tab(
                                selected = mode == entry,
                                onClick = { mode = entry },
                                text = { Text(entry.title, fontSize = 12.sp) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp),
        ) {
            if (callState.phase != CallUiPhase.IDLE) {
                // Dedicated tactile call-control deck on lower screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DuoSurface, RoundedCornerShape(12.dp))
                        .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "${if (callState.isSimulated) "[SIMULATED] " else ""}${callState.phase.label} · ${callState.remoteNumber.ifBlank { "未知号码" }}",
                        color = if (callState.phase == CallUiPhase.ACTIVE) DuoGreen else DuoAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    if (callState.phase == CallUiPhase.ACTIVE) {
                        Text(
                            text = "计时 ${formatDuration(callState.elapsedSec)}   DTMF: ${callState.dtmfHistory.ifBlank { "—" }}",
                            color = DuoCyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (callState.phase == CallUiPhase.INCOMING) {
                            Button(
                                onClick = onAnswer,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = DuoGreen),
                            ) {
                                Text("接听 (A)", color = Color(0xFF04121F), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                        Button(
                            onClick = onHangupOrReject,
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DuoRed),
                        ) {
                            Text(
                                text = if (callState.phase == CallUiPhase.INCOMING) "拒接 (B)" else "挂断 (B)",
                                color = Color(0xFF04121F),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                        }
                    }

                    if (callState.phase == CallUiPhase.ACTIVE) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = onToggleMute,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                            ) {
                                Text(if (callState.isMuted) "已静音 (X)" else "静音 (X)")
                            }
                            OutlinedButton(
                                onClick = onToggleSpeaker,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                            ) {
                                Text(if (callState.isSpeakerOn) "扬声器开 (Y)" else "扬声器关 (Y)")
                            }
                        }

                        DECK_DIALPAD.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                row.forEach { tone ->
                                    OutlinedButton(
                                        onClick = { onSendDtmf(tone) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp),
                                    ) {
                                        Text(tone, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                when (mode) {
                    DeckMode.DIALER -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(DuoSurface, RoundedCornerShape(12.dp))
                                .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = dialInput,
                                onValueChange = { dialInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("下屏拨号输入") },
                            )
                            DECK_DIALPAD.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    row.forEach { digit ->
                                        OutlinedButton(
                                            onClick = { dialInput += digit },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp),
                                        ) {
                                            Text(digit, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        dialInput = if (dialInput.isEmpty()) "+" else dialInput.dropLast(1)
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(if (dialInput.isEmpty()) "+号" else "退格")
                                }
                                Button(
                                    onClick = { onDial(dialInput) },
                                    enabled = modemReady && dialInput.isNotBlank(),
                                    modifier = Modifier.weight(2f),
                                    colors = ButtonDefaults.buttonColors(containerColor = DuoGreen),
                                ) {
                                    Text("拨出", color = Color(0xFF04121F), fontWeight = FontWeight.Bold)
                                }
                                if (!modemReady) {
                                    OutlinedButton(
                                        onClick = onSimulateIncomingCall,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("模拟来电", color = DuoAmber, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    DeckMode.QUICK_SMS -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(DuoSurface, RoundedCornerShape(12.dp))
                                .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = smsTarget,
                                onValueChange = { smsTarget = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("收件号码") },
                            )
                            OutlinedTextField(
                                value = smsText,
                                onValueChange = { smsText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                label = { Text("下屏输入短信内容") },
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Button(
                                    onClick = {
                                        onSendSms(smsTarget, smsText)
                                        smsText = ""
                                    },
                                    enabled = modemReady && smsTarget.isNotBlank() && smsText.isNotBlank(),
                                ) {
                                    Text("发送短信")
                                }
                            }
                        }
                    }

                    DeckMode.QUICK_AT -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(DuoSurface, RoundedCornerShape(12.dp))
                                .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("下屏一键 AT 诊断", color = DuoAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            QUICK_AT_PRESETS.chunked(2).forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    pair.forEach { cmd ->
                                        OutlinedButton(
                                            onClick = { onRunAt(cmd) },
                                            enabled = modemReady,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            val latest = modemState.consoleRecords.lastOrNull()
                            Text(
                                text = latest?.let { "> ${it.command}\n${it.response}" }
                                    ?: "结果同步显示于此及上屏 AT 调试终端。",
                                color = DuoTextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
