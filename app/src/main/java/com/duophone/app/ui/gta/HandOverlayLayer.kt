package com.duophone.app.ui.gta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import com.duophone.app.stage.AssetSource
import com.duophone.app.stage.InteractionEvent
import com.duophone.app.stage.ResolvedStageAssets
import com.duophone.app.stage.VirtualPhoneKeyMap
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Layer 3 (Top Glass Feedback): Renders a subtle, clean glass specular tap glow at the
 * pressed virtual key coordinate without superimposing a floating thumb over the hand.
 */
@Composable
fun HandFeedbackOverlayLayer(
    events: SharedFlow<InteractionEvent>,
    @Suppress("UNUSED_PARAMETER") resolvedAssets: ResolvedStageAssets,
    isStageInteractive: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isStageInteractive) return

    var tapX by remember { mutableStateOf(0.5f) }
    var tapY by remember { mutableStateOf(0.5f) }
    val glowProgress = remember { Animatable(0f) }

    LaunchedEffect(events) {
        events.collect { ev ->
            if (ev is InteractionEvent.KeyPressed) {
                val anchor = VirtualPhoneKeyMap.anchorFor(ev.keyId)
                tapX = anchor.x
                tapY = anchor.y
                launch {
                    glowProgress.snapTo(1f)
                    glowProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
                    )
                }
            }
        }
    }

    val progress = glowProgress.value
    if (progress <= 0.01f) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val baseRadius = widthPx * 0.11f

        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(widthPx * tapX, heightPx * tapY)
            val expandedRadius = baseRadius * (0.72f + 0.58f * (1f - progress))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.24f * progress),
                        Color.White.copy(alpha = 0.08f * progress),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = expandedRadius,
                ),
                radius = expandedRadius,
                center = center,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.22f * progress),
                radius = expandedRadius * 0.85f,
                center = center,
                style = Stroke(width = 1.5f),
            )
        }
    }
}

@Composable
internal fun rememberAssetBitmap(context: Context, source: AssetSource?): ImageBitmap? {
    return remember(source) {
        loadAssetBitmap(context, source)?.asImageBitmap()
    }
}

internal fun loadAssetBitmap(context: Context, source: AssetSource?): Bitmap? {
    if (source == null) return null
    return runCatching {
        when (source) {
            is AssetSource.ExternalFile -> BitmapFactory.decodeFile(source.path)
            is AssetSource.BundledAsset -> context.assets.open(source.assetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }
    }.getOrNull()
}
