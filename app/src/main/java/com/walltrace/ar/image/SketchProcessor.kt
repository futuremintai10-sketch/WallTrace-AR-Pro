package com.walltrace.ar.image

import com.walltrace.ar.model.SketchAdjustments
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * CPU implementation of the sketch adjustments, used by Overlay mode (no OpenGL scene).
 * It mirrors the AR fragment shader: colour adjustments, black & white, white removal,
 * invert and Sobel outline extraction. Opacity, flips and the grid are applied by the view.
 *
 * Input and output are non-premultiplied ARGB pixels as returned by Bitmap.getPixels.
 */
object SketchProcessor {

    /** Thrown when processing is abandoned because a newer request superseded it. */
    class Cancelled : CancellationException("Processing cancelled")

    fun process(
        pixels: IntArray,
        width: Int,
        height: Int,
        a: SketchAdjustments,
        isActive: () -> Boolean = { true }
    ): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Invalid pixel buffer" }
        return if (a.outline) outline(pixels, width, height, a, isActive) else adjust(pixels, a, isActive)
    }

    private fun adjust(src: IntArray, a: SketchAdjustments, isActive: () -> Boolean): IntArray {
        val out = IntArray(src.size)
        for (i in src.indices) {
            if ((i and 0xFFFF) == 0 && !isActive()) throw Cancelled()
            val p = src[i]
            var alpha = ((p ushr 24) and 0xFF) / 255f
            var r = ((p shr 16) and 0xFF) / 255f
            var g = ((p shr 8) and 0xFF) / 255f
            var b = (p and 0xFF) / 255f

            r = (r - 0.5f) * a.contrast + 0.5f + a.brightness
            g = (g - 0.5f) * a.contrast + 0.5f + a.brightness
            b = (b - 0.5f) * a.contrast + 0.5f + a.brightness
            val lum = luma(r, g, b)
            r = lum + (r - lum) * a.saturation
            g = lum + (g - lum) * a.saturation
            b = lum + (b - lum) * a.saturation
            if (a.grayscale) { r = lum; g = lum; b = lum }
            if (a.blackAndWhite) {
                val v = if (lum >= 0.5f) 1f else 0f
                r = v; g = v; b = v
            }
            if (a.removeWhiteBackground) {
                val lw = luma(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
                alpha *= 1f - smoothstep(0.78f, 0.94f, lw)
            }
            if (a.invert) { r = 1f - r; g = 1f - g; b = 1f - b }
            out[i] = argb(alpha, r, g, b)
        }
        return out
    }

    private fun outline(src: IntArray, w: Int, h: Int, a: SketchAdjustments, isActive: () -> Boolean): IntArray {
        // Luminance with transparent areas composited over white (avoids false edges on PNG cut-outs).
        val lum = FloatArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            val alpha = ((p ushr 24) and 0xFF) / 255f
            val l = luma(((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f)
            lum[i] = 1f + (l - 1f) * alpha
        }
        if (!isActive()) throw Cancelled()

        // Noise reduction: blur grows with line thickness (matches the shader's mip bias).
        val blurRadius = (a.outlineThickness - 1f).roundToInt()
        val smooth = if (blurRadius > 0) boxBlur(lum, w, h, blurRadius, isActive) else lum
        val d = max(1, a.outlineThickness.roundToInt())
        val threshold = 0.45f - 0.38f * a.outlineStrength
        val lineValue = if (a.invert) 1f else 0f

        val out = IntArray(src.size)
        for (y in 0 until h) {
            if (!isActive()) throw Cancelled()
            val yU = max(0, y - d)
            val yD = min(h - 1, y + d)
            for (x in 0 until w) {
                val xL = max(0, x - d)
                val xR = min(w - 1, x + d)
                val tl = smooth[yU * w + xL]
                val t = smooth[yU * w + x]
                val tr = smooth[yU * w + xR]
                val l = smooth[y * w + xL]
                val r = smooth[y * w + xR]
                val bl = smooth[yD * w + xL]
                val b = smooth[yD * w + x]
                val br = smooth[yD * w + xR]
                val gx = -tl - 2f * l - bl + tr + 2f * r + br
                val gy = -tl - 2f * t - tr + bl + 2f * b + br
                val g = sqrt(gx * gx + gy * gy)
                val edge = smoothstep(threshold, threshold + 0.18f, g)
                out[y * w + x] = argb(edge, lineValue, lineValue, lineValue)
            }
        }
        return out
    }

    /** Separable box blur with clamped edges. */
    internal fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int, isActive: () -> Boolean = { true }): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val size = (2 * radius + 1).toFloat()
        for (y in 0 until h) {
            if (!isActive()) throw Cancelled()
            val row = y * w
            for (x in 0 until w) {
                var sum = 0f
                for (k in -radius..radius) sum += src[row + (x + k).coerceIn(0, w - 1)]
                tmp[row + x] = sum / size
            }
        }
        for (y in 0 until h) {
            if (!isActive()) throw Cancelled()
            for (x in 0 until w) {
                var sum = 0f
                for (k in -radius..radius) sum += tmp[(y + k).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = sum / size
            }
        }
        return out
    }

    private fun luma(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun argb(alpha: Float, r: Float, g: Float, b: Float): Int {
        val ai = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        val ri = (r.coerceIn(0f, 1f) * 255f).roundToInt()
        val gi = (g.coerceIn(0f, 1f) * 255f).roundToInt()
        val bi = (b.coerceIn(0f, 1f) * 255f).roundToInt()
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
