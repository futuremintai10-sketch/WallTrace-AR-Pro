package com.walltrace.ar.ar

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure math helpers used by the AR renderer. Kept free of Android/ARCore types so they can be
 * unit tested on the JVM.
 */
object SketchMath {

    private val WORLD_UP = floatArrayOf(0f, 1f, 0f)

    fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    fun sub(a: FloatArray, b: FloatArray): FloatArray =
        floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    fun length(a: FloatArray): Float = sqrt(dot(a, a))

    fun normalize(a: FloatArray): FloatArray {
        val len = length(a)
        require(len > 1e-6f) { "Cannot normalize a zero-length vector" }
        return floatArrayOf(a[0] / len, a[1] / len, a[2] / len)
    }

    /**
     * Returns true when a surface with the given (unit) normal is wall-like, i.e. roughly vertical.
     */
    fun isWallLike(normal: FloatArray, maxUpDot: Float = 0.5f): Boolean =
        abs(dot(normal, WORLD_UP)) < maxUpDot

    /**
     * Builds the sketch orientation for a surface.
     *
     * The resulting frame has:
     *  - +Z along the surface normal, facing the viewer,
     *  - +Y as "image up" (world up projected onto the surface),
     *  - +X as "image right".
     *
     * @param surfaceNormal normal of the surface (any length, any sign).
     * @param toCamera vector from the surface point to the camera; used to make +Z face the viewer.
     * @param cameraRight camera X axis in world space; used only when the surface is horizontal.
     * @return quaternion (x, y, z, w).
     */
    fun wallOrientation(
        surfaceNormal: FloatArray,
        toCamera: FloatArray,
        cameraRight: FloatArray
    ): FloatArray {
        var n = normalize(surfaceNormal)
        if (dot(n, toCamera) < 0f) n = floatArrayOf(-n[0], -n[1], -n[2])

        val upCrossN = cross(WORLD_UP, n)
        val right = if (length(upCrossN) > 0.2f) {
            normalize(upCrossN)
        } else {
            // Surface is close to horizontal: use the camera's right vector projected on the surface.
            val d = dot(cameraRight, n)
            val projected = floatArrayOf(
                cameraRight[0] - n[0] * d,
                cameraRight[1] - n[1] * d,
                cameraRight[2] - n[2] * d
            )
            if (length(projected) > 1e-4f) normalize(projected) else normalize(cross(floatArrayOf(0f, 0f, 1f), n))
        }
        val up = normalize(cross(n, right))
        return quaternionFromBasis(right, up, n)
    }

    /** Converts an orthonormal basis (matrix columns) to a quaternion (x, y, z, w). */
    fun quaternionFromBasis(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
        val m00 = x[0]; val m10 = x[1]; val m20 = x[2]
        val m01 = y[0]; val m11 = y[1]; val m21 = y[2]
        val m02 = z[0]; val m12 = z[1]; val m22 = z[2]
        val trace = m00 + m11 + m22
        val q = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            q[3] = 0.25f * s
            q[0] = (m21 - m12) / s
            q[1] = (m02 - m20) / s
            q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (m21 - m12) / s
            q[0] = 0.25f * s
            q[1] = (m01 + m10) / s
            q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (m02 - m20) / s
            q[0] = (m01 + m10) / s
            q[1] = 0.25f * s
            q[2] = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (m10 - m01) / s
            q[0] = (m02 + m20) / s
            q[1] = (m12 + m21) / s
            q[2] = 0.25f * s
        }
        return normalizeQuaternion(q)
    }

    fun normalizeQuaternion(q: FloatArray): FloatArray {
        val len = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        require(len > 1e-6f) { "Invalid quaternion" }
        return floatArrayOf(q[0] / len, q[1] / len, q[2] / len, q[3] / len)
    }

    /** Rotates vector v by unit quaternion q (x, y, z, w). */
    fun rotate(q: FloatArray, v: FloatArray): FloatArray {
        val u = floatArrayOf(q[0], q[1], q[2])
        val s = q[3]
        val uv = cross(u, v)
        val uuv = cross(u, uv)
        return floatArrayOf(
            v[0] + 2f * (s * uv[0] + uuv[0]),
            v[1] + 2f * (s * uv[1] + uuv[1]),
            v[2] + 2f * (s * uv[2] + uuv[2])
        )
    }

    /** Spherical linear interpolation between unit quaternions. */
    fun slerp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        var bx = b[0]; var by = b[1]; var bz = b[2]; var bw = b[3]
        var cos = a[0] * bx + a[1] * by + a[2] * bz + a[3] * bw
        if (cos < 0f) {
            bx = -bx; by = -by; bz = -bz; bw = -bw; cos = -cos
        }
        if (cos > 0.9995f) {
            return normalizeQuaternion(
                floatArrayOf(
                    a[0] + (bx - a[0]) * t,
                    a[1] + (by - a[1]) * t,
                    a[2] + (bz - a[2]) * t,
                    a[3] + (bw - a[3]) * t
                )
            )
        }
        val theta = acos(cos.coerceIn(-1f, 1f))
        val sinTheta = sin(theta)
        val wa = sin((1f - t) * theta) / sinTheta
        val wb = sin(t * theta) / sinTheta
        return floatArrayOf(
            a[0] * wa + bx * wb,
            a[1] * wa + by * wb,
            a[2] * wa + bz * wb,
            a[3] * wa + bw * wb
        )
    }

    /** Angle in radians between two unit quaternions. */
    fun angleBetween(a: FloatArray, b: FloatArray): Float {
        val d = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).coerceAtMost(1f)
        return 2f * acos(d)
    }

    /**
     * Intersects a ray with a plane.
     * @return the world-space intersection point, or null if the ray is parallel or points away.
     */
    fun rayPlaneIntersection(
        rayOrigin: FloatArray,
        rayDirection: FloatArray,
        planePoint: FloatArray,
        planeNormal: FloatArray
    ): FloatArray? {
        val denom = dot(planeNormal, rayDirection)
        if (abs(denom) < 1e-6f) return null
        val t = dot(planeNormal, sub(planePoint, rayOrigin)) / denom
        if (t < 0f) return null
        return floatArrayOf(
            rayOrigin[0] + rayDirection[0] * t,
            rayOrigin[1] + rayDirection[1] * t,
            rayOrigin[2] + rayDirection[2] * t
        )
    }

    /**
     * Unprojects a screen point to a world-space ray using the inverse of (projection * view).
     * @param inverseViewProjection column-major 4x4 matrix.
     * @return pair of (origin, normalized direction), or null if the matrix is degenerate.
     */
    fun screenPointToRay(
        x: Float,
        y: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        inverseViewProjection: FloatArray
    ): Pair<FloatArray, FloatArray>? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        val ndcX = 2f * x / viewportWidth - 1f
        val ndcY = 1f - 2f * y / viewportHeight
        val near = transformPoint(inverseViewProjection, ndcX, ndcY, -1f) ?: return null
        val far = transformPoint(inverseViewProjection, ndcX, ndcY, 1f) ?: return null
        val dir = sub(far, near)
        if (length(dir) < 1e-6f) return null
        return near to normalize(dir)
    }

    private fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray? {
        val rx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val ry = m[1] * x + m[5] * y + m[9] * z + m[13]
        val rz = m[2] * x + m[6] * y + m[10] * z + m[14]
        val rw = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (abs(rw) < 1e-9f) return null
        return floatArrayOf(rx / rw, ry / rw, rz / rw)
    }
}

/**
 * Exponential pose smoother. Removes small high-frequency anchor corrections while snapping
 * immediately when ARCore makes a large correction (so the sketch never lags far behind reality).
 */
class PoseSmoother(
    private val positionAlpha: Float = 0.35f,
    private val rotationAlpha: Float = 0.35f,
    private val snapDistanceMeters: Float = 0.08f,
    private val snapAngleRadians: Float = 0.12f
) {
    private var position: FloatArray? = null
    private var rotation: FloatArray? = null

    val hasValue: Boolean get() = position != null

    fun reset() {
        position = null
        rotation = null
    }

    /**
     * Feeds a new target pose and returns the smoothed pose as (translation[3], quaternion[4]).
     */
    fun update(targetPosition: FloatArray, targetRotation: FloatArray): Pair<FloatArray, FloatArray> {
        val p = position
        val r = rotation
        if (p == null || r == null) {
            return snap(targetPosition, targetRotation)
        }
        val distance = SketchMath.length(SketchMath.sub(targetPosition, p))
        val angle = SketchMath.angleBetween(r, targetRotation)
        if (distance > snapDistanceMeters || angle > snapAngleRadians) {
            return snap(targetPosition, targetRotation)
        }
        val newPos = floatArrayOf(
            p[0] + (targetPosition[0] - p[0]) * positionAlpha,
            p[1] + (targetPosition[1] - p[1]) * positionAlpha,
            p[2] + (targetPosition[2] - p[2]) * positionAlpha
        )
        val newRot = SketchMath.slerp(r, targetRotation, rotationAlpha)
        position = newPos
        rotation = newRot
        return newPos.copyOf() to newRot.copyOf()
    }

    private fun snap(pos: FloatArray, rot: FloatArray): Pair<FloatArray, FloatArray> {
        val normalized = SketchMath.normalizeQuaternion(rot)
        position = pos.copyOf()
        rotation = normalized
        return pos.copyOf() to normalized.copyOf()
    }
}
