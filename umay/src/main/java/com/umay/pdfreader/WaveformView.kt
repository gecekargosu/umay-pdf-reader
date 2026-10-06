package com.umay.pdfreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * Ses dalgalanması görselleştiricisi (ADIM 3).
 *
 * - Okuma **sürerken**: turkuaz → turkuaz/turuncu barlar yumuşak bir zarfla
 *   yukarı-aşağı hareket eder (~30 fps, `postDelayed` zamanlayıcısı).
 * - **Duraklatıldığında / okuma bittiğinde**: ortada düz sabit çizgi.
 *
 * Bilinçli teknik karar: gerçek ses genliği Android'de yalnızca
 * `android.media.audiofx.Visualizer` + **RECORD_AUDIO (mikrofon) izni** ile
 * alınabilir. Bir PDF okuyucuya mikrofon izni eklemek hem gereksiz hem
 * gizlilik açısından yanlış olurdu; bu yüzden hareket çalma DURUMUNDAN
 * beslenen deterministik bir zarfla üretilir. **Hiçbir ses kaydı/alımı yoktur.**
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 32
    private val amps = FloatArray(barCount)
    private var active = false
    private var phase = 0f
    private val density = resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            step()
            postInvalidateOnAnimation()
            postDelayed(this, FRAME_MS)
        }
    }

    /** true = ses çalıyor (dalga), false = duraklatıldı (düz çizgi). */
    fun setActive(value: Boolean) {
        if (value == active) return
        active = value
        removeCallbacks(ticker)
        if (value) {
            phase = 0f
            for (i in amps.indices) amps[i] = 0.2f
            post(ticker)
        } else {
            for (i in amps.indices) amps[i] = 0f
            postInvalidateOnAnimation()
        }
    }

    fun isActive(): Boolean = active

    private fun step() {
        phase += 0.28f
        for (i in amps.indices) {
            val t = i / barCount.toFloat()
            // İki sinüsün karışımı: doğal dalga hissi (sabit gürültü değil)
            val target = 0.30f +
                0.42f * abs(sin(phase + t * 6.2f)) +
                0.28f * abs(sin(phase * 1.7f + t * 11f))
            amps[i] += (target.coerceAtMost(1f) - amps[i]) * 0.35f  // yumuşak sönümleme
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Duraklatıldı → düz sabit çizgi
        if (!active || amps.all { it <= 0.001f }) {
            barPaint.color = COLOR_IDLE
            barPaint.strokeWidth = 2f * density
            canvas.drawLine(0f, h / 2f, w, h / 2f, barPaint)
            return
        }

        val slot = w / barCount
        val radius = (slot * 0.28f).coerceAtLeast(1.5f * density)
        val maxHalf = (h / 2f - 2f * density).coerceAtLeast(radius)
        for (i in 0 until barCount) {
            val a = amps[i].coerceIn(0f, 1f)
            val half = (a * maxHalf).coerceAtLeast(radius)
            val cx = slot * i + slot / 2f
            barPaint.strokeWidth = radius * 2f
            // Zirve (>0.72) turuncu, gerisi turkuaz
            barPaint.color = if (a > 0.72f) COLOR_ORANGE else COLOR_ACCENT
            canvas.drawLine(cx, h / 2f - half, cx, h / 2f + half, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        active = false
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val FRAME_MS = 33L
        private const val COLOR_ACCENT = 0xFF22D3EE.toInt()   // Elektrik turkuaz
        private const val COLOR_ORANGE = 0xFFFF9F45.toInt()   // Sıcak turuncu
        private const val COLOR_IDLE = 0x558AA0C2             // Duraklatıldı çizgisi
    }
}
