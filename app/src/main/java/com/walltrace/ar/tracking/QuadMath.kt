package com.walltrace.ar.tracking

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pure geometry used by the OpenCV wall tracker and the overlay renderer.
 * Quads are FloatArray(8): TL, TR, BR, BL as interleaved x, y.
 * Homographies are DoubleArray(9), row-major.
 */
object QuadMath {

    val IDENTITY: DoubleArray get() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /**
     * Screen corners of an image of size [imageW] x [imageH] (px) centred in a view of [viewW] x [viewH],
     * after a Compose graphicsLayer transform (scale and clockwise rotation about its centre, then translation).
     */
    fun imageCorners(
        viewW: Float,
        viewH: Float,
        imageW: Float,
        imageH: Float,
        offsetX: Float,
        offsetY: Float,
        scale: Float,
        rotationDegrees: Float
    ): FloatArray {
        val cx = viewW / 2f + offsetX
        val cy = viewH / 2f + offsetY
        val hw = imageW / 2f * scale
        val hh = imageH / 2f * scale
        val rad = Math.toRadians(rotationDegrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val local = floatArrayOf(-hw, -hh, hw, -hh, hw, hh, -hw, hh)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val x = local[i * 2]
            val y = local[i * 2 + 1]
            out[i * 2] = cx + x * c - y * s
            out[i * 2 + 1] = cy + x * s + y * c
        }
        return out
    }

    /** Applies homography [h] to interleaved points. Returns null if a point maps to infinity. */
    fun applyHomography(h: DoubleArray, points: FloatArray): FloatArray? {
        require(h.size == 9 && points.size % 2 == 0)
        val out = FloatArray(points.size)
        var i = 0
        while (i < points.size) {
            val x = points[i].toDouble()
            val y = points[i + 1].toDouble()
            val w = h[6] * x + h[7] * y + h[8]
            if (abs(w) < 1e-9) return null
            out[i] = ((h[0] * x + h[1] * y + h[2]) / w).toFloat()
            out[i + 1] = ((h[3] * x + h[4] * y + h[5]) / w).toFloat()
            i += 2
        }
        return out
    }

    /** Inverse of a 3x3 row-major matrix, or null when singular. */
    fun invert3x3(m: DoubleArray): DoubleArray? {
        require(m.size == 9)
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val co00 = e * i - f * h
        val co01 = -(d * i - f * g)
        val co02 = d * h - e * g
        val det = a * co00 + b * co01 + c * co02
        if (abs(det) < 1e-12) return null
        val inv = doubleArrayOf(
            co00, -(b * i - c * h), b * f - c * e,
            co01, a * i - c * g, -(a * f - c * d),
            co02, -(a * h - b * g), a * e - b * d
        )
        for (k in inv.indices) inv[k] /= det
        return inv
    }

    /** Signed area (shoelace). Positive for clockwise order in screen coordinates (y down). */
    fun signedArea(quad: FloatArray): Float {
        var sum = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            sum += quad[i * 2] * quad[j * 2 + 1] - quad[j * 2] * quad[i * 2 + 1]
        }
        return sum / 2f
    }

    /** True when the quad is convex with consistent winding. */
    fun isConvex(quad: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = quad[i * 2]; val ay = quad[i * 2 + 1]
            val bx = quad[((i + 1) % 4) * 2]; val by = quad[((i + 1) % 4) * 2 + 1]
            val cx = quad[((i + 2) % 4) * 2]; val cy = quad[((i + 2) % 4) * 2 + 1]
            val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /**
     * Rejects degenerate tracking results: the tracked quad must stay convex, keep its winding and
     * not change area by more than [maxAreaRatio] relative to the reference quad.
     */
    fun isPlausible(quad: FloatArray, reference: FloatArray, maxAreaRatio: Float = 25f): Boolean {
        if (quad.any { !it.isFinite() || abs(it) > 1e6f }) return false
        if (!isConvex(quad)) return false
        val a = signedArea(quad)
        val r = signedArea(reference)
        if (r == 0f || a == 0f || (a > 0f) != (r > 0f)) return false
        val ratio = abs(a / r)
        return ratio in (1f / maxAreaRatio)..maxAreaRatio
    }

    /** Largest corner displacement between two quads, in pixels. */
    fun maxCornerDistance(a: FloatArray, b: FloatArray): Float {
        var max = 0f
        for (i in 0 until 4) {
            val d = hypot(a[i * 2] - b[i * 2], a[i * 2 + 1] - b[i * 2 + 1])
            if (d > max) max = d
        }
        return max
    }

    /**
     * Exponential smoothing of corners to remove sub-pixel jitter. Large motions snap immediately so
     * the sketch never lags behind real camera movement.
     */
    fun smooth(previous: FloatArray?, target: FloatArray, alpha: Float, snapPx: Float): FloatArray {
        if (previous == null || maxCornerDistance(previous, target) > snapPx) return target.copyOf()
        return FloatArray(target.size) { previous[it] + (target[it] - previous[it]) * alpha }
    }
}
