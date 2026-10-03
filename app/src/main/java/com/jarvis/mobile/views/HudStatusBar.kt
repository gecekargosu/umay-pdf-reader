package com.jarvis.mobile.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A horizontal HUD status bar with animated fill.
 * Shows metrics like CPU, RAM, Battery with a futuristic progress bar.
 */
class HudStatusBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress: Float = 0f
    private var label: String = ""
    private var value: String = ""

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D00E5FF")
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E5FF")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8ECF4")
        textSize = 10f * resources.displayMetrics.density
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = 9f * resources.displayMetrics.density
    }

    private val barHeight = 6f * resources.displayMetrics.density
    private val cornerRadius = 3f * resources.displayMetrics.density

    fun setProgress(value: Float) {
        this.progress = value.coerceIn(0f, 100f)
        invalidate()
    }

    fun setLabel(label: String) {
        this.label = label
        invalidate()
    }

    fun setDisplayValue(value: String) {
        this.value = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val barY = height - barHeight - 4f * resources.displayMetrics.density
        val barWidth = width.toFloat()

        // Label
        canvas.drawText(label, 0f, barY - 4f * resources.displayMetrics.density, labelPaint)

        // Value on right
        val valueWidth = textPaint.measureText(value)
        canvas.drawText(value, barWidth - valueWidth, barY - 4f * resources.displayMetrics.density, textPaint)

        // Background bar
        val bgRect = android.graphics.RectF(0f, barY, barWidth, barY + barHeight)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

        // Fill bar
        val fillWidth = barWidth * (progress / 100f)
        if (fillWidth > 0) {
            val fillRect = android.graphics.RectF(0f, barY, fillWidth, barY + barHeight)
            // Glow under fill
            val glowRect = android.graphics.RectF(
                -2f, barY - 2f, fillWidth + 2f, barY + barHeight + 2f
            )
            canvas.drawRoundRect(glowRect, cornerRadius, cornerRadius, glowPaint)
            canvas.drawRoundRect(fillRect, cornerRadius, cornerRadius, fillPaint)
        }
    }
}
