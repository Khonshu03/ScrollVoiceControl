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

        // Set when the service connects, cleared when it's destroyed.
        // VoiceListenerService calls into this to trigger gestures.
        var instance: ScrollAccessibilityService? = null
            private set
    }

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
     * "back" = perform system back action.
     */
    fun performScroll(direction: String) {
        when (direction) {
            "down" -> swipe(fromBottomFraction = 0.75f, toTopFraction = 0.25f)
            "up" -> swipe(fromBottomFraction = 0.25f, toTopFraction = 0.75f)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
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

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()

        dispatchGesture(gesture, null, null)
    }
}
