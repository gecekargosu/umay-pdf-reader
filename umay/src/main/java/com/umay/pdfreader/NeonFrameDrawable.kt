package com.umay.pdfreader

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * v24 — NEON ÇERÇEVE (arka plan çizilebiliri).
 *
 * İki mod:
 *  • **Sabit** (`animated = false`): neon renkler yerinde durur — raflar,
 *    araçlar paneli gibi alanları birbirinden ayırır (yanıp sönmez).
 *  • **Hareketli** (`animated = true`): renkli ışık demeti çerçeve boyunca
 *    dolaşır. Yalnız sesli okuma SÜRERKEN kullanılır (ana sayfa "En son
 *    okunan" kartı) — kullanıcı isteği: dinlerken yanar dönerli olsun.
 *
 * Pil dostu: animasyon yalnız açıkça başlatıldığında çalışır ve
 * `setAnimated(false)` / `stop()` ile tamamen durur.
 */
class NeonFrameDrawable @JvmOverloads constructor(
    private val radiusDp: Float = 16f,
    private val strokeDp: Float = 2f,
    private val palette: IntArray = DEFAULT_PALETTE,
    animated: Boolean = false
) : Drawable() {

    private var animatedNow = animated
    private var phase = 0.32f

    /**
     * v36.4 — KÖK NEDEN: cihazda **animator_duration_scale = 0** (animasyonlar
     * kapalı). ValueAnimator o durumda hiç oynamaz → ışık demeti yerinde
     * kalıyordu, kullanıcı "ışıldama yok" diyordu. Artık zaman tabanlı
     * Handler tıklaması: animasyon ölçeğinden bağımsız çalışır.
     */
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var startedAt = 0L

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeDp * DENSITY
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (strokeDp + 2.4f) * DENSITY
        alpha = 60
    }
    private val rect = RectF()

    init {
        if (animated) start()
    }

    /** Animasyonu açar/kapatır (kapalıyken sabit neon çerçeve çizilir). */
    fun setAnimated(on: Boolean) {
        if (on == animatedNow) return
        animatedNow = on
        if (on) start() else stop()
        invalidateSelf()
    }

    fun isAnimated(): Boolean = animatedNow

    /**
     * v32 — SÜREKLİ DEĞİL, DAKİKADA 2–3 IŞILTI.
     *
     * Kullanıcı isteği: ışık demeti durmadan dolaşınca göz yoruyor.
     * Artık 22 sn'lik döngünün yalnız ilk 1,4 sn'sinde süpürme var; kalan
     * sürede sabit sakin neon çerçeve görünür (animasyon boşa çizim yapmaz).
     */
    private var settled = false

    private fun start() {
        stop()
        settled = false
        startedAt = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (!animatedNow) return
                val cycle = CYCLE_MS.toLong()
                val ms = ((SystemClock.uptimeMillis() - startedAt) % cycle).toFloat()
                if (ms <= SWEEP_MS) {
                    phase = (ms / SWEEP_MS).coerceIn(0f, 1f)
                    settled = false
                    invalidateSelf()
                    handler.postDelayed(this, 40L)
                } else {
                    if (!settled) {
                        // Işıltı bitti → sakin çerçeveye dön, bir kez çiz
                        settled = true
                        phase = 0.32f
                        invalidateSelf()
                    }
                    // Bir sonraki süpürmeye kadar uyu (boşa çizim yok).
                    handler.postDelayed(this, (cycle - ms.toLong()).coerceAtLeast(80L))
                }
            }
        }
        ticker = r
        handler.post(r)
    }

    private fun stop() {
        ticker?.let { runCatching { handler.removeCallbacks(it) } }
        ticker = null
        if (!animatedNow) phase = 0.32f
    }

    /** Görünüm ayrılırken çağrılabilir — animasyon kalmasın. */
    fun release() {
        animatedNow = false
        stop()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val inset = linePaint.strokeWidth / 2f
        rect.set(
            b.left + inset, b.top + inset,
            b.right - inset, b.bottom - inset
        )
        val w = rect.width()
        val span = w * 1.1f
        val x0 = -span + (w + 2 * span) * phase
        val shader = LinearGradient(
            x0, rect.top, x0 + span, rect.bottom,
            palette, POSITIONS, Shader.TileMode.CLAMP
        )
        linePaint.shader = shader
        glowPaint.shader = shader
        val r = radiusDp * DENSITY
        if (animatedNow) canvas.drawRoundRect(rect, r, r, glowPaint)
        canvas.drawRoundRect(rect, r, r, linePaint)
    }

    override fun setAlpha(alpha: Int) {
        linePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        linePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        /** 22 sn döngü → dakikada ~2,7 ışıltı (kullanıcı: sürekli olmasın). */
        const val CYCLE_MS = 22_000f
        /** Tek süpürme süresi (ms). */
        const val SWEEP_MS = 1_400f

        private val DENSITY: Float =
            android.content.res.Resources.getSystem().displayMetrics.density
        private val POSITIONS = floatArrayOf(0f, 0.33f, 0.66f, 1f)

        /** Turkuaz → turuncu → mavi (marka paleti). */
        val DEFAULT_PALETTE = intArrayOf(
            Color.parseColor("#00E5FF"),
            Color.parseColor("#FF7A18"),
            Color.parseColor("#4C8DFF"),
            Color.parseColor("#00E5FF")
        )
    }
}
