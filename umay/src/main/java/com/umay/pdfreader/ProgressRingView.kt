package com.umay.pdfreader

import android.content.Context
import android.graphics.Canvas
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

    // -1: henüz hiç ayarlanmadı → setPercent(0) bile contentDescription güncellesin
    private var percent = -1
    private val density = resources.displayMetrics.density

    /** Kapağın üstünde okunsun diye şeffaf koyu zemin halkası. */
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xB3000000.toInt()  // %70 siyah
    }

    /** v24 — Rakamın arkasına KOYU disk: beyaz kapakta da yazı okunur kalır. */
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF2101A2B.toInt()   // %95 koyu lacivert (beyaz kapakta tam kontrast)
    }

    /** v25 — Yazının dış hattı: neon rakamın beyaz zeminde kaybolmasını önler. */
    private val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeJoin = Paint.Join.ROUND
        color = 0xFF070C14.toInt()
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /** v24 — Rojetin neon rengi (rafın çerçeve rengiyle aynı). */
    private var accent: Int = COLOR_ACCENT

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        color = COLOR_ACCENT
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // v24 — yazı beyaz DEĞİL: neon renk (beyaz kapakta okunmuyordu)
        color = COLOR_ACCENT
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(4f * density, 0f, 0f, 0x99000000.toInt())
    }

    private val arcRect = RectF()

    /**
     * v24 — Rafın neon rengini uygular. %100 olan kitap yine turuncu kalır
     * (tamamlanma vurgusu); diğerleri raf rengiyle çizilir.
     */
    fun setAccent(color: Int) {
        if (color == accent) return
        accent = color
        applyColors()
        invalidate()
    }

    private fun applyColors() {
        val c = if (percent >= 100) COLOR_ORANGE else accent
        ringPaint.color = c
        textPaint.color = c
        textPaint.setShadowLayer(5f * density, 0f, 0f, (c and 0x00FFFFFF) or 0x66000000)
    }

    fun setPercent(p: Int) {
        val np = p.coerceIn(0, 100)
        if (np == percent) return
        percent = np
        applyColors()
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

        // v24 — önce koyu disk (beyaz kapak üstünde kontrast), sonra halka
        canvas.drawCircle(w / 2f, h / 2f, minOf(w, h) / 2f - ringPaint.strokeWidth / 2f, discPaint)
        canvas.drawOval(arcRect, bgPaint)
        if (percent > 0) {
            canvas.drawArc(arcRect, -90f, 360f * percent / 100f, false, ringPaint)
        }

        // Rakam: "%" işareti + yüzde (spec: okunma oranında açıkça %)
        val label = "%$percent"
        textPaint.textSize = when {
            label.length > 3 -> h * 0.27f   // %100
            label.length > 2 -> h * 0.31f   // %18 gibi 2 haneli
            else -> h * 0.37f
        }
        textOutlinePaint.textSize = textPaint.textSize
        val baseline = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        // v25 — önce koyu dış hat, sonra neon dolgu: her zeminde okunur
        canvas.drawText(label, w / 2f, baseline, textOutlinePaint)
        canvas.drawText(label, w / 2f, baseline, textPaint)
    }

    companion object {
        private const val COLOR_ACCENT = 0xFF00E5FF.toInt()   // Elektrik turkuaz
        private const val COLOR_ORANGE = 0xFFFF6B00.toInt()   // Sıcak turuncu
    }
}
