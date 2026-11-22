package com.walltrace.ar.ar

enum class CameraTracking { INITIALIZING, TRACKING, LIMITED, STOPPED }

enum class TrackingIssue { NONE, BAD_STATE, INSUFFICIENT_LIGHT, EXCESSIVE_MOTION, INSUFFICIENT_FEATURES, CAMERA_UNAVAILABLE }

enum class PlacementState { NONE, PLACED, LOCKED }

/** How the sketch's anchor was obtained. */
enum class PlacementKind {
    /** Anchored to an ARCore vertical plane (best). */
    WALL_PLANE,

    /** Anchored to a depth / feature point with an estimated surface normal. */
    SURFACE_ESTIMATE,

    /** Manual fallback: anchored in front of the camera, not on a detected surface. */
    APPROXIMATE
}

/**
 * Snapshot of genuine ARCore state published from the GL thread to the UI.
 */
data class ArStatus(
    val sessionRunning: Boolean = false,
    val cameraTracking: CameraTracking = CameraTracking.INITIALIZING,
    val issue: TrackingIssue = TrackingIssue.NONE,
    val featurePoints: Int = 0,
    val wallPlanes: Int = 0,
    val wallAreaSquareMeters: Float = 0f,
    val depthEnabled: Boolean = false,
    val scanningSeconds: Int = 0,
    val placement: PlacementState = PlacementState.NONE,
    val placementKind: PlacementKind? = null,
    /** True when the sketch's anchor is currently tracked (position reliable). */
    val anchorTracking: Boolean = false,
    val sketchWidthCm: Int = 0,
    val sketchHeightCm: Int = 0,
    val hasImage: Boolean = false
)
