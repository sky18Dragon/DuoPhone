package com.duophone.app.ui.gta

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duophone.app.audio.AudioBridgeState
import com.duophone.app.audio.LocalAudioRoute
import com.duophone.app.data.CallLogRecord
import com.duophone.app.data.ContactRecord
import com.duophone.app.data.SmsRecord
import com.duophone.app.data.SmsThreadSummary
import com.duophone.app.domain.CallUiPhase
import com.duophone.app.domain.CallUiState
import com.duophone.app.domain.ModemConnectionState
import com.duophone.app.domain.ModemUiState
import com.duophone.app.stage.DeckMenu
import com.duophone.app.stage.GtaStageState
import com.duophone.app.stage.PhoneOverlayConfig
import com.duophone.app.stage.StageThemeMode

@Composable
fun BottomDeckScreen(
    displayId: Int,
    stageState: GtaStageState,
    modemState: ModemUiState,
    callState: CallUiState,
    callLogs: List<CallLogRecord>,
    contacts: List<ContactRecord>,
    hasContactsPermission: Boolean,
    audioState: AudioBridgeState,
    smsMessages: List<SmsRecord>,
    smsThreads: List<SmsThreadSummary>,
    isSmsSyncing: Boolean,
    smsSyncStatus: String,
    snackbarHostState: SnackbarHostState,
    onSelectMenu: (DeckMenu) -> Unit,
    onConnectModem: () -> Unit,
    onRefreshTelemetry: () -> Unit,
    // Phone actions
    onDialKeyPressed: (String) -> Unit,
    onDialBackspace: () -> Unit,
    onDialClear: () -> Unit,
    onSetDialBuffer: (String) -> Unit,
    onDial: (String) -> Unit,
    onAnswer: () -> Unit,
    onHangupOrReject: () -> Unit,
    onSendDtmf: (String) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSelectAudioRoute: (LocalAudioRoute) -> Unit,
    onAdjustCallVolume: (Int) -> Unit,
    onDeleteCallLog: (Long) -> Unit,
    onClearCallLogs: () -> Unit,
    onSaveLocalContact: (Long?, String, String, String) -> Unit,
    onDeleteLocalContact: (Long) -> Unit,
    onSyncSystemContacts: () -> Unit,
    // SMS actions
    onSelectSmsThread: (String?) -> Unit,
    onStartNewSms: (String) -> Unit,
    onUpdateSmsDraftNumber: (String) -> Unit,
    onUpdateSmsDraftBody: (String) -> Unit,
    onSyncSms: () -> Unit,
    onSendSms: (String, String) -> Unit,
    onCopyOtp: (String) -> Unit,
    onDeleteSms: (Long) -> Unit,
    // Settings & calibration actions
    onSelectThemeMode: (StageThemeMode) -> Unit,
    onToggleSound: (Boolean) -> Unit,
    onToggleHaptic: (Boolean) -> Unit,
    onToggleCalibrationGrid: (Boolean) -> Unit,
    onUpdateOverlay: ((PhoneOverlayConfig) -> PhoneOverlayConfig) -> Unit,
    onResetOverlay: () -> Unit,
    onRescanAssets: () -> Unit,
    onReplayOpening: () -> Unit,
    onOpenLegacyDiagnostics: () -> Unit,
    onRelaunchDualDisplay: (() -> Unit)? = null,
    onExitSingleScreenDeck: (() -> Unit)? = null,
) {
    val palette = LocalGtaDeckPalette.current
    val modemReady = modemState.activeAtInterfaceId >= 0
    val unreadSmsCount = smsThreads.sumOf { it.unreadCount }

    val statusDotColor = when (modemState.connectionState) {
        ModemConnectionState.READY,
        ModemConnectionState.NETWORK_REGISTERED,
        -> palette.accentGreen
        ModemConnectionState.CONNECTING,
        ModemConnectionState.AT_READY,
        ModemConnectionState.SIM_READY,
        -> palette.accentAmber
        ModemConnectionState.DISCONNECTED -> palette.textSecondary
        else -> palette.accentRed
    }

    val telemetryLine = buildString {
        append(modemState.connectionState.label)
        append(" · ")
        append(modemState.operator.name.ifBlank { "无运营商" })
        if (modemReady) {
            append(" · ${modemState.signal.bars}/5 信号")
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = palette.canvas,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(statusDotColor),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "DUO PHONE",
                                    color = palette.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.4.sp,
                                    maxLines = 1,
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(palette.surfaceHighlight)
                                        .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = "LEONIDA #$displayId",
                                        color = palette.textSecondary,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 0.8.sp,
                                    )
                                }
                            }
                            Text(
                                text = telemetryLine,
                                color = palette.textSecondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onExitSingleScreenDeck != null) {
                            HeaderActionPill(
                                label = "上屏",
                                containerColor = palette.surfaceElevated,
                                contentColor = palette.textPrimary,
                                borderColor = palette.borderStrong,
                                onClick = onExitSingleScreenDeck,
                            )
                        }
                        HeaderActionPill(
                            label = "刷新",
                            containerColor = palette.surfaceElevated,
                            contentColor = palette.textPrimary,
                            borderColor = palette.borderStrong,
                            enabled = !modemState.isBusy,
                            onClick = onRefreshTelemetry,
                        )
                        HeaderActionPill(
                            label = if (modemReady) "重连 4G" else "连接 4G",
                            containerColor = if (modemReady) palette.surfaceElevated else palette.accentGreen,
                            contentColor = if (modemReady) palette.accentTeal else Color.White,
                            borderColor = if (modemReady) palette.accentTeal else palette.accentGreen,
                            enabled = !modemState.isBusy,
                            onClick = onConnectModem,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.border),
                )
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .navigationBarsPadding(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.border),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShoulderKeyCap(label = "L1")

                    DeckMenu.entries.forEach { menu ->
                        val selected = stageState.activeMenu == menu
                        val subtitle = when (menu) {
                            DeckMenu.PHONE -> "DIALER"
                            DeckMenu.SMS -> "MESSAGES"
                            DeckMenu.SETTINGS -> "SYSTEM"
                        }
                        val badgeText = when {
                            menu == DeckMenu.PHONE && callState.phase != CallUiPhase.IDLE -> callState.phase.label
                            menu == DeckMenu.SMS && unreadSmsCount > 0 -> "$unreadSmsCount"
                            else -> null
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    if (selected) palette.accentPrimary else palette.surfaceElevated,
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) palette.accentPrimary else palette.border,
                                    shape = RoundedCornerShape(7.dp),
                                )
                                .clickable { onSelectMenu(menu) }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = menu.title,
                                        color = if (selected) palette.accentOnPrimary else palette.textPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                    Text(
                                        text = subtitle,
                                        color = if (selected) {
                                            palette.accentOnPrimary.copy(alpha = 0.72f)
                                        } else {
                                            palette.textSecondary
                                        },
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 1.1.sp,
                                    )
                                }
                                if (badgeText != null) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(
                                                if (menu == DeckMenu.PHONE) palette.accentGreen else palette.accentBlue,
                                            )
                                            .padding(horizontal = 5.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = badgeText,
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    ShoulderKeyCap(label = "R1")
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (callState.phase != CallUiPhase.IDLE && stageState.activeMenu != DeckMenu.PHONE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.accentGreen.copy(alpha = 0.14f))
                        .border(1.dp, palette.accentGreen, RoundedCornerShape(8.dp))
                        .clickable { onSelectMenu(DeckMenu.PHONE) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${callState.phase.label} · ${callState.remoteNumber}",
                        color = palette.accentGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "切回通话 >",
                        color = palette.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (stageState.activeMenu) {
                    DeckMenu.PHONE -> DeckPhonePanel(
                        dialBuffer = stageState.dialBuffer,
                        modemReady = modemReady,
                        callState = callState,
                        callLogs = callLogs,
                        contacts = contacts,
                        hasContactsPermission = hasContactsPermission,
                        audioState = audioState,
                        onDialKeyPressed = onDialKeyPressed,
                        onDialBackspace = onDialBackspace,
                        onDialClear = onDialClear,
                        onSetDialBuffer = onSetDialBuffer,
                        onDial = onDial,
                        onAnswer = onAnswer,
                        onHangupOrReject = onHangupOrReject,
                        onSendDtmf = onSendDtmf,
                        onToggleMute = onToggleMute,
                        onToggleSpeaker = onToggleSpeaker,
                        onSelectAudioRoute = onSelectAudioRoute,
                        onAdjustCallVolume = onAdjustCallVolume,
                        onDeleteCallLog = onDeleteCallLog,
                        onClearCallLogs = onClearCallLogs,
                        onSaveLocalContact = onSaveLocalContact,
                        onDeleteLocalContact = onDeleteLocalContact,
                        onSyncSystemContacts = onSyncSystemContacts,
                        onOpenSmsForNumber = { num -> onStartNewSms(num) },
                    )

                    DeckMenu.SMS -> DeckSmsPanel(
                        stageState = stageState,
                        messages = smsMessages,
                        threads = smsThreads,
                        contacts = contacts,
                        modemReady = modemReady,
                        isSyncing = isSmsSyncing,
                        syncStatus = smsSyncStatus,
                        onSelectThread = onSelectSmsThread,
                        onStartNewSms = { onStartNewSms("") },
                        onUpdateDraftNumber = onUpdateSmsDraftNumber,
                        onUpdateDraftBody = onUpdateSmsDraftBody,
                        onSyncSms = onSyncSms,
                        onSendSms = onSendSms,
                        onCopyOtp = onCopyOtp,
                        onDeleteMessage = onDeleteSms,
                    )

                    DeckMenu.SETTINGS -> DeckSettingsPanel(
                        stageState = stageState,
                        onSelectThemeMode = onSelectThemeMode,
                        onToggleSound = onToggleSound,
                        onToggleHaptic = onToggleHaptic,
                        onToggleCalibrationGrid = onToggleCalibrationGrid,
                        onUpdateOverlay = onUpdateOverlay,
                        onResetOverlay = onResetOverlay,
                        onRescanAssets = onRescanAssets,
                        onReplayOpening = onReplayOpening,
                        onOpenLegacyDiagnostics = onOpenLegacyDiagnostics,
                        onRelaunchDualDisplay = onRelaunchDualDisplay,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderActionPill(
    label: String,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) containerColor else containerColor.copy(alpha = 0.5f))
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) contentColor else contentColor.copy(alpha = 0.55f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ShoulderKeyCap(label: String) {
    val palette = LocalGtaDeckPalette.current
    Box(
        modifier = Modifier
            .width(34.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(palette.surfaceHighlight)
            .border(1.dp, palette.borderStrong, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = palette.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}
