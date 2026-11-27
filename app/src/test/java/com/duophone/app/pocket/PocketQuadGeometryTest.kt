package com.duophone.app.pocket

import com.duophone.app.stage.PhoneOverlayConfig
import com.duophone.app.stage.QuadCorners
import org.junit.Assert.assertEquals
import org.junit.Test

class PocketQuadGeometryTest {
    @Test
    fun `default stage_config quad maps cleanly to 1920x1080 crop coordinates`() {
        val overlay = PhoneOverlayConfig(
            cornerRadiusDp = 9.0f,
            screenBrightness = 0.96f,
            quadCorners = QuadCorners(
                tlX = 0.455414f, tlY = 0.216261f,
                trX = 0.602703f, trY = 0.248412f,
                brX = 0.565333f, brY = 0.789487f,
                blX = 0.418044f, blY = 0.757336f,
            ),
        )
        val pts = PocketQuadGeometry.computeScreenQuad(overlay, 1920f, 1080f, 1.03f)
        assertEquals(8, pts.size)
        assertEquals(871.8f, pts[0], 0.5f)
        assertEquals(224.3f, pts[1], 0.5f)
        assertEquals(1163.1f, pts[2], 0.5f)
        assertEquals(260.1f, pts[3], 0.5f)
        assertEquals(1089.2f, pts[4], 0.5f)
        assertEquals(862.0f, pts[5], 0.5f)
        assertEquals(797.8f, pts[6], 0.5f)
        assertEquals(826.3f, pts[7], 0.5f)
        assertEquals(27.0f, PocketQuadGeometry.cornerRadiusLogical(overlay), 0.01f)
    }

    @Test
    fun `fallback rotated rectangle computes four oriented corners when quadCorners is null`() {
        val overlay = PhoneOverlayConfig(
            leftFraction = 0.4f,
            topFraction = 0.2f,
            widthFraction = 0.2f,
            heightFraction = 0.6f,
            rotationZ = 0f,
            cornerRadiusDp = 24f,
            quadCorners = null,
        )
        val pts = PocketQuadGeometry.computeScreenQuad(overlay, 1000f, 1000f, 1.0f)
        assertEquals(400f, pts[0], 0.1f)
        assertEquals(200f, pts[1], 0.1f)
        assertEquals(600f, pts[2], 0.1f)
        assertEquals(200f, pts[3], 0.1f)
        assertEquals(600f, pts[4], 0.1f)
        assertEquals(800f, pts[5], 0.1f)
        assertEquals(400f, pts[6], 0.1f)
        assertEquals(800f, pts[7], 0.1f)
        assertEquals(44.0f, PocketQuadGeometry.cornerRadiusLogical(overlay), 0.01f)
    }
}
