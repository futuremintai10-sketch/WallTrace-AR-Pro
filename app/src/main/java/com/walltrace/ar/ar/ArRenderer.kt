package com.walltrace.ar.ar

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.walltrace.ar.ar.rendering.BackgroundRenderer
import com.walltrace.ar.ar.rendering.PlaneRenderer
import com.walltrace.ar.ar.rendering.PointCloudRenderer
import com.walltrace.ar.ar.rendering.SketchRenderer
import com.walltrace.ar.model.SketchAdjustments
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/** User intents forwarded from the UI thread and executed on the GL thread with the current frame. */
sealed interface ArCommand {
    data class Tap(val x: Float, val y: Float) : ArCommand
    data class DragStart(val x: Float, val y: Float) : ArCommand
    data class DragMove(val x: Float, val y: Float) : ArCommand
    data object DragEnd : ArCommand
    data class Scale(val factor: Float) : ArCommand
    data class Rotate(val deltaRadians: Float) : ArCommand
    data object Lock : ArCommand
    data object Unlock : ArCommand
    data object ResetPlacement : ArCommand
    data object PlaceApproximate : ArCommand
}

/**
 * Renders the AR scene and owns all world-anchoring logic. Every method that touches ARCore runs
 * on the GL thread; the UI communicates via [submit], [setImage], [adjustments] and observes
 * [status] / [messages].
 */
class ArRenderer(
    density: Float,
    private val displayRotationProvider: () -> Int
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null

    @Volatile
    var depthEnabled: Boolean = false

    @Volatile
    var adjustments: SketchAdjustments = SketchAdjustments()

    private val commands = ConcurrentLinkedQueue<ArCommand>()
    private val pendingImage = AtomicReference<Bitmap?>(null)

    /** Last image handed to the renderer; re-uploaded if the GL context is recreated. */
    @Volatile
    private var currentImage: Bitmap? = null

    private val _status = MutableStateFlow(ArStatus())
    val status: StateFlow<ArStatus> = _status.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val background = BackgroundRenderer()
    private val pointCloudRenderer = PointCloudRenderer(density)
    private val planeRenderer = PlaneRenderer()
    private val sketchRenderer = SketchRenderer()

    // Viewport / display geometry
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var geometryDirty = true
    private var geometrySession: Session? = null
    private var cameraTextureSession: Session? = null

    // Matrices (reused every frame, no allocations)
    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val inverseViewProjection = FloatArray(16)
    private val anchorMatrix = FloatArray(16)
    private val localMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvp = FloatArray(16)

    // Sketch placement state (GL thread only)
    private var stateSession: Session? = null
    private var anchor: Anchor? = null
    private var anchorTrackable: Trackable? = null
    private var placementKind: PlacementKind? = null
    private var locked = false
    private var offsetX = 0f
    private var offsetY = 0f
    private var widthMeters = DEFAULT_WIDTH_METERS
    private var rotationRadians = 0f
    private var dragGrab: FloatArray? = null
    private val smoother = PoseSmoother()

    // Status bookkeeping
    private var scanStartMs = 0L
    private var lastPublishMs = 0L
    private var lastAnchorTracking = false

    fun submit(command: ArCommand) {
        commands.add(command)
    }

    /** Replaces the sketch image without touching the anchor. The bitmap must not be recycled by the caller while in use. */
    fun setImage(bitmap: Bitmap) {
        currentImage = bitmap
        pendingImage.set(bitmap)
    }

    // ---------------------------------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // ---------------------------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        pointCloudRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
        sketchRenderer.createOnGlThread()
        cameraTextureSession = null
        geometryDirty = true
        currentImage?.let { pendingImage.compareAndSet(null, it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES30.glViewport(0, 0, width, height)
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val session = this.session ?: return

        if (stateSession !== session) {
            // A new ARCore session: anchors of the previous one are no longer valid.
            clearPlacementState(detach = false)
            planeRenderer.clear()
            stateSession = session
            scanStartMs = 0L
        }
        if (cameraTextureSession !== session) {
            session.setCameraTextureName(background.cameraTextureId)
            cameraTextureSession = session
        }
        if (geometryDirty || geometrySession !== session) {
            if (viewportWidth > 0 && viewportHeight > 0) {
                session.setDisplayGeometry(displayRotationProvider(), viewportWidth, viewportHeight)
                geometryDirty = false
                geometrySession = session
            }
        }

        uploadPendingImage()

        val frame: Frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Camera not available during update", e)
            publish(_status.value.copy(sessionRunning = false, issue = TrackingIssue.CAMERA_UNAVAILABLE), force = true)
            return
        } catch (e: SessionPausedException) {
            Log.d(TAG, "Session paused while rendering; skipping frame")
            return
        }

        background.draw(frame)
        val camera = frame.camera
        val cameraTracking = camera.trackingState == TrackingState.TRACKING

        if (cameraTracking) {
            camera.getProjectionMatrix(projection, 0, NEAR, FAR)
            camera.getViewMatrix(view, 0)
            Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
            if (!Matrix.invertM(inverseViewProjection, 0, viewProjection, 0)) {
                Matrix.setIdentityM(inverseViewProjection, 0)
            }
        }

        processCommands(session, frame, camera, cameraTracking)

        val now = SystemClock.uptimeMillis()
        var featurePoints = 0
        var wallPlanes = 0
        var wallArea = 0f

        if (cameraTracking) {
            if (scanStartMs == 0L) scanStartMs = now

            val walls = session.getAllTrackables(Plane::class.java).filter {
                it.trackingState == TrackingState.TRACKING &&
                    it.subsumedBy == null &&
                    it.type == Plane.Type.VERTICAL
            }
            wallPlanes = walls.size
            for (p in walls) wallArea += p.extentX * p.extentZ

            // While positioning, show scanning feedback. After locking, keep the view clean for drawing.
            val scanAlpha = when {
                locked -> 0f
                anchor != null -> 0.45f
                else -> 1f
            }
            planeRenderer.draw(walls, viewProjection, scanAlpha)
            frame.acquirePointCloud().use { cloud ->
                featurePoints = pointCloudRenderer.draw(cloud, view, projection, scanAlpha)
            }

            drawSketch()
        }

        val anchorState = anchor?.trackingState
        if (anchorState == TrackingState.STOPPED) {
            clearPlacementState(detach = true)
            _messages.tryEmit("Lost the sketch position. Tap the wall to place it again.")
        }

        val anchorTracking = anchor?.trackingState == TrackingState.TRACKING && cameraTracking
        if (lastAnchorTracking && !anchorTracking && anchor != null) {
            Log.i(TAG, "Anchor tracking interrupted; keeping anchor and waiting for recovery")
        }
        lastAnchorTracking = anchorTracking

        val heightMeters = currentAspect() * widthMeters
        val status = ArStatus(
            sessionRunning = true,
            cameraTracking = when (camera.trackingState) {
                TrackingState.TRACKING -> CameraTracking.TRACKING
                TrackingState.PAUSED -> if (scanStartMs == 0L) CameraTracking.INITIALIZING else CameraTracking.LIMITED
                TrackingState.STOPPED -> CameraTracking.STOPPED
            },
            issue = if (cameraTracking) TrackingIssue.NONE else mapFailure(camera.trackingFailureReason),
            featurePoints = featurePoints,
            wallPlanes = wallPlanes,
            wallAreaSquareMeters = wallArea,
            depthEnabled = depthEnabled,
            scanningSeconds = if (scanStartMs == 0L) 0 else ((now - scanStartMs) / 1000L).toInt(),
            placement = when {
                anchor == null -> PlacementState.NONE
                locked -> PlacementState.LOCKED
                else -> PlacementState.PLACED
            },
            placementKind = placementKind,
            anchorTracking = anchorTracking,
            sketchWidthCm = (widthMeters * 100f).roundToInt(),
            sketchHeightCm = (heightMeters * 100f).roundToInt(),
            hasImage = sketchRenderer.hasTexture
        )
        publish(status, force = false)
    }

    // ---------------------------------------------------------------------------------------------
    // Sketch drawing
    // ---------------------------------------------------------------------------------------------

    private fun drawSketch() {
        val a = anchor ?: return
        if (!sketchRenderer.hasTexture) return

        val pose = a.pose
        val smoothed = if (a.trackingState == TrackingState.TRACKING || !smoother.hasValue) {
            smoother.update(pose.translation, pose.rotationQuaternion)
        } else {
            null
        }
        if (smoothed != null) {
            Pose(smoothed.first, smoothed.second).toMatrix(anchorMatrix, 0)
        }
        // If the anchor is paused we keep drawing at the last smoothed pose (anchorMatrix unchanged).

        val heightMeters = currentAspect() * widthMeters
        Matrix.setIdentityM(localMatrix, 0)
        Matrix.translateM(localMatrix, 0, offsetX, offsetY, SURFACE_OFFSET_METERS)
        Matrix.rotateM(localMatrix, 0, Math.toDegrees(rotationRadians.toDouble()).toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(localMatrix, 0, widthMeters, heightMeters, 1f)
        Matrix.multiplyMM(modelMatrix, 0, anchorMatrix, 0, localMatrix, 0)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, modelMatrix, 0)

        sketchRenderer.draw(mvp, adjustments, showBorder = !locked)
    }

    private fun currentAspect(): Float {
        val w = sketchRenderer.imageWidth
        val h = sketchRenderer.imageHeight
        return if (w > 0 && h > 0) h.toFloat() / w.toFloat() else 1f
    }

    private fun uploadPendingImage() {
        val bitmap = pendingImage.getAndSet(null) ?: return
        if (bitmap.isRecycled) {
            Log.w(TAG, "Pending image was recycled before upload")
            _messages.tryEmit("Couldn't display that image. Please import it again.")
            return
        }
        try {
            sketchRenderer.setImage(bitmap)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Texture upload failed", e)
            _messages.tryEmit("Couldn't display that image on this device.")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------------

    private fun processCommands(session: Session, frame: Frame, camera: Camera, tracking: Boolean) {
        // Only the latest drag-move matters for this frame; keep order for everything else.
        var pendingMove: ArCommand.DragMove? = null
        while (true) {
            val cmd = commands.poll() ?: break
            if (cmd is ArCommand.DragMove) {
                pendingMove = cmd
                continue
            }
            pendingMove?.let { handleDragMove(it, tracking); pendingMove = null }
            when (cmd) {
                is ArCommand.Tap -> handleTap(frame, camera, cmd.x, cmd.y, tracking)
                is ArCommand.DragStart -> handleDragStart(cmd, tracking)
                ArCommand.DragEnd -> dragGrab = null
                is ArCommand.Scale -> if (!locked && anchor != null) {
                    widthMeters = (widthMeters * cmd.factor).coerceIn(MIN_WIDTH_METERS, MAX_WIDTH_METERS)
                }
                is ArCommand.Rotate -> if (!locked && anchor != null) {
                    // Screen angles grow clockwise; the sketch's +Z faces the viewer, so negate.
                    rotationRadians -= cmd.deltaRadians
                }
                ArCommand.Lock -> handleLock(session, tracking)
                ArCommand.Unlock -> if (anchor != null) {
                    locked = false
                    _messages.tryEmit("Unlocked. Drag, pinch or twist to adjust.")
                }
                ArCommand.ResetPlacement -> {
                    clearPlacementState(detach = true)
                    widthMeters = DEFAULT_WIDTH_METERS
                    rotationRadians = 0f
                }
                ArCommand.PlaceApproximate -> handlePlaceApproximate(session, camera, tracking)
                is ArCommand.DragMove -> Unit // handled above
            }
        }
        pendingMove?.let { handleDragMove(it, tracking) }
    }

    private fun handleTap(frame: Frame, camera: Camera, x: Float, y: Float, tracking: Boolean) {
        if (locked) {
            _messages.tryEmit("Sketch is locked. Tap Unlock to move it.")
            return
        }
        if (!sketchRenderer.hasTexture) {
            _messages.tryEmit("Import an image first.")
            return
        }
        if (!tracking) {
            _messages.tryEmit("Tracking isn't ready yet. Move the phone slowly.")
            return
        }
        val candidate = chooseHit(frame.hitTest(x, y))
        if (candidate == null) {
            _messages.tryEmit("No wall detected at that spot yet. Keep scanning, or use Place approximately.")
            return
        }
        val (hit, kind) = candidate
        val hitPose = hit.hitPose
        val position = hitPose.translation
        val cameraPose = camera.pose
        val rotation = SketchMath.wallOrientation(
            surfaceNormal = hitPose.yAxis,
            toCamera = SketchMath.sub(cameraPose.translation, position),
            cameraRight = cameraPose.xAxis
        )
        val newAnchor = try {
            hit.trackable.createAnchor(Pose(position, rotation))
        } catch (e: Exception) {
            Log.w(TAG, "createAnchor failed", e)
            _messages.tryEmit("Couldn't attach to the wall. Hold steady and try again.")
            return
        }
        replaceAnchor(newAnchor, hit.trackable, kind)
        offsetX = 0f
        offsetY = 0f
        _messages.tryEmit(
            if (kind == PlacementKind.WALL_PLANE) "Placed on wall. Adjust, then tap LOCK SKETCH."
            else "Placed on the surface. Adjust, then tap LOCK SKETCH."
        )
    }

    private fun chooseHit(hits: List<HitResult>): Pair<HitResult, PlacementKind>? {
        var fallback: Pair<HitResult, PlacementKind>? = null
        for (hit in hits) {
            val trackable = hit.trackable
            if (trackable.trackingState != TrackingState.TRACKING) continue
            when (trackable) {
                is Plane -> if (
                    trackable.type == Plane.Type.VERTICAL &&
                    trackable.subsumedBy == null &&
                    trackable.isPoseInPolygon(hit.hitPose)
                ) {
                    return hit to PlacementKind.WALL_PLANE
                }
                is DepthPoint -> if (fallback == null && SketchMath.isWallLike(hit.hitPose.yAxis)) {
                    fallback = hit to PlacementKind.SURFACE_ESTIMATE
                }
                is Point -> if (
                    fallback == null &&
                    trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL &&
                    SketchMath.isWallLike(hit.hitPose.yAxis)
                ) {
                    fallback = hit to PlacementKind.SURFACE_ESTIMATE
                }
            }
        }
        return fallback
    }

    private fun handlePlaceApproximate(session: Session, camera: Camera, tracking: Boolean) {
        if (!sketchRenderer.hasTexture) {
            _messages.tryEmit("Import an image first.")
            return
        }
        if (locked) {
            _messages.tryEmit("Sketch is locked. Tap Unlock first.")
            return
        }
        if (!tracking) {
            _messages.tryEmit("Tracking isn't ready yet. Move the phone slowly.")
            return
        }
        val cameraPose = camera.pose
        val camPos = cameraPose.translation
        val z = cameraPose.zAxis
        val forward = floatArrayOf(-z[0], -z[1], -z[2])
        val position = floatArrayOf(
            camPos[0] + forward[0] * APPROXIMATE_DISTANCE_METERS,
            camPos[1] + forward[1] * APPROXIMATE_DISTANCE_METERS,
            camPos[2] + forward[2] * APPROXIMATE_DISTANCE_METERS
        )
        val horizontal = floatArrayOf(forward[0], 0f, forward[2])
        val normal = if (SketchMath.length(horizontal) > 0.3f) {
            val h = SketchMath.normalize(horizontal)
            floatArrayOf(-h[0], -h[1], -h[2])
        } else {
            floatArrayOf(-forward[0], -forward[1], -forward[2])
        }
        val rotation = SketchMath.wallOrientation(normal, SketchMath.sub(camPos, position), cameraPose.xAxis)
        val newAnchor = try {
            session.createAnchor(Pose(position, rotation))
        } catch (e: Exception) {
            Log.w(TAG, "Approximate anchor failed", e)
            _messages.tryEmit("Couldn't place the sketch right now. Try again.")
            return
        }
        replaceAnchor(newAnchor, null, PlacementKind.APPROXIMATE)
        offsetX = 0f
        offsetY = 0f
        _messages.tryEmit("Placed approximately (not on a detected wall). Position may drift.")
    }

    private fun handleDragStart(cmd: ArCommand.DragStart, tracking: Boolean) {
        if (locked || anchor == null || !tracking) {
            dragGrab = null
            return
        }
        val local = screenToSketchPlane(cmd.x, cmd.y)
        dragGrab = local?.let { floatArrayOf(offsetX - it[0], offsetY - it[1]) }
    }

    private fun handleDragMove(cmd: ArCommand.DragMove, tracking: Boolean) {
        val grab = dragGrab ?: return
        if (locked || anchor == null || !tracking) return
        val local = screenToSketchPlane(cmd.x, cmd.y) ?: return
        offsetX = (local[0] + grab[0]).coerceIn(-MAX_OFFSET_METERS, MAX_OFFSET_METERS)
        offsetY = (local[1] + grab[1]).coerceIn(-MAX_OFFSET_METERS, MAX_OFFSET_METERS)
    }

    /** Intersects the touch ray with the sketch's surface plane; returns anchor-local (x, y). */
    private fun screenToSketchPlane(x: Float, y: Float): FloatArray? {
        val a = anchor ?: return null
        val ray = SketchMath.screenPointToRay(x, y, viewportWidth, viewportHeight, inverseViewProjection)
            ?: return null
        val pose = a.pose
        val origin = pose.translation
        val hit = SketchMath.rayPlaneIntersection(ray.first, ray.second, origin, pose.zAxis) ?: return null
        val d = SketchMath.sub(hit, origin)
        return floatArrayOf(SketchMath.dot(d, pose.xAxis), SketchMath.dot(d, pose.yAxis))
    }

    private fun handleLock(session: Session, tracking: Boolean) {
        val current = anchor
        if (current == null) {
            _messages.tryEmit("Place the sketch on a wall first.")
            return
        }
        if (locked) return
        if (!tracking || current.trackingState != TrackingState.TRACKING) {
            _messages.tryEmit("Tracking isn't stable right now. Point at the wall and try again.")
            return
        }
        // Re-anchor exactly at the final sketch centre so the anchor sits where the sketch is.
        val centerPose = current.pose.compose(Pose.makeTranslation(offsetX, offsetY, 0f))
        val trackable = anchorTrackable?.takeIf { it.trackingState == TrackingState.TRACKING }
        val newAnchor = try {
            trackable?.createAnchor(centerPose) ?: session.createAnchor(centerPose)
        } catch (e: Exception) {
            Log.w(TAG, "Lock anchor creation failed", e)
            null
        }
        if (newAnchor == null || newAnchor.trackingState != TrackingState.TRACKING) {
            newAnchor?.detach()
            _messages.tryEmit("Couldn't lock right now. Hold steady and try again.")
            return
        }
        current.detach()
        anchor = newAnchor
        anchorTrackable = trackable
        offsetX = 0f
        offsetY = 0f
        dragGrab = null
        smoother.reset()
        locked = true
        _messages.tryEmit("Sketch locked to the wall.")
    }

    private fun replaceAnchor(newAnchor: Anchor, trackable: Trackable?, kind: PlacementKind) {
        anchor?.detach()
        anchor = newAnchor
        anchorTrackable = trackable
        placementKind = kind
        locked = false
        dragGrab = null
        smoother.reset()
    }

    private fun clearPlacementState(detach: Boolean) {
        if (detach) anchor?.detach()
        anchor = null
        anchorTrackable = null
        placementKind = null
        locked = false
        offsetX = 0f
        offsetY = 0f
        dragGrab = null
        smoother.reset()
    }

    // ---------------------------------------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------------------------------------

    private fun publish(status: ArStatus, force: Boolean) {
        val now = SystemClock.uptimeMillis()
        val previous = _status.value
        val discreteChange = previous.placement != status.placement ||
            previous.cameraTracking != status.cameraTracking ||
            previous.issue != status.issue ||
            previous.anchorTracking != status.anchorTracking ||
            previous.wallPlanes != status.wallPlanes ||
            previous.hasImage != status.hasImage ||
            previous.sessionRunning != status.sessionRunning
        if (force || discreteChange || now - lastPublishMs >= STATUS_INTERVAL_MS) {
            _status.value = status
            lastPublishMs = now
        }
    }

    private fun mapFailure(reason: TrackingFailureReason): TrackingIssue = when (reason) {
        TrackingFailureReason.NONE -> TrackingIssue.NONE
        TrackingFailureReason.BAD_STATE -> TrackingIssue.BAD_STATE
        TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingIssue.INSUFFICIENT_LIGHT
        TrackingFailureReason.EXCESSIVE_MOTION -> TrackingIssue.EXCESSIVE_MOTION
        TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingIssue.INSUFFICIENT_FEATURES
        TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingIssue.CAMERA_UNAVAILABLE
    }

    private companion object {
        const val TAG = "WallTraceRenderer"
        const val NEAR = 0.05f
        const val FAR = 100f
        const val DEFAULT_WIDTH_METERS = 0.6f
        const val MIN_WIDTH_METERS = 0.05f
        const val MAX_WIDTH_METERS = 8f
        const val MAX_OFFSET_METERS = 5f
        const val SURFACE_OFFSET_METERS = 0.004f
        const val APPROXIMATE_DISTANCE_METERS = 1.2f
        const val STATUS_INTERVAL_MS = 200L
    }
}
