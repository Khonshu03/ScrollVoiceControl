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
        private const val INDEX_PIP = 6
        private const val INDEX_TIP = 8
        private const val MIDDLE_PIP = 10
        private const val MIDDLE_TIP = 12
        private const val RING_PIP = 14
        private const val RING_TIP = 16
        private const val PINKY_PIP = 18
        private const val PINKY_TIP = 20
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
    private var smoothedX: Float? = null
    private var smoothedY: Float? = null

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
            postCursorUpdate(now)
            return
        }

        val landmarks = hands[0]
        val wrist = landmarks[WRIST]

        fun extended(tipIdx: Int, pipIdx: Int): Boolean {
            val tip = landmarks[tipIdx]
            val pip = landmarks[pipIdx]
            val tipDist = hypot((tip.x() - wrist.x()).toDouble(), (tip.y() - wrist.y()).toDouble())
            val pipDist = hypot((pip.x() - wrist.x()).toDouble(), (pip.y() - wrist.y()).toDouble())
            return tipDist > pipDist * EXTENDED_RATIO
        }

        val indexExtended = extended(INDEX_TIP, INDEX_PIP)
        val middleExtended = extended(MIDDLE_TIP, MIDDLE_PIP)
        val ringExtended = extended(RING_TIP, RING_PIP)
        val pinkyExtended = extended(PINKY_TIP, PINKY_PIP)
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

        val currentRef = refY
        if (currentRef == null) {
            refY = displayY
            refSetTime = now
            return
        }

        val duration = now - refSetTime
        val delta = displayY - currentRef
        val inCooldown = now - lastFireTime < COOLDOWN_MS

        val threshold = if (delta < 0) MIN_SWIPE_DELTA_DOWN else MIN_SWIPE_DELTA_UP
        if (!inCooldown && duration >= MIN_GESTURE_DURATION_MS && kotlin.math.abs(delta) >= threshold) {
            fire(delta, now)
            refY = displayY
            refSetTime = now
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
    }

    private fun fire(delta: Float, now: Long) {
        var direction = if (delta < 0) "down" else "up"
        if (INVERT_DIRECTION) direction = if (direction == "down") "up" else "down"

        lastFireTime = now
        Log.d(TAG, "Pointing swipe -> $direction (delta=$delta)")
        mainHandler.post { onSwipe(direction) }
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
