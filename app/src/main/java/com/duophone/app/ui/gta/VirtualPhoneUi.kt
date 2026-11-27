package com.duophone.app.ui.gta

import android.content.Context
import android.os.BatteryManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.duophone.app.audio.AudioBridgeState
import com.duophone.app.data.ContactPhoneMatcher
import com.duophone.app.data.ContactRecord
import com.duophone.app.data.SmsDirection
import com.duophone.app.data.SmsRecord
import com.duophone.app.data.SmsThreadSummary
import com.duophone.app.domain.CallUiPhase
import com.duophone.app.domain.CallUiState
import com.duophone.app.domain.ModemUiState
import com.duophone.app.stage.AppScreenState
import com.duophone.app.stage.GtaStageState
import com.duophone.app.stage.InteractionEvent
import com.duophone.app.stage.SmsBannerNotice
import com.duophone.app.stage.TopScreenPhoneMasker
import com.duophone.app.stage.VirtualKeyId
import com.duophone.app.ui.DIALPAD_ROWS
import com.duophone.app.ui.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private data class VirtualPhonePalette(
    val bg: Color,
    val headerBg: Color,
    val divider: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val accentBlue: Color,
    val incomingBubble: Color,
    val outgoingBubble: Color,
    val dialKeyBg: Color,
    val dialKeyPressed: Color,
    val callGreen: Color,
    val callRed: Color,
    val cardBg: Color,
    val glassSheenAlpha: Float,
)

private val LightPhonePalette = VirtualPhonePalette(
    bg = Color(0xFFF2F4F8),
    headerBg = Color(0xFFE5E8F0),
    divider = Color(0xFFD0D5E0),
    primaryText = Color(0xFF171A21),
    secondaryText = Color(0xFF667080),
    accentBlue = Color(0xFF2E6CE6),
    incomingBubble = Color(0xFFDFE3EC),
    outgoingBubble = Color(0xFFBFD6F8),
    dialKeyBg = Color(0xFFE0E4EC),
    dialKeyPressed = Color(0xFFC2CAD8),
    callGreen = Color(0xFF2EB860),
    callRed = Color(0xFFE24444),
    cardBg = Color(0xFFE6E9F1),
    glassSheenAlpha = 0.06f,
)

private val DarkPhonePalette = VirtualPhonePalette(
    bg = Color(0xFF12151B),
    headerBg = Color(0xFF1A1E27),
    divider = Color(0xFF2B313F),
    primaryText = Color(0xFFEDF0F7),
    secondaryText = Color(0xFF939DAD),
    accentBlue = Color(0xFF6EA4FF),
    incomingBubble = Color(0xFF242934),
    outgoingBubble = Color(0xFF284A76),
    dialKeyBg = Color(0xFF242934),
    dialKeyPressed = Color(0xFF424C60),
    callGreen = Color(0xFF2EB860),
    callRed = Color(0xFFE04848),
    cardBg = Color(0xFF1C212B),
    glassSheenAlpha = 0.05f,
)

private val NoPadText = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))

private val T9_SUBTEXT = mapOf(
    "1" to "",
    "2" to "ABC",
    "3" to "DEF",
    "4" to "GHI",
    "5" to "JKL",
    "6" to "MNO",
    "7" to "PQRS",
    "8" to "TUV",
    "9" to "WXYZ",
    "*" to "",
    "0" to "+",
    "#" to "",
)

@Composable
fun VirtualPhoneOsContent(
    stageState: GtaStageState,
    events: SharedFlow<InteractionEvent>,
    modemState: ModemUiState,
    callState: CallUiState,
    audioState: AudioBridgeState,
    smsMessages: List<SmsRecord>,
    smsThreads: List<SmsThreadSummary>,
    contacts: List<ContactRecord>,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = if (isDarkTheme) DarkPhonePalette else LightPhonePalette
    var highlightedKey by remember { mutableStateOf<VirtualKeyId?>(null) }
    var keyFlashSeq by remember { mutableIntStateOf(0) }

    LaunchedEffect(events) {
        events.collect { ev ->
            if (ev is InteractionEvent.KeyPressed) {
                highlightedKey = ev.keyId
                keyFlashSeq++
            }
        }
    }

    LaunchedEffect(keyFlashSeq) {
        if (keyFlashSeq > 0) {
            delay(170L)
            highlightedKey = null
        }
    }

    val useHeaderTintOnChrome = stageState.screenState == AppScreenState.SmsChat ||
        stageState.screenState == AppScreenState.Opening ||
        stageState.screenState == AppScreenState.PhoneDialing ||
        stageState.screenState == AppScreenState.Settings

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.bg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            VirtualPhoneStatusBar(
                palette = palette,
                modemState = modemState,
                bgColor = if (stageState.screenState == AppScreenState.SmsChat) palette.headerBg else palette.bg,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AnimatedContent(
                    targetState = stageState.screenState,
                    transitionSpec = {
                        (fadeIn(tween(180)) + slideInHorizontally(tween(200)) { it / 8 })
                            .togetherWith(
                                fadeOut(tween(140)) + slideOutHorizontally(tween(160)) { -it / 8 },
                            )
                    },
                    label = "gta_phone_os",
                ) { screen ->
                    when (screen) {
                        AppScreenState.Opening,
                        AppScreenState.PhoneDialing,
                        -> VirtualDialerPage(
                            palette = palette,
                            dialBuffer = stageState.dialBuffer,
                            contacts = contacts,
                            highlightedKey = highlightedKey,
                        )

                        AppScreenState.CallDialing -> VirtualDialingCallPage(
                            palette = palette,
                            callState = callState,
                            contacts = contacts,
                            highlightedKey = highlightedKey,
                        )

                        AppScreenState.CallIncoming -> VirtualIncomingCallPage(
                            palette = palette,
                            callState = callState,
                            contacts = contacts,
                        )

                        AppScreenState.InCall -> VirtualInCallPage(
                            palette = palette,
                            callState = callState,
                            audioState = audioState,
                            contacts = contacts,
                        )

                        AppScreenState.SmsChat -> VirtualSmsPage(
                            palette = palette,
                            stageState = stageState,
                            smsMessages = smsMessages,
                            smsThreads = smsThreads,
                            contacts = contacts,
                            highlightedKey = highlightedKey,
                        )

                        AppScreenState.Settings -> VirtualSettingsPreviewPage(
                            palette = palette,
                            stageState = stageState,
                            modemState = modemState,
                            isDarkTheme = isDarkTheme,
                        )
                    }
                }
            }

            VirtualPhoneBottomChrome(
                palette = palette,
                screenState = stageState.screenState,
                homeStripBg = if (useHeaderTintOnChrome) palette.headerBg else palette.bg,
            )
        }

        // Top slide-in incoming SMS toast banner (positioned cleanly below the status bar)
        AnimatedVisibility(
            visible = stageState.incomingSmsBanner != null,
            enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(180)) { -it } + fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(start = 6.dp, end = 6.dp, top = 16.dp),
        ) {
            stageState.incomingSmsBanner?.let { banner ->
                VirtualSmsBanner(palette = palette, banner = banner)
            }
        }

        // Physical diegetic screen glass reflection & symmetric hairline bezel recess
        DiegeticScreenOverlay(glassAlpha = palette.glassSheenAlpha)
    }
}

/**
 * Authentic modern smartphone status bar: live clock on left, real modem signal bars + 4G badge + battery pill on right.
 */
@Composable
private fun VirtualPhoneStatusBar(
    palette: VirtualPhonePalette,
    modemState: ModemUiState,
    bgColor: Color,
) {
    val context = LocalContext.current
    var clockText by remember { mutableStateOf(formatCurrentTime()) }
    var batteryPct by remember { mutableIntStateOf(readBatteryPercent(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            clockText = formatCurrentTime()
            batteryPct = readBatteryPercent(context)
            delay(15_000L)
        }
    }

    val bars = modemState.signal.bars.coerceIn(0, 4)
    val showCellularBadge = modemState.registration.isRegistered || bars > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(15.dp)
            .background(bgColor)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = clockText,
            color = palette.primaryText,
            fontSize = 6.8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            style = NoPadText,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            SignalBarsGlyph(
                bars = bars,
                activeColor = palette.primaryText,
                inactiveColor = palette.secondaryText.copy(alpha = 0.28f),
            )
            if (showCellularBadge) {
                Text(
                    text = "4G",
                    color = palette.primaryText,
                    fontSize = 5.3.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    style = NoPadText,
                )
            }
            BatteryPillGlyph(
                percent = batteryPct,
                strokeColor = palette.primaryText.copy(alpha = 0.78f),
                fillColor = palette.primaryText,
            )
        }
    }
}

/**
 * Bottom navigation strip + modern iOS/Android home indicator capsule so the virtual UI occupies the full
 * screen height cleanly with a realistic thumb-level bottom dock.
 */
@Composable
private fun VirtualPhoneBottomChrome(
    palette: VirtualPhonePalette,
    screenState: AppScreenState,
    homeStripBg: Color,
) {
    val showTabBar = screenState == AppScreenState.Opening ||
        screenState == AppScreenState.PhoneDialing ||
        screenState == AppScreenState.Settings

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(homeStripBg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showTabBar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(palette.divider.copy(alpha = 0.75f)),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .background(palette.headerBg.copy(alpha = 0.94f))
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomNavItem(
                    label = "Keypad",
                    selected = screenState == AppScreenState.PhoneDialing || screenState == AppScreenState.Opening,
                    palette = palette,
                ) { color ->
                    KeypadTabGlyph(color = color, sizeDp = 6.5.dp)
                }
                BottomNavItem(
                    label = "Messages",
                    selected = false,
                    palette = palette,
                ) { color ->
                    MessagesTabGlyph(color = color, sizeDp = 6.5.dp)
                }
                BottomNavItem(
                    label = "Settings",
                    selected = screenState == AppScreenState.Settings,
                    palette = palette,
                ) { color ->
                    SettingsTabGlyph(color = color, sizeDp = 6.5.dp)
                }
            }
        }

        // Home indicator pill safe zone
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.5.dp)
                .background(if (showTabBar) palette.headerBg.copy(alpha = 0.94f) else homeStripBg),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(palette.primaryText.copy(alpha = 0.28f)),
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    selected: Boolean,
    palette: VirtualPhonePalette,
    icon: @Composable (Color) -> Unit,
) {
    val tint = if (selected) palette.accentBlue else palette.secondaryText.copy(alpha = 0.75f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon(tint)
        Text(
            text = label,
            color = tint,
            fontSize = 4.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            style = NoPadText,
        )
    }
}

private fun formatCurrentTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun readBatteryPercent(context: Context): Int {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 85
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return if (level in 1..100) level else 85
}

/**
 * Simulates optically flat tempered glass inside Jason's hand:
 * gentle top-left specular sheen with symmetric 4-edge hairline recess so left/right borders share uniform depth.
 */
@Composable
private fun DiegeticScreenOverlay(glassAlpha: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = glassAlpha * 0.55f),
                    Color.Transparent,
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width * 0.85f, size.height * 0.85f),
            ),
        )
        drawRect(
            color = Color.Black.copy(alpha = 0.08f),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

@Composable
private fun VirtualDialerPage(
    palette: VirtualPhonePalette,
    dialBuffer: String,
    contacts: List<ContactRecord>,
    highlightedKey: VirtualKeyId?,
) {
    val matchedName = remember(dialBuffer, contacts) {
        if (dialBuffer.isBlank()) null else ContactPhoneMatcher.resolveDisplayName(dialBuffer, contacts)
    }
    val maskedDial = remember(dialBuffer) { TopScreenPhoneMasker.mask(dialBuffer) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Generous upper number display zone (~30% of body) so typed digits sit in the natural focal zone
        // rather than jammed against the top bezel, keeping the T9 pad anchored in the lower thumb zone.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.30f)
                .padding(bottom = 10.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = maskedDial.ifEmpty { " " },
                    color = palette.primaryText,
                    fontSize = when {
                        maskedDial.length > 13 -> 12.5.sp
                        maskedDial.length > 10 -> 15.sp
                        else -> 18.sp
                    },
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = NoPadText,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = when {
                        matchedName != null -> matchedName
                        dialBuffer.isNotEmpty() -> "Add Number"
                        else -> " "
                    },
                    color = palette.accentBlue,
                    fontSize = 6.8.sp,
                    fontWeight = if (matchedName != null) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    style = NoPadText,
                )
            }
        }

        // Lower thumb-ergonomic 3x4 circular T9 keypad + green handset call button (~70% of body)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.70f)
                .padding(bottom = 4.dp),
        ) {
            val cellWidth = maxWidth / 3f
            val rowHeight = maxHeight / 5f
            val circleSize = min(cellWidth * 0.78f, rowHeight * 0.85f)

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DIALPAD_ROWS.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        row.forEach { digit ->
                            val keyId = VirtualKeyId.fromDialChar(digit)
                            val isPressed = keyId != null && keyId == highlightedKey
                            val keyScale by animateFloatAsState(
                                targetValue = if (isPressed) 0.88f else 1f,
                                animationSpec = tween(90),
                                label = "key_scale_$digit",
                            )
                            Box(
                                modifier = Modifier
                                    .size(circleSize)
                                    .graphicsLayer {
                                        scaleX = keyScale
                                        scaleY = keyScale
                                    }
                                    .clip(CircleShape)
                                    .background(if (isPressed) palette.dialKeyPressed else palette.dialKeyBg),
                                contentAlignment = Alignment.Center,
                            ) {
                                val sub = T9_SUBTEXT[digit].orEmpty()
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = digit,
                                        color = palette.primaryText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Normal,
                                        maxLines = 1,
                                        softWrap = false,
                                        style = NoPadText,
                                    )
                                    if (sub.isNotEmpty()) {
                                        Text(
                                            text = sub,
                                            color = palette.secondaryText,
                                            fontSize = 4.0.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 0.35.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            style = NoPadText,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom row: Clear / Green Call Button / Backspace
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(circleSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (dialBuffer.isNotEmpty()) {
                            Text(
                                text = "×",
                                color = palette.secondaryText,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Normal,
                                style = NoPadText,
                            )
                        }
                    }

                    val callPressed = highlightedKey == VirtualKeyId.CALL
                    val callScale by animateFloatAsState(
                        targetValue = if (callPressed) 0.88f else 1f,
                        animationSpec = tween(90),
                        label = "call_scale",
                    )
                    Box(
                        modifier = Modifier
                            .size(circleSize)
                            .graphicsLayer {
                                scaleX = callScale
                                scaleY = callScale
                            }
                            .clip(CircleShape)
                            .background(palette.callGreen),
                        contentAlignment = Alignment.Center,
                    ) {
                        HandsetGlyph(
                            color = Color.White,
                            hangup = false,
                            sizeDp = circleSize * 0.44f,
                        )
                    }

                    val delPressed = highlightedKey == VirtualKeyId.BACKSPACE
                    Box(
                        modifier = Modifier.size(circleSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (dialBuffer.isNotEmpty() || delPressed) {
                            BackspaceGlyph(
                                color = if (delPressed) palette.primaryText else palette.secondaryText,
                                sizeDp = circleSize * 0.45f,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VirtualDialingCallPage(
    palette: VirtualPhonePalette,
    callState: CallUiState,
    contacts: List<ContactRecord>,
    highlightedKey: VirtualKeyId?,
) {
    val displayName = remember(callState.remoteNumber, contacts) {
        ContactPhoneMatcher.resolveDisplayName(callState.remoteNumber, contacts)
    }
    val maskedRemote = remember(callState.remoteNumber) { TopScreenPhoneMasker.mask(callState.remoteNumber) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.12f))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(palette.accentBlue.copy(alpha = 0.16f))
                    .border(1.dp, palette.accentBlue.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (displayName?.firstOrNull() ?: callState.remoteNumber.lastOrNull() ?: '#').toString(),
                    color = palette.accentBlue,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    style = NoPadText,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = displayName ?: maskedRemote.ifBlank { "Unknown" },
                color = palette.primaryText,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = NoPadText,
            )
            if (displayName != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = maskedRemote,
                    color = palette.secondaryText,
                    fontSize = 7.2.sp,
                    maxLines = 1,
                    style = NoPadText,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (callState.phase == CallUiPhase.ALERTING) "ringing..." else "calling mobile...",
                color = palette.secondaryText,
                fontSize = 7.5.sp,
                style = NoPadText,
            )
        }

        Spacer(Modifier.weight(0.28f))

        Column(
            modifier = Modifier.padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallControlCircle(palette = palette, label = "mute", active = callState.isMuted)
                CallControlCircle(palette = palette, label = "keypad", active = false)
                CallControlCircle(palette = palette, label = "speaker", active = callState.isSpeakerOn)
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (highlightedKey == VirtualKeyId.HANGUP) palette.callRed.copy(alpha = 0.75f) else palette.callRed),
                contentAlignment = Alignment.Center,
            ) {
                HandsetGlyph(color = Color.White, hangup = true, sizeDp = 14.dp)
            }
        }
    }
}

@Composable
private fun VirtualIncomingCallPage(
    palette: VirtualPhonePalette,
    callState: CallUiState,
    contacts: List<ContactRecord>,
) {
    val ringAlpha by rememberInfiniteTransition(label = "inc_pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse),
        label = "inc_alpha",
    )
    val displayName = remember(callState.remoteNumber, contacts) {
        ContactPhoneMatcher.resolveDisplayName(callState.remoteNumber, contacts)
    }
    val maskedRemote = remember(callState.remoteNumber) { TopScreenPhoneMasker.mask(callState.remoteNumber) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.12f))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(palette.callGreen.copy(alpha = 0.14f * ringAlpha))
                    .border(1.5.dp, palette.callGreen.copy(alpha = ringAlpha), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (displayName?.firstOrNull() ?: '?').toString(),
                    color = palette.primaryText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    style = NoPadText,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = displayName ?: maskedRemote.ifBlank { "Incoming" },
                color = palette.primaryText,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = NoPadText,
            )
            if (callState.remoteNumber.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = maskedRemote,
                    color = palette.secondaryText,
                    fontSize = 7.2.sp,
                    maxLines = 1,
                    style = NoPadText,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Incoming Call",
                color = palette.callGreen,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.SemiBold,
                style = NoPadText,
            )
        }

        Spacer(Modifier.weight(0.32f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(palette.callRed),
                    contentAlignment = Alignment.Center,
                ) {
                    HandsetGlyph(color = Color.White, hangup = true, sizeDp = 14.dp)
                }
                Spacer(Modifier.height(3.dp))
                Text("Decline", color = palette.secondaryText, fontSize = 6.3.sp, style = NoPadText)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(palette.callGreen),
                    contentAlignment = Alignment.Center,
                ) {
                    HandsetGlyph(color = Color.White, hangup = false, sizeDp = 14.dp)
                }
                Spacer(Modifier.height(3.dp))
                Text("Accept", color = palette.secondaryText, fontSize = 6.3.sp, style = NoPadText)
            }
        }
    }
}

@Composable
private fun VirtualInCallPage(
    palette: VirtualPhonePalette,
    callState: CallUiState,
    audioState: AudioBridgeState,
    contacts: List<ContactRecord>,
) {
    val wavePhase by rememberInfiniteTransition(label = "call_wave").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "wave",
    )
    val displayName = remember(callState.remoteNumber, contacts) {
        ContactPhoneMatcher.resolveDisplayName(callState.remoteNumber, contacts)
    }
    val maskedRemote = remember(callState.remoteNumber) { TopScreenPhoneMasker.mask(callState.remoteNumber) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.10f))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatDuration(callState.elapsedSec),
                color = palette.callGreen,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = NoPadText,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = displayName ?: maskedRemote.ifBlank { "Active Call" },
                color = palette.primaryText,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = NoPadText,
            )
            if (callState.dtmfHistory.isNotBlank()) {
                Text(
                    text = callState.dtmfHistory,
                    color = palette.secondaryText,
                    fontSize = 7.2.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    style = NoPadText,
                )
            }
        }

        Spacer(Modifier.weight(0.14f))

        Row(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .height(24.dp),
            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val boost = if (audioState.isRunning) {
                ((audioState.downlinkRmsDbfs.toFloat() + 60f) / 60f).coerceIn(0.25f, 1.0f)
            } else {
                0.65f
            }
            for (i in 0 until 10) {
                val h = (0.30f + 0.70f * abs(((i * 0.23f + wavePhase) % 1f) - 0.5f) * 2f) * boost
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(h.coerceIn(0.18f, 1.0f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.accentBlue.copy(alpha = 0.8f)),
                )
            }
        }

        Spacer(Modifier.weight(0.20f))

        Column(
            modifier = Modifier.padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallControlCircle(palette = palette, label = "mute", active = callState.isMuted)
                CallControlCircle(palette = palette, label = "audio", active = false)
                CallControlCircle(palette = palette, label = "speaker", active = callState.isSpeakerOn)
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(palette.callRed),
                contentAlignment = Alignment.Center,
            ) {
                HandsetGlyph(color = Color.White, hangup = true, sizeDp = 14.dp)
            }
        }
    }
}

@Composable
private fun CallControlCircle(palette: VirtualPhonePalette, label: String, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(25.dp)
                .clip(CircleShape)
                .background(if (active) palette.primaryText else palette.dialKeyBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.take(1).uppercase(),
                color = if (active) palette.bg else palette.primaryText,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                style = NoPadText,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = palette.secondaryText,
            fontSize = 5.3.sp,
            maxLines = 1,
            style = NoPadText,
        )
    }
}

@Composable
private fun VirtualSmsPage(
    palette: VirtualPhonePalette,
    stageState: GtaStageState,
    smsMessages: List<SmsRecord>,
    smsThreads: List<SmsThreadSummary>,
    contacts: List<ContactRecord>,
    highlightedKey: VirtualKeyId?,
) {
    val activeAddress = if (stageState.isComposingNewSms) {
        stageState.smsDraftNumber.ifBlank { "New Message" }
    } else {
        stageState.selectedSmsAddress ?: smsThreads.firstOrNull()?.address
    }

    val threadMessages = remember(smsMessages, activeAddress, stageState.isComposingNewSms) {
        if (stageState.isComposingNewSms || activeAddress == null) {
            emptyList()
        } else {
            smsMessages.filter { it.address == activeAddress }.sortedBy { it.timestampMs }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(threadMessages.size) {
        if (threadMessages.isNotEmpty()) {
            listState.animateScrollToItem(threadMessages.lastIndex)
        }
    }

    val displayName = remember(activeAddress, contacts) {
        if (activeAddress == null || stageState.isComposingNewSms) null
        else ContactPhoneMatcher.resolveDisplayName(activeAddress, contacts)
    }
    val headerTitle = displayName ?: activeAddress?.let { TopScreenPhoneMasker.mask(it) } ?: "Messages"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg),
    ) {
        // Real-smartphone 3-part header (< | Bold Contact | Circular Avatar)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(palette.headerBg)
                .padding(horizontal = 7.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(8.5.dp),
            ) {
                val path = Path().apply {
                    moveTo(size.width * 0.70f, size.height * 0.16f)
                    lineTo(size.width * 0.25f, size.height * 0.50f)
                    lineTo(size.width * 0.70f, size.height * 0.84f)
                }
                drawPath(
                    path = path,
                    color = palette.accentBlue,
                    style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            Text(
                text = headerTitle,
                color = palette.primaryText,
                fontSize = 8.8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = NoPadText,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 20.dp),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFFD16D5E), Color(0xFF7B4B6B))),
                    )
                    .border(0.6.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (headerTitle.firstOrNull() ?: 'M').uppercaseChar().toString(),
                    color = Color.White,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    style = NoPadText,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(palette.divider),
        )

        // Message stream filling center area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            if (threadMessages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (stageState.isComposingNewSms) {
                            "To: ${TopScreenPhoneMasker.mask(stageState.smsDraftNumber).ifBlank { "..." }}"
                        } else {
                            "No messages yet"
                        },
                        color = palette.secondaryText,
                        fontSize = 7.6.sp,
                        textAlign = TextAlign.Center,
                        style = NoPadText,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item(key = "sms_day_header") {
                        Text(
                            text = "Today",
                            color = palette.secondaryText.copy(alpha = 0.78f),
                            fontSize = 5.4.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            style = NoPadText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 1.dp, bottom = 2.dp),
                        )
                    }
                    items(threadMessages.takeLast(14), key = { it.id }) { msg ->
                        val isOut = msg.direction == SmsDirection.OUTGOING
                        val bubbleShape = RoundedCornerShape(
                            topStart = 8.dp,
                            topEnd = 8.dp,
                            bottomStart = if (isOut) 8.dp else 2.5.dp,
                            bottomEnd = if (isOut) 2.5.dp else 8.dp,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.84f)
                                    .clip(bubbleShape)
                                    .background(if (isOut) palette.outgoingBubble else palette.incomingBubble)
                                    .padding(horizontal = 6.dp, vertical = 4.5.dp),
                            ) {
                                Text(
                                    text = msg.body,
                                    color = palette.primaryText,
                                    fontSize = 7.6.sp,
                                    lineHeight = 9.5.sp,
                                    style = NoPadText,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Persistent realistic smartphone SMS composer bar above home indicator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(palette.divider.copy(alpha = 0.7f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.headerBg)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(palette.bg)
                    .border(0.6.dp, palette.divider, RoundedCornerShape(9.dp))
                    .padding(horizontal = 6.dp, vertical = 3.5.dp),
            ) {
                val hasDraft = stageState.smsDraftBody.isNotEmpty()
                Text(
                    text = if (hasDraft) "${stageState.smsDraftBody}|" else "Text Message",
                    color = if (hasDraft) palette.primaryText else palette.secondaryText.copy(alpha = 0.72f),
                    fontSize = 7.0.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = NoPadText,
                )
            }
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            highlightedKey == VirtualKeyId.SMS_SEND -> palette.callGreen
                            stageState.smsDraftBody.isNotEmpty() || stageState.isComposingNewSms -> palette.accentBlue
                            else -> palette.secondaryText.copy(alpha = 0.35f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                SendPlaneGlyph(color = Color.White, sizeDp = 7.dp)
            }
        }
    }
}

@Composable
private fun VirtualSettingsPreviewPage(
    palette: VirtualPhonePalette,
    stageState: GtaStageState,
    modemState: ModemUiState,
    isDarkTheme: Boolean,
) {
    val cfg = stageState.stageConfig.phoneOverlay
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = "Settings",
            color = palette.primaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            style = NoPadText,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.cardBg),
        ) {
            SettingsRowItem(palette, palette.callGreen, "Network", modemState.connectionState.label)
            SettingsDivider(palette)
            SettingsRowItem(palette, Color(0xFF3E8EE0), "Signal", "${modemState.signal.bars.coerceIn(0, 4)}/4 bars")
            SettingsDivider(palette)
            SettingsRowItem(palette, palette.accentBlue, "Theme", if (isDarkTheme) "Night" else "Daylight")
            SettingsDivider(palette)
            SettingsRowItem(palette, Color(0xFFE0912E), "Angle", "${"%.1f".format(cfg.rotationZ)}°")
            SettingsDivider(palette)
            SettingsRowItem(palette, palette.callRed, "Sound", if (stageState.soundEnabled) "On" else "Off")
        }
    }
}

@Composable
private fun SettingsDivider(palette: VirtualPhonePalette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp)
            .height(0.5.dp)
            .background(palette.divider.copy(alpha = 0.7f)),
    )
}

@Composable
private fun SettingsRowItem(
    palette: VirtualPhonePalette,
    badgeColor: Color,
    title: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(badgeColor),
        )
        Text(
            text = title,
            color = palette.primaryText,
            fontSize = 7.4.sp,
            maxLines = 1,
            style = NoPadText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = palette.secondaryText,
            fontSize = 6.8.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = NoPadText,
        )
    }
}

@Composable
private fun VirtualSmsBanner(palette: VirtualPhonePalette, banner: SmsBannerNotice) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.headerBg.copy(alpha = 0.96f))
            .border(0.7.dp, palette.accentBlue.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(palette.accentBlue),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (banner.displayName ?: banner.address).firstOrNull()?.uppercaseChar()?.toString() ?: "M",
                color = Color.White,
                fontSize = 6.5.sp,
                fontWeight = FontWeight.Bold,
                style = NoPadText,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = banner.displayName ?: TopScreenPhoneMasker.mask(banner.address),
                color = palette.primaryText,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = NoPadText,
            )
            Text(
                text = banner.otpCode?.let { "OTP $it · ${banner.preview}" } ?: banner.preview,
                color = palette.secondaryText,
                fontSize = 6.8.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = NoPadText,
            )
        }
    }
}

@Composable
private fun SignalBarsGlyph(
    bars: Int,
    activeColor: Color,
    inactiveColor: Color,
) {
    Canvas(modifier = Modifier.size(width = 9.5.dp, height = 6.dp)) {
        val barCount = 4
        val gap = size.width * 0.12f
        val barW = (size.width - gap * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val hFrac = (i + 1) / barCount.toFloat()
            val barH = size.height * (0.32f + 0.68f * hFrac)
            val x = i * (barW + gap)
            val y = size.height - barH
            drawRoundRect(
                color = if (i < bars) activeColor else inactiveColor,
                topLeft = Offset(x, y),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW * 0.4f, barW * 0.4f),
            )
        }
    }
}

@Composable
private fun BatteryPillGlyph(
    percent: Int,
    strokeColor: Color,
    fillColor: Color,
) {
    Canvas(modifier = Modifier.size(width = 12.dp, height = 6.dp)) {
        val strokeW = 0.75.dp.toPx()
        val nubW = 1.2.dp.toPx()
        val bodyW = size.width - nubW - strokeW * 0.5f
        val bodyH = size.height
        val radius = CornerRadius(1.8.dp.toPx(), 1.8.dp.toPx())
        drawRoundRect(
            color = strokeColor,
            topLeft = Offset.Zero,
            size = Size(bodyW, bodyH),
            cornerRadius = radius,
            style = Stroke(width = strokeW),
        )
        val pad = strokeW * 1.35f
        val innerMaxW = (bodyW - pad * 2f).coerceAtLeast(1f)
        val fillFrac = (percent / 100f).coerceIn(0.10f, 1f)
        drawRoundRect(
            color = fillColor,
            topLeft = Offset(pad, pad),
            size = Size(innerMaxW * fillFrac, (bodyH - pad * 2f).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(0.9.dp.toPx(), 0.9.dp.toPx()),
        )
        drawRoundRect(
            color = strokeColor,
            topLeft = Offset(bodyW + strokeW * 0.5f, bodyH * 0.28f),
            size = Size(nubW, bodyH * 0.44f),
            cornerRadius = CornerRadius(0.6.dp.toPx(), 0.6.dp.toPx()),
        )
    }
}

@Composable
private fun KeypadTabGlyph(color: Color, sizeDp: Dp) {
    Canvas(modifier = Modifier.size(sizeDp)) {
        val r = size.minDimension * 0.115f
        for (row in 0..2) {
            for (col in 0..2) {
                val cx = size.width * (0.20f + col * 0.30f)
                val cy = size.height * (0.20f + row * 0.30f)
                drawCircle(color = color, radius = r, center = Offset(cx, cy))
            }
        }
    }
}

@Composable
private fun MessagesTabGlyph(color: Color, sizeDp: Dp) {
    Canvas(modifier = Modifier.size(sizeDp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.14f),
            size = Size(w * 0.84f, h * 0.62f),
            cornerRadius = CornerRadius(w * 0.20f, w * 0.20f),
        )
        val tail = Path().apply {
            moveTo(w * 0.26f, h * 0.72f)
            lineTo(w * 0.20f, h * 0.92f)
            lineTo(w * 0.44f, h * 0.72f)
            close()
        }
        drawPath(path = tail, color = color)
    }
}

@Composable
private fun SettingsTabGlyph(color: Color, sizeDp: Dp) {
    Canvas(modifier = Modifier.size(sizeDp)) {
        val w = size.width
        val h = size.height
        val strokeW = size.minDimension * 0.14f
        for (i in 0..2) {
            val y = h * (0.24f + i * 0.26f)
            drawLine(
                color = color,
                start = Offset(w * 0.12f, y),
                end = Offset(w * 0.88f, y),
                strokeWidth = strokeW,
                cap = StrokeCap.Round,
            )
            val knobX = when (i) {
                0 -> w * 0.34f
                1 -> w * 0.68f
                else -> w * 0.44f
            }
            drawCircle(color = color, radius = strokeW * 1.05f, center = Offset(knobX, y))
        }
    }
}

@Composable
private fun HandsetGlyph(
    color: Color,
    hangup: Boolean,
    sizeDp: Dp,
) {
    Canvas(modifier = Modifier.size(sizeDp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (!hangup) {
                moveTo(w * 0.22f, h * 0.14f)
                quadraticTo(w * 0.10f, h * 0.26f, w * 0.24f, h * 0.56f)
                quadraticTo(w * 0.44f, h * 0.90f, w * 0.74f, h * 0.90f)
                quadraticTo(w * 0.88f, h * 0.78f, w * 0.82f, h * 0.66f)
                lineTo(w * 0.66f, h * 0.60f)
                lineTo(w * 0.58f, h * 0.68f)
                quadraticTo(w * 0.46f, h * 0.60f, w * 0.34f, h * 0.42f)
                lineTo(w * 0.42f, h * 0.34f)
                lineTo(w * 0.34f, h * 0.16f)
                close()
            } else {
                moveTo(w * 0.08f, h * 0.56f)
                quadraticTo(w * 0.50f, h * 0.20f, w * 0.92f, h * 0.56f)
                lineTo(w * 0.84f, h * 0.72f)
                lineTo(w * 0.68f, h * 0.64f)
                lineTo(w * 0.66f, h * 0.52f)
                quadraticTo(w * 0.50f, h * 0.44f, w * 0.34f, h * 0.52f)
                lineTo(w * 0.32f, h * 0.64f)
                lineTo(w * 0.16f, h * 0.72f)
                close()
            }
        }
        drawPath(path = path, color = color)
    }
}

@Composable
private fun BackspaceGlyph(color: Color, sizeDp: Dp) {
    Canvas(modifier = Modifier.size(sizeDp)) {
        val w = size.width
        val h = size.height
        val outline = Path().apply {
            moveTo(w * 0.34f, h * 0.18f)
            lineTo(w * 0.92f, h * 0.18f)
            lineTo(w * 0.92f, h * 0.82f)
            lineTo(w * 0.34f, h * 0.82f)
            lineTo(w * 0.08f, h * 0.50f)
            close()
        }
        drawPath(outline, color = color, style = Stroke(width = 2.0f, join = StrokeJoin.Round))
        drawLine(
            color = color,
            start = Offset(w * 0.48f, h * 0.36f),
            end = Offset(w * 0.74f, h * 0.64f),
            strokeWidth = 2.0f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.74f, h * 0.36f),
            end = Offset(w * 0.48f, h * 0.64f),
            strokeWidth = 2.0f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun SendPlaneGlyph(color: Color, sizeDp: Dp) {
    Canvas(modifier = Modifier.size(sizeDp)) {
        val w = size.width
        val h = size.height
        val plane = Path().apply {
            moveTo(w * 0.12f, h * 0.16f)
            lineTo(w * 0.92f, h * 0.50f)
            lineTo(w * 0.12f, h * 0.84f)
            lineTo(w * 0.28f, h * 0.54f)
            lineTo(w * 0.64f, h * 0.50f)
            lineTo(w * 0.28f, h * 0.46f)
            close()
        }
        drawPath(plane, color = color)
    }
}
