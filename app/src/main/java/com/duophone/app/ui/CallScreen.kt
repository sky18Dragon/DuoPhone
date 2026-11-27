package com.duophone.app.ui

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val DIALPAD_ROWS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("*", "0", "#"),
)

private enum class RightCallPaneMode {
    HISTORY,
    CONTACTS,
}

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
    contacts: List<ContactRecord> = emptyList(),
    hasContactsPermission: Boolean = false,
    audioState: AudioBridgeState = AudioBridgeState(),
    onSelectAudioRoute: (LocalAudioRoute) -> Unit = {},
    onAdjustCallVolume: (Int) -> Unit = {},
    onSaveLocalContact: (Long?, String, String, String) -> Unit = { _, _, _, _ -> },
    onDeleteLocalContact: (Long) -> Unit = {},
    onSyncSystemContacts: () -> Unit = {},
    onOpenSmsForNumber: (String) -> Unit = {},
) {
    var dialInput by rememberSaveable { mutableStateOf("") }
    var rightMode by rememberSaveable { mutableStateOf(RightCallPaneMode.HISTORY) }
    var contactSearch by rememberSaveable { mutableStateOf("") }
    var editingContactId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingName by rememberSaveable { mutableStateOf("") }
    var editingPhone by rememberSaveable { mutableStateOf("") }
    var editingNote by rememberSaveable { mutableStateOf("") }
    var showContactDialog by rememberSaveable { mutableStateOf(false) }

    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    if (showContactDialog) {
        AlertDialog(
            onDismissRequest = { showContactDialog = false },
            title = { Text(if (editingContactId == null) "新建本地联系人" else "编辑联系人") },
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
                        label = { Text("备注（可选）") },
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Left pane: Active Call Card OR Compact Landscape Dialer
        Column(
            modifier = Modifier
                .weight(0.54f)
                .fillMaxSize()
                .background(DuoSurface, RoundedCornerShape(12.dp))
                .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (callState.phase != CallUiPhase.IDLE) {
                val callerName = ContactPhoneMatcher.resolveDisplayName(callState.remoteNumber, contacts)
                ActiveCallPanel(
                    callState = callState,
                    callerDisplayName = callerName,
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
                val dialMatchedName = remember(dialInput, contacts) {
                    ContactPhoneMatcher.resolveDisplayName(dialInput, contacts)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = dialMatchedName?.let { "拨号 · $it" } ?: "拨号键盘",
                        color = DuoAccent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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

        // Right pane: Call History OR Contacts
        Column(
            modifier = Modifier
                .weight(0.46f)
                .fillMaxSize()
                .background(DuoSurface, RoundedCornerShape(12.dp))
                .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = rightMode == RightCallPaneMode.HISTORY,
                    onClick = { rightMode = RightCallPaneMode.HISTORY },
                    modifier = Modifier.height(28.dp),
                    label = { Text("通话记录(${callLogs.size})", fontSize = 11.sp) },
                )
                FilterChip(
                    selected = rightMode == RightCallPaneMode.CONTACTS,
                    onClick = { rightMode = RightCallPaneMode.CONTACTS },
                    modifier = Modifier.height(28.dp),
                    label = { Text("通讯录(${contacts.size})", fontSize = 11.sp) },
                )
                Spacer(Modifier.weight(1f))
                if (rightMode == RightCallPaneMode.HISTORY && callLogs.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClearCallLogs,
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("清空", fontSize = 11.sp)
                    }
                } else if (rightMode == RightCallPaneMode.CONTACTS) {
                    OutlinedButton(
                        onClick = {
                            editingContactId = null
                            editingName = ""
                            editingPhone = dialInput
                            editingNote = ""
                            showContactDialog = true
                        },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("+新建", fontSize = 11.sp, color = DuoGreen)
                    }
                }
            }

            if (rightMode == RightCallPaneMode.HISTORY) {
                if (callLogs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "暂无通话记录。\n输入号码后点击绿色「拨出」按钮发起呼叫。",
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
                            val matchedName = ContactPhoneMatcher.resolveDisplayName(log.number, contacts)
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
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "${if (log.direction == CallDirection.INCOMING) "↙ " else "↗ "}${matchedName ?: TopScreenPhoneMasker.mask(log.number)}",
                                            color = DuoTextPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = log.outcome.label,
                                            color = badgeColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                    if (matchedName != null) {
                                        Text(
                                            text = TopScreenPhoneMasker.mask(log.number),
                                            color = DuoCyan,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
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
                                        if (matchedName == null && ContactPhoneMatcher.normalize(log.number).isNotEmpty()) {
                                            Text(
                                                text = "存",
                                                color = DuoAccent,
                                                fontSize = 10.sp,
                                                modifier = Modifier
                                                    .padding(start = 6.dp)
                                                    .clickable {
                                                        editingContactId = null
                                                        editingName = ""
                                                        editingPhone = log.number
                                                        editingNote = ""
                                                        showContactDialog = true
                                                    },
                                            )
                                        }
                                        Text(
                                            text = "删",
                                            color = DuoRed,
                                            fontSize = 10.sp,
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .clickable { onDeleteCallLog(log.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Contacts sub-pane
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .background(Color(0xFF0A0F1D), RoundedCornerShape(8.dp))
                            .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (contactSearch.isEmpty()) {
                            Text("搜索姓名或号码", color = DuoTextSecondary, fontSize = 11.sp)
                        }
                        BasicTextField(
                            value = contactSearch,
                            onValueChange = { contactSearch = it },
                            singleLine = true,
                            textStyle = TextStyle(color = DuoTextPrimary, fontSize = 12.sp),
                            cursorBrush = SolidColor(DuoAccent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedButton(
                        onClick = onSyncSystemContacts,
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = if (hasContactsPermission) "同步系统" else "导入系统",
                            fontSize = 11.sp,
                            color = DuoCyan,
                        )
                    }
                }

                val filteredContacts = remember(contacts, contactSearch) {
                    val q = contactSearch.trim().lowercase()
                    if (q.isEmpty()) {
                        contacts
                    } else {
                        contacts.filter {
                            it.name.lowercase().contains(q) ||
                                it.phone.lowercase().contains(q) ||
                                it.normalizedPhone.contains(q)
                        }
                    }
                }

                if (filteredContacts.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (contacts.isEmpty()) "暂无联系人，可点击「+新建」或「导入系统」。" else "无匹配联系人",
                            color = DuoTextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filteredContacts, key = { "${it.source.name}_${it.id}_${it.normalizedPhone}" }) { contact ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(10.dp))
                                    .clickable { dialInput = contact.phone },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1629)),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = contact.name,
                                            color = DuoTextPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = contact.source.label,
                                            color = if (contact.source == ContactSource.LOCAL) DuoAccent else DuoCyan,
                                            fontSize = 10.sp,
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = TopScreenPhoneMasker.mask(contact.phone),
                                            color = DuoTextSecondary,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = "拨号",
                                                color = DuoGreen,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable {
                                                    dialInput = contact.phone
                                                    if (modemReady) onDial(contact.phone)
                                                },
                                            )
                                            Text(
                                                text = "短信",
                                                color = DuoCyan,
                                                fontSize = 11.sp,
                                                modifier = Modifier.clickable { onOpenSmsForNumber(contact.phone) },
                                            )
                                            if (contact.source == ContactSource.LOCAL) {
                                                Text(
                                                    text = "改",
                                                    color = DuoAccent,
                                                    fontSize = 10.sp,
                                                    modifier = Modifier.clickable {
                                                        editingContactId = contact.id
                                                        editingName = contact.name
                                                        editingPhone = contact.phone
                                                        editingNote = contact.note
                                                        showContactDialog = true
                                                    },
                                                )
                                                Text(
                                                    text = "删",
                                                    color = DuoRed,
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
                visualTransformation = PhoneMaskVisualTransformation,
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
    if (callState.phase == CallUiPhase.ACTIVE) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(0.52f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (callState.isSimulated) "[SIMULATED] ${callState.phase.label}" else callState.phase.label,
                    color = DuoGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                val maskedRemote = TopScreenPhoneMasker.mask(callState.remoteNumber)
                Text(
                    text = callerDisplayName ?: maskedRemote.ifBlank { "未知号码" },
                    color = DuoTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (callerDisplayName != null && callState.remoteNumber.isNotBlank()) {
                    Text(
                        text = maskedRemote,
                        color = DuoTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "计时 ${formatDuration(callState.elapsedSec)}" +
                        if (callState.dtmfHistory.isNotEmpty()) " · ${callState.dtmfHistory}" else "",
                    color = DuoCyan,
                    fontSize = 11.sp,
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
                            .height(30.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(if (callState.isMuted) "已静音(X)" else "静音(X)", fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("路由:${audioState.localRoute.label}", fontSize = 10.sp, maxLines = 1)
                    }
                }

                // Route selector + Call volume control
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

                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onHangupOrReject,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DuoRed),
                ) {
                    Text("挂断 (B)", color = Color(0xFF04121F), fontWeight = FontWeight.Bold)
                }
            }

            CompactDtmfGrid(
                modifier = Modifier
                    .weight(0.48f)
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
            val maskedRemote = TopScreenPhoneMasker.mask(callState.remoteNumber)
            Text(
                text = callerDisplayName ?: maskedRemote.ifBlank { "未知号码" },
                color = DuoTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            if (callerDisplayName != null && callState.remoteNumber.isNotBlank()) {
                Text(
                    text = maskedRemote,
                    color = DuoCyan,
                    fontSize = 12.sp,
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
