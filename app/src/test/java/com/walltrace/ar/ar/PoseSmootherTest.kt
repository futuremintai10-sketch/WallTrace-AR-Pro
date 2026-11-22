package com.walltrace.ar.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PoseSmootherTest {

    private val identity = floatArrayOf(0f, 0f, 0f, 1f)

    @Test
    fun firstUpdate_snapsToTarget() {
        val s = PoseSmoother()
        assertFalse(s.hasValue)
        val (p, _) = s.update(floatArrayOf(1f, 2f, 3f), identity)
        assertEquals(1f, p[0], 1e-6f)
        assertEquals(2f, p[1], 1e-6f)
        assertEquals(3f, p[2], 1e-6f)
        assertTrue(s.hasValue)
    }

    @Test
    fun smallJitter_isDampedButConverges() {
        val s = PoseSmoother(positionAlpha = 0.35f)
        s.update(floatArrayOf(0f, 0f, 0f), identity)
        val (p1, _) = s.update(floatArrayOf(0.01f, 0f, 0f), identity)
        assertEquals(0.0035f, p1[0], 1e-6f)
        var last = p1
        repeat(40) { last = s.update(floatArrayOf(0.01f, 0f, 0f), identity).first }
        assertEquals(0.01f, last[0], 1e-5f)
    }

    @Test
    fun largeCorrection_snapsImmediately() {
        val s = PoseSmoother(snapDistanceMeters = 0.08f)
        s.update(floatArrayOf(0f, 0f, 0f), identity)
        val (p, _) = s.update(floatArrayOf(0.5f, 0f, 0f), identity)
        assertEquals(0.5f, p[0], 1e-6f)
    }

    @Test
    fun largeRotation_snapsImmediately() {
        val s = PoseSmoother(snapAngleRadians = 0.12f)
        s.update(floatArrayOf(0f, 0f, 0f), identity)
        val target = floatArrayOf(0f, sin(0.5f), 0f, cos(0.5f)) // 1 rad about Y
        val (_, r) = s.update(floatArrayOf(0f, 0f, 0f), target)
        for (i in 0..3) assertEquals(target[i], r[i], 1e-5f)
    }

    @Test
    fun reset_clearsState() {
        val s = PoseSmoother()
        s.update(floatArrayOf(0f, 0f, 0f), identity)
        s.reset()
        assertFalse(s.hasValue)
        val (p, _) = s.update(floatArrayOf(0.03f, 0f, 0f), identity)
        assertEquals(0.03f, p[0], 1e-6f)
    }
}
