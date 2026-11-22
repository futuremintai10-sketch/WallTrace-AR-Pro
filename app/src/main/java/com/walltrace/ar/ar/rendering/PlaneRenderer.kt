package com.walltrace.ar.ar.rendering

import android.opengl.GLES30
import android.os.SystemClock
import com.google.ar.core.Plane
import com.walltrace.ar.ar.SketchMath
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Visualizes detected ARCore planes with a feathered fill and a world-locked dot grid.
 * Newly detected planes fade in gradually.
 */
class PlaneRenderer {

    private var program = 0
    private var vertexAttrib = 0
    private var modelUniform = 0
    private var viewProjectionUniform = 0
    private var axisUUniform = 0
    private var axisVUniform = 0
    private var colorUniform = 0
    private var alphaUniform = 0

    private var vertexBuffer: FloatBuffer = GlUtil.floatBuffer(INITIAL_VERTICES * 3)
    private var indexBuffer: ShortBuffer = GlUtil.shortBuffer(INITIAL_VERTICES * 9)

    private val modelMatrix = FloatArray(16)
    private val firstSeen = HashMap<Plane, Long>()

    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        vertexAttrib = GlUtil.attribute(program, "a_XZAlpha")
        modelUniform = GlUtil.uniform(program, "u_Model")
        viewProjectionUniform = GlUtil.uniform(program, "u_ViewProjection")
        axisUUniform = GlUtil.uniform(program, "u_AxisU")
        axisVUniform = GlUtil.uniform(program, "u_AxisV")
        colorUniform = GlUtil.uniform(program, "u_Color")
        alphaUniform = GlUtil.uniform(program, "u_Alpha")
    }

    /** Forget fade state (e.g. after a session reset). */
    fun clear() {
        firstSeen.clear()
    }

    fun draw(planes: Collection<Plane>, viewProjection: FloatArray, globalAlpha: Float) {
        val now = SystemClock.uptimeMillis()
        firstSeen.keys.retainAll(planes.toSet())
        if (planes.isEmpty() || globalAlpha <= 0f) return

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(viewProjectionUniform, 1, false, viewProjection, 0)
        GLES30.glUniform3f(colorUniform, 0.18f, 0.77f, 0.71f)

        for (plane in planes) {
            val seen = firstSeen.getOrPut(plane) { now }
            val fade = ((now - seen) / FADE_IN_MS).coerceIn(0f, 1f)
            if (!buildMesh(plane)) continue

            plane.centerPose.toMatrix(modelMatrix, 0)
            val normal = plane.centerPose.yAxis
            val up = floatArrayOf(0f, 1f, 0f)
            val crossed = SketchMath.cross(up, normal)
            val axisU: FloatArray
            val axisV: FloatArray
            if (SketchMath.length(crossed) > 0.2f) {
                axisU = SketchMath.normalize(crossed)
                axisV = up
            } else {
                axisU = floatArrayOf(1f, 0f, 0f)
                axisV = floatArrayOf(0f, 0f, 1f)
            }

            GLES30.glUniformMatrix4fv(modelUniform, 1, false, modelMatrix, 0)
            GLES30.glUniform3f(axisUUniform, axisU[0], axisU[1], axisU[2])
            GLES30.glUniform3f(axisVUniform, axisV[0], axisV[1], axisV[2])
            GLES30.glUniform1f(alphaUniform, fade * globalAlpha)

            vertexBuffer.position(0)
            indexBuffer.position(0)
            GLES30.glVertexAttribPointer(vertexAttrib, 3, GLES30.GL_FLOAT, false, 12, vertexBuffer)
            GLES30.glEnableVertexAttribArray(vertexAttrib)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexBuffer.limit(), GLES30.GL_UNSIGNED_SHORT, indexBuffer)
            GLES30.glDisableVertexAttribArray(vertexAttrib)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /**
     * Builds a feathered mesh from the plane polygon: an opaque inner polygon and a ring that fades
     * to transparent at the true boundary.
     */
    private fun buildMesh(plane: Plane): Boolean {
        val polygon = plane.polygon
        val n = polygon.remaining() / 2
        if (n < 3) return false
        if (n * 2 > Short.MAX_VALUE) return false

        ensureCapacity(n)
        vertexBuffer.clear()
        indexBuffer.clear()

        val start = polygon.position()
        // Outer ring (alpha 0)
        for (i in 0 until n) {
            val x = polygon.get(start + i * 2)
            val z = polygon.get(start + i * 2 + 1)
            vertexBuffer.put(x); vertexBuffer.put(z); vertexBuffer.put(0f)
        }
        // Inner ring (alpha 1)
        for (i in 0 until n) {
            val x = polygon.get(start + i * 2)
            val z = polygon.get(start + i * 2 + 1)
            val len = sqrt(x * x + z * z)
            val scale = if (len > 1e-4f) max(0.5f, 1f - FEATHER_METERS / len) else 1f
            vertexBuffer.put(x * scale); vertexBuffer.put(z * scale); vertexBuffer.put(1f)
        }
        vertexBuffer.flip()

        // Inner convex fan
        for (i in 1 until n - 1) {
            indexBuffer.put(n.toShort())
            indexBuffer.put((n + i).toShort())
            indexBuffer.put((n + i + 1).toShort())
        }
        // Feather ring
        for (i in 0 until n) {
            val next = (i + 1) % n
            indexBuffer.put(i.toShort())
            indexBuffer.put(next.toShort())
            indexBuffer.put((n + i).toShort())
            indexBuffer.put((n + i).toShort())
            indexBuffer.put(next.toShort())
            indexBuffer.put((n + next).toShort())
        }
        indexBuffer.flip()
        return true
    }

    private fun ensureCapacity(vertices: Int) {
        val neededFloats = vertices * 2 * 3
        if (vertexBuffer.capacity() < neededFloats) {
            vertexBuffer = GlUtil.floatBuffer(neededFloats * 2)
        }
        val neededIndices = (vertices - 2) * 3 + vertices * 6
        if (indexBuffer.capacity() < neededIndices) {
            indexBuffer = GlUtil.shortBuffer(neededIndices * 2)
        }
    }

    private companion object {
        const val INITIAL_VERTICES = 128
        const val FEATHER_METERS = 0.12f
        const val FADE_IN_MS = 700f

        const val VERTEX_SHADER = """
            uniform mat4 u_Model;
            uniform mat4 u_ViewProjection;
            attribute vec3 a_XZAlpha;
            varying vec3 v_WorldPos;
            varying float v_Alpha;
            void main() {
                vec4 world = u_Model * vec4(a_XZAlpha.x, 0.0, a_XZAlpha.y, 1.0);
                v_WorldPos = world.xyz;
                v_Alpha = a_XZAlpha.z;
                gl_Position = u_ViewProjection * world;
            }
        """

        const val FRAGMENT_SHADER = """
            precision highp float;
            uniform vec3 u_AxisU;
            uniform vec3 u_AxisV;
            uniform vec3 u_Color;
            uniform float u_Alpha;
            varying vec3 v_WorldPos;
            varying float v_Alpha;
            void main() {
                vec2 p = vec2(dot(v_WorldPos, u_AxisU), dot(v_WorldPos, u_AxisV));
                vec2 cell = fract(p * 10.0) - vec2(0.5);
                float d = length(cell);
                float dotMask = smoothstep(0.13, 0.07, d);
                float a = (0.10 + 0.55 * dotMask) * v_Alpha * u_Alpha;
                gl_FragColor = vec4(u_Color * a, a);
            }
        """
    }
}
