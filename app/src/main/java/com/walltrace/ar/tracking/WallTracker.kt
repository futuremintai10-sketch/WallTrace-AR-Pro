package com.walltrace.ar.tracking

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

/**
 * Markerless planar wall tracker for phones without ARCore.
 *
 * A wall is a plane, so camera motion maps the locked view of the wall to the current frame by a
 * homography. On lock the tracker stores a reference frame, Shi-Tomasi corners and ORB descriptors.
 * Every frame it tracks the corners with pyramidal Lucas-Kanade optical flow (with a forward-backward
 * consistency check), estimates the reference-to-current homography with RANSAC and moves the sketch
 * corners with it. Points are replenished as they leave the view, ORB matching against the reference
 * corrects slow drift, and ORB relocalisation recovers after tracking is lost.
 *
 * All tracking runs on the CameraX analysis thread. Results are published in view pixels.
 */
class WallTracker : ImageAnalysis.Analyzer {

    enum class Status { IDLE, TRACKING, WEAK, LOST, UNAVAILABLE }

    data class State(
        val status: Status = Status.IDLE,
        /** Sketch corners in view pixels (TL, TR, BR, BL), present while locked. */
        val corners: FloatArray? = null,
        /** Tracked / detected feature points in view pixels (for the scanning dots). */
        val points: FloatArray = FloatArray(0),
        val featureCount: Int = 0
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private sealed interface Command {
        class Lock(val viewCorners: FloatArray) : Command
        data object Unlock : Command
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val pending = AtomicReference<Command?>(null)

    @Volatile
    private var viewWidth = 0

    @Volatile
    private var viewHeight = 0

    private var openCvReady: Boolean? = null

    // Frames
    private var gray: Mat? = null
    private var prevGray: Mat? = null
    private var frameBytes = ByteArray(0)

    // Reference (locked) data
    private var locked = false
    private var lost = false
    private var refCorners = FloatArray(8)
    private var refPts = FloatArray(0)
    private var curPts = FloatArray(0)
    private var refOrbPts = FloatArray(0)
    private var refDescriptors: Mat? = null
    private var smoothedCorners: FloatArray? = null
    private var lastViewCorners: FloatArray? = null
    private var frameIndex = 0L

    private val orb by lazy { ORB.create(ORB_FEATURES) }
    private val matcher by lazy { DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING) }

    fun setViewSize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    /** Locks the sketch to the wall at [viewCorners] (TL, TR, BR, BL in view pixels). */
    fun lock(viewCorners: FloatArray) {
        require(viewCorners.size == 8)
        pending.set(Command.Lock(viewCorners.copyOf()))
    }

    fun unlock() {
        pending.set(Command.Unlock)
    }

    override fun analyze(image: ImageProxy) {
        try {
            process(image)
        } catch (e: CvException) {
            Log.e(TAG, "OpenCV error while tracking", e)
            if (locked) markLost()
        } finally {
            image.close()
        }
    }

    /** Frees native memory. Call on the analysis thread after the analyzer is cleared. */
    fun release() {
        gray?.release(); gray = null
        prevGray?.release(); prevGray = null
        refDescriptors?.release(); refDescriptors = null
    }

    private fun process(image: ImageProxy) {
        val ready = openCvReady ?: OpenCVLoader.initLocal().also {
            openCvReady = it
            if (!it) Log.e(TAG, "OpenCV failed to load; overlay will stay fixed to the screen")
        }
        val command = pending.getAndSet(null)
        if (!ready) {
            _state.value = State(status = Status.UNAVAILABLE, corners = (command as? Command.Lock)?.viewCorners)
            return
        }
        val vw = viewWidth
        val vh = viewHeight
        if (vw <= 0 || vh <= 0) return
        val crop = image.cropRect
        val geometry = FrameGeometry(
            crop.left.toFloat(), crop.top.toFloat(), crop.width().toFloat(), crop.height().toFloat(),
            image.imageInfo.rotationDegrees, vw.toFloat(), vh.toFloat()
        )
        val current = readLuminance(image)

        when (command) {
            is Command.Lock -> startTracking(current, geometry.viewToBuffer(command.viewCorners), geometry)
            Command.Unlock -> resetTracking()
            null -> if (locked) track(current, geometry) else scan(current, geometry)
        }

        // Keep this frame as the previous one for optical flow.
        val old = prevGray
        prevGray = current
        gray = old
        frameIndex++
    }

    // ---------------------------------------------------------------------------------------------
    // Scanning (before lock): show genuine trackable features as dots.
    // ---------------------------------------------------------------------------------------------

    private fun scan(current: Mat, geometry: FrameGeometry) {
        if (frameIndex % SCAN_INTERVAL != 0L) return
        val pts = detectFeatures(current, null, SCAN_MAX_POINTS)
        _state.value = State(
            status = Status.IDLE,
            points = geometry.bufferToView(pts),
            featureCount = pts.size / 2
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Locking
    // ---------------------------------------------------------------------------------------------

    private fun startTracking(current: Mat, bufferCorners: FloatArray, geometry: FrameGeometry) {
        resetTracking()
        refCorners = bufferCorners
        refPts = detectFeatures(current, null, MAX_POINTS)
        curPts = refPts.copyOf()

        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val noMask = Mat()
        orb.detectAndCompute(current, noMask, keypoints, descriptors)
        noMask.release()
        refOrbPts = keypointsToArray(keypoints)
        refDescriptors = descriptors
        keypoints.release()

        locked = true
        lost = false
        smoothedCorners = bufferCorners.copyOf()
        val count = refPts.size / 2
        publishLocked(
            geometry,
            bufferCorners,
            if (count >= MIN_GOOD_POINTS) Status.TRACKING else Status.WEAK,
            count
        )
    }

    private fun resetTracking() {
        locked = false
        lost = false
        refPts = FloatArray(0)
        curPts = FloatArray(0)
        refOrbPts = FloatArray(0)
        refDescriptors?.release()
        refDescriptors = null
        smoothedCorners = null
        lastViewCorners = null
        _state.value = State(status = Status.IDLE)
    }

    // ---------------------------------------------------------------------------------------------
    // Tracking
    // ---------------------------------------------------------------------------------------------

    private fun track(current: Mat, geometry: FrameGeometry) {
        val previous = prevGray
        if (lost || previous == null || curPts.size < 8) {
            if (frameIndex % RELOCALIZE_INTERVAL == 0L) relocalize(current, geometry)
            if (lost || curPts.size < 8) markLost()
            return
        }

        val n = curPts.size / 2
        val prevMat = toMat(curPts)
        val nextMat = MatOfPoint2f()
        val backMat = MatOfPoint2f()
        val status = MatOfByte()
        val backStatus = MatOfByte()
        val err = MatOfFloat()
        val backErr = MatOfFloat()
        val win = Size(LK_WINDOW, LK_WINDOW)
        Video.calcOpticalFlowPyrLK(previous, current, prevMat, nextMat, status, err, win, LK_LEVELS)
        Video.calcOpticalFlowPyrLK(current, previous, nextMat, backMat, backStatus, backErr, win, LK_LEVELS)

        val next = nextMat.toArray()
        val back = backMat.toArray()
        val st = status.toArray()
        val bst = backStatus.toArray()
        val goodRef = ArrayList<Float>(n * 2)
        val goodCur = ArrayList<Float>(n * 2)
        for (i in 0 until n) {
            if (i >= next.size || i >= back.size || st[i].toInt() != 1 || bst[i].toInt() != 1) continue
            val fbError = hypot(back[i].x - curPts[i * 2], back[i].y - curPts[i * 2 + 1])
            if (fbError > FB_MAX_ERROR) continue
            goodRef.add(refPts[i * 2]); goodRef.add(refPts[i * 2 + 1])
            goodCur.add(next[i].x.toFloat()); goodCur.add(next[i].y.toFloat())
        }
        listOf(prevMat, nextMat, backMat, status, backStatus, err, backErr).forEach { it.release() }

        if (goodRef.size / 2 < MIN_TRACK_POINTS) {
            markLost()
            return
        }
        val estimate = estimateHomography(goodRef.toFloatArray(), goodCur.toFloatArray(), RANSAC_PX)
        if (estimate == null) {
            markLost()
            return
        }
        var (h, inlierRef, inlierCur) = estimate
        if (inlierRef.size / 2 < MIN_TRACK_POINTS) {
            markLost()
            return
        }

        // Periodic drift correction: re-match directly against the reference frame.
        if (frameIndex % DRIFT_CHECK_INTERVAL == 0L) {
            orbMatch(current)?.let { orbEstimate ->
                if (orbEstimate.refPoints.size / 2 >= DRIFT_MIN_INLIERS) {
                    val a = QuadMath.applyHomography(h, refCorners)
                    val b = QuadMath.applyHomography(orbEstimate.h, refCorners)
                    if (a != null && b != null && QuadMath.maxCornerDistance(a, b) < DRIFT_MAX_JUMP_PX) {
                        h = orbEstimate.h
                        inlierRef = orbEstimate.refPoints
                        inlierCur = orbEstimate.curPoints
                    }
                }
            }
        }

        val corners = QuadMath.applyHomography(h, refCorners)
        if (corners == null || !QuadMath.isPlausible(corners, refCorners)) {
            markLost()
            return
        }

        refPts = inlierRef
        curPts = inlierCur
        if (curPts.size / 2 < REPLENISH_BELOW) replenish(current, h)

        val inliers = inlierRef.size / 2
        smoothedCorners = QuadMath.smooth(smoothedCorners, corners, SMOOTH_ALPHA, SMOOTH_SNAP_PX)
        publishLocked(
            geometry,
            smoothedCorners ?: corners,
            if (inliers >= MIN_GOOD_POINTS) Status.TRACKING else Status.WEAK,
            inliers
        )
    }

    private fun relocalize(current: Mat, geometry: FrameGeometry) {
        val estimate = orbMatch(current) ?: return
        if (estimate.refPoints.size / 2 < RELOCALIZE_MIN_INLIERS) return
        val corners = QuadMath.applyHomography(estimate.h, refCorners) ?: return
        if (!QuadMath.isPlausible(corners, refCorners)) return
        refPts = estimate.refPoints
        curPts = estimate.curPoints
        replenish(current, estimate.h)
        lost = false
        smoothedCorners = corners
        publishLocked(geometry, corners, Status.WEAK, refPts.size / 2)
    }

    private fun markLost() {
        lost = true
        val s = _state.value
        if (s.status != Status.LOST) {
            _state.value = State(status = Status.LOST, corners = lastViewCorners, featureCount = 0)
        }
    }

    private fun publishLocked(geometry: FrameGeometry, bufferCorners: FloatArray, status: Status, count: Int) {
        val viewCorners = geometry.bufferToView(bufferCorners)
        lastViewCorners = viewCorners
        _state.value = State(status = status, corners = viewCorners, featureCount = count)
    }

    /** Adds fresh corners from the current frame, mapped back into reference coordinates. */
    private fun replenish(current: Mat, h: DoubleArray) {
        val inverse = QuadMath.invert3x3(h) ?: return
        val mask = Mat(current.rows(), current.cols(), CvType.CV_8UC1, Scalar(255.0))
        var i = 0
        while (i < curPts.size) {
            Imgproc.circle(mask, Point(curPts[i].toDouble(), curPts[i + 1].toDouble()), MIN_DISTANCE.toInt(), Scalar(0.0), -1)
            i += 2
        }
        val room = MAX_POINTS - curPts.size / 2
        val fresh = if (room > 0) detectFeatures(current, mask, room) else FloatArray(0)
        mask.release()
        if (fresh.isEmpty()) return
        val freshRef = QuadMath.applyHomography(inverse, fresh) ?: return
        curPts += fresh
        refPts += freshRef
    }

    // ---------------------------------------------------------------------------------------------
    // OpenCV helpers
    // ---------------------------------------------------------------------------------------------

    @Suppress("ArrayInDataClass")
    private data class Estimate(val h: DoubleArray, val refPoints: FloatArray, val curPoints: FloatArray)

    private fun estimateHomography(ref: FloatArray, cur: FloatArray, thresholdPx: Double): Estimate? {
        if (ref.size / 2 < 4) return null
        val src = toMat(ref)
        val dst = toMat(cur)
        val mask = Mat()
        val hMat = Calib3d.findHomography(src, dst, Calib3d.RANSAC, thresholdPx, mask)
        src.release(); dst.release()
        if (hMat.empty() || hMat.rows() != 3 || hMat.cols() != 3) {
            hMat.release(); mask.release()
            return null
        }
        val h = DoubleArray(9)
        hMat.get(0, 0, h)
        hMat.release()
        val inlierMask = ByteArray(mask.rows() * mask.cols())
        if (inlierMask.isNotEmpty()) mask.get(0, 0, inlierMask)
        mask.release()
        val inRef = ArrayList<Float>()
        val inCur = ArrayList<Float>()
        for (k in inlierMask.indices) {
            if (inlierMask[k].toInt() == 0) continue
            inRef.add(ref[k * 2]); inRef.add(ref[k * 2 + 1])
            inCur.add(cur[k * 2]); inCur.add(cur[k * 2 + 1])
        }
        return Estimate(h, inRef.toFloatArray(), inCur.toFloatArray())
    }

    /** Matches ORB features of [current] against the reference frame and estimates a homography. */
    private fun orbMatch(current: Mat): Estimate? {
        val refDesc = refDescriptors ?: return null
        if (refDesc.empty()) return null
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val noMask = Mat()
        orb.detectAndCompute(current, noMask, keypoints, descriptors)
        noMask.release()
        val curKp = keypointsToArray(keypoints)
        keypoints.release()
        if (descriptors.empty()) {
            descriptors.release()
            return null
        }
        val matches = ArrayList<MatOfDMatch>()
        matcher.knnMatch(descriptors, refDesc, matches, 2)
        descriptors.release()
        val ref = ArrayList<Float>()
        val cur = ArrayList<Float>()
        for (m in matches) {
            val pair = m.toArray()
            m.release()
            if (pair.size < 2) continue
            if (pair[0].distance < ORB_RATIO * pair[1].distance) {
                val q = pair[0].queryIdx
                val t = pair[0].trainIdx
                ref.add(refOrbPts[t * 2]); ref.add(refOrbPts[t * 2 + 1])
                cur.add(curKp[q * 2]); cur.add(curKp[q * 2 + 1])
            }
        }
        if (ref.size / 2 < ORB_MIN_MATCHES) return null
        return estimateHomography(ref.toFloatArray(), cur.toFloatArray(), ORB_RANSAC_PX)
    }

    private fun detectFeatures(image: Mat, mask: Mat?, maxCount: Int): FloatArray {
        val corners = MatOfPoint()
        val m = mask ?: Mat()
        Imgproc.goodFeaturesToTrack(image, corners, maxCount, QUALITY, MIN_DISTANCE, m)
        if (mask == null) m.release()
        val pts = corners.toArray()
        corners.release()
        val out = FloatArray(pts.size * 2)
        for (i in pts.indices) {
            out[i * 2] = pts[i].x.toFloat()
            out[i * 2 + 1] = pts[i].y.toFloat()
        }
        return out
    }

    private fun keypointsToArray(keypoints: MatOfKeyPoint): FloatArray {
        val kps = keypoints.toArray()
        val out = FloatArray(kps.size * 2)
        for (i in kps.indices) {
            out[i * 2] = kps[i].pt.x.toFloat()
            out[i * 2 + 1] = kps[i].pt.y.toFloat()
        }
        return out
    }

    private fun toMat(points: FloatArray): MatOfPoint2f {
        val arr = Array(points.size / 2) { Point(points[it * 2].toDouble(), points[it * 2 + 1].toDouble()) }
        return MatOfPoint2f(*arr)
    }

    /** Copies the Y (luminance) plane into a reusable 8-bit Mat. */
    private fun readLuminance(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        if (frameBytes.size != width * height) frameBytes = ByteArray(width * height)
        buffer.rewind()
        if (rowStride == width) {
            buffer.get(frameBytes, 0, width * height)
        } else {
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(frameBytes, y * width, width)
            }
        }
        val target = gray?.takeIf { it.rows() == height && it.cols() == width }
            ?: Mat(height, width, CvType.CV_8UC1).also { gray?.release() }
        target.put(0, 0, frameBytes)
        return target
    }

    private companion object {
        const val TAG = "WallTracker"
        const val MAX_POINTS = 400
        const val SCAN_MAX_POINTS = 200
        const val SCAN_INTERVAL = 3L
        const val QUALITY = 0.01
        const val MIN_DISTANCE = 10.0
        const val LK_WINDOW = 21.0
        const val LK_LEVELS = 3
        const val FB_MAX_ERROR = 1.0
        const val RANSAC_PX = 3.0
        const val MIN_TRACK_POINTS = 12
        const val MIN_GOOD_POINTS = 40
        const val REPLENISH_BELOW = 200
        const val SMOOTH_ALPHA = 0.6f
        const val SMOOTH_SNAP_PX = 25f
        const val ORB_FEATURES = 800
        const val ORB_RATIO = 0.75f
        const val ORB_MIN_MATCHES = 15
        const val ORB_RANSAC_PX = 4.0
        const val RELOCALIZE_INTERVAL = 3L
        const val RELOCALIZE_MIN_INLIERS = 15
        const val DRIFT_CHECK_INTERVAL = 30L
        const val DRIFT_MIN_INLIERS = 40
        const val DRIFT_MAX_JUMP_PX = 40f
    }
}
