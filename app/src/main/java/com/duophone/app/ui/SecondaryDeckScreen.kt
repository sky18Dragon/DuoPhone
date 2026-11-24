package com.duophone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duophone.app.audio.AudioBridgeState
import com.duophone.app.audio.LocalAudioRoute
import com.duophone.app.data.ContactPhoneMatcher
import com.duophone.app.data.ContactRecord
import com.duophone.app.domain.CallUiPhase
import com.duophone.app.domain.CallUiState
import com.duophone.app.domain.ModemUiState

enum class DeckMode(val title: String) {
    DIALER("下屏拨号盘"),
    QUICK_SMS("快捷短信"),
    QUICK_AT("AT 快捷板"),
}

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
    contacts: List<ContactRecord> = emptyList(),
    audioState: AudioBridgeState = AudioBridgeState(),
    onSelectAudioRoute: (LocalAudioRoute) -> Unit = {},
    onAdjustCallVolume: (Int) -> Unit = {},
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
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DUO DECK · 下屏控制台 (Display #$displayId)",
                            color = DuoCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${modemState.connectionState.label} · 运营商: ${modemState.operator.name.ifBlank { "未连接" }}",
                            color = DuoTextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = onCloseDeck,
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
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
                                modifier = Modifier.height(34.dp),
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
                .navigationBarsPadding()
                .padding(8.dp),
        ) {
            if (callState.phase != CallUiPhase.IDLE) {
                SecondaryActiveCallDeck(
                    callState = callState,
                    callerDisplayName = ContactPhoneMatcher.resolveDisplayName(callState.remoteNumber, contacts),
                    audioState = audioState,
                    onAnswer = onAnswer,
                    onHangupOrReject = onHangupOrReject,
                    onSendDtmf = onSendDtmf,
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker,
                    onSelectAudioRoute = onSelectAudioRoute,
                    onAdjustCallVolume = onAdjustCallVolume,
                )
            } else {
                when (mode) {
                    DeckMode.DIALER -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(DuoSurface, RoundedCornerShape(12.dp))
                                .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CompactLandscapeDialer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                dialInput = dialInput,
                                onDialInputChange = { dialInput = it },
                                modemReady = modemReady,
                                placeholder = "下屏拨号输入",
                                onDial = { onDial(dialInput) },
                            )
                            if (!modemReady) {
                                OutlinedButton(
                                    onClick = onSimulateIncomingCall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(30.dp),
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Text("模拟来电 (无硬件预览)", color = DuoAmber, fontSize = 11.sp)
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
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedTextField(
                                value = smsTarget,
                                onValueChange = { smsTarget = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("收件号码", fontSize = 12.sp) },
                            )
                            OutlinedTextField(
                                value = smsText,
                                onValueChange = { smsText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                label = { Text("下屏输入短信内容", fontSize = 12.sp) },
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
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                ) {
                                    Text("发送短信", fontSize = 12.sp)
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
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("下屏一键 AT 诊断", color = DuoAccent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            QUICK_AT_PRESETS.chunked(3).forEach { rowCmds ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    rowCmds.forEach { cmd ->
                                        OutlinedButton(
                                            onClick = { onRunAt(cmd) },
                                            enabled = modemReady,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(34.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                        ) {
                                            Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
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

@Composable
private fun SecondaryActiveCallDeck(
    callState: CallUiState,
    callerDisplayName: String?,
    audioState: AudioBridgeState,
    onAnswer: () -> Unit,
    onHangupOrReject: () -> Unit,
    onSendDtmf: (String) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSelectAudioRoute: (LocalAudioRoute) -> Unit,
    onAdjustCallVolume: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(DuoSurface, RoundedCornerShape(12.dp))
            .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(if (callState.phase == CallUiPhase.ACTIVE) 0.50f else 1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${if (callState.isSimulated) "[SIMULATED] " else ""}${callState.phase.label}",
                color = if (callState.phase == CallUiPhase.ACTIVE) DuoGreen else DuoAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(
                text = callerDisplayName ?: callState.remoteNumber.ifBlank { "未知号码" },
                color = DuoTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (callerDisplayName != null && callState.remoteNumber.isNotBlank()) {
                Text(
                    text = callState.remoteNumber,
                    color = DuoTextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            if (callState.phase == CallUiPhase.ACTIVE) {
                Text(
                    text = "计时 ${formatDuration(callState.elapsedSec)}   DTMF: ${callState.dtmfHistory.ifBlank { "—" }}",
                    color = DuoCyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(if (callState.isMuted) "已静音 (X)" else "静音 (X)", fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("路由:${audioState.localRoute.label}", fontSize = 10.sp, maxLines = 1)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    audioState.availableRoutes.forEach { route ->
                        FilterChip(
                            selected = audioState.localRoute == route,
                            onClick = { onSelectAudioRoute(route) },
                            modifier = Modifier.height(26.dp),
                            label = { Text(route.label, fontSize = 10.sp) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { onAdjustCallVolume(-1) },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("音-", fontSize = 10.sp)
                    }
                    Text(
                        text = "音量 ${audioState.callVolumePercent}%",
                        color = DuoTextSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { onAdjustCallVolume(+1) },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("音+", fontSize = 10.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

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
                        Text("接听 (A)", color = Color(0xFF04121F), fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                        fontSize = 14.sp,
                    )
                }
            }
        }

        if (callState.phase == CallUiPhase.ACTIVE) {
            CompactDtmfGrid(
                modifier = Modifier
                    .weight(0.50f)
                    .fillMaxHeight(),
                onSendDtmf = onSendDtmf,
            )
        }
    }
}
