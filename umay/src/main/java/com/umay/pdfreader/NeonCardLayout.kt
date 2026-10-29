package com.umay.pdfreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * v21 — NEON HAREKETLİ ÇERÇEVE (ders kartları).
 *
 * Ders kartının zeminini DEĞİŞTİRMEZ; üzerine hareketli bir ışık halkası
 * çizer. Işık, çerçevenin etrafında yavaşça dolaşır (turkuaz → turuncu →
 * mavi). Ekran kapalıyken/arka plandayken animasyon durur (pil dostu);
 * animasyon yalnız görünürken 25 fps'te çalışır.
 *
 * Not: kart düzeni LinearLayout olduğu için alt görünümler etkilenmez;
 * çerçeve onDraw'da YALNIZCA kenara çizilir.
 */
class NeonCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val radius = 12f * density

    /** Yumuşak dış parıltı (geniş, yarı saydam). */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        alpha = 70
    }

    /** Keskin neon çizgi. */
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }

    /**
     * v25 — SABİT ZEMİN ÇİZGİSİ. Hareketli ışık demeti bazen çerçevenin
     * dışına kayıyor ve kart tamamen çerçevesiz görünüyordu (profil kartı).
     * Bu soluk turkuaz çizgi her zaman çizilir; ışık demeti üstünde dolaşır.
     */
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
        color = 0x8C22D3EE.toInt()   // %55 turkuaz
    }

    private val rect = RectF()
    private var shader: Shader? = null
    private var phase = 0f

    /**
     * v32 — IŞILTİ ARALIĞI. Kullanıcı isteği: ışık demeti sürekli dolaşmasın;
     * dakikada 2–3 kez kısa bir parıltı geçsin. Sürekli hareket hem göz
     * yoruyor hem pili tüketiyordu.
     *
     * Zamanlama: ~1,4 sn'lik süpürme + 18–26 sn sessiz bekleme.
     * Kartlar birbirinden bağımsız (rastgele faz) parlar → hepsi aynı anda
     * yanıp sönmüş görünmez.
     */
    private var sweepAt = 0L
    private var waiting = true

    private fun scheduleNextSweep(now: Long) {
        // 18–26 sn arası rastgele bekleme → dakikada 2–3 ışıltı
        val idle = 18_000L + (Math.random() * 8_000L).toLong()
        sweepAt = now + idle
        waiting = true
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            if (waiting) {
                if (now >= sweepAt) waiting = false
                else {
                    postDelayed(this, 200L)
                    return
                }
            }
            val t = (now - sweepAt) / SWEEP_MS.toFloat()
            if (t >= 1f) {
                // Süpürme bitti → şerit kaldır, sakin neon çerçeve kalsın
                phase = 0f
                shader = null
                invalidate()
                scheduleNextSweep(now)
                postDelayed(this, 200L)
                return
            }
            phase = t
            applyShader()
            invalidate()
            postDelayed(this, 40L)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        // İlk ışıltı da rastgele: ekran açılır açılmaz hepsi parlamasın
        scheduleNextSweep(android.os.SystemClock.uptimeMillis())
        sweepAt = android.os.SystemClock.uptimeMillis() +
            3_000L + (Math.random() * 9_000L).toLong()
    }

    private fun applyShader() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Renkli ışık demeti çerçeve boyunca kayar: başlangıç noktası faz ile
        // yatay eksende dolaşır, renkler marka paletinden (turkuaz/turuncu/mavi).
        val span = (w + h) * 0.7f
        val x0 = -span + (w + 2 * span) * phase
        val x1 = x0 + span
        val colors = intArrayOf(
            Color.TRANSPARENT,
            color(0xFF00E5FF),
            color(0xFFFF7A18),
            color(0xFF4C8DFF),
            Color.TRANSPARENT
        )
        val positions = floatArrayOf(0f, 0.30f, 0.50f, 0.70f, 1f)
        shader = LinearGradient(
            x0, 0f, x1, h,
            colors, positions, Shader.TileMode.CLAMP
        )
    }

    private fun color(c: Long): Int = c.toInt()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(
            linePaint.strokeWidth / 2f,
            linePaint.strokeWidth / 2f,
            w - linePaint.strokeWidth / 2f,
            h - linePaint.strokeWidth / 2f
        )
        applyShader()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rect.width() <= 0f) return
        // v25 — önce her zaman görünen sabit neon çerçeve
        linePaint.shader = null
        linePaint.color = basePaint.color
        canvas.drawRoundRect(rect, radius, radius, linePaint)
        // sonra çerçeve boyunca dolaşan parlak ışık demeti
        val s = shader ?: return
        glowPaint.shader = s
        linePaint.shader = s
        linePaint.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(rect, radius, radius, glowPaint)
        canvas.drawRoundRect(rect, radius, radius, linePaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Görünür olduğunda başlat (liste kaydırma performansı için).
        removeCallbacks(ticker)
        if (isShown) post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            removeCallbacks(ticker)
            // Görünür olduğunda sakin başla, kısa süre sonra ilk ışıltı
            if (shader == null) scheduleNextSweep(android.os.SystemClock.uptimeMillis())
            post(ticker)
        } else {
            removeCallbacks(ticker)
        }
    }

    companion object {
        /** Tek ışıltının süresi (ms). */
        private const val SWEEP_MS = 1_400L
    }
}
