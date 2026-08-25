package com.yhash.scrollvoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Real hand-skeleton tracking using MediaPipe's Hand Landmarker (21 points
 * per hand) - not a brightness-motion guess. Looks for a pointing gesture
 * (index finger extended, other fingers curled) and tracks that fingertip's
 * position. Swipe the pointing finger up/down in front of the camera to
 * scroll; the fingertip position also drives the on-screen cursor.
 *
 * The ~7MB model file isn't bundled in the app - it's downloaded once from
 * Google's public model server on first use and cached in app storage.
 *
 * Two reliability layers on top of the base detector, both aimed at the
 * "works once, then stops working after a while" failure mode:
 *  - Frame conversion is done with direct YUV->RGB pixel math instead of a
 *    JPEG encode/decode roundtrip, which was by far the most expensive
 *    thing happening every single frame, continuously, for however long the
 *    mode stays on. Long sessions were almost certainly degrading from the
 *    sustained allocation/compression churn.
 *  - A watchdog checks that the full pipeline (camera -> conversion ->
 *    MediaPipe inference -> result callback) is still actually producing
 *    results every few seconds. If it goes quiet - camera silently lost,
 *    MediaPipe wedged, whatever the cause - it tears the whole pipeline
 *    down and rebuilds it from scratch, the same "calibration test" you'd
 *    do by hand by toggling the mode off and on, just automatic.
 */
class HandGestureDetector(
    private val context: Context,
    private val onSwipe: (direction: String) -> Unit,
    private val onPositionUpdate: ((normalizedX: Float, normalizedY: Float, pointing: Boolean) -> Unit)? = null,
    private val onError: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "HandGestureDetector"

        private const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
        private const val MODEL_FILENAME = "hand_landmarker.task"

        private const val ANALYSIS_WIDTH = 320
        private const val ANALYSIS_HEIGHT = 240

        // A finger counts as "extended" when its tip sits at least this many
        // times farther from the wrist than its own PIP knuckle.
        private const val EXTENDED_RATIO = 1.15

        private const val MIN_SWIPE_DELTA_DOWN = 0.12f // finger rises (Y decreases)
        private const val MIN_SWIPE_DELTA_UP = 0.08f   // finger drops (Y increases)
        private const val MIN_GESTURE_DURATION_MS = 60L
        private const val COOLDOWN_MS = 550L

        // How still (max net movement, normalized units) and for how long the
        // finger must hold after a fire before the next swipe can be armed.
        // Tightened from 0.035/150ms: that was loose enough that a real
        // swipe's natural mid-motion deceleration (which isn't a full stop)
        // could still read as "settled," re-arming a new reference point
        // before the swipe's momentum had actually finished - letting one
        // continuous swipe cross the threshold twice (double scroll).
        private const val SETTLE_STILL_THRESHOLD = 0.02f
        private const val SETTLE_HOLD_MS = 250L

        // Extra scrutiny for a fire whose direction is the *opposite* of the
        // one that just fired. A deliberate direction change covers real
        // distance; a hand recoiling/relaxing right after a swipe usually
        // doesn't, so require more movement to accept it during this window.
        private const val OPPOSITE_DIRECTION_WINDOW_MS = 900L
        private const val OPPOSITE_DIRECTION_THRESHOLD_MULTIPLIER = 1.7f

        private const val POINTING_LOSS_GRACE_MS = 220L

        private const val INVERT_DIRECTION = false
        private const val MIRROR_X = true

        private const val CURSOR_SMOOTHING = 0.35f

        private const val MAX_CAMERA_RETRIES = 3
        private const val CAMERA_RETRY_DELAY_MS = 500L

        // Watchdog: how often to check the pipeline is still alive, and how
        // long a silence has to last before we treat it as stalled rather
        // than just a slow moment (hand out of frame, brief exposure
        // adjustment, etc).
        private const val WATCHDOG_INTERVAL_MS = 6000L
        private const val STALL_TIMEOUT_MS = 10000L

        // Frame processing failures (including OOM) in a row before we
        // treat the pipeline as broken rather than just having a bad frame.
        private const val MAX_CONSECUTIVE_FRAME_FAILURES = 8

        private const val WRIST = 0
        private const val THUMB_TIP = 4
        private const val INDEX_PIP = 6
        private const val INDEX_TIP = 8
        private const val MIDDLE_MCP = 9
        private const val MIDDLE_PIP = 10
        private const val MIDDLE_TIP = 12
        private const val RING_PIP = 14
        private const val RING_TIP = 16
        private const val PINKY_PIP = 18
        private const val PINKY_TIP = 20

        // "Chef's kiss" pose: all five fingertips pinched together to a
        // point. Detected as the average fingertip-to-centroid distance,
        // normalized by palm size (wrist-to-middle-knuckle) so it works
        // regardless of how close your hand is to the camera. Lower =
        // stricter pinch required; raise if it's not triggering on a
        // genuine pinch, lower if it fires too easily.
        private const val KISS_PINCH_RATIO = 0.35

        // The play/pause toggle no longer fires off the kiss pose alone -
        // that fired instantly any time the hand happened to pass through a
        // pinch shape (closing a fist, adjusting grip, etc). It now
        // requires a deliberate two-phase gesture: hold an open palm, then
        // fold it into a kiss (fires "pause"), or hold a kiss, then open
        // the palm back up (fires "play"). Both phases must be held for
        // POSE_HOLD_MS to count as "confirmed", so a hand merely passing
        // through either shape mid-motion doesn't trigger anything. Raised
        // from 120ms - that was short enough that an open palm glimpsed for
        // a couple frames while your hand was just moving through the shot
        // (not deliberately held) still counted as "confirmed".
        private const val POSE_HOLD_MS = 350L
        private const val TOGGLE_COOLDOWN_MS = 900L
        // Same idea as POINTING_LOSS_GRACE_MS - tolerates a brief tracking
        // blip without resetting the in-progress open<->kiss sequence.
        private const val POSE_LOSS_GRACE_MS = 150L
    }

    private var executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private val lifecycleOwner = SimpleLifecycleOwner()
    private var handLandmarker: HandLandmarker? = null
    private var stopped = false
    @Volatile private var restarting = false

    private var refY: Float? = null
    private var refSetTime = 0L
    private var lastPointingTrueTime = 0L
    private var lastFireTime = 0L
    // Direction of the last fired swipe (true = "down"), used for the
    // opposite-direction hysteresis check above.
    private var lastFireWasDown: Boolean? = null
    // After a fire, the finger is almost always still coasting through the
    // swipe motion (or about to recoil back toward its start). Re-arming
    // refY off that in-motion position was causing one long swipe to cross
    // the threshold twice (double scroll) and the post-swipe recoil to read
    // as a small opposite-direction swipe (scrolls up right after down). So
    // instead of re-arming immediately, wait for the finger to actually
    // hold still for SETTLE_HOLD_MS before establishing the next refY.
    private var awaitingSettle = false
    private var settleAnchorY: Float? = null
    private var settleAnchorTime = 0L
    private var smoothedX: Float? = null
    private var smoothedY: Float? = null

    // Open-palm <-> kiss-pose (play/pause toggle) state machine, separate
    // from the pointing/swipe state above since these gestures are mutually
    // exclusive with pointing per frame.
    private enum class Pose { NONE, OPEN, KISS }
    // The last pose that was actually held long enough to "confirm".
    private var confirmedPose = Pose.NONE
    // The pose the hand currently looks like it's forming, mid-hold.
    private var candidatePose = Pose.NONE
    private var candidateStartTime = 0L
    private var lastCandidateTrueTime = 0L
    private var lastToggleFireTime = 0L

    // Reused across frames to avoid a fresh IntArray allocation every frame -
    // the Bitmap itself is still allocated fresh each frame (see
    // imageProxyToBitmap) since MediaPipe's LIVE_STREAM mode consumes it
    // asynchronously and mutating a Bitmap MediaPipe hasn't finished reading
    // yet would be a real race, not just a performance nit.
    private var pixelBuffer: IntArray? = null

    @Volatile private var lastResultTime = 0L
    @Volatile private var consecutiveFrameFailures = 0

    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            val last = lastResultTime
            if (last != 0L && System.currentTimeMillis() - last > STALL_TIMEOUT_MS) {
                Log.w(TAG, "No hand-tracking results for ${STALL_TIMEOUT_MS}ms - rebuilding pipeline")
                restartPipeline()
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    fun start() {
        stopped = false
        restarting = false
        if (executor.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
        }
        initPipeline()
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    fun stop() {
        stopped = true
        restarting = false
        mainHandler.removeCallbacks(watchdog)
        refY = null
        smoothedX = null
        smoothedY = null
        lastResultTime = 0L
        mainHandler.post {
            cameraProvider?.unbindAll()
            cameraProvider = null
            lifecycleOwner.stop()
        }
        executor.execute {
            try {
                handLandmarker?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing landmarker", e)
            }
            handLandmarker = null
        }
        executor.shutdown()
    }

    private fun initPipeline() {
        executor.execute {
            try {
                val modelFile = ensureModelDownloaded()
                val landmarker = buildLandmarker(modelFile)
                if (stopped) {
                    landmarker.close()
                    return@execute
                }
                handLandmarker = landmarker
                mainHandler.post { startCamera() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize hand tracking", e)
                mainHandler.post { onError?.invoke() }
            }
        }
    }

    /**
     * Full teardown + rebuild of the entire pipeline: closes the old
     * HandLandmarker, unbinds the camera, then reinitializes both from
     * scratch. This is the automatic version of "turn camera mode off and
     * on again" - triggered by the watchdog when results have stopped
     * coming in for too long.
     */
    private fun restartPipeline() {
        if (restarting || stopped) return
        restarting = true
        onError?.invoke() // visible red flash while recovering

        cameraProvider?.unbindAll()
        cameraProvider = null
        lifecycleOwner.stop()

        executor.execute {
            try {
                handLandmarker?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing landmarker during restart", e)
            }
            handLandmarker = null

            try {
                val modelFile = ensureModelDownloaded()
                val landmarker = buildLandmarker(modelFile)
                if (stopped) {
                    landmarker.close()
                    return@execute
                }
                handLandmarker = landmarker
                lastResultTime = 0L
                consecutiveFrameFailures = 0
                mainHandler.post {
                    restarting = false
                    if (!stopped) startCamera()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rebuild hand tracking pipeline", e)
                restarting = false
                mainHandler.post { onError?.invoke() }
            }
        }
    }

    private fun ensureModelDownloaded(): File {
        val file = File(context.filesDir, MODEL_FILENAME)
        if (file.exists() && file.length() > 0) return file

        Log.d(TAG, "Downloading hand landmark model...")
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")
        URL(MODEL_URL).openStream().use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        tempFile.renameTo(file)
        Log.d(TAG, "Model downloaded (${file.length()} bytes)")
        return file
    }

    private fun buildLandmarker(modelFile: File): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelFile.absolutePath)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { e ->
                Log.e(TAG, "HandLandmarker runtime error", e)
                mainHandler.post { onError?.invoke() }
            }
            .build()
        return HandLandmarker.createFromOptions(context, options)
    }

    private fun startCamera(attempt: Int = 1) {
        lifecycleOwner.start()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image -> analyze(image) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
                Log.d(TAG, "Camera bound successfully (attempt $attempt)")
            } catch (e: Exception) {
                Log.w(TAG, "Camera bind failed on attempt $attempt: ${e.message}")
                if (attempt < MAX_CAMERA_RETRIES && !stopped) {
                    mainHandler.postDelayed({ startCamera(attempt + 1) }, CAMERA_RETRY_DELAY_MS)
                } else {
                    Log.e(TAG, "Camera bind failed after $attempt attempts", e)
                    onError?.invoke()
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(image: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(image)
            if (bitmap != null) {
                handLandmarker?.detectAsync(BitmapImageBuilder(bitmap).build(), System.currentTimeMillis())
            }
            consecutiveFrameFailures = 0
        } catch (e: OutOfMemoryError) {
            // A regular Exception catch doesn't see this (Error, not
            // Exception) - it used to crash the analyzer thread silently.
            // Now it's logged and counted instead of just vanishing.
            Log.e(TAG, "Out of memory converting a frame - skipping it", e)
            handleFrameFailure()
        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis error", e)
            handleFrameFailure()
        } finally {
            image.close()
        }
    }

    private fun handleFrameFailure() {
        consecutiveFrameFailures++
        if (consecutiveFrameFailures >= MAX_CONSECUTIVE_FRAME_FAILURES) {
            Log.w(TAG, "$consecutiveFrameFailures consecutive frame failures - rebuilding pipeline")
            consecutiveFrameFailures = 0
            mainHandler.post { restartPipeline() }
        }
    }

    /**
     * Converts a YUV_420_888 camera frame to an upright RGB Bitmap using
     * direct pixel math, not a JPEG encode/decode roundtrip. The pixel
     * IntArray is reused across frames since setPixels() copies its values
     * into the Bitmap's own storage synchronously - safe to overwrite on
     * the next frame. The Bitmap itself is still allocated fresh each frame
     * on purpose (see class doc).
     */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val pixelCount = width * height
        var pixels = pixelBuffer
        if (pixels == null || pixels.size != pixelCount) {
            pixels = IntArray(pixelCount)
            pixelBuffer = pixels
        }

        for (row in 0 until height) {
            val yRowStart = row * yRowStride
            val uvRowStart = (row / 2) * uvRowStride
            for (col in 0 until width) {
                val yValue = yBuffer.get(yRowStart + col).toInt() and 0xFF

                val uvIndex = uvRowStart + (col / 2) * uvPixelStride
                val uValue = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val vValue = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                val y1192 = 1192 * (yValue - 16)
                var r = (y1192 + 1634 * vValue) shr 10
                var g = (y1192 - 833 * vValue - 400 * uValue) shr 10
                var b = (y1192 + 2066 * uValue) shr 10

                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun handleResult(result: HandLandmarkerResult) {
        lastResultTime = System.currentTimeMillis()
        val hands = result.landmarks()
        val now = lastResultTime

        if (hands.isEmpty()) {
            maybeEndSession()
            maybeEndPoseSession(now)
            postCursorUpdate(now)
            return
        }

        val landmarks = hands[0]
        val wrist = landmarks[WRIST]

        val poseNow = classifyPose(landmarks, wrist)
        if (poseNow != Pose.NONE) {
            handlePoseFrame(poseNow, now)
            // Open palm and kiss are mutually exclusive with pointing per
            // frame, so don't fall through to the swipe logic below.
            postCursorUpdate(now)
            return
        }
        maybeEndPoseSession(now)

        val indexExtended = extendedFinger(landmarks, wrist, INDEX_TIP, INDEX_PIP)
        val middleExtended = extendedFinger(landmarks, wrist, MIDDLE_TIP, MIDDLE_PIP)
        val ringExtended = extendedFinger(landmarks, wrist, RING_TIP, RING_PIP)
        val pinkyExtended = extendedFinger(landmarks, wrist, PINKY_TIP, PINKY_PIP)
        val isPointing = indexExtended && !middleExtended && !ringExtended && !pinkyExtended

        val tip = landmarks[INDEX_TIP]
        val rawX = tip.x()
        val displayX = if (MIRROR_X) 1f - rawX else rawX
        val displayY = tip.y()

        smoothedX = lerp(smoothedX, displayX)
        smoothedY = lerp(smoothedY, displayY)

        if (!isPointing) {
            maybeEndSession()
            postCursorUpdate(now)
            return
        }
        lastPointingTrueTime = now
        postCursorUpdate(now)

        if (awaitingSettle) {
            val anchor = settleAnchorY
            if (anchor == null || kotlin.math.abs(displayY - anchor) > SETTLE_STILL_THRESHOLD) {
                // Still moving (coasting through the swipe or recoiling back) - keep waiting.
                settleAnchorY = displayY
                settleAnchorTime = now
            } else if (now - settleAnchorTime >= SETTLE_HOLD_MS) {
                // Held still long enough - safe to arm the next swipe from here.
                awaitingSettle = false
                refY = displayY
                refSetTime = now
            }
            return
        }

        val currentRef = refY
        if (currentRef == null) {
            refY = displayY
            refSetTime = now
            return
        }

        val duration = now - refSetTime
        val delta = displayY - currentRef
        val inCooldown = now - lastFireTime < COOLDOWN_MS

        val candidateIsDown = delta < 0
        val baseThreshold = if (candidateIsDown) MIN_SWIPE_DELTA_DOWN else MIN_SWIPE_DELTA_UP
        val isOppositeOfLastFire = lastFireWasDown != null &&
            lastFireWasDown != candidateIsDown &&
            (now - lastFireTime) < OPPOSITE_DIRECTION_WINDOW_MS
        val threshold = if (isOppositeOfLastFire) {
            baseThreshold * OPPOSITE_DIRECTION_THRESHOLD_MULTIPLIER
        } else {
            baseThreshold
        }

        if (!inCooldown && duration >= MIN_GESTURE_DURATION_MS && kotlin.math.abs(delta) >= threshold) {
            fire(delta, now)
            lastFireWasDown = candidateIsDown
            refY = null
            awaitingSettle = true
            settleAnchorY = displayY
            settleAnchorTime = now
        }
    }

    private fun lerp(current: Float?, target: Float): Float =
        if (current == null) target else current + (target - current) * CURSOR_SMOOTHING

    private fun postCursorUpdate(now: Long) {
        val displayPointing = now - lastPointingTrueTime <= POINTING_LOSS_GRACE_MS
        val x = smoothedX ?: 0.5f
        val y = smoothedY ?: 0.5f
        mainHandler.post { onPositionUpdate?.invoke(x, y, displayPointing) }
    }

    private fun maybeEndSession() {
        val now = System.currentTimeMillis()
        if (refY != null && now - lastPointingTrueTime > POINTING_LOSS_GRACE_MS) {
            refY = null
        }
        if (awaitingSettle && now - lastPointingTrueTime > POINTING_LOSS_GRACE_MS) {
            awaitingSettle = false
            settleAnchorY = null
        }
    }

    private fun fire(delta: Float, now: Long) {
        var direction = if (delta < 0) "down" else "up"
        if (INVERT_DIRECTION) direction = if (direction == "down") "up" else "down"

        lastFireTime = now
        Log.d(TAG, "Pointing swipe -> $direction (delta=$delta)")
        mainHandler.post { onSwipe(direction) }
    }

    /** Whether a single finger is straightened (tip far from wrist relative to its own PIP knuckle). */
    private fun extendedFinger(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        wrist: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        tipIdx: Int,
        pipIdx: Int
    ): Boolean {
        val tip = landmarks[tipIdx]
        val pip = landmarks[pipIdx]
        val tipDist = hypot((tip.x() - wrist.x()).toDouble(), (tip.y() - wrist.y()).toDouble())
        val pipDist = hypot((pip.x() - wrist.x()).toDouble(), (pip.y() - wrist.y()).toDouble())
        return tipDist > pipDist * EXTENDED_RATIO
    }

    /**
     * "Chef's kiss" pose: all five fingertips pinched together to a point.
     * Detected as the average fingertip-to-centroid distance, normalized by
     * palm size (wrist-to-middle-knuckle) so it works at any distance from
     * the camera.
     */
    private fun isKissPose(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        wrist: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Boolean {
        val middleMcp = landmarks[MIDDLE_MCP]
        val palmSize = hypot((middleMcp.x() - wrist.x()).toDouble(), (middleMcp.y() - wrist.y()).toDouble())
        if (palmSize < 1e-4) return false

        val tips = listOf(
            landmarks[THUMB_TIP],
            landmarks[INDEX_TIP],
            landmarks[MIDDLE_TIP],
            landmarks[RING_TIP],
            landmarks[PINKY_TIP]
        )
        val centroidX = tips.sumOf { it.x().toDouble() } / tips.size
        val centroidY = tips.sumOf { it.y().toDouble() } / tips.size
        val avgDist = tips.sumOf { hypot((it.x() - centroidX), (it.y() - centroidY)) } / tips.size

        return (avgDist / palmSize) < KISS_PINCH_RATIO
    }

    /**
     * Open-palm pose: the four fingers (thumb excluded, since its extension
     * geometry relative to the wrist is different) all straightened out -
     * i.e. the opposite shape from the kiss pinch above.
     */
    private fun isOpenPalm(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        wrist: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Boolean {
        return extendedFinger(landmarks, wrist, INDEX_TIP, INDEX_PIP) &&
            extendedFinger(landmarks, wrist, MIDDLE_TIP, MIDDLE_PIP) &&
            extendedFinger(landmarks, wrist, RING_TIP, RING_PIP) &&
            extendedFinger(landmarks, wrist, PINKY_TIP, PINKY_PIP)
    }

    private fun classifyPose(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        wrist: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Pose = when {
        isKissPose(landmarks, wrist) -> Pose.KISS
        isOpenPalm(landmarks, wrist) -> Pose.OPEN
        else -> Pose.NONE
    }

    /**
     * Tracks the open-palm <-> kiss sequence and fires the play/pause toggle
     * only on a confirmed transition between the two - open held, then
     * folded into a kiss (pause), or kiss held, then opened back up (play).
     * Landing on the same pose twice in a row (e.g. open, briefly lost,
     * open again) does not refire.
     */
    private fun handlePoseFrame(poseNow: Pose, now: Long) {
        if (poseNow != candidatePose) {
            candidatePose = poseNow
            candidateStartTime = now
        }
        lastCandidateTrueTime = now

        val held = now - candidateStartTime
        if (held < POSE_HOLD_MS || candidatePose == confirmedPose) return

        val previousConfirmed = confirmedPose
        confirmedPose = candidatePose

        val isOpenToKiss = previousConfirmed == Pose.OPEN && confirmedPose == Pose.KISS
        val isKissToOpen = previousConfirmed == Pose.KISS && confirmedPose == Pose.OPEN
        if (!isOpenToKiss && !isKissToOpen) return

        if (now - lastToggleFireTime < TOGGLE_COOLDOWN_MS) return

        lastToggleFireTime = now
        val label = if (isOpenToKiss) "open hand -> kiss (pause)" else "kiss -> open hand (play)"
        Log.d(TAG, "Pose transition: $label")
        mainHandler.post { onSwipe("toggle") }
    }

    /** Resets the open/kiss sequence once no recognized pose has been seen for longer than the grace period. */
    private fun maybeEndPoseSession(now: Long) {
        if (candidatePose != Pose.NONE && now - lastCandidateTrueTime > POSE_LOSS_GRACE_MS) {
            candidatePose = Pose.NONE
            confirmedPose = Pose.NONE
        }
    }

    private class SimpleLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.CREATED
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
