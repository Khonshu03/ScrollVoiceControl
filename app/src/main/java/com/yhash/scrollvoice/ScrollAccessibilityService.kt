package com.yhash.scrollvoice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Performs the actual on-screen gestures. Any app (FB, IG, TikTok) can be
 * scrolled this way because accessibility gestures are system-wide and don't
 * need the target app's cooperation.
 */
class ScrollAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScrollA11yService"
        private const val SWIPE_DURATION_MS = 300L
        private const val TAP_DURATION_MS = 50L

        // Set when the service connects, cleared when it's destroyed.
        // VoiceListenerService calls into this to trigger gestures.
        var instance: ScrollAccessibilityService? = null
            private set
    }

    // Android only allows one in-flight gesture from a service at a time -
    // dispatchGesture() silently returns false if you call it again before
    // the previous one finishes. Tracking this so a rapid-fire command
    // (e.g. a fast clap burst) doesn't get dropped without any feedback.
    @Volatile private var gestureInFlight = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used - we only need gesture dispatch, not event monitoring.
    }

    override fun onInterrupt() {}

    /**
     * direction: "down" = advance to next reel, "up" = go back to previous reel,
     * "back" = perform system back action, "toggle" = tap center of screen
     * to play/pause the current video.
     */
    fun performAction(direction: String) {
        when (direction) {
            "down" -> swipe(fromBottomFraction = 0.75f, toTopFraction = 0.25f)
            "up" -> swipe(fromBottomFraction = 0.25f, toTopFraction = 0.75f)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "toggle" -> tap()
        }
    }

    private fun swipe(fromBottomFraction: Float, toTopFraction: Float) {
        val metrics: DisplayMetrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * fromBottomFraction
        val endY = metrics.heightPixels * toTopFraction

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        dispatch(path, SWIPE_DURATION_MS, "swipe")
    }

    /** Single tap at the center of the screen - toggles play/pause on virtually every video player/feed. */
    private fun tap() {
        val metrics: DisplayMetrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(metrics.widthPixels / 2f, metrics.heightPixels / 2f)
        }
        dispatch(path, TAP_DURATION_MS, "tap")
    }

    private fun dispatch(path: Path, durationMs: Long, label: String) {
        if (gestureInFlight) {
            // A previous gesture hasn't finished yet - dispatching now would
            // just be silently rejected. Log it so it's at least visible
            // why this particular command didn't do anything, instead of
            // looking like a random miss.
            Log.w(TAG, "Skipped $label - previous gesture still in flight")
            OverlayService.instance?.showError()
            return
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        gestureInFlight = true
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureInFlight = false
                OverlayService.instance?.clearError()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureInFlight = false
                Log.w(TAG, "$label gesture was cancelled by the system")
                OverlayService.instance?.showError()
            }
        }, null)

        if (!accepted) {
            // dispatchGesture() returned false synchronously - it never
            // started at all (service not ready, screen off, etc).
            gestureInFlight = false
            Log.w(TAG, "dispatchGesture() rejected the $label")
            OverlayService.instance?.showError()
        }
    }
}
