package com.yhash.scrollvoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
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
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Real hand-skeleton tracking using MediaPipe's Hand Landmarker (21 points
 * per hand, the same technology behind Google's own hand-tracking demos) -
 * not a brightness-motion guess. Specifically looks for a pointing gesture
 * (index finger extended, other fingers curled) and tracks that fingertip's
 * position. Swipe the pointing finger up/down in front of the camera to
 * scroll; the fingertip position also drives the on-screen cursor so you
 * can see what it's tracking.
 *
 * The ~7MB model file isn't bundled in the app - it's downloaded once from
 * Google's public model server on first use and cached in app storage.
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

        // "up" (finger moving down toward you) needs less travel than "down"
        // (finger moving up away from you) - your hand naturally has less
        // room to move downward toward your body than upward away from it,
        // so an equal threshold makes "up" feel less reliable in practice.
        private const val MIN_SWIPE_DELTA_DOWN = 0.12f // finger rises (Y decreases)
        private const val MIN_SWIPE_DELTA_UP = 0.08f   // finger drops (Y increases)
        private const val MIN_GESTURE_DURATION_MS = 60L
        private const val COOLDOWN_MS = 550L

        // Fast swipes cause motion blur, which can make MediaPipe briefly
        // lose clean landmarks mid-gesture. Tolerate a short gap in
        // "pointing" detection before treating the gesture as actually over.
        private const val POINTING_LOSS_GRACE_MS = 220L

        private const val INVERT_DIRECTION = false
        private const val MIRROR_X = true // front camera: flip so cursor moves the way it feels natural

        // Smooths the cursor's on-screen position only - the raw (unsmoothed)
        // position still drives swipe detection below, so this doesn't dull
        // gesture responsiveness, it just stops the dot from jittering.
        private const val CURSOR_SMOOTHING = 0.35f

        // Standard MediaPipe hand landmark indices.
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

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private val lifecycleOwner = SimpleLifecycleOwner()
    private var handLandmarker: HandLandmarker? = null
    private var stopped = false

    private var refY: Float? = null
    private var refSetTime = 0L
    private var lastPointingTrueTime = 0L
    private var lastFireTime = 0L
    private var smoothedX: Float? = null
    private var smoothedY: Float? = null

    fun start() {
        stopped = false
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

    fun stop() {
        stopped = true
        refY = null
        smoothedX = null
        smoothedY = null
        mainHandler.post {
            cameraProvider?.unbindAll()
            cameraProvider = null
            lifecycleOwner.stop()
        }
        executor.execute {
            handLandmarker?.close()
            handLandmarker = null
        }
        executor.shutdown()
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

    private fun startCamera() {
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                onError?.invoke()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(image: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(image)
            if (bitmap != null) {
                handLandmarker?.detectAsync(BitmapImageBuilder(bitmap).build(), System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis error", e)
        } finally {
            image.close()
        }
    }

    /** Converts a YUV_420_888 camera frame to an upright RGB Bitmap. */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)

        yPlane.buffer.get(nv21, 0, ySize)
        vPlane.buffer.get(nv21, ySize, vSize)
        uPlane.buffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
        val bytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun handleResult(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        val now = System.currentTimeMillis()

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

        // Smooth only the cursor's visual position - gesture math below
        // keeps using the raw displayY so swipe sensitivity stays sharp.
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
            // First frame of a new pointing session - establish baseline.
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
            // Reset baseline to current position immediately, so the
            // natural "bring hand back down" motion after a swipe needs
            // its own full-size movement to count as a new gesture,
            // instead of the retract itself being read as the opposite swipe.
            refY = displayY
            refSetTime = now
        }
    }

    private fun lerp(current: Float?, target: Float): Float =
        if (current == null) target else current + (target - current) * CURSOR_SMOOTHING

    /**
     * The cursor stays visible as long as pointing was seen recently (within
     * the grace window), even if this exact frame lost clean tracking -
     * this is what stops the dot from blinking out during fast motion.
     */
    private fun postCursorUpdate(now: Long) {
        val displayPointing = now - lastPointingTrueTime <= POINTING_LOSS_GRACE_MS
        val x = smoothedX ?: 0.5f
        val y = smoothedY ?: 0.5f
        mainHandler.post { onPositionUpdate?.invoke(x, y, displayPointing) }
    }

    /** Ends the current pointing session once the loss has outlasted the motion-blur grace period. */
    private fun maybeEndSession() {
        val now = System.currentTimeMillis()
        if (refY != null && now - lastPointingTrueTime > POINTING_LOSS_GRACE_MS) {
            refY = null
        }
    }

    private fun fire(delta: Float, now: Long) {
        // Fingertip Y decreasing = moved toward top of frame = swipe upward,
        // which matches Reels/TikTok's physical "swipe up for next" motion.
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
