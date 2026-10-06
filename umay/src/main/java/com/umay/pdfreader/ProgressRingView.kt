package com.umay.pdfreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Kitap kapağı köşesinde dairesel okuma yüzdesi rozeti (ADIM 2).
 *
 * - %0…99 → elektrik turkuaz halka
 * - %100  → sıcak turuncu halka (kitap tamamlanmış)
 * - Merkezde yüzde rakamı; `contentDescription` "%N okundu" biçiminde.
 *
 * Çizim tamamen saf `Canvas` (hiçbir görünürlük animasyonu yok;
 * yalnız `setPercent` çağrısında yeniden çizilir).
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var percent = 0
    private val density = resources.displayMetrics.density

    /** Kapağın üstünde okunsun diye şeffaf koyu zemin halkası. */
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xB3000000.toInt()  // %70 siyah
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        color = COLOR_ACCENT
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        setShadowLayer(2.5f * density, 0f, density, 0xE0000000.toInt())
    }

    private val arcRect = RectF()

    fun setPercent(p: Int) {
        val np = p.coerceIn(0, 100)
        if (np == percent) return
        percent = np
        ringPaint.color = if (percent >= 100) COLOR_ORANGE else COLOR_ACCENT
        contentDescription = context.getString(R.string.rozet_okundu, percent)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val inset = ringPaint.strokeWidth / 2f + 1f * density
        arcRect.set(inset, inset, w - inset, h - inset)

        canvas.drawOval(arcRect, bgPaint)
        if (percent > 0) {
            canvas.drawArc(arcRect, -90f, 360f * percent / 100f, false, ringPaint)
        }

        // Rakam: 3 haneli (100) taşmasın diye küçülür
        val label = percent.toString()
        textPaint.textSize = if (label.length > 2) h * 0.30f else h * 0.36f
        val baseline = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, w / 2f, baseline, textPaint)
    }

    companion object {
        private const val COLOR_ACCENT = 0xFF22D3EE.toInt()   // Elektrik turkuaz
        private const val COLOR_ORANGE = 0xFFFF9F45.toInt()   // Sıcak turuncu
    }
}
