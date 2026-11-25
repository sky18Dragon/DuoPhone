package com.duophone.app.ui.gta

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.duophone.app.stage.QuadCorners
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders Compose UI warped onto an arbitrary 4-corner perspective quadrilateral via Android's native
 * [Matrix.setPolyToPoly] homography so tilted/sheared screens in hand-held POV photography align pixel-for-pixel.
 */
@Composable
fun PerspectiveQuadHost(
    quadCorners: QuadCorners,
    stageWidthPx: Float,
    stageHeightPx: Float,
    backdropScale: Float,
    backdropRotationZ: Float,
    offsetXPx: Float,
    offsetYPx: Float,
    alpha: Float,
    cornerRadiusDp: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { cornerRadiusDp.dp.toPx() }
    val latestContent by rememberUpdatedState(content)
    val latestAlpha by rememberUpdatedState(alpha)

    // Base phone pixel size matching the physical display region in the 1.03x idle frame (~352x676 at 1080p)
    val basePts = computeTransformedQuad(
        quad = quadCorners,
        stageW = stageWidthPx,
        stageH = stageHeightPx,
        scaleFactor = 1.03f,
        rotDeg = 0f,
        transX = 0f,
        transY = 0f,
    )
    val baseWidthPx = (((hypot(basePts[2] - basePts[0], basePts[3] - basePts[1]) +
        hypot(basePts[4] - basePts[6], basePts[5] - basePts[7])) * 0.5f))
        .roundToInt()
        .coerceAtLeast(160)
    val baseHeightPx = (((hypot(basePts[6] - basePts[0], basePts[7] - basePts[1]) +
        hypot(basePts[4] - basePts[2], basePts[5] - basePts[3])) * 0.5f))
        .roundToInt()
        .coerceAtLeast(240)

    val dstPts = computeTransformedQuad(
        quad = quadCorners,
        stageW = stageWidthPx,
        stageH = stageHeightPx,
        scaleFactor = backdropScale,
        rotDeg = backdropRotationZ,
        transX = offsetXPx,
        transY = offsetYPx,
    )

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            PerspectiveQuadLayout(context).apply {
                attachComposeContent {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(latestAlpha),
                    ) {
                        latestContent()
                    }
                }
            }
        },
        update = { view ->
            view.updateQuad(
                childWidthPx = baseWidthPx,
                childHeightPx = baseHeightPx,
                dstCorners = dstPts,
                cornerRadiusPx = cornerRadiusPx,
            )
        },
    )
}

private fun computeTransformedQuad(
    quad: QuadCorners,
    stageW: Float,
    stageH: Float,
    scaleFactor: Float,
    rotDeg: Float,
    transX: Float,
    transY: Float,
): FloatArray {
    // StageBackdropLayer renders 16:9 artwork via ContentScale.Crop centered in stageW x stageH
    val safeW = stageW.coerceAtLeast(1f)
    val safeH = stageH.coerceAtLeast(1f)
    val imgAspect = 16f / 9f
    val viewAspect = safeW / safeH
    val cropW: Float
    val cropH: Float
    if (viewAspect >= imgAspect) {
        cropW = safeW
        cropH = safeW / imgAspect
    } else {
        cropH = safeH
        cropW = safeH * imgAspect
    }

    val cx = safeW * 0.5f
    val cy = safeH * 0.5f
    val rad = Math.toRadians(rotDeg.toDouble())
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()

    fun mapPt(nx: Float, ny: Float): Pair<Float, Float> {
        val dx = (nx - 0.5f) * cropW * scaleFactor
        val dy = (ny - 0.5f) * cropH * scaleFactor
        val rx = dx * cosA - dy * sinA
        val ry = dx * sinA + dy * cosA
        return (cx + rx + transX) to (cy + ry + transY)
    }

    val (tlX, tlY) = mapPt(quad.tlX, quad.tlY)
    val (trX, trY) = mapPt(quad.trX, quad.trY)
    val (brX, brY) = mapPt(quad.brX, quad.brY)
    val (blX, blY) = mapPt(quad.blX, quad.blY)
    return floatArrayOf(tlX, tlY, trX, trY, brX, brY, blX, blY)
}

private class PerspectiveQuadLayout(context: Context) : FrameLayout(context) {
    private val composeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }
    private val polyMatrix = Matrix()
    private val clipPath = Path()
    private val clipRect = RectF()

    private var targetChildW = 352
    private var targetChildH = 676
    private var targetCornerPx = 13f
    private val currentDst = FloatArray(8)
    private var hasValidMatrix = false

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        addView(composeView, LayoutParams(targetChildW, targetChildH))
    }

    fun attachComposeContent(content: @Composable () -> Unit) {
        composeView.setContent(content)
    }

    fun updateQuad(
        childWidthPx: Int,
        childHeightPx: Int,
        dstCorners: FloatArray,
        cornerRadiusPx: Float,
    ) {
        val sizeChanged = childWidthPx != targetChildW || childHeightPx != targetChildH
        targetChildW = childWidthPx
        targetChildH = childHeightPx

        if (sizeChanged) {
            composeView.layoutParams = LayoutParams(childWidthPx, childHeightPx)
            requestLayout()
        }

        if (sizeChanged || kotlin.math.abs(cornerRadiusPx - targetCornerPx) > 0.25f || clipPath.isEmpty) {
            targetCornerPx = cornerRadiusPx
            clipRect.set(0f, 0f, childWidthPx.toFloat(), childHeightPx.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(clipRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        }

        val matrixChanged = sizeChanged || !currentDst.contentEquals(dstCorners)
        if (matrixChanged) {
            dstCorners.copyInto(currentDst)
            val w = childWidthPx.toFloat()
            val h = childHeightPx.toFloat()
            val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
            hasValidMatrix = polyMatrix.setPolyToPoly(src, 0, currentDst, 0, 4)
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        composeView.measure(
            MeasureSpec.makeMeasureSpec(targetChildW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(targetChildH, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        composeView.layout(0, 0, targetChildW, targetChildH)
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        // Child lives at (0..targetChildW, 0..targetChildH) pre-homography; invalidate full stage
        // so HWUI repaints the entire perspective-mapped quad region.
        invalidate()
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (!hasValidMatrix) {
            return super.drawChild(canvas, child, drawingTime)
        }
        val save = canvas.save()
        canvas.concat(polyMatrix)
        canvas.clipPath(clipPath)
        val drawn = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(save)
        return drawn
    }
}
