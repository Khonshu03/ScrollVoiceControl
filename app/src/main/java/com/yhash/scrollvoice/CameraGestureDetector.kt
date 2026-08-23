package com.yhash.scrollvoice

import android.content.Context
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Watches the front camera for a hand/finger swiping through the air in
 * front of the phone - not a phone tilt (see MotionDetector), not touching
 * the screen at all. Reports "up" / "down" the same way the other
 * detectors do.
 *
 * This does NOT do full hand-skeleton tracking (no bundled ML model, no
 * MediaPipe/ML Kit dependency). Instead it reads only the Y (luminance)
 * plane of each analysis frame, slices it into horizontal bands, and
 * tracks a brightness-change "centroid" - basically: where in the frame is
 * something moving right now. A hand swiping past the lens produces a
 * strong, fast-moving centroid; ambient scene motion mostly doesn't. This
 * is far cheaper than real hand tracking and tends to be plenty reliable
 * for a deliberate swipe gesture at arm's length.
 *
 * Swipe your hand/finger upward past the camera (mimicking a swipe-up on
 * the screen) -> "down" (next reel). Swipe downward -> "up" (previous
 * reel). This matches the physical swipe direction Reels/TikTok already
 * use for "next". Flip INVERT_DIRECTION below if it feels backwards once
 * you try it.
 */
class CameraGestureDetector(
    private val context: Context,
    private val onSwipe: (direction: String) -> Unit
) {
    companion object {
        private const val TAG = "CameraGestureDetector"

        private const val INVERT_DIRECTION = false

        // How many horizontal bands the frame is sliced into vertically to
        // build the motion centroid signal. More bands = finer position
        // resolution but noisier per-band averages.
        private const val BAND_COUNT = 8

        // Only reads every Nth row/col of the Y plane. Keeps this cheap
        // enough to run on every analysis frame without a backlog.
        private const val SAMPLE_STEP = 4

        // Summed absolute per-band brightness change (0-255 scale, summed
        // across BAND_COUNT bands) needed to consider a gesture as having
        // started. Raise this if background motion (people walking past,
        // screen brightness changes) triggers it too easily; lower it if
        // a real hand swipe doesn't register. This is the main constant
        // to tune per lighting condition.
        private const val MOTION_START_THRESHOLD = 55.0

        // Lower bar to keep an in-progress gesture "alive" between frames
        // so a genuine swipe doesn't fragment into several small triggers.
        private const val MOTION_CONTINUE_THRESHOLD = 25.0

        // Minimum vertical travel of the motion centroid (in band units,
        // out of BAND_COUNT - 1 total range) to count as a deliberate
        // swipe rather than jitter or a hand just resting in frame.
        private const val MIN_SWIPE_BAND_DELTA = 2.5

        private const val MIN_GESTURE_DURATION_MS = 60L

        // If motion stays above MOTION_CONTINUE_THRESHOLD longer than
        // this, treat it as ambient motion (not a quick swipe) and reset
        // rather than waiting indefinitely for it to settle.
        private const val MAX_GESTURE_DURATION_MS = 900L

        // Must be idle this long after firing before the next gesture can
        // start, so one swipe doesn't get read as two.
        private const val COOLDOWN_MS = 700L
    }

    private enum class State { IDLE, TRACKING }

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private val lifecycleOwner = SimpleLifecycleOwner()

    private var previousBandAvg: DoubleArray? = null
    private var state = State.IDLE
    private var gestureStartTime = 0L
    private var startCentroid = 0.0
    private var lastCentroid = 0.0
    private var lastFireTime = 0L

    fun start() {
        lifecycleOwner.start()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                @Suppress("DEPRECATION") // setTargetResolution is deprecated but simplest for a
                // fixed small analysis frame; ResolutionSelector is overkill here.
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(160, 120))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera gesture detection", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        lifecycleOwner.stop()
        analysisExecutor.shutdown()
        previousBandAvg = null
        state = State.IDLE
    }

    private fun analyze(image: ImageProxy) {
        try {
            processFrame(image)
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            image.close()
        }
    }

    private fun processFrame(image: ImageProxy) {
        val plane = image.planes[0] // Y (luminance) plane - ignore U/V, we don't need color.
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val capacity = buffer.capacity()

        val bandSum = DoubleArray(BAND_COUNT)
        val bandCount = IntArray(BAND_COUNT)

        var row = 0
        while (row < height) {
            val band = (row * BAND_COUNT / height).coerceIn(0, BAND_COUNT - 1)
            val rowStart = row * rowStride
            var col = 0
            while (col < width) {
                val idx = rowStart + col * pixelStride
                if (idx in 0 until capacity) {
                    val luminance = (buffer.get(idx).toInt() and 0xFF).toDouble()
                    bandSum[band] = bandSum[band] + luminance
                    bandCount[band] = bandCount[band] + 1
                }
                col += SAMPLE_STEP
            }
            row += SAMPLE_STEP
        }

        val bandAvg = DoubleArray(BAND_COUNT) { i ->
            if (bandCount[i] > 0) bandSum[i] / bandCount[i] else 0.0
        }

        val previous = previousBandAvg
        previousBandAvg = bandAvg
        if (previous == null) return // first frame - nothing to diff against yet

        var totalMotion = 0.0
        var weightedSum = 0.0
        for (i in 0 until BAND_COUNT) {
            val delta = abs(bandAvg[i] - previous[i])
            totalMotion += delta
            weightedSum += i * delta
        }
        // centroid is a continuous position from 0 (top of frame) to
        // BAND_COUNT - 1 (bottom of frame), weighted toward wherever the
        // brightness is changing most this frame.
        val centroid = if (totalMotion > 0.01) weightedSum / totalMotion else lastCentroid

        val now = System.currentTimeMillis()
        when (state) {
            State.IDLE -> {
                if (now - lastFireTime > COOLDOWN_MS && totalMotion > MOTION_START_THRESHOLD) {
                    state = State.TRACKING
                    gestureStartTime = now
                    startCentroid = centroid
                    lastCentroid = centroid
                }
            }
            State.TRACKING -> {
                lastCentroid = centroid
                val duration = now - gestureStartTime
                if (totalMotion < MOTION_CONTINUE_THRESHOLD || duration > MAX_GESTURE_DURATION_MS) {
                    if (duration >= MIN_GESTURE_DURATION_MS) {
                        evaluateGesture(startCentroid, lastCentroid, now)
                    }
                    state = State.IDLE
                }
            }
        }
    }

    private fun evaluateGesture(start: Double, end: Double, now: Long) {
        val delta = end - start
        if (abs(delta) < MIN_SWIPE_BAND_DELTA) return

        // Centroid decreasing = the moving thing (hand) traveled toward
        // the top of the frame, i.e. swiped upward.
        var direction = if (delta < 0) "down" else "up"
        if (INVERT_DIRECTION) {
            direction = if (direction == "down") "up" else "down"
        }
        lastFireTime = now
        Log.d(TAG, "Camera swipe detected -> $direction (centroid delta=$delta)")
        onSwipe(direction)
    }

    /**
     * CameraX needs a LifecycleOwner to bind to, but this detector runs
     * from a Service with no Activity/Fragment around. This is a minimal
     * standalone one driven manually by start()/stop().
     */
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
