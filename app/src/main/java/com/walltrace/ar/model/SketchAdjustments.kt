package com.walltrace.ar.model

import org.json.JSONObject

/**
 * All user-adjustable rendering settings for the sketch. Applied in real time by the GPU shader.
 */
data class SketchAdjustments(
    /** 0..1 */
    val opacity: Float = 0.6f,
    /** -0.5..0.5, 0 = unchanged */
    val brightness: Float = 0f,
    /** 0.2..2, 1 = unchanged */
    val contrast: Float = 1f,
    /** 0..2, 1 = unchanged */
    val saturation: Float = 1f,
    val grayscale: Boolean = false,
    val blackAndWhite: Boolean = false,
    val invert: Boolean = false,
    val outline: Boolean = false,
    /** 0..1, higher shows more (fainter) edges */
    val outlineStrength: Float = 0.5f,
    /** 1..4 texel radius */
    val outlineThickness: Float = 1.5f,
    val removeWhiteBackground: Boolean = false,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val showGrid: Boolean = false
) {
    fun sanitized(): SketchAdjustments = copy(
        opacity = opacity.finiteOr(0.6f).coerceIn(0f, 1f),
        brightness = brightness.finiteOr(0f).coerceIn(-0.5f, 0.5f),
        contrast = contrast.finiteOr(1f).coerceIn(0.2f, 2f),
        saturation = saturation.finiteOr(1f).coerceIn(0f, 2f),
        outlineStrength = outlineStrength.finiteOr(0.5f).coerceIn(0f, 1f),
        outlineThickness = outlineThickness.finiteOr(1.5f).coerceIn(1f, 4f)
    )

    /** Resets image adjustments but keeps the user's opacity choice. */
    fun resetImageAdjustments(): SketchAdjustments = SketchAdjustments(opacity = opacity)

    fun toJson(): JSONObject = JSONObject().apply {
        put("opacity", opacity.toDouble())
        put("brightness", brightness.toDouble())
        put("contrast", contrast.toDouble())
        put("saturation", saturation.toDouble())
        put("grayscale", grayscale)
        put("blackAndWhite", blackAndWhite)
        put("invert", invert)
        put("outline", outline)
        put("outlineStrength", outlineStrength.toDouble())
        put("outlineThickness", outlineThickness.toDouble())
        put("removeWhiteBackground", removeWhiteBackground)
        put("flipHorizontal", flipHorizontal)
        put("flipVertical", flipVertical)
        put("showGrid", showGrid)
    }

    companion object {
        fun fromJson(json: JSONObject?): SketchAdjustments {
            if (json == null) return SketchAdjustments()
            val d = SketchAdjustments()
            return SketchAdjustments(
                opacity = json.optDouble("opacity", d.opacity.toDouble()).toFloat(),
                brightness = json.optDouble("brightness", d.brightness.toDouble()).toFloat(),
                contrast = json.optDouble("contrast", d.contrast.toDouble()).toFloat(),
                saturation = json.optDouble("saturation", d.saturation.toDouble()).toFloat(),
                grayscale = json.optBoolean("grayscale", d.grayscale),
                blackAndWhite = json.optBoolean("blackAndWhite", d.blackAndWhite),
                invert = json.optBoolean("invert", d.invert),
                outline = json.optBoolean("outline", d.outline),
                outlineStrength = json.optDouble("outlineStrength", d.outlineStrength.toDouble()).toFloat(),
                outlineThickness = json.optDouble("outlineThickness", d.outlineThickness.toDouble()).toFloat(),
                removeWhiteBackground = json.optBoolean("removeWhiteBackground", d.removeWhiteBackground),
                flipHorizontal = json.optBoolean("flipHorizontal", d.flipHorizontal),
                flipVertical = json.optBoolean("flipVertical", d.flipVertical),
                showGrid = json.optBoolean("showGrid", d.showGrid)
            ).sanitized()
        }
    }
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
