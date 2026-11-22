package com.walltrace.ar.ar.rendering

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import com.walltrace.ar.model.SketchAdjustments
import java.nio.FloatBuffer

/**
 * Renders the imported image as a textured quad in world space. All image adjustments
 * (opacity, brightness, contrast, saturation, grayscale, black & white, invert, outline / edge
 * detection, white background removal, flips and a tracing grid) are computed per-pixel on the GPU.
 */
class SketchRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var uvAttrib = 0

    private var uMvp = 0
    private var uTexture = 0
    private var uTexelSize = 0
    private var uOpacity = 0
    private var uBrightness = 0
    private var uContrast = 0
    private var uSaturation = 0
    private var uGrayscale = 0
    private var uBlackWhite = 0
    private var uInvert = 0
    private var uOutline = 0
    private var uEdgeThreshold = 0
    private var uEdgeThickness = 0
    private var uLodBias = 0
    private var uRemoveWhite = 0
    private var uFlip = 0
    private var uGrid = 0
    private var uBorder = 0

    private var textureId = 0
    var imageWidth = 0
        private set
    var imageHeight = 0
        private set
    val hasTexture: Boolean get() = textureId != 0 && imageWidth > 0 && imageHeight > 0

    private val quad: FloatBuffer = GlUtil.floatBuffer(
        floatArrayOf(
            // x, y, u, v  (v = 0 is the top row of the bitmap)
            -0.5f, -0.5f, 0f, 1f,
            0.5f, -0.5f, 1f, 1f,
            -0.5f, 0.5f, 0f, 0f,
            0.5f, 0.5f, 1f, 0f
        )
    )

    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttrib = GlUtil.attribute(program, "a_Position")
        uvAttrib = GlUtil.attribute(program, "a_Uv")
        uMvp = GlUtil.uniform(program, "u_Mvp")
        uTexture = GlUtil.uniform(program, "u_Texture")
        uTexelSize = GlUtil.uniform(program, "u_TexelSize")
        uOpacity = GlUtil.uniform(program, "u_Opacity")
        uBrightness = GlUtil.uniform(program, "u_Brightness")
        uContrast = GlUtil.uniform(program, "u_Contrast")
        uSaturation = GlUtil.uniform(program, "u_Saturation")
        uGrayscale = GlUtil.uniform(program, "u_Grayscale")
        uBlackWhite = GlUtil.uniform(program, "u_BlackWhite")
        uInvert = GlUtil.uniform(program, "u_Invert")
        uOutline = GlUtil.uniform(program, "u_Outline")
        uEdgeThreshold = GlUtil.uniform(program, "u_EdgeThreshold")
        uEdgeThickness = GlUtil.uniform(program, "u_EdgeThickness")
        uLodBias = GlUtil.uniform(program, "u_LodBias")
        uRemoveWhite = GlUtil.uniform(program, "u_RemoveWhite")
        uFlip = GlUtil.uniform(program, "u_Flip")
        uGrid = GlUtil.uniform(program, "u_Grid")
        uBorder = GlUtil.uniform(program, "u_Border")
        // GL context was (re)created: any old texture handle is invalid.
        textureId = 0
        imageWidth = 0
        imageHeight = 0
    }

    /** Uploads [bitmap] as the sketch texture, replacing any previous one. Must run on the GL thread. */
    fun setImage(bitmap: Bitmap) {
        require(!bitmap.isRecycled) { "Bitmap is recycled" }
        val upload = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Unable to convert image to ARGB_8888")
        }
        if (textureId == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            textureId = ids[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, upload, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        val error = GLES30.glGetError()
        imageWidth = upload.width
        imageHeight = upload.height
        if (upload !== bitmap) upload.recycle()
        if (error != GLES30.GL_NO_ERROR) {
            throw IllegalStateException("Texture upload failed (GL error $error)")
        }
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        imageWidth = 0
        imageHeight = 0
    }

    fun draw(mvp: FloatArray, adjustments: SketchAdjustments, showBorder: Boolean) {
        if (!hasTexture) return
        val a = adjustments

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(uTexture, 0)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform2f(uTexelSize, 1f / imageWidth, 1f / imageHeight)
        GLES30.glUniform1f(uOpacity, a.opacity)
        GLES30.glUniform1f(uBrightness, a.brightness)
        GLES30.glUniform1f(uContrast, a.contrast)
        GLES30.glUniform1f(uSaturation, a.saturation)
        GLES30.glUniform1i(uGrayscale, if (a.grayscale) 1 else 0)
        GLES30.glUniform1i(uBlackWhite, if (a.blackAndWhite) 1 else 0)
        GLES30.glUniform1i(uInvert, if (a.invert) 1 else 0)
        GLES30.glUniform1i(uOutline, if (a.outline) 1 else 0)
        // Higher strength -> lower gradient threshold -> more edges.
        GLES30.glUniform1f(uEdgeThreshold, 0.45f - 0.38f * a.outlineStrength)
        GLES30.glUniform1f(uEdgeThickness, a.outlineThickness)
        // Sampling a coarser mip level pre-blurs the image: reduces noise and thickens edges.
        GLES30.glUniform1f(uLodBias, (a.outlineThickness - 1f) * 0.6f)
        GLES30.glUniform1i(uRemoveWhite, if (a.removeWhiteBackground) 1 else 0)
        GLES30.glUniform2f(uFlip, if (a.flipHorizontal) 1f else 0f, if (a.flipVertical) 1f else 0f)
        GLES30.glUniform1i(uGrid, if (a.showGrid) 1 else 0)
        GLES30.glUniform1i(uBorder, if (showBorder) 1 else 0)

        quad.position(0)
        GLES30.glVertexAttribPointer(positionAttrib, 2, GLES30.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES30.glVertexAttribPointer(uvAttrib, 2, GLES30.GL_FLOAT, false, 16, quad)
        quad.position(0)
        GLES30.glEnableVertexAttribArray(positionAttrib)
        GLES30.glEnableVertexAttribArray(uvAttrib)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(positionAttrib)
        GLES30.glDisableVertexAttribArray(uvAttrib)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private companion object {
        const val VERTEX_SHADER = """
            #version 300 es
            uniform mat4 u_Mvp;
            in vec2 a_Position;
            in vec2 a_Uv;
            out vec2 v_Uv;
            void main() {
                v_Uv = a_Uv;
                gl_Position = u_Mvp * vec4(a_Position, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;

            uniform sampler2D u_Texture;
            uniform vec2 u_TexelSize;
            uniform float u_Opacity;
            uniform float u_Brightness;
            uniform float u_Contrast;
            uniform float u_Saturation;
            uniform int u_Grayscale;
            uniform int u_BlackWhite;
            uniform int u_Invert;
            uniform int u_Outline;
            uniform float u_EdgeThreshold;
            uniform float u_EdgeThickness;
            uniform float u_LodBias;
            uniform int u_RemoveWhite;
            uniform vec2 u_Flip;
            uniform int u_Grid;
            uniform int u_Border;

            in vec2 v_Uv;
            out vec4 o_Color;

            const vec3 LUMA = vec3(0.299, 0.587, 0.114);
            const vec3 ACCENT = vec3(0.18, 0.77, 0.71);

            // Luminance of a premultiplied texel, compositing transparent areas over white
            // so transparent PNG backgrounds do not create false edges.
            float lumaAt(vec2 uv) {
                vec4 t = texture(u_Texture, uv, u_LodBias);
                vec3 rgb = t.a > 0.0001 ? t.rgb / t.a : vec3(1.0);
                return mix(1.0, dot(rgb, LUMA), t.a);
            }

            void main() {
                vec2 uv = vec2(
                    u_Flip.x > 0.5 ? 1.0 - v_Uv.x : v_Uv.x,
                    u_Flip.y > 0.5 ? 1.0 - v_Uv.y : v_Uv.y
                );

                vec3 rgb;
                float alpha;

                if (u_Outline == 1) {
                    vec2 d = u_TexelSize * u_EdgeThickness;
                    float tl = lumaAt(uv + vec2(-d.x, -d.y));
                    float t  = lumaAt(uv + vec2(0.0, -d.y));
                    float tr = lumaAt(uv + vec2(d.x, -d.y));
                    float l  = lumaAt(uv + vec2(-d.x, 0.0));
                    float r  = lumaAt(uv + vec2(d.x, 0.0));
                    float bl = lumaAt(uv + vec2(-d.x, d.y));
                    float b  = lumaAt(uv + vec2(0.0, d.y));
                    float br = lumaAt(uv + vec2(d.x, d.y));
                    float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
                    float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
                    float g = length(vec2(gx, gy));
                    float edge = smoothstep(u_EdgeThreshold, u_EdgeThreshold + 0.18, g);
                    rgb = vec3(0.0);
                    alpha = edge;
                } else {
                    vec4 texel = texture(u_Texture, uv);
                    alpha = texel.a;
                    rgb = alpha > 0.0001 ? texel.rgb / alpha : vec3(0.0);
                    rgb = (rgb - 0.5) * u_Contrast + 0.5 + u_Brightness;
                    float lum = dot(rgb, LUMA);
                    rgb = mix(vec3(lum), rgb, u_Saturation);
                    if (u_Grayscale == 1) rgb = vec3(lum);
                    if (u_BlackWhite == 1) rgb = vec3(step(0.5, lum));
                    if (u_RemoveWhite == 1) {
                        float lw = dot(clamp(rgb, 0.0, 1.0), LUMA);
                        alpha *= 1.0 - smoothstep(0.78, 0.94, lw);
                    }
                }

                if (u_Invert == 1) rgb = vec3(1.0) - rgb;
                rgb = clamp(rgb, 0.0, 1.0);

                if (u_Grid == 1) {
                    vec2 gp = v_Uv * 8.0;
                    vec2 gd = abs(fract(gp - 0.5) - 0.5) / max(fwidth(gp), vec2(1e-5));
                    float line = 1.0 - clamp(min(gd.x, gd.y), 0.0, 1.0);
                    rgb = mix(rgb, ACCENT, line * 0.9);
                    alpha = max(alpha, line * 0.85);
                }

                float outA = alpha * u_Opacity;

                if (u_Border == 1) {
                    vec2 bw = fwidth(v_Uv) * 2.5;
                    vec2 edgeDist = min(v_Uv, 1.0 - v_Uv);
                    float border = (edgeDist.x < bw.x || edgeDist.y < bw.y) ? 1.0 : 0.0;
                    rgb = mix(rgb, ACCENT, border);
                    outA = max(outA, border);
                }

                o_Color = vec4(rgb * outA, outA);
            }
        """
    }
}
