package com.yhash.scrollvoice

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView

class OverlayService : Service() {

    companion object {
        var instance: OverlayService? = null
            private set
    }

    private var windowManager: WindowManager? = null
    private var dotView: ImageView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        showDot()
    }

    override fun onDestroy() {
        instance = null
        removeDot()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showDot() {
        if (dotView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val sizePx = (22 * resources.displayMetrics.density).toInt()

        val dot = ImageView(this).apply {
            background = makeCircleDrawable(Color.parseColor("#E6555555"))
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * resources.displayMetrics.density).toInt()
            y = (160 * resources.displayMetrics.density).toInt()
        }

        windowManager?.addView(dot, params)
        dotView = dot
    }

    private fun removeDot() {
        dotView?.let { windowManager?.removeView(it) }
        dotView = null
    }

    fun pulse() {
        val view = dotView ?: return
        view.background = makeCircleDrawable(Color.parseColor("#FF4CAF50"))
        handler.postDelayed({
            view.background = makeCircleDrawable(Color.parseColor("#E6555555"))
        }, 350)
    }

    private fun makeCircleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}
