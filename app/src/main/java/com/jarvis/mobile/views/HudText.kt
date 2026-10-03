package com.jarvis.mobile.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A simple HUD-styled text display with optional glow.
 * Used for labels, values, and system readouts.
 */
class HudText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var text: String = ""
    private var textColor: Int = Color.parseColor("#E8ECF4")
    private var textSizePx: Float = 14f * resources.displayMetrics.density
    private var isGlowing: Boolean = false

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@HudText.textColor
        textSize = this@HudText.textSizePx
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E5FF")
        textSize = this@HudText.textSizePx
        maskFilter = android.graphics.BlurMaskFilter(4f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    fun setText(text: String) {
        this.text = text
        invalidate()
    }

    fun setTextColor(color: Int) {
        this.textColor = color
        textPaint.color = color
        invalidate()
    }

    fun setTextSize(sizeSp: Float) {
        this.textSizePx = sizeSp * resources.displayMetrics.density
        textPaint.textSize = this.textSizePx
        glowPaint.textSize = this.textSizePx
        invalidate()
    }

    fun setGlowing(glowing: Boolean) {
        this.isGlowing = glowing
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (text.isNotEmpty()) {
            if (isGlowing) {
                canvas.drawText(text, 0f, height * 0.7f, glowPaint)
            }
            canvas.drawText(text, 0f, height * 0.7f, textPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (text.isNotEmpty()) {
            (textPaint.measureText(text) + paddingStart + paddingEnd).toInt()
        } else {
            0
        }
        val height = (textSizePx * 1.4f + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(width, height)
    }
}
