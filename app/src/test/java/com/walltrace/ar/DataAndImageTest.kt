package com.walltrace.ar

import com.walltrace.ar.data.Project
import com.walltrace.ar.image.ImageLoader
import com.walltrace.ar.model.SketchAdjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataAndImageTest {

    @Test
    fun targetSize_preservesAspectAndLimit() {
        assertEquals(2048 to 1536, ImageLoader.targetSize(4000, 3000, 2048))
        assertEquals(1152 to 2048, ImageLoader.targetSize(2250, 4000, 2048))
        assertEquals(800 to 600, ImageLoader.targetSize(800, 600, 2048))
        assertEquals(2048 to 1, ImageLoader.targetSize(10000, 2, 2048))
    }

    @Test
    fun inSampleSize_neverUndershootsTarget() {
        assertEquals(1, ImageLoader.inSampleSize(2000, 1000, 2048))
        assertEquals(2, ImageLoader.inSampleSize(4096, 3072, 2048))
        assertEquals(4, ImageLoader.inSampleSize(9000, 6000, 2048))
        val s = ImageLoader.inSampleSize(12000, 9000, 2048)
        assertTrue(12000 / s >= 2048)
    }

    @Test
    fun adjustments_jsonRoundTrip() {
        val a = SketchAdjustments(
            opacity = 0.35f, brightness = 0.2f, contrast = 1.4f, saturation = 0.5f,
            grayscale = true, blackAndWhite = false, invert = true, outline = true,
            outlineStrength = 0.8f, outlineThickness = 2.5f, removeWhiteBackground = true,
            flipHorizontal = true, flipVertical = false, showGrid = true
        )
        val restored = SketchAdjustments.fromJson(a.toJson())
        assertEquals(a, restored)
    }

    @Test
    fun adjustments_sanitizeClampsAndRejectsNaN() {
        val s = SketchAdjustments(opacity = 3f, brightness = Float.NaN, contrast = 0f, outlineThickness = 99f).sanitized()
        assertEquals(1f, s.opacity, 0f)
        assertEquals(0f, s.brightness, 0f)
        assertEquals(0.2f, s.contrast, 0f)
        assertEquals(4f, s.outlineThickness, 0f)
    }

    @Test
    fun resetImageAdjustments_keepsOpacity() {
        val s = SketchAdjustments(opacity = 0.3f, outline = true, contrast = 1.8f).resetImageAdjustments()
        assertEquals(SketchAdjustments(opacity = 0.3f), s)
    }

    @Test
    fun projects_roundTripAndRejectUnsafeFileNames() {
        val p = Project("id-1", "Mural", "id-1.img", 10L, 20L, SketchAdjustments(opacity = 0.4f))
        val list = Project.listFromJson(Project.listToJson(listOf(p)))
        assertEquals(listOf(p), list)

        val bad = p.copy(imageFileName = "../../databases/x")
        assertNull(Project.fromJson(bad.toJson()))
        assertTrue(Project.listFromJson("").isEmpty())
    }
}
