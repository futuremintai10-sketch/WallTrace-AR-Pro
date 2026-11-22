package com.walltrace.ar.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class SketchMathTest {

    private fun assertVec(expected: FloatArray, actual: FloatArray, eps: Float = 1e-4f) {
        for (i in expected.indices) assertEquals("component $i", expected[i], actual[i], eps)
    }

    @Test
    fun wallFacingViewer_axesAreUprightAndFacingCamera() {
        // Wall in the XY plane, viewer on +Z.
        val q = SketchMath.wallOrientation(
            surfaceNormal = floatArrayOf(0f, 0f, 1f),
            toCamera = floatArrayOf(0f, 0f, 2f),
            cameraRight = floatArrayOf(1f, 0f, 0f)
        )
        assertVec(floatArrayOf(1f, 0f, 0f), SketchMath.rotate(q, floatArrayOf(1f, 0f, 0f)))
        assertVec(floatArrayOf(0f, 1f, 0f), SketchMath.rotate(q, floatArrayOf(0f, 1f, 0f)))
        assertVec(floatArrayOf(0f, 0f, 1f), SketchMath.rotate(q, floatArrayOf(0f, 0f, 1f)))
    }

    @Test
    fun normalPointingAway_isFlippedTowardCamera() {
        val q = SketchMath.wallOrientation(
            surfaceNormal = floatArrayOf(0f, 0f, -1f),
            toCamera = floatArrayOf(0f, 0f, 1f),
            cameraRight = floatArrayOf(1f, 0f, 0f)
        )
        val z = SketchMath.rotate(q, floatArrayOf(0f, 0f, 1f))
        assertTrue(z[2] > 0.99f)
    }

    @Test
    fun sideWall_imageUpStaysWorldUp() {
        // Wall facing +X (viewer standing on +X side).
        val q = SketchMath.wallOrientation(
            surfaceNormal = floatArrayOf(1f, 0f, 0f),
            toCamera = floatArrayOf(1.5f, 0.2f, 0f),
            cameraRight = floatArrayOf(0f, 0f, -1f)
        )
        assertVec(floatArrayOf(0f, 1f, 0f), SketchMath.rotate(q, floatArrayOf(0f, 1f, 0f)))
        assertVec(floatArrayOf(1f, 0f, 0f), SketchMath.rotate(q, floatArrayOf(0f, 0f, 1f)))
        // Image right must be the viewer's right: viewer looks along -X, so right is -Z.
        assertVec(floatArrayOf(0f, 0f, -1f), SketchMath.rotate(q, floatArrayOf(1f, 0f, 0f)))
    }

    @Test
    fun slightlyTiltedNormal_producesOrthonormalFrame() {
        val n = SketchMath.normalize(floatArrayOf(0.3f, 0.1f, 0.95f))
        val q = SketchMath.wallOrientation(n, floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f))
        val x = SketchMath.rotate(q, floatArrayOf(1f, 0f, 0f))
        val y = SketchMath.rotate(q, floatArrayOf(0f, 1f, 0f))
        val z = SketchMath.rotate(q, floatArrayOf(0f, 0f, 1f))
        assertEquals(0f, SketchMath.dot(x, y), 1e-4f)
        assertEquals(0f, SketchMath.dot(y, z), 1e-4f)
        assertEquals(1f, SketchMath.dot(z, n), 1e-4f)
        assertEquals(0f, x[1], 1e-4f) // image right stays horizontal
    }

    @Test
    fun horizontalSurface_usesCameraRight() {
        val q = SketchMath.wallOrientation(
            surfaceNormal = floatArrayOf(0f, 1f, 0f),
            toCamera = floatArrayOf(0f, 1f, 0.2f),
            cameraRight = floatArrayOf(1f, 0f, 0f)
        )
        assertVec(floatArrayOf(1f, 0f, 0f), SketchMath.rotate(q, floatArrayOf(1f, 0f, 0f)))
        assertVec(floatArrayOf(0f, 1f, 0f), SketchMath.rotate(q, floatArrayOf(0f, 0f, 1f)))
    }

    @Test
    fun wallLikeDetection() {
        assertTrue(SketchMath.isWallLike(floatArrayOf(0f, 0f, 1f)))
        assertTrue(SketchMath.isWallLike(SketchMath.normalize(floatArrayOf(0f, 0.3f, 1f))))
        assertFalse(SketchMath.isWallLike(floatArrayOf(0f, 1f, 0f)))
        assertFalse(SketchMath.isWallLike(floatArrayOf(0f, -1f, 0f)))
    }

    @Test
    fun rayPlaneIntersection_hitsAndMisses() {
        val hit = SketchMath.rayPlaneIntersection(
            floatArrayOf(0f, 0f, 2f), floatArrayOf(0f, 0f, -1f),
            floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 1f)
        )
        assertNotNull(hit)
        assertVec(floatArrayOf(0f, 0f, 0f), hit!!)

        val parallel = SketchMath.rayPlaneIntersection(
            floatArrayOf(0f, 0f, 2f), floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 1f)
        )
        assertNull(parallel)

        val behind = SketchMath.rayPlaneIntersection(
            floatArrayOf(0f, 0f, 2f), floatArrayOf(0f, 0f, 1f),
            floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 1f)
        )
        assertNull(behind)
    }

    @Test
    fun screenPointToRay_centerOfIdentityProjection() {
        val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        val ray = SketchMath.screenPointToRay(50f, 50f, 100, 100, identity)
        assertNotNull(ray)
        val (origin, dir) = ray!!
        assertVec(floatArrayOf(0f, 0f, -1f), origin)
        assertVec(floatArrayOf(0f, 0f, 1f), dir)
        assertNull(SketchMath.screenPointToRay(1f, 1f, 0, 100, identity))
    }

    @Test
    fun slerp_endpointsAndMidpoint() {
        val a = floatArrayOf(0f, 0f, 0f, 1f)
        val half = (PI / 2).toFloat()
        val b = floatArrayOf(0f, 0f, sin(half / 2), cos(half / 2)) // 90° about Z
        assertVec(a, SketchMath.slerp(a, b, 0f))
        assertVec(b, SketchMath.slerp(a, b, 1f))
        val mid = SketchMath.slerp(a, b, 0.5f)
        assertEquals(half / 2, SketchMath.angleBetween(a, mid), 1e-3f)
    }

    @Test
    fun quaternionFromBasis_roundTrip() {
        val angle = 0.7f
        val x = floatArrayOf(cos(angle), sin(angle), 0f)
        val y = floatArrayOf(-sin(angle), cos(angle), 0f)
        val z = floatArrayOf(0f, 0f, 1f)
        val q = SketchMath.quaternionFromBasis(x, y, z)
        assertVec(x, SketchMath.rotate(q, floatArrayOf(1f, 0f, 0f)))
        assertVec(y, SketchMath.rotate(q, floatArrayOf(0f, 1f, 0f)))
        // 180° rotations exercise the non-positive-trace branches.
        val q180 = SketchMath.quaternionFromBasis(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, -1f, 0f), z)
        assertVec(floatArrayOf(-1f, 0f, 0f), SketchMath.rotate(q180, floatArrayOf(1f, 0f, 0f)))
        assertTrue(abs(q180[2]) > 0.99f)
    }
}
