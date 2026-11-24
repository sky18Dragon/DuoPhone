package com.duophone.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.width
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SmsTabScreen(
    messages: List<SmsRecord>,
    threads: List<SmsThreadSummary>,
    modemReady: Boolean,
    isSyncing: Boolean,
    syncStatus: String,
    selectedAddress: String?,
    onSelectAddress: (String?) -> Unit,
    onSyncSms: () -> Unit,
    onSendSms: (String, String) -> Unit,
    onSimulateIncomingSms: () -> Unit,
    onCopyOtp: (String) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    contacts: List<ContactRecord> = emptyList(),
) {
    val context = LocalContext.current
    var composeNumber by rememberSaveable { mutableStateOf("") }
    var composeBody by rememberSaveable { mutableStateOf("") }
    var showNewComposer by rememberSaveable { mutableStateOf(false) }

    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Left pane: SMS threads list + actions
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = {
                        showNewComposer = true
                        onSelectAddress(null)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = DuoAccent),
                ) {
                    Text("写短信", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onSyncSms,
                    enabled = modemReady && !isSyncing,
                ) {
                    Text(if (isSyncing) "同步中" else "拉取", fontSize = 12.sp)
                }
            }

            if (!modemReady) {
                OutlinedButton(
                    onClick = onSimulateIncomingSms,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("模拟收件 (App 本地验证)", fontSize = 11.sp, color = DuoAmber)
                }
            }

            Text(
                text = "PDU 模式 · $syncStatus",
                color = DuoTextSecondary,
                fontSize = 11.sp,
            )

            if (threads.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DuoSurface, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无短信记录。\n接入模块后自动同步，或点击上方「模拟收件」验证界面与通知。",
                        color = DuoTextSecondary,
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(threads, key = { it.address }) { thread ->
                        val isSelected = selectedAddress == thread.address && !showNewComposer
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) DuoAccent else DuoSurfaceAlt,
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable {
                                    showNewComposer = false
                                    onSelectAddress(thread.address)
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) DuoSurfaceAlt else DuoSurface,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val threadDisplayName = ContactPhoneMatcher.resolveDisplayName(thread.address, contacts)
                                    Text(
                                        text = threadDisplayName?.let { "$it · ${thread.address}" } ?: thread.address,
                                        color = DuoTextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = timeFmt.format(Date(thread.latestTimestampMs)),
                                        color = DuoTextSecondary,
                                        fontSize = 10.sp,
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = thread.latestBody,
                                    color = DuoTextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (thread.unreadCount > 0 || !thread.latestOtp.isNullOrBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (thread.unreadCount > 0) {
                                            Text(
                                                text = "未读 ${thread.unreadCount}",
                                                color = DuoAmber,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        if (!thread.latestOtp.isNullOrBlank()) {
                                            Text(
                                                text = "验证码 ${thread.latestOtp}",
                                                color = DuoGreen,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
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

        // Right pane: Thread detail OR new SMS composer
        Column(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxSize()
                .background(DuoSurface, RoundedCornerShape(12.dp))
                .border(1.dp, DuoSurfaceAlt, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val activeAddr = if (showNewComposer) null else (selectedAddress ?: threads.firstOrNull()?.address)
            if (activeAddr == null) {
                Text("新建短信 (3GPP PDU · 中英文/长短信)", color = DuoAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                OutlinedTextField(
                    value = composeNumber,
                    onValueChange = { composeNumber = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("收件号码（如 +8613800138000）") },
                )
                OutlinedTextField(
                    value = composeBody,
                    onValueChange = { composeBody = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    label = { Text("短信正文（自动识别 GSM 7-bit / UCS2 与长短信分片）") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (modemReady) "已就绪，可发送" else "未接模块（仅编辑预览）",
                        color = if (modemReady) DuoGreen else DuoAmber,
                        fontSize = 12.sp,
                    )
                    Button(
                        onClick = {
                            onSendSms(composeNumber, composeBody)
                            composeBody = ""
                        },
                        enabled = modemReady && composeNumber.isNotBlank() && composeBody.isNotBlank(),
                    ) {
                        Text("发送短信")
                    }
                }
            } else {
                val threadMessages = remember(messages, activeAddr) {
                    messages.filter { it.address == activeAddr }.sortedBy { it.timestampMs }
                }
                val activeDisplayName = ContactPhoneMatcher.resolveDisplayName(activeAddr, contacts)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (activeDisplayName != null) {
                            "会话：$activeDisplayName ($activeAddr · ${threadMessages.size} 条)"
                        } else {
                            "会话：$activeAddr (${threadMessages.size} 条)"
                        },
                        color = DuoAccent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(threadMessages, key = { it.id }) { msg ->
                        val isOut = msg.direction == SmsDirection.OUTGOING
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (isOut) Color(0xFF163554) else DuoSurfaceAlt,
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                    .padding(10.dp),
                            ) {
                                Column {
                                    Text(text = msg.body, color = DuoTextPrimary, fontSize = 13.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = "${timeFmt.format(Date(msg.timestampMs))} · ${msg.status.label}",
                                            color = DuoTextSecondary,
                                            fontSize = 10.sp,
                                        )
                                        if (!msg.otpCode.isNullOrBlank()) {
                                            Text(
                                                text = "复制验证码 ${msg.otpCode}",
                                                color = DuoGreen,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable {
                                                    copyToClipboard(context, msg.otpCode)
                                                    onCopyOtp(msg.otpCode)
                                                },
                                            )
                                        }
                                        Text(
                                            text = "删除",
                                            color = DuoRed,
                                            fontSize = 10.sp,
                                            modifier = Modifier.clickable { onDeleteMessage(msg.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Quick reply bar
                var replyText by rememberSaveable(activeAddr) { mutableStateOf("") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("快捷回复 $activeAddr") },
                    )
                    Spacer(Modifier.width(2.dp))
                    Button(
                        onClick = {
                            onSendSms(activeAddr, replyText)
                            replyText = ""
                        },
                        enabled = modemReady && replyText.isNotBlank(),
                    ) {
                        Text("发送")
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("OTP", text))
}
