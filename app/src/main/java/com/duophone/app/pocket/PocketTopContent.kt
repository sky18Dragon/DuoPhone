package com.duophone.app.pocket

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duophone.app.DuoPhoneApplication
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.domain.CallUiPhase
import com.duophone.app.stage.DeckMenu
import com.duophone.app.stage.InteractionEvent
import com.duophone.app.stage.StageThemeMode
import com.duophone.app.ui.gta.GtaTopRightHud
import com.duophone.app.ui.gta.HandheldControlsHud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

private fun decodeBackdropRgba(context: Context, assetName: String): Triple<ByteArray, Int, Int>? {
    return runCatching {
        context.assets.open(assetName).use { stream ->
            val raw = BitmapFactory.decodeStream(stream) ?: return@runCatching null
            val argb = if (raw.config == Bitmap.Config.ARGB_8888) {
                raw
            } else {
                raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
            }
            val w = argb.width
            val h = argb.height
            val bytes = ByteArray(w * h * 4)
            argb.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
            argb.recycle()
            Triple(bytes, w, h)
        }
    }.onFailure { Log.w("PocketHost", "backdrop_load_failed") }.getOrNull()
}

/** Default fallback is the unchanged Compose content supplied by MainActivity. */
@Composable
fun PocketTopContent(
    app: DuoPhoneApplication,
    onOpenSingleScreenDeck: (() -> Unit)? = null,
    fallback: @Composable () -> Unit,
) {
    if (!PocketBackend.requested) { fallback(); return }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var failure by remember { mutableStateOf<String?>(null) }
    val stage by app.gtaStageCoordinator.state.collectAsStateWithLifecycle()
    val modem by app.modemController.state.collectAsStateWithLifecycle()
    val call by app.callController.state.collectAsStateWithLifecycle()
    val sms by app.smsController.messages.collectAsStateWithLifecycle()
    val snapshot = PocketStateMapper.map(PocketDisplayRole.TOP, stage, modem, call, sms.count { !it.isRead }).json()
    var surface by remember { mutableStateOf<PocketSurface?>(null) }
    var lastPushedSnapshot by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<AlertDialog?>(null) }
    val scope = rememberCoroutineScope()
    val dispatcher = remember(context, app, scope) {
        PocketIntentDispatcher(object : PocketIntentDispatcher.Target {
            override fun navigate(menu: DeckMenu) { app.gtaStageCoordinator.selectMenu(menu) }
            override fun digit(digit: String) { app.gtaStageCoordinator.onDialKeyPressed(digit) }
            override fun backspace() { app.gtaStageCoordinator.onDialBackspace() }
            override fun back() {
                val cur = app.gtaStageCoordinator.state.value
                if (cur.activeMenu == DeckMenu.PHONE && cur.dialBuffer.isNotEmpty()) {
                    app.gtaStageCoordinator.onDialClear()
                } else {
                    app.gtaStageCoordinator.selectMenu(DeckMenu.PHONE)
                }
            }
            override fun editDraft() {
                surface?.clearInput()
                val input = EditText(context).apply {
                    setText(app.gtaStageCoordinator.state.value.smsDraftBody)
                    hint = "短信草稿"
                    filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
                }
                editor?.dismiss()
                editor = AlertDialog.Builder(context).setTitle("IME = native fallback")
                    .setView(input).setNegativeButton("取消", null)
                    .setPositiveButton("保存草稿") { _, _ ->
                        app.gtaStageCoordinator.updateSmsDraftBody(input.text.toString())
                    }.create().apply {
                        setOnDismissListener { surface?.requestFocus() }
                        show()
                    }
            }
            override fun call(): String {
                if (app.callController.state.value.phase == CallUiPhase.INCOMING) {
                    app.gtaStageCoordinator.onAnswerOrHangupVisualFeedback(isAnswer = true)
                    scope.launch { app.callController.answer() }
                    return "ANSWERING"
                }
                val number = app.gtaStageCoordinator.state.value.dialBuffer.trim()
                if (number.isEmpty()) return "NO_NUMBER"
                app.gtaStageCoordinator.onDialTrigger(number)
                scope.launch { app.callController.dial(number) }
                return "DIALING"
            }
            override fun hangup(): String {
                app.gtaStageCoordinator.onAnswerOrHangupVisualFeedback(isAnswer = false)
                scope.launch { app.callController.hangupOrReject() }
                return "HANGUP"
            }
            override fun sendSms(): String {
                val s = app.gtaStageCoordinator.state.value
                val dest = (s.selectedSmsAddress?.takeIf { it.isNotBlank() } ?: s.smsDraftNumber).trim()
                val body = s.smsDraftBody
                if (dest.isEmpty() || body.isBlank()) return "SMS_INCOMPLETE"
                scope.launch {
                    val res = app.smsController.sendSms(dest, body)
                    if (res.success) app.gtaStageCoordinator.onSmsSent(dest, body)
                }
                return "SMS_SENDING"
            }
        })
    }
    BackHandler(enabled = failure == null && stage.activeMenu != DeckMenu.PHONE) {
        surface?.event(PocketProtocol.event(dispatcher.dispatch(PocketUiIntent.Back)))
    }

    if (failure != null) {
        LaunchedEffect(failure) { Log.e("PocketHost", "fallback=$failure role=TOP") }
        Box(Modifier.fillMaxSize()) {
            fallback()
            Text(
                text = "PocketJS 已回退 Compose：$failure",
                color = Color(0xFFFFD166),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xDD141820))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        return
    }
    val nativeReady = remember {
        try {
            if (PocketBackend.faultInjection) error("injected")
            PocketNative.load()
            true
        } catch (_: LinkageError) { failure = "native_library_load"; false
        } catch (_: RuntimeException) { failure = "init_failure_injected"; false }
    }
    if (!nativeReady) { fallback(); return }
    val bundle = remember {
        runCatching { context.assets.open("pocket/top.js").use { it.readBytes() } }
            .getOrElse { failure = "bundle_load"; byteArrayOf() }
    }
    if (bundle.isEmpty()) { fallback(); return }
    val dark = when (stage.themeMode) {
        StageThemeMode.DARK -> true
        StageThemeMode.LIGHT -> false
        StageThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val overlayConfig = stage.stageConfig.phoneOverlay
    LaunchedEffect(dark, surface) {
        val view = surface ?: return@LaunchedEffect
        val name = if (dark) "gta/idle_hand_phone_dark.png" else "gta/idle_hand_phone_light.png"
        val decoded = withContext(Dispatchers.IO) { decodeBackdropRgba(context, name) }
        if (decoded != null) {
            view.setBackdrop(decoded.first, decoded.second, decoded.third)
        }
    }
    LaunchedEffect(surface) {
        val view = surface ?: return@LaunchedEffect
        app.gtaStageCoordinator.events.collect { ev ->
            if (ev is InteractionEvent.KeyPressed || ev is InteractionEvent.TextChanged) {
                view.pulseRecoil()
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                val view = try { PocketSurface(context, bundle, onIntent = { raw ->
                    val intent = PocketProtocol.parse(raw)
                    if (intent == null) Log.w("PocketBridge", "intent_rejected role=TOP") else {
                        Log.d("PocketBridge", "intent_receive type=${intent.javaClass.simpleName} role=TOP")
                        val code = dispatcher.dispatch(intent)
                        DuoLogger.i(LogCategory.INPUT, "pocket_intent", "type=${intent.javaClass.simpleName} result=$code")
                        surface?.event(PocketProtocol.event(code))
                    }
                }, onFailure = { failure = it },
                    onReady = { app.gtaStageCoordinator.onOpeningFinished() })
                } catch (_: RuntimeException) {
                    failure = "surface_setup"
                    return@AndroidView FrameLayout(context)
                }
                view.setOverlayConfig(overlayConfig)
                view.snapshot(snapshot)
                lastPushedSnapshot = snapshot
                surface = view
                FrameLayout(context).apply {
                    addView(view, FrameLayout.LayoutParams(-1, -1))
                    view.requestFocus()
                }
            },
            update = { root ->
                ((root as? FrameLayout)?.getChildAt(0) as? PocketSurface)?.setOverlayConfig(overlayConfig)
            },
            onRelease = { root ->
                ((root as? FrameLayout)?.getChildAt(0) as? PocketSurface)?.close("onRelease")
            },
        )
        GtaTopRightHud(
            modemState = modem,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 22.dp),
        )
        HandheldControlsHud(
            screenState = stage.screenState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 18.dp, end = 22.dp),
        )
        if (onOpenSingleScreenDeck != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(14.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC1B1533))
                    .border(1.dp, Color(0xFFFF4D9D), RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenSingleScreenDeck)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "单屏模式 · 打开操控台",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
    SideEffect {
        val view = surface
        if (view != null && lastPushedSnapshot != snapshot) {
            lastPushedSnapshot = snapshot
            view.snapshot(snapshot)
            DuoLogger.d(
                LogCategory.DISPLAY,
                "pocket_snapshot_queue",
                "page=${stage.activeMenu.name} screen=${stage.screenState.name} dialLen=${stage.dialBuffer.length}",
            )
        }
    }
    val currentSurface = surface
    DisposableEffect(currentSurface, owner) {
        val view = currentSurface ?: return@DisposableEffect onDispose {}
        DuoLogger.i(LogCategory.DISPLAY, "pocket_effect", "op=enter hasSurface=true state=${owner.lifecycle.currentState}")
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    DuoLogger.i(LogCategory.DISPLAY, "pocket_effect", "op=ON_START hasSurface=true")
                    view.start()
                }
                Lifecycle.Event.ON_STOP -> {
                    DuoLogger.i(LogCategory.DISPLAY, "pocket_effect", "op=ON_STOP hasSurface=true")
                    view.stop("ON_STOP")
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            DuoLogger.i(LogCategory.DISPLAY, "pocket_effect", "op=onDispose hasSurface=true")
            owner.lifecycle.removeObserver(observer)
            editor?.dismiss()
        }
    }
}
