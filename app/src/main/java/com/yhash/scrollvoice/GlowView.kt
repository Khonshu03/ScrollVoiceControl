package com.yhash.scrollvoice

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Renders a soft, genuinely-blurred glow behind a shape - the halo-behind-a-
 * button look. Android's `<shape>` radial-gradient drawables can only fake
 * this with hard-edged concentric rings, which reads as banded rather than
 * soft. This uses a real [BlurMaskFilter] instead, which needs software
 * rendering (mask filters aren't supported on the hardware-accelerated
 * layer) - that's set automatically here, so nothing else has to opt in.
 *
 * Usage: size the view generously larger than the button it sits behind
 * (the blur needs room to bleed outward within the view's own bounds, or
 * it gets clipped at the edge) and center the button on top of it.
 */
class GlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var glowColor: Int = 0xFF8B5CF6.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var blurRadiusPx: Float = 40f * resources.displayMetrics.density
        set(value) {
            field = value
            rebuildBlur()
            invalidate()
        }

    var shapeCornerRadiusPx: Float = 28f * resources.displayMetrics.density
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        context.theme.obtainStyledAttributes(attrs, R.styleable.GlowView, 0, 0).apply {
            try {
                glowColor = getColor(R.styleable.GlowView_glowColor, glowColor)
                blurRadiusPx = getDimension(R.styleable.GlowView_glowBlurRadius, blurRadiusPx)
                shapeCornerRadiusPx = getDimension(R.styleable.GlowView_glowCornerRadius, shapeCornerRadiusPx)
            } finally {
                recycle()
            }
        }
        rebuildBlur()
    }

    private fun rebuildBlur() {
        paint.maskFilter = BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = blurRadiusPx
        rect.set(inset, inset, (w - inset).coerceAtLeast(inset), (h - inset).coerceAtLeast(inset))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = glowColor
        canvas.drawRoundRect(rect, shapeCornerRadiusPx, shapeCornerRadiusPx, paint)
    }
}
