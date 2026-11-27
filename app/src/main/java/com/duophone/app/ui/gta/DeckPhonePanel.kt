package com.duophone.app.ui.gta

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duophone.app.audio.AudioBridgeState
import com.duophone.app.audio.LocalAudioRoute
import com.duophone.app.data.CallDirection
import com.duophone.app.data.CallLogRecord
import com.duophone.app.data.CallRecordOutcome
import com.duophone.app.data.ContactPhoneMatcher
import com.duophone.app.data.ContactRecord
import com.duophone.app.data.ContactSource
import com.duophone.app.domain.CallUiPhase
import com.duophone.app.domain.CallUiState
import com.duophone.app.stage.TopScreenPhoneMasker
import com.duophone.app.ui.CompactDtmfGrid
import com.duophone.app.ui.DIALPAD_ROWS
import com.duophone.app.ui.PhoneMaskVisualTransformation
import com.duophone.app.ui.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PhoneSidePaneMode {
    HISTORY,
    CONTACTS,
}

private fun dialSubLabel(digit: String): String = when (digit) {
    "2" -> "ABC"
    "3" -> "DEF"
    "4" -> "GHI"
    "5" -> "JKL"
    "6" -> "MNO"
    "7" -> "PQRS"
    "8" -> "TUV"
    "9" -> "WXYZ"
    "0" -> "+"
    else -> ""
}

@Composable
fun DeckPhonePanel(
    dialBuffer: String,
    modemReady: Boolean,
    callState: CallUiState,
    callLogs: List<CallLogRecord>,
    contacts: List<ContactRecord>,
    hasContactsPermission: Boolean,
    audioState: AudioBridgeState,
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
    onOpenSmsForNumber: (String) -> Unit,
) {
    val palette = LocalGtaDeckPalette.current
    var rightPaneMode by rememberSaveable { mutableStateOf(PhoneSidePaneMode.HISTORY) }
    var contactSearch by rememberSaveable { mutableStateOf("") }
    var showContactDialog by rememberSaveable { mutableStateOf(false) }
    var editingContactId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingName by rememberSaveable { mutableStateOf("") }
    var editingPhone by rememberSaveable { mutableStateOf("") }
    var editingNote by rememberSaveable { mutableStateOf("") }
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    if (showContactDialog) {
        AlertDialog(
            onDismissRequest = { showContactDialog = false },
            title = { Text(if (editingContactId == null) "新建联系人" else "编辑联系人") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editingName,
                        onValueChange = { editingName = it },
                        singleLine = true,
                        label = { Text("姓名") },
                    )
                    OutlinedTextField(
                        value = editingPhone,
                        onValueChange = { editingPhone = it },
                        singleLine = true,
                        visualTransformation = PhoneMaskVisualTransformation,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        label = { Text("电话号码") },
                    )
                    OutlinedTextField(
                        value = editingNote,
                        onValueChange = { editingNote = it },
                        singleLine = true,
                        label = { Text("备注") },
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = editingName.isNotBlank() && ContactPhoneMatcher.normalize(editingPhone).isNotEmpty(),
                    onClick = {
                        onSaveLocalContact(editingContactId, editingName, editingPhone, editingNote)
                        showContactDialog = false
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showContactDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Left: Clean Rockstar Dialer or Active Call Control
        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.surface)
                .border(1.dp, palette.border, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (callState.phase != CallUiPhase.IDLE) {
                DeckActiveCallPanel(
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
                val matchedName = remember(dialBuffer, contacts) {
                    ContactPhoneMatcher.resolveDisplayName(dialBuffer, contacts)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = matchedName?.let { "拨号目标 · $it" } ?: "DIALER · 拨号键盘",
                        color = if (matchedName != null) palette.accentTeal else palette.textSecondary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!callState.lastError.isNullOrBlank()) {
                        Text(
                            text = callState.lastError,
                            color = palette.accentRed,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // T9 4x3 keypad synced to top virtual phone
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    DIALPAD_ROWS.forEach { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            row.forEach { digit ->
                                val sub = dialSubLabel(digit)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.surfaceElevated)
                                        .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                                        .clickable { onDialKeyPressed(digit) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            text = digit,
                                            color = palette.textPrimary,
                                            fontSize = 16.sp,
                                            lineHeight = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        if (sub.isNotEmpty()) {
                                            Text(
                                                text = sub,
                                                color = palette.textSecondary,
                                                fontSize = 7.5.sp,
                                                lineHeight = 8.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                letterSpacing = 0.8.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Clean bottom action row (+ / Clear | Emerald Call | Backspace)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.surfaceElevated)
                            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                            .clickable {
                                if (dialBuffer.isEmpty()) onDialKeyPressed("+") else onDialClear()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (dialBuffer.isEmpty()) "+" else "清空",
                            color = palette.textSecondary,
                            fontSize = if (dialBuffer.isEmpty()) 20.sp else 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    val canDial = modemReady && dialBuffer.isNotBlank()
                    Box(
                        modifier = Modifier
                            .weight(1.45f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (canDial) palette.accentGreen else palette.surfaceHighlight,
                            )
                            .border(
                                width = 1.dp,
                                color = if (canDial) palette.accentGreen else palette.border,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable(enabled = canDial) { onDial(dialBuffer) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (modemReady) "拨打" else "未连 4G",
                            color = if (canDial) Color.White else palette.textSecondary,
                            fontSize = if (modemReady) 15.sp else 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.surfaceElevated)
                            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                            .clickable(enabled = dialBuffer.isNotEmpty()) { onDialBackspace() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "⌫",
                            color = if (dialBuffer.isNotEmpty()) palette.textPrimary else palette.textSecondary.copy(alpha = 0.45f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // Right: Call Logs / Contacts
        Column(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.surface)
                .border(1.dp, palette.border, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SegmentTabPill(
                    label = "通话记录 (${callLogs.size})",
                    selected = rightPaneMode == PhoneSidePaneMode.HISTORY,
                    onClick = { rightPaneMode = PhoneSidePaneMode.HISTORY },
                )
                SegmentTabPill(
                    label = "通讯录 (${contacts.size})",
                    selected = rightPaneMode == PhoneSidePaneMode.CONTACTS,
                    onClick = { rightPaneMode = PhoneSidePaneMode.CONTACTS },
                )
                Spacer(Modifier.weight(1f))
                if (rightPaneMode == PhoneSidePaneMode.HISTORY && callLogs.isNotEmpty()) {
                    CompactActionChip(
                        label = "清空",
                        textColor = palette.textSecondary,
                        borderColor = palette.borderStrong,
                        onClick = onClearCallLogs,
                    )
                } else if (rightPaneMode == PhoneSidePaneMode.CONTACTS) {
                    CompactActionChip(
                        label = "+ 新建",
                        textColor = palette.accentGreen,
                        borderColor = palette.accentGreen.copy(alpha = 0.6f),
                        onClick = {
                            editingContactId = null
                            editingName = ""
                            editingPhone = dialBuffer
                            editingNote = ""
                            showContactDialog = true
                        },
                    )
                }
            }

            if (rightPaneMode == PhoneSidePaneMode.HISTORY) {
                if (callLogs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无通话记录", color = palette.textSecondary, fontSize = 11.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(callLogs, key = { it.id }) { log ->
                            val badgeColor = when (log.outcome) {
                                CallRecordOutcome.ANSWERED, CallRecordOutcome.OUTGOING_CONNECTED -> palette.accentGreen
                                CallRecordOutcome.MISSED, CallRecordOutcome.REJECTED -> palette.accentAmber
                                CallRecordOutcome.OUTGOING_FAILED -> palette.accentRed
                            }
                            val name = ContactPhoneMatcher.resolveDisplayName(log.number, contacts)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(palette.surfaceElevated)
                                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                                    .clickable { onSetDialBuffer(log.number) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${if (log.direction == CallDirection.INCOMING) "↙ " else "↗ "}${name ?: TopScreenPhoneMasker.mask(log.number)}",
                                        color = palette.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = log.outcome.label,
                                        color = badgeColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${timeFmt.format(Date(log.startedAtMs))} · ${formatDuration(log.durationSec)}",
                                        color = palette.textSecondary,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        text = "删除",
                                        color = palette.accentRed,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clickable { onDeleteCallLog(log.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(palette.canvas)
                            .border(1.dp, palette.border, RoundedCornerShape(7.dp))
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (contactSearch.isEmpty()) {
                            Text("搜索姓名或号码", color = palette.textSecondary, fontSize = 10.sp)
                        }
                        BasicTextField(
                            value = contactSearch,
                            onValueChange = { contactSearch = it },
                            singleLine = true,
                            textStyle = TextStyle(color = palette.textPrimary, fontSize = 11.sp),
                            cursorBrush = SolidColor(palette.accentBlue),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CompactActionChip(
                        label = if (hasContactsPermission) "同步" else "导入",
                        textColor = palette.accentTeal,
                        borderColor = palette.accentTeal.copy(alpha = 0.6f),
                        onClick = onSyncSystemContacts,
                    )
                }

                val filtered = remember(contacts, contactSearch) {
                    val q = contactSearch.trim().lowercase()
                    if (q.isEmpty()) contacts else contacts.filter {
                        it.name.lowercase().contains(q) || it.phone.lowercase().contains(q)
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filtered, key = { "${it.source.name}_${it.id}_${it.normalizedPhone}" }) { contact ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.surfaceElevated)
                                .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                                .clickable { onSetDialBuffer(contact.phone) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = contact.name,
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(contact.source.label, color = palette.accentTeal, fontSize = 9.sp)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = TopScreenPhoneMasker.mask(contact.phone),
                                    color = palette.textSecondary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        text = "拨打",
                                        color = palette.accentGreen,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable {
                                            onSetDialBuffer(contact.phone)
                                            if (modemReady) onDial(contact.phone)
                                        },
                                    )
                                    Text(
                                        text = "短信",
                                        color = palette.accentBlue,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clickable { onOpenSmsForNumber(contact.phone) },
                                    )
                                    if (contact.source == ContactSource.LOCAL) {
                                        Text(
                                            text = "删",
                                            color = palette.accentRed,
                                            fontSize = 10.sp,
                                            modifier = Modifier.clickable { onDeleteLocalContact(contact.id) },
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
}

@Composable
private fun SegmentTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalGtaDeckPalette.current
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) palette.accentPrimary else palette.surfaceElevated)
            .border(
                width = 1.dp,
                color = if (selected) palette.accentPrimary else palette.border,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) palette.accentOnPrimary else palette.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CompactActionChip(
    label: String,
    textColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
) {
    val palette = LocalGtaDeckPalette.current
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.surfaceElevated)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DeckActiveCallPanel(
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
    val palette = LocalGtaDeckPalette.current
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(if (callState.phase == CallUiPhase.ACTIVE) 0.52f else 1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = callState.phase.label,
                color = if (callState.phase == CallUiPhase.ACTIVE) palette.accentGreen else palette.accentAmber,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
            )
            Text(
                text = callerDisplayName ?: TopScreenPhoneMasker.mask(callState.remoteNumber).ifBlank { "未知号码" },
                color = palette.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (callState.phase == CallUiPhase.ACTIVE) {
                Text(
                    text = "时长 ${formatDuration(callState.elapsedSec)} · DTMF: ${callState.dtmfHistory.ifBlank { "—" }}",
                    color = palette.accentTeal,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CompactActionChip(
                        label = if (callState.isMuted) "已静音 (X)" else "静音 (X)",
                        textColor = if (callState.isMuted) palette.accentAmber else palette.textPrimary,
                        borderColor = if (callState.isMuted) palette.accentAmber else palette.borderStrong,
                        onClick = onToggleMute,
                    )
                    CompactActionChip(
                        label = audioState.localRoute.label,
                        textColor = palette.textPrimary,
                        borderColor = palette.borderStrong,
                        onClick = onToggleSpeaker,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    audioState.availableRoutes.forEach { route ->
                        val selected = audioState.localRoute == route
                        CompactActionChip(
                            label = route.label,
                            textColor = if (selected) palette.accentTeal else palette.textSecondary,
                            borderColor = if (selected) palette.accentTeal else palette.border,
                            onClick = { onSelectAudioRoute(route) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactActionChip(
                        label = "音-",
                        textColor = palette.textPrimary,
                        borderColor = palette.borderStrong,
                        onClick = { onAdjustCallVolume(-1) },
                    )
                    Text(
                        text = "音量 ${audioState.callVolumePercent}%",
                        color = palette.textSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    CompactActionChip(
                        label = "音+",
                        textColor = palette.textPrimary,
                        borderColor = palette.borderStrong,
                        onClick = { onAdjustCallVolume(+1) },
                    )
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
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accentGreen),
                    ) {
                        Text("接听 (A)", color = Color.White, fontWeight = FontWeight.ExtraBold)
                    }
                }
                Button(
                    onClick = onHangupOrReject,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accentRed),
                ) {
                    Text(
                        text = if (callState.phase == CallUiPhase.INCOMING) "拒接 (B)" else "挂断 (B)",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }

        if (callState.phase == CallUiPhase.ACTIVE) {
            CompactDtmfGrid(
                modifier = Modifier
                    .weight(0.48f)
                    .fillMaxHeight(),
                onSendDtmf = onSendDtmf,
            )
        }
    }
}
