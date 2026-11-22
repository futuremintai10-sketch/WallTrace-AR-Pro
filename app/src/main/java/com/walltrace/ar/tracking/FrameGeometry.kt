package com.walltrace.ar.tracking

import kotlin.math.max

/**
 * Maps between camera-buffer pixels (ImageAnalysis, unrotated sensor orientation) and view pixels
 * (PreviewView in FILL_CENTER). [cropLeft]..[cropH] is the ImageProxy crop rect, which CameraX's
 * ViewPort aligns with what the PreviewView shows.
 */
data class FrameGeometry(
    val cropLeft: Float,
    val cropTop: Float,
    val cropW: Float,
    val cropH: Float,
    val rotationDegrees: Int,
    val viewW: Float,
    val viewH: Float
) {
    init {
        require(cropW > 0f && cropH > 0f && viewW > 0f && viewH > 0f) { "Invalid geometry" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "Unsupported rotation $rotationDegrees" }
    }

    private val swapped = rotationDegrees == 90 || rotationDegrees == 270
    private val uprightW = if (swapped) cropH else cropW
    private val uprightH = if (swapped) cropW else cropH
    private val scale = max(viewW / uprightW, viewH / uprightH)
    private val dx = (viewW - uprightW * scale) / 2f
    private val dy = (viewH - uprightH * scale) / 2f

    fun bufferToView(points: FloatArray): FloatArray {
        val out = FloatArray(points.size)
        var i = 0
        while (i < points.size) {
            val cx = points[i] - cropLeft
            val cy = points[i + 1] - cropTop
            val u: Float
            val v: Float
            when (rotationDegrees) {
                90 -> { u = cropH - cy; v = cx }
                180 -> { u = cropW - cx; v = cropH - cy }
                270 -> { u = cy; v = cropW - cx }
                else -> { u = cx; v = cy }
            }
            out[i] = u * scale + dx
            out[i + 1] = v * scale + dy
            i += 2
        }
        return out
    }

    fun viewToBuffer(points: FloatArray): FloatArray {
        val out = FloatArray(points.size)
        var i = 0
        while (i < points.size) {
            val u = (points[i] - dx) / scale
            val v = (points[i + 1] - dy) / scale
            val cx: Float
            val cy: Float
            when (rotationDegrees) {
                90 -> { cx = v; cy = cropH - u }
                180 -> { cx = cropW - u; cy = cropH - v }
                270 -> { cx = cropW - v; cy = u }
                else -> { cx = u; cy = v }
            }
            out[i] = cx + cropLeft
            out[i + 1] = cy + cropTop
            i += 2
        }
        return out
    }
}
