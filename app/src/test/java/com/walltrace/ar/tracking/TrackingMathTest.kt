package com.walltrace.ar.tracking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingMathTest {

    @Test
    fun imageCorners_unrotatedCentred() {
        val c = QuadMath.imageCorners(100f, 200f, 40f, 20f, 0f, 0f, 1f, 0f)
        assertArrayEquals(floatArrayOf(30f, 90f, 70f, 90f, 70f, 110f, 30f, 110f), c, 1e-4f)
    }

    @Test
    fun imageCorners_rotation90IsClockwiseOnScreen() {
        val c = QuadMath.imageCorners(100f, 100f, 40f, 20f, 10f, 0f, 2f, 90f)
        // Centre (60, 50); half size (40, 20); TL (-40,-20) rotated 90° clockwise -> (20, -40).
        assertEquals(80f, c[0], 1e-3f)
        assertEquals(10f, c[1], 1e-3f)
    }

    @Test
    fun homography_applyAndInvertRoundTrip() {
        val h = doubleArrayOf(1.2, 0.1, 5.0, -0.05, 0.9, -3.0, 0.0002, -0.0001, 1.0)
        val pts = floatArrayOf(0f, 0f, 100f, 50f, 320f, 240f)
        val mapped = QuadMath.applyHomography(h, pts)
        assertNotNull(mapped)
        val inv = QuadMath.invert3x3(h)
        assertNotNull(inv)
        val back = QuadMath.applyHomography(inv!!, mapped!!)
        assertArrayEquals(pts, back, 1e-2f)
        assertNull(QuadMath.invert3x3(doubleArrayOf(1.0, 2.0, 3.0, 2.0, 4.0, 6.0, 0.0, 0.0, 1.0)))
    }

    @Test
    fun identityHomography_keepsPoints() {
        val pts = floatArrayOf(1f, 2f, 3f, 4f)
        assertArrayEquals(pts, QuadMath.applyHomography(QuadMath.IDENTITY, pts), 0f)
    }

    @Test
    fun plausibility_rejectsDegenerateQuads() {
        val ref = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        assertTrue(QuadMath.isPlausible(floatArrayOf(10f, 5f, 120f, 0f, 115f, 110f, 5f, 95f), ref))
        // Self-intersecting (bow-tie)
        assertFalse(QuadMath.isPlausible(floatArrayOf(0f, 0f, 100f, 100f, 100f, 0f, 0f, 100f), ref))
        // Mirrored winding
        assertFalse(QuadMath.isPlausible(floatArrayOf(100f, 0f, 0f, 0f, 0f, 100f, 100f, 100f), ref))
        // Collapsed
        assertFalse(QuadMath.isPlausible(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), ref))
        // Non-finite
        assertFalse(QuadMath.isPlausible(floatArrayOf(Float.NaN, 0f, 100f, 0f, 100f, 100f, 0f, 100f), ref))
    }

    @Test
    fun plausibility_allowsWalkingUpToTheWall() {
        val ref = floatArrayOf(100f, 100f, 200f, 100f, 200f, 200f, 100f, 200f)
        // 8x closer: area grows 64x, which must not count as lost tracking.
        val close = floatArrayOf(-250f, -250f, 550f, -250f, 550f, 550f, -250f, 550f)
        assertTrue(QuadMath.isPlausible(close, ref, 400f))
        assertFalse(QuadMath.isPlausible(close, ref, 25f))
    }

    @Test
    fun expand_scalesAboutCentroid() {
        val quad = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertArrayEquals(floatArrayOf(-5f, -5f, 15f, -5f, 15f, 15f, -5f, 15f), QuadMath.expand(quad, 2f), 1e-5f)
        assertEquals(Math.hypot(10.0, 10.0).toFloat(), QuadMath.diagonal(quad), 1e-4f)
    }

    @Test
    fun smoothing_dampsJitterAndSnapsOnLargeMotion() {
        val a = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        val jitter = a.map { it + 2f }.toFloatArray()
        val s = QuadMath.smooth(a, jitter, 0.5f, 25f)
        assertEquals(1f, s[0], 1e-5f)
        val jump = a.map { it + 100f }.toFloatArray()
        assertArrayEquals(jump, QuadMath.smooth(a, jump, 0.5f, 25f), 0f)
        assertArrayEquals(a, QuadMath.smooth(null, a, 0.5f, 25f), 0f)
    }

    @Test
    fun frameGeometry_roundTripAllRotations() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val g = FrameGeometry(10f, 20f, 640f, 480f, rotation, 1080f, 2340f)
            val pts = floatArrayOf(10f, 20f, 650f, 500f, 300f, 260f)
            val back = g.viewToBuffer(g.bufferToView(pts))
            assertArrayEquals("rotation $rotation", pts, back, 1e-2f)
        }
    }

    @Test
    fun frameGeometry_rotation90MapsSensorTopToScreenRight() {
        // Portrait phone, back camera: buffer is landscape and rotated 90° clockwise for display.
        val g = FrameGeometry(0f, 0f, 640f, 480f, 90, 480f, 640f)
        val topLeft = g.bufferToView(floatArrayOf(0f, 0f))
        assertEquals(480f, topLeft[0], 1e-3f)
        assertEquals(0f, topLeft[1], 1e-3f)
        val centre = g.bufferToView(floatArrayOf(320f, 240f))
        assertEquals(240f, centre[0], 1e-3f)
        assertEquals(320f, centre[1], 1e-3f)
    }

    @Test
    fun frameGeometry_fillCenterCropsLongSide() {
        // 3:4 upright buffer shown on a 9:19.5 screen: scaled to fill height, cropped left/right.
        val g = FrameGeometry(0f, 0f, 640f, 480f, 90, 1080f, 2340f)
        val centre = g.bufferToView(floatArrayOf(320f, 240f))
        assertEquals(540f, centre[0], 1e-2f)
        assertEquals(1170f, centre[1], 1e-2f)
    }
}
