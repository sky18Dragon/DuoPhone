package com.duophone.app.ui.gta

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duophone.app.data.ContactPhoneMatcher
import com.duophone.app.data.ContactRecord
import com.duophone.app.data.SmsDirection
import com.duophone.app.data.SmsRecord
import com.duophone.app.data.SmsThreadSummary
import com.duophone.app.stage.GtaStageState
import com.duophone.app.stage.TopScreenPhoneMasker
import com.duophone.app.ui.PhoneMaskVisualTransformation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUICK_SMS_TEMPLATES = listOf(
    "正在路上，马上到。",
    "方便接电话吗？",
    "收到，稍后联系你。",
)

@Composable
fun DeckSmsPanel(
    stageState: GtaStageState,
    messages: List<SmsRecord>,
    threads: List<SmsThreadSummary>,
    contacts: List<ContactRecord>,
    modemReady: Boolean,
    isSyncing: Boolean,
    syncStatus: String,
    onSelectThread: (String?) -> Unit,
    onStartNewSms: () -> Unit,
    onUpdateDraftNumber: (String) -> Unit,
    onUpdateDraftBody: (String) -> Unit,
    onSyncSms: () -> Unit,
    onSendSms: (String, String) -> Unit,
    onCopyOtp: (String) -> Unit,
    onDeleteMessage: (Long) -> Unit,
) {
    val palette = LocalGtaDeckPalette.current
    val context = LocalContext.current
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Left: Thread list
        Column(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.surface)
                .border(1.dp, palette.border, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(palette.accentPrimary)
                        .clickable(onClick = onStartNewSms),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+ 写短信",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = palette.accentOnPrimary,
                    )
                }
                val canSync = modemReady && !isSyncing
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(palette.surfaceElevated)
                        .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
                        .clickable(enabled = canSync, onClick = onSyncSms)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isSyncing) "同步中" else "拉取",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (canSync) palette.textPrimary else palette.textSecondary.copy(alpha = 0.5f),
                    )
                }
            }

            Text(
                text = syncStatus,
                color = palette.textSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (threads.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无短信会话", color = palette.textSecondary, fontSize = 11.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(threads, key = { it.address }) { thread ->
                        val activeAddr = if (stageState.isComposingNewSms) {
                            null
                        } else {
                            stageState.selectedSmsAddress ?: threads.firstOrNull()?.address
                        }
                        val selected = activeAddr == thread.address
                        val displayName = ContactPhoneMatcher.resolveDisplayName(thread.address, contacts)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) palette.surfaceHighlight else palette.surfaceElevated)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) palette.accentBlue else palette.border,
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .clickable { onSelectThread(thread.address) },
                        ) {
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .fillMaxHeight()
                                        .background(palette.accentBlue),
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = displayName ?: TopScreenPhoneMasker.mask(thread.address),
                                        color = palette.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = timeFmt.format(Date(thread.latestTimestampMs)),
                                        color = palette.textSecondary,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Text(
                                    text = thread.latestBody,
                                    color = palette.textSecondary,
                                    fontSize = 10.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (thread.unreadCount > 0 || !thread.latestOtp.isNullOrBlank()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (thread.unreadCount > 0) {
                                            Text("未读 ${thread.unreadCount}", color = palette.accentAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                        if (!thread.latestOtp.isNullOrBlank()) {
                                            Text("验证码 ${thread.latestOtp}", color = palette.accentTeal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Right: Live synced composer / conversation
        Column(
            modifier = Modifier
                .weight(0.60f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.surface)
                .border(1.dp, palette.border, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val activeAddr = if (stageState.isComposingNewSms) {
                null
            } else {
                stageState.selectedSmsAddress ?: threads.firstOrNull()?.address
            }

            if (activeAddr == null) {
                Text(
                    text = "MESSAGES · 新建短信",
                    color = palette.textSecondary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                )
                OutlinedTextField(
                    value = stageState.smsDraftNumber,
                    onValueChange = onUpdateDraftNumber,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PhoneMaskVisualTransformation,
                    label = { Text("收件号码") },
                )
                if (contacts.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        contacts.take(8).forEach { contact ->
                            TemplatePill(
                                label = contact.name,
                                onClick = { onUpdateDraftNumber(contact.phone) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = stageState.smsDraftBody,
                    onValueChange = onUpdateDraftBody,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    label = { Text("短信内容（实时同步上屏）") },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QUICK_SMS_TEMPLATES.forEach { tpl ->
                        TemplatePill(label = tpl, onClick = { onUpdateDraftBody(tpl) })
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (modemReady) "4G 短信就绪" else "4G 模块未连接",
                        color = if (modemReady) palette.accentGreen else palette.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Button(
                        onClick = { onSendSms(stageState.smsDraftNumber, stageState.smsDraftBody) },
                        enabled = modemReady && stageState.smsDraftNumber.isNotBlank() && stageState.smsDraftBody.isNotBlank(),
                        shape = RoundedCornerShape(7.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accentGreen),
                    ) {
                        Text("发送", color = Color.White, fontWeight = FontWeight.ExtraBold)
                    }
                }
            } else {
                val threadMessages = remember(messages, activeAddr) {
                    messages.filter { it.address == activeAddr }.sortedBy { it.timestampMs }
                }
                val messageListState = rememberLazyListState()
                LaunchedEffect(activeAddr, threadMessages.size) {
                    if (threadMessages.isNotEmpty()) {
                        messageListState.scrollToItem(threadMessages.lastIndex)
                    }
                }
                val activeName = ContactPhoneMatcher.resolveDisplayName(activeAddr, contacts)
                val maskedActiveAddr = TopScreenPhoneMasker.mask(activeAddr)
                Text(
                    text = activeName?.let { "$it · $maskedActiveAddr" } ?: maskedActiveAddr,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                )

                LazyColumn(
                    state = messageListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(threadMessages, key = { it.id }) { msg ->
                        val isOut = msg.direction == SmsDirection.OUTGOING
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isOut) palette.bubbleOutgoing else palette.surfaceElevated)
                                    .border(
                                        width = 1.dp,
                                        color = if (isOut) palette.accentBlue.copy(alpha = 0.45f) else palette.border,
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Column {
                                    Text(
                                        text = msg.body,
                                        color = if (isOut) palette.bubbleOutgoingText else palette.textPrimary,
                                        fontSize = 12.sp,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "${timeFmt.format(Date(msg.timestampMs))} · ${msg.status.label}",
                                            color = if (isOut) {
                                                palette.bubbleOutgoingText.copy(alpha = 0.72f)
                                            } else {
                                                palette.textSecondary
                                            },
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        if (!msg.otpCode.isNullOrBlank()) {
                                            Text(
                                                text = "复制 ${msg.otpCode}",
                                                color = palette.accentTeal,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable {
                                                    copyOtpToClipboard(context, msg.otpCode)
                                                    onCopyOtp(msg.otpCode)
                                                },
                                            )
                                        }
                                        Text(
                                            text = "删",
                                            color = palette.accentRed,
                                            fontSize = 10.sp,
                                            modifier = Modifier.clickable { onDeleteMessage(msg.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QUICK_SMS_TEMPLATES.forEach { tpl ->
                        TemplatePill(label = tpl, onClick = { onUpdateDraftBody(tpl) })
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = stageState.smsDraftBody,
                        onValueChange = onUpdateDraftBody,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("快捷回复（实时同步上屏）", fontSize = 11.sp) },
                    )
                    Button(
                        onClick = { onSendSms(activeAddr, stageState.smsDraftBody) },
                        enabled = modemReady && stageState.smsDraftBody.isNotBlank(),
                        shape = RoundedCornerShape(7.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accentGreen),
                    ) {
                        Text("发送", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplatePill(
    label: String,
    onClick: () -> Unit,
) {
    val palette = LocalGtaDeckPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.surfaceElevated)
            .border(1.dp, palette.border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = palette.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun copyOtpToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("OTP", text))
}
