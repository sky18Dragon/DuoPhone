package com.duophone.app.ui.gta

import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.duophone.app.audio.AudioBridgeState
import com.duophone.app.data.ContactRecord
import com.duophone.app.data.SmsRecord
import com.duophone.app.data.SmsThreadSummary
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.domain.CallUiState
import com.duophone.app.domain.ModemUiState
import com.duophone.app.stage.AppScreenState
import com.duophone.app.stage.AssetSource
import com.duophone.app.stage.GtaStageState
import com.duophone.app.stage.InteractionEvent
import com.duophone.app.stage.OpeningPhase
import com.duophone.app.stage.StageThemeMode
import com.duophone.app.stage.VirtualPhoneKeyMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Top immersive visual stage (Display#0, 1920x1080) implementing the 3-layer stack:
 *   Layer 1: Opening MP4 / Idle loop video / Jason holding-phone artwork (or stylized Vice City fallback)
 *   Layer 2: 3D-perspective Virtual Phone OS container aligned via PhoneOverlayConfig
 *   Layer 3: Interactive thumb overlay responding in real time to bottom-deck inputs
 */
@Composable
fun TopStageScreen(
    stageState: GtaStageState,
    events: SharedFlow<InteractionEvent>,
    modemState: ModemUiState,
    callState: CallUiState,
    audioState: AudioBridgeState,
    smsMessages: List<SmsRecord>,
    smsThreads: List<SmsThreadSummary>,
    contacts: List<ContactRecord>,
    onOpeningFinished: () -> Unit,
    onSkipOpening: () -> Unit,
    hasSecondaryDisplay: Boolean,
    singleScreenPreviewDeck: Boolean,
    onToggleSingleScreenDeck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overlayCfg = stageState.stageConfig.phoneOverlay
    val isIdleReady = stageState.openingPhase == OpeningPhase.IDLE_READY
    val systemDark = isSystemInDarkTheme()
    val isDark = when (stageState.themeMode) {
        StageThemeMode.SYSTEM -> systemDark
        StageThemeMode.LIGHT -> false
        StageThemeMode.DARK -> true
    }

    // Shared handheld breathing motion so Layer 1 backdrop, Layer 2 virtual phone,
    // and Layer 3 thumb overlay move together as one physical unit.
    val breathClock by rememberInfiniteTransition(label = "hand_breathing").animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "breath_angle",
    )

    val amp = if (isIdleReady && overlayCfg.breathingEnabled) overlayCfg.breathingAmplitudeDp else 0f
    val breathOffsetXDp = (sin(breathClock.toDouble()) * amp * 0.50).toFloat()
    val breathOffsetYDp = (cos(breathClock.toDouble() * 2.0) * amp * 0.85).toFloat()
    val breathRotZ = (sin(breathClock.toDouble()) * 0.35 * (amp / 5f)).toFloat()

    // Handheld tactile press recoil synchronized across Jason's hand and the virtual phone
    val recoilRotX = remember { Animatable(0f) }
    val recoilRotY = remember { Animatable(0f) }
    val recoilDropDp = remember { Animatable(0f) }
    val recoilScale = remember { Animatable(1f) }

    LaunchedEffect(events, isIdleReady) {
        events.collect { ev ->
            if (!isIdleReady) return@collect
            val anchor = when (ev) {
                is InteractionEvent.KeyPressed -> VirtualPhoneKeyMap.anchorFor(ev.keyId)
                is InteractionEvent.TextChanged -> VirtualPhoneKeyMap.anchorFor(
                    com.duophone.app.stage.VirtualKeyId.SMS_KEY_CENTER,
                )
                else -> null
            } ?: return@collect

            val dx = (anchor.x - 0.5f) * 2f // -1..+1
            val dy = (anchor.y - 0.5f) * 2f // -1..+1
            launch {
                recoilRotY.snapTo(dx * 1.8f)
                recoilRotY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.62f))
            }
            launch {
                recoilRotX.snapTo(-dy * 1.3f)
                recoilRotX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.62f))
            }
            launch {
                recoilDropDp.snapTo(1.8f)
                recoilDropDp.animateTo(0f, spring(stiffness = Spring.StiffnessMedium, dampingRatio = 0.65f))
            }
            launch {
                recoilScale.snapTo(0.992f)
                recoilScale.animateTo(1f, spring(stiffness = Spring.StiffnessMedium, dampingRatio = 0.65f))
            }
        }
    }

    // Fallback opening animation progress (0f -> 1f over ~2.2s)
    val fallbackOpeningProgress = remember { Animatable(0f) }
    LaunchedEffect(stageState.openingPhase, stageState.resolvedAssets.openingVideoUri) {
        if (stageState.openingPhase == OpeningPhase.FALLBACK_ANIMATION) {
            fallbackOpeningProgress.snapTo(0f)
            fallbackOpeningProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            )
            delay(180L)
            onOpeningFinished()
        } else if (stageState.openingPhase == OpeningPhase.IDLE_READY) {
            fallbackOpeningProgress.snapTo(1f)
        }
    }

    val virtualPhoneAlpha by animateFloatAsState(
        targetValue = when (stageState.openingPhase) {
            OpeningPhase.IDLE_READY -> overlayCfg.screenBrightness
            OpeningPhase.FALLBACK_ANIMATION -> ((fallbackOpeningProgress.value - 0.55f) / 0.45f).coerceIn(0f, 1f)
            OpeningPhase.PLAYING_VIDEO -> 0f
        },
        animationSpec = tween(280),
        label = "phone_alpha",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(GtaMidnight),
    ) {
        val density = LocalDensity.current
        val stageWidthPx = with(density) { maxWidth.toPx() }
        val stageHeightPx = with(density) { maxHeight.toPx() }
        val breathXPx = with(density) { breathOffsetXDp.dp.toPx() }
        val breathYPx = with(density) { (breathOffsetYDp + recoilDropDp.value).dp.toPx() }

        val openingScale = if (stageState.openingPhase == OpeningPhase.FALLBACK_ANIMATION) {
            0.84f + 0.16f * fallbackOpeningProgress.value
        } else {
            recoilScale.value
        }
        val openingSlideYPx = if (stageState.openingPhase == OpeningPhase.FALLBACK_ANIMATION) {
            (1f - fallbackOpeningProgress.value) * stageHeightPx * 0.28f
        } else {
            0f
        }

        // LAYER 1: Video / Idle Backdrop
        StageBackdropLayer(
            stageState = stageState,
            isDark = isDark,
            openingProgress = fallbackOpeningProgress.value,
            breathXPx = breathXPx,
            breathYPx = breathYPx,
            breathRotZ = breathRotZ,
            openingScale = openingScale,
            openingSlideYPx = openingSlideYPx,
            onOpeningFinished = onOpeningFinished,
        )

        // LAYER 2 & 3: Positioned Virtual Phone Screen + Glass Tactile Glow
        val phoneWidthDp = maxWidth * overlayCfg.widthFraction
        val phoneHeightDp = maxHeight * overlayCfg.heightFraction
        val phoneLeftPx = stageWidthPx * overlayCfg.leftFraction + breathXPx
        val phoneTopPx = stageHeightPx * overlayCfg.topFraction + breathYPx + openingSlideYPx

        if (virtualPhoneAlpha > 0.01f) {
            val activeQuad = overlayCfg.quadCorners
            val phoneContent: @Composable () -> Unit = {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Layer 2: Virtual Phone OS
                    VirtualPhoneOsContent(
                        stageState = stageState,
                        events = events,
                        modemState = modemState,
                        callState = callState,
                        audioState = audioState,
                        smsMessages = smsMessages,
                        smsThreads = smsThreads,
                        contacts = contacts,
                        isDarkTheme = isDark,
                    )

                    // Layer 3: Subtle glass specular tap glow
                    HandFeedbackOverlayLayer(
                        events = events,
                        resolvedAssets = stageState.resolvedAssets,
                        isStageInteractive = isIdleReady,
                    )

                    // Optional calibration wireframe overlay
                    if (stageState.isCalibrationGridVisible) {
                        CalibrationGridOverlay()
                    }
                }
            }

            if (activeQuad != null) {
                PerspectiveQuadHost(
                    quadCorners = activeQuad,
                    stageWidthPx = stageWidthPx,
                    stageHeightPx = stageHeightPx,
                    backdropScale = 1.03f * openingScale,
                    backdropRotationZ = breathRotZ * 0.45f,
                    offsetXPx = breathXPx,
                    offsetYPx = breathYPx + openingSlideYPx,
                    alpha = virtualPhoneAlpha,
                    cornerRadiusDp = overlayCfg.cornerRadiusDp,
                    modifier = Modifier.fillMaxSize(),
                    content = phoneContent,
                )
            } else {
                val dialingTiltBoost = if (stageState.screenState == AppScreenState.CallDialing ||
                    stageState.screenState == AppScreenState.InCall
                ) {
                    -1.8f
                } else {
                    0f
                }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(phoneLeftPx.roundToInt(), phoneTopPx.roundToInt()) }
                        .width(phoneWidthDp)
                        .height(phoneHeightDp)
                        .graphicsLayer {
                            alpha = virtualPhoneAlpha
                            rotationX = overlayCfg.rotationX + recoilRotX.value
                            rotationY = overlayCfg.rotationY + recoilRotY.value
                            rotationZ = overlayCfg.rotationZ + breathRotZ + dialingTiltBoost
                            cameraDistance = overlayCfg.cameraDistanceDp * density.density
                            scaleX = openingScale
                            scaleY = openingScale
                            shape = RoundedCornerShape(overlayCfg.cornerRadiusDp.dp)
                            clip = true
                        },
                ) {
                    phoneContent()
                }
            }
        }

        // Top-right GTA cash/bank HUD + compact 4G indicator
        if (isIdleReady) {
            GtaTopRightHud(
                modemState = modemState,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 18.dp, end = 22.dp),
            )

            // Bottom-right Retroid Pocket Duo handheld controls legend
            HandheldControlsHud(
                screenState = stageState.screenState,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 18.dp, end = 22.dp),
            )
        }

        // Opening tap-to-skip HUD
        if (stageState.openingPhase != OpeningPhase.IDLE_READY) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSkipOpening,
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x99141029))
                        .border(1.dp, GtaMiamiCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "点击屏幕跳过开场 · SKIP",
                        color = GtaTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Calibration HUD tag
        if (stageState.isCalibrationGridVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC0A0817))
                    .border(1.dp, GtaMiamiCyan, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "校准模式 ON · X=${"%.2f".format(overlayCfg.leftFraction)} Y=${"%.2f".format(overlayCfg.topFraction)} " +
                        "W=${"%.2f".format(overlayCfg.widthFraction)} H=${"%.2f".format(overlayCfg.heightFraction)}",
                    color = GtaMiamiCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // Single-screen device fallback switch pill
        if (!hasSecondaryDisplay) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC1B1533))
                    .border(1.dp, GtaNeonPink, RoundedCornerShape(16.dp))
                    .clickable(onClick = onToggleSingleScreenDeck)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (singleScreenPreviewDeck) "返回上屏舞台" else "单屏模式 · 打开操控台",
                    color = GtaTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun GtaTopRightHud(
    modemState: ModemUiState,
    modifier: Modifier = Modifier,
) {
    val hudTextStyle = remember {
        TextStyle(
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.SansSerif,
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.85f),
                offset = Offset(1.5f, 1.5f),
                blurRadius = 3f,
            ),
        )
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Cash row: $0 + green banknote icon
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "$0", style = hudTextStyle)
            CashNoteHudIcon(modifier = Modifier.size(16.dp))
        }

        // Bank row: $3,880 + teal shield/bank icon
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "$3,880", style = hudTextStyle)
            BankShieldHudIcon(modifier = Modifier.size(16.dp))
        }

        if (modemState.activeAtInterfaceId >= 0) {
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x880B0D12))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${modemState.operator.name.ifBlank { "4G" }} · ${modemState.signal.bars}/5",
                    color = Color(0xFF56E39F),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CashNoteHudIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Outer dark stroke + emerald green cash bill badge
        drawRoundRect(
            color = Color(0xFF142419),
            topLeft = Offset(w * 0.06f, h * 0.16f),
            size = Size(w * 0.88f, h * 0.68f),
            cornerRadius = CornerRadius(4f, 4f),
        )
        drawRoundRect(
            color = Color(0xFF4CD97B),
            topLeft = Offset(w * 0.12f, h * 0.22f),
            size = Size(w * 0.76f, h * 0.56f),
            cornerRadius = CornerRadius(3f, 3f),
        )
        drawCircle(
            color = Color(0xFF1E6E3B),
            radius = h * 0.15f,
            center = Offset(w * 0.50f, h * 0.50f),
        )
    }
}

@Composable
private fun BankShieldHudIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val shield = Path().apply {
            moveTo(w * 0.50f, h * 0.08f)
            lineTo(w * 0.88f, h * 0.24f)
            lineTo(w * 0.84f, h * 0.64f)
            quadraticTo(w * 0.50f, h * 0.94f, w * 0.50f, h * 0.94f)
            quadraticTo(w * 0.50f, h * 0.94f, w * 0.16f, h * 0.64f)
            lineTo(w * 0.12f, h * 0.24f)
            close()
        }
        drawPath(shield, color = Color(0xFF102A2E))
        drawPath(
            shield,
            color = Color(0xFF3FD6B9),
            style = Stroke(width = 2.5f),
        )
        // Lock arch & body inside shield
        drawCircle(
            color = Color(0xFF3FD6B9),
            radius = w * 0.14f,
            center = Offset(w * 0.50f, h * 0.44f),
            style = Stroke(width = 2.0f),
        )
        drawRoundRect(
            color = Color(0xFF3FD6B9),
            topLeft = Offset(w * 0.34f, h * 0.46f),
            size = Size(w * 0.32f, h * 0.24f),
            cornerRadius = CornerRadius(2f, 2f),
        )
    }
}

@Composable
internal fun HandheldControlsHud(
    screenState: AppScreenState,
    modifier: Modifier = Modifier,
) {
    val hints: List<Pair<String, String>> = when (screenState) {
        AppScreenState.Opening,
        AppScreenState.PhoneDialing,
        -> listOf(
            "SMS" to "SEL",
            "SWITCH" to "L1/R1",
            "INPUT" to "DECK",
        )
        AppScreenState.SmsChat -> listOf(
            "SCROLL" to "D-PAD",
            "SWITCH" to "L1/R1",
            "PHONE" to "START",
        )
        AppScreenState.CallIncoming -> listOf(
            "ANSWER" to "A",
            "REJECT" to "B",
        )
        AppScreenState.CallDialing -> listOf(
            "HANGUP" to "B",
        )
        AppScreenState.InCall -> listOf(
            "MUTE" to "X",
            "SPEAKER" to "Y",
            "HANGUP" to "B",
        )
        AppScreenState.Settings -> listOf(
            "SWITCH" to "L1/R1",
            "PHONE" to "START",
        )
    }

    val labelStyle = remember {
        TextStyle(
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.85f),
                offset = Offset(1f, 1f),
                blurRadius = 3f,
            ),
        )
    }

    val keyCapStyle = remember {
        TextStyle(
            color = Color.White,
            fontSize = 8.5.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        )
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        hints.forEach { (actionLabel, buttonKey) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = actionLabel, style = labelStyle)
                val isCircleKey = buttonKey.length == 1
                Box(
                    modifier = Modifier
                        .then(
                            if (isCircleKey) {
                                Modifier.size(19.dp).clip(CircleShape)
                            } else {
                                Modifier.height(18.dp).clip(RoundedCornerShape(5.dp))
                            },
                        )
                        .background(Color(0xDD1A1D24))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.80f),
                            shape = if (isCircleKey) CircleShape else RoundedCornerShape(5.dp),
                        )
                        .padding(horizontal = if (isCircleKey) 0.dp else 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = buttonKey,
                        style = keyCapStyle,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun StageBackdropLayer(
    stageState: GtaStageState,
    isDark: Boolean,
    openingProgress: Float,
    breathXPx: Float,
    breathYPx: Float,
    breathRotZ: Float,
    openingScale: Float,
    openingSlideYPx: Float,
    onOpeningFinished: () -> Unit,
) {
    val context = LocalContext.current
    val assets = stageState.resolvedAssets

    when (stageState.openingPhase) {
        OpeningPhase.PLAYING_VIDEO -> {
            val videoUri = assets.openingVideoFor(isDark)
            if (videoUri != null) {
                ExoVideoBackdrop(
                    videoUri = videoUri,
                    looping = false,
                    muted = false,
                    onEnded = onOpeningFinished,
                    onError = onOpeningFinished,
                )
            } else {
                StylizedViceCityBackdrop(
                    stageState = stageState,
                    openingProgress = openingProgress,
                    breathXPx = breathXPx,
                    breathYPx = breathYPx,
                    breathRotZ = breathRotZ,
                    openingScale = openingScale,
                    openingSlideYPx = openingSlideYPx,
                )
            }
        }

        OpeningPhase.FALLBACK_ANIMATION -> {
            StylizedViceCityBackdrop(
                stageState = stageState,
                openingProgress = openingProgress,
                breathXPx = breathXPx,
                breathYPx = breathYPx,
                breathRotZ = breathRotZ,
                openingScale = openingScale,
                openingSlideYPx = openingSlideYPx,
            )
        }

        OpeningPhase.IDLE_READY -> {
            val idleVideoUri = assets.idleLoopVideoUri
            val preferredImage: AssetSource? = when (stageState.screenState) {
                AppScreenState.CallDialing, AppScreenState.InCall ->
                    assets.dialingImageFor(isDark)
                else -> assets.idleImageFor(isDark)
            }
            val loadedBitmap = rememberAssetBitmap(context, preferredImage)

            when {
                loadedBitmap != null -> {
                    Image(
                        bitmap = loadedBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = breathXPx
                                translationY = breathYPx
                                rotationZ = breathRotZ * 0.45f
                                scaleX = 1.03f * openingScale
                                scaleY = 1.03f * openingScale
                            },
                    )
                }
                idleVideoUri != null -> {
                    ExoVideoBackdrop(
                        videoUri = idleVideoUri,
                        looping = true,
                        muted = true,
                        onEnded = {},
                        onError = {},
                    )
                }
                else -> {
                    StylizedViceCityBackdrop(
                        stageState = stageState,
                        openingProgress = 1f,
                        breathXPx = breathXPx,
                        breathYPx = breathYPx,
                        breathRotZ = breathRotZ,
                        openingScale = 1f,
                        openingSlideYPx = 0f,
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ExoVideoBackdrop(
    videoUri: String,
    looping: Boolean,
    muted: Boolean,
    onEnded: () -> Unit,
    onError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(videoUri, looping, muted) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (muted) 0f else 1f
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player, onEnded, onError) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !looping) {
                    onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                DuoLogger.w(LogCategory.DISPLAY, "exo_video_error", error.message.orEmpty())
                onError()
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> player.play()
                else -> Unit
            }
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = player
            }
        },
        update = { view ->
            if (view.player !== player) {
                view.player = player
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Procedural GTA 6 Vice City placeholder stage used before real captured MP4/PNG assets are pushed.
 * Draws the sunset/neon skyline, palm silhouettes, Jason's hand/arm raising the phone chassis
 * matching PhoneOverlayConfig coordinates so Layer 2 & 3 align seamlessly out-of-the-box.
 */
@Composable
private fun StylizedViceCityBackdrop(
    stageState: GtaStageState,
    openingProgress: Float,
    breathXPx: Float,
    breathYPx: Float,
    breathRotZ: Float,
    openingScale: Float,
    openingSlideYPx: Float,
) {
    val cfg = stageState.stageConfig.phoneOverlay
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Vice City twilight sky gradient
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0618),
                        Color(0xFF21103A),
                        Color(0xFF51194D),
                        Color(0xFF8C2F4C),
                        Color(0xFF1A0F2C),
                    ),
                ),
            )

            // Horizon sunset disc
            val sunCenter = Offset(size.width * 0.28f, size.height * 0.56f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        GtaGold.copy(alpha = 0.55f),
                        GtaSunsetOrange.copy(alpha = 0.35f),
                        GtaNeonPink.copy(alpha = 0.12f),
                        Color.Transparent,
                    ),
                    center = sunCenter,
                    radius = size.height * 0.34f,
                ),
                radius = size.height * 0.34f,
                center = sunCenter,
            )

            // Distant Leonida city skyline silhouette
            val skylineY = size.height * 0.66f
            val buildings = listOf(
                0.04f to 0.14f, 0.09f to 0.22f, 0.15f to 0.17f, 0.20f to 0.28f,
                0.26f to 0.19f, 0.32f to 0.25f, 0.39f to 0.16f, 0.44f to 0.21f,
            )
            buildings.forEachIndexed { idx, (xFrac, heightFrac) ->
                val bx = size.width * xFrac
                val bw = size.width * 0.042f
                val bh = size.height * heightFrac
                drawRect(
                    color = Color(0xFF120B24),
                    topLeft = Offset(bx, skylineY - bh),
                    size = Size(bw, bh + size.height * 0.34f),
                )
                if (idx % 2 == 0) {
                    drawRect(
                        color = GtaMiamiCyan.copy(alpha = 0.28f),
                        topLeft = Offset(bx + bw * 0.2f, skylineY - bh + 8f),
                        size = Size(bw * 0.6f, 3f),
                    )
                }
            }

            // Neon road reflection strip
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(GtaNeonPink.copy(alpha = 0.28f), GtaMiamiCyan.copy(alpha = 0.22f), Color.Transparent),
                ),
                topLeft = Offset(0f, size.height * 0.78f),
                size = Size(size.width, size.height * 0.22f),
            )

            // Palm tree silhouette on the left
            val trunkPath = Path().apply {
                moveTo(size.width * 0.08f, size.height)
                quadraticTo(size.width * 0.12f, size.height * 0.62f, size.width * 0.15f, size.height * 0.34f)
                lineTo(size.width * 0.17f, size.height * 0.34f)
                quadraticTo(size.width * 0.14f, size.height * 0.62f, size.width * 0.105f, size.height)
                close()
            }
            drawPath(trunkPath, color = Color(0xFF0B0718))
        }

        // Stylized Jason HUD title badge on left during idle or opening
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 32.dp, top = 28.dp)
                .alpha(if (stageState.openingPhase == OpeningPhase.FALLBACK_ANIMATION) openingProgress else 0.92f),
        ) {
            Text(
                text = "LEONIDA // VICE CITY",
                color = GtaMiamiCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
            )
            Text(
                text = "JASON DUVAL",
                color = GtaTextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
            )
            Text(
                text = when (stageState.screenState) {
                    AppScreenState.Opening -> "RAISING DEVICE..."
                    AppScreenState.PhoneDialing -> "STANDBY · PHONE READY"
                    AppScreenState.CallDialing -> "CONNECTING VOICE LINK..."
                    AppScreenState.CallIncoming -> "INCOMING TRANSMISSION"
                    AppScreenState.InCall -> "VOICE CHANNEL ACTIVE"
                    AppScreenState.SmsChat -> "ENCRYPTED MESSAGING"
                    AppScreenState.Settings -> "DISPLAY CALIBRATION"
                },
                color = GtaNeonPink,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }

        // Stylized hand & matte-black phone chassis aligned to PhoneOverlayConfig
        val bezelPadPx = with(density) { 10.dp.toPx() }
        val chassisLeft = wPx * cfg.leftFraction + breathXPx - bezelPadPx
        val chassisTop = hPx * cfg.topFraction + breathYPx + openingSlideYPx - bezelPadPx * 1.4f
        val chassisW = wPx * cfg.widthFraction + bezelPadPx * 2f
        val chassisH = hPx * cfg.heightFraction + bezelPadPx * 2.8f

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = breathRotZ * 0.4f
                },
        ) {
            // Forearm rising from bottom-right holding the phone
            val armPath = Path().apply {
                moveTo(chassisLeft + chassisW * 0.15f, chassisTop + chassisH * 0.70f)
                lineTo(chassisLeft + chassisW * 1.35f, size.height * 1.05f)
                lineTo(chassisLeft + chassisW * 0.35f, size.height * 1.05f)
                lineTo(chassisLeft - chassisW * 0.08f, chassisTop + chassisH * 0.88f)
                close()
            }
            drawPath(
                path = armPath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFC48562), Color(0xFF7D4B34)),
                ),
            )

            // Left grip fingers wrapping edge
            for (i in 0..2) {
                val fy = chassisTop + chassisH * (0.28f + i * 0.14f)
                drawRoundRect(
                    color = Color(0xFFB57756),
                    topLeft = Offset(chassisLeft - 14f, fy),
                    size = Size(24f, 22f),
                    cornerRadius = CornerRadius(11f, 11f),
                )
            }

            // Phone hardware bezel & metallic rim
            drawRoundRect(
                color = Color(0xFF08070D),
                topLeft = Offset(chassisLeft, chassisTop),
                size = Size(chassisW * openingScale, chassisH * openingScale),
                cornerRadius = CornerRadius(32f, 32f),
            )
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(GtaMiamiCyan.copy(alpha = 0.55f), GtaNeonPink.copy(alpha = 0.55f)),
                ),
                topLeft = Offset(chassisLeft, chassisTop),
                size = Size(chassisW * openingScale, chassisH * openingScale),
                cornerRadius = CornerRadius(32f, 32f),
                style = Stroke(width = 2.5f),
            )
        }
    }
}

@Composable
private fun CalibrationGridOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
        drawRect(
            color = GtaMiamiCyan,
            style = Stroke(width = 3f, pathEffect = dash),
        )
        // Center crosshair
        drawLine(
            color = GtaNeonPink.copy(alpha = 0.75f),
            start = Offset(size.width * 0.5f, 0f),
            end = Offset(size.width * 0.5f, size.height),
            strokeWidth = 1.5f,
            pathEffect = dash,
        )
        drawLine(
            color = GtaNeonPink.copy(alpha = 0.75f),
            start = Offset(0f, size.height * 0.5f),
            end = Offset(size.width, size.height * 0.5f),
            strokeWidth = 1.5f,
            pathEffect = dash,
        )
    }
}
