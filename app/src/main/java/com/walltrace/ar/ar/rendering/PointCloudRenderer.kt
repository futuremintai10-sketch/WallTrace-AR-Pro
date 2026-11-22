package com.walltrace.ar.ar.rendering

import android.opengl.GLES30
import com.google.ar.core.PointCloud

/**
 * Draws ARCore's genuinely tracked feature points as small, smooth, round dots.
 * Dot opacity follows ARCore's per-point confidence; low-confidence points are hidden.
 */
class PointCloudRenderer(private val density: Float) {

    private var program = 0
    private var positionAttrib = 0
    private var viewUniform = 0
    private var projectionUniform = 0
    private var pointSizeUniform = 0
    private var colorUniform = 0
    private var alphaUniform = 0

    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttrib = GlUtil.attribute(program, "a_Position")
        viewUniform = GlUtil.uniform(program, "u_View")
        projectionUniform = GlUtil.uniform(program, "u_Projection")
        pointSizeUniform = GlUtil.uniform(program, "u_PointSize")
        colorUniform = GlUtil.uniform(program, "u_Color")
        alphaUniform = GlUtil.uniform(program, "u_Alpha")
    }

    /**
     * @return number of points drawn (points with confidence data from ARCore).
     */
    fun draw(pointCloud: PointCloud, view: FloatArray, projection: FloatArray, alpha: Float): Int {
        val points = pointCloud.points
        val count = points.remaining() / 4
        if (count == 0 || alpha <= 0f) return count

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(viewUniform, 1, false, view, 0)
        GLES30.glUniformMatrix4fv(projectionUniform, 1, false, projection, 0)
        GLES30.glUniform1f(pointSizeUniform, 7f * density)
        GLES30.glUniform3f(colorUniform, 0.85f, 1.0f, 0.97f)
        GLES30.glUniform1f(alphaUniform, alpha)

        GLES30.glVertexAttribPointer(positionAttrib, 4, GLES30.GL_FLOAT, false, 16, points)
        GLES30.glEnableVertexAttribArray(positionAttrib)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
        GLES30.glDisableVertexAttribArray(positionAttrib)

        GLES30.glDisable(GLES30.GL_BLEND)
        return count
    }

    private companion object {
        const val VERTEX_SHADER = """
            uniform mat4 u_View;
            uniform mat4 u_Projection;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            varying float v_Confidence;
            void main() {
                vec4 viewPos = u_View * vec4(a_Position.xyz, 1.0);
                gl_Position = u_Projection * viewPos;
                float dist = max(0.25, -viewPos.z);
                gl_PointSize = clamp(u_PointSize / dist, 3.0, 18.0);
                v_Confidence = a_Position.w;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 u_Color;
            uniform float u_Alpha;
            varying float v_Confidence;
            void main() {
                if (v_Confidence < 0.15) discard;
                vec2 c = gl_PointCoord - vec2(0.5);
                float r = length(c);
                if (r > 0.5) discard;
                float edge = smoothstep(0.5, 0.25, r);
                float a = edge * mix(0.35, 0.95, clamp(v_Confidence, 0.0, 1.0)) * u_Alpha;
                gl_FragColor = vec4(u_Color * a, a);
            }
        """
    }
}
