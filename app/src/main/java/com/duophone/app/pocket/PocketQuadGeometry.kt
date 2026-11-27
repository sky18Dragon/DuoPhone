package com.duophone.app.pocket

import com.duophone.app.stage.PhoneOverlayConfig
import kotlin.math.cos
import kotlin.math.sin

internal object PocketQuadGeometry {
    /**
     * Computes screen-pixel quad corners [tlX, tlY, trX, trY, brX, brY, blX, blY] matching
     * StageBackdropLayer's 16:9 ContentScale.Crop + backdropScale transform.
     */
    fun computeScreenQuad(
        overlay: PhoneOverlayConfig,
        stageW: Float,
        stageH: Float,
        backdropScale: Float = 1.03f,
    ): FloatArray {
        val safeW = stageW.coerceAtLeast(1f)
        val safeH = stageH.coerceAtLeast(1f)
        val quad = overlay.quadCorners
        if (quad != null) {
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
            fun mapPt(nx: Float, ny: Float): Pair<Float, Float> {
                val dx = (nx - 0.5f) * cropW * backdropScale
                val dy = (ny - 0.5f) * cropH * backdropScale
                return (cx + dx) to (cy + dy)
            }
            val (tlX, tlY) = mapPt(quad.tlX, quad.tlY)
            val (trX, trY) = mapPt(quad.trX, quad.trY)
            val (brX, brY) = mapPt(quad.brX, quad.brY)
            val (blX, blY) = mapPt(quad.blX, quad.blY)
            return floatArrayOf(tlX, tlY, trX, trY, brX, brY, blX, blY)
        }
        val left = safeW * overlay.leftFraction
        val top = safeH * overlay.topFraction
        val w = safeW * overlay.widthFraction
        val h = safeH * overlay.heightFraction
        val cx = left + w * 0.5f
        val cy = top + h * 0.5f
        val rad = Math.toRadians(overlay.rotationZ.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        fun rotPt(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - cx
            val dy = y - cy
            return (cx + dx * cosA - dy * sinA) to (cy + dx * sinA + dy * cosA)
        }
        val (tlX, tlY) = rotPt(left, top)
        val (trX, trY) = rotPt(left + w, top)
        val (brX, brY) = rotPt(left + w, top + h)
        val (blX, blY) = rotPt(left, top + h)
        return floatArrayOf(tlX, tlY, trX, trY, brX, brY, blX, blY)
    }

    fun cornerRadiusLogical(overlay: PhoneOverlayConfig): Float =
        (overlay.cornerRadiusDp * 3.0f).coerceIn(12f, 44f)
}
