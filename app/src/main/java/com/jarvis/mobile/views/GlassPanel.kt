package com.jarvis.mobile.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A semi-transparent glass panel with subtle cyan border and glow effect.
 * Used throughout the JARVIS HUD for card-like containers.
 */
class GlassPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1400E5FF") // Very subtle cyan tint
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D00E5FF")
        style = Paint.Style.FILL
        maskFilter = android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    private val cornerRadius = 16f * resources.displayMetrics.density
    private val rect = RectF()
    private val glowRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padding = 1f * resources.displayMetrics.density
        rect.set(padding, padding, width - padding, height - padding)
        glowRect.set(
            padding - 4f * resources.displayMetrics.density,
            padding - 4f * resources.displayMetrics.density,
            width - padding + 4f * resources.displayMetrics.density,
            height - padding + 4f * resources.displayMetrics.density
        )

        // Glow effect
        canvas.drawRoundRect(glowRect, cornerRadius + 4f, cornerRadius + 4f, glowPaint)
        // Background
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
        // Border
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
    }
}
