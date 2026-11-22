package com.walltrace.ar.image

import com.walltrace.ar.model.SketchAdjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchProcessorTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun alpha(p: Int) = (p ushr 24) and 0xFF
    private fun red(p: Int) = (p shr 16) and 0xFF

    @Test
    fun defaultAdjustments_keepPixelsUnchanged() {
        val src = intArrayOf(argb(255, 10, 128, 250), argb(128, 200, 50, 0), argb(0, 0, 0, 0))
        val out = SketchProcessor.process(src, 3, 1, SketchAdjustments())
        assertEquals(src.toList(), out.toList())
    }

    @Test
    fun invert_flipsColourKeepsAlpha() {
        val out = SketchProcessor.process(intArrayOf(argb(200, 0, 255, 100)), 1, 1, SketchAdjustments(invert = true))
        assertEquals(argb(200, 255, 0, 155), out[0])
    }

    @Test
    fun blackAndWhite_thresholdsLuminance() {
        val src = intArrayOf(argb(255, 30, 30, 30), argb(255, 220, 220, 220))
        val out = SketchProcessor.process(src, 2, 1, SketchAdjustments(blackAndWhite = true))
        assertEquals(argb(255, 0, 0, 0), out[0])
        assertEquals(argb(255, 255, 255, 255), out[1])
    }

    @Test
    fun removeWhite_makesWhiteTransparentKeepsDark() {
        val src = intArrayOf(argb(255, 255, 255, 255), argb(255, 20, 20, 20))
        val out = SketchProcessor.process(src, 2, 1, SketchAdjustments(removeWhiteBackground = true))
        assertEquals(0, alpha(out[0]))
        assertEquals(255, alpha(out[1]))
    }

    @Test
    fun outline_findsEdgeOfBlackSquareOnly() {
        val w = 20
        val h = 20
        val src = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 6..13 && y in 6..13) argb(255, 0, 0, 0) else argb(255, 255, 255, 255)
        }
        val out = SketchProcessor.process(src, w, h, SketchAdjustments(outline = true, outlineThickness = 1f))
        // Flat areas (far from the square border) produce no line.
        assertEquals(0, alpha(out[1 * w + 1]))
        assertEquals(0, alpha(out[10 * w + 10]))
        // The border produces an opaque black line.
        val edge = out[10 * w + 6]
        assertTrue(alpha(edge) > 200)
        assertEquals(0, red(edge))
    }

    @Test
    fun outline_invertGivesWhiteLines() {
        val w = 10
        val src = IntArray(w * w) { i -> if (i % w < 5) argb(255, 0, 0, 0) else argb(255, 255, 255, 255) }
        val out = SketchProcessor.process(src, w, w, SketchAdjustments(outline = true, invert = true, outlineThickness = 1f))
        val edge = out[5 * w + 5]
        assertTrue(alpha(edge) > 200)
        assertEquals(255, red(edge))
    }

    @Test
    fun boxBlur_preservesConstantImageAndAverages() {
        val flat = FloatArray(16) { 0.5f }
        SketchProcessor.boxBlur(flat, 4, 4, 1).forEach { assertEquals(0.5f, it, 1e-6f) }
        val impulse = FloatArray(9).also { it[4] = 9f }
        val blurred = SketchProcessor.boxBlur(impulse, 3, 3, 1)
        assertEquals(1f, blurred[4], 1e-5f)
    }

    @Test(expected = SketchProcessor.Cancelled::class)
    fun cancellation_isCooperative() {
        SketchProcessor.process(IntArray(4), 2, 2, SketchAdjustments(outline = true)) { false }
    }
}
