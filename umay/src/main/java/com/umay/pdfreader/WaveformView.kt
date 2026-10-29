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

    private val barCount = 42
    private val amps = FloatArray(barCount)
    private var active = false
    private var phase = 0f
    private val density = resources.displayMetrics.density

    /**
     * v32 — RENKLİ SPEKTRUM (kullanıcı ekran görüntülerindeki stil):
     * soldan sağa kırmızı → turuncu → sarı → yeşil → camgöbeği → mavi → mor
     * geçişli, alt/üst aynalı ışıklı çubuklar. Önce geniş ve soluk bir "parıltı"
     * katmanı, sonra keskin çubuk çizilir (blur maliyeti yok).
     */
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        alpha = 90
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f * density
        color = 0x14FFFFFF
    }
    private var spectrum: android.graphics.LinearGradient? = null

    /** Son "konuşma darbesi" zamanı — darbe gelmezse çubuklar söner. */
    private var lastPulseAt = 0L
    private var ticking = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) {
                ticking = false
                return
            }
            val now = android.os.SystemClock.uptimeMillis()
            // v5.7 — Konuşma durduysa (darbe yok) çubuklar yavaşça SÖNER ve
            // animasyon tamamen durur. Eskiden okuma bitse de hareket ediyordu.
            if (now - lastPulseAt > SILENCE_MS) {
                var maxAmp = 0f
                for (i in amps.indices) {
                    amps[i] *= 0.82f
                    if (amps[i] > maxAmp) maxAmp = amps[i]
                }
                postInvalidateOnAnimation()
                if (maxAmp < 0.02f) {
                    for (i in amps.indices) amps[i] = 0f
                    postInvalidateOnAnimation()
                    ticking = false
                    return
                }
            }
            postInvalidateOnAnimation()
            postDelayed(this, FRAME_MS)
        }
    }

    /** true = ses çalıyor (dalga), false = duraklatıldı (düz çizgi). */
    fun setActive(value: Boolean) {
        if (value == active) return
        active = value
        removeCallbacks(ticker)
        ticking = false
        if (value) {
            phase = 0f
            for (i in amps.indices) amps[i] = 0.12f
            lastPulseAt = android.os.SystemClock.uptimeMillis()
            ticking = true
            post(ticker)
        } else {
            for (i in amps.indices) amps[i] = 0f
            postInvalidateOnAnimation()
        }
    }

    /**
     * v5.7 — KONUŞMA DARBESİ. Okunan kelime/cümle ilerledikçe çağrılır;
     * çubuklar yalnız bu darbelerle hareket eder. Böylece görsel, okuma ile
     * senkron kalır: okuma yoksa darbe yok → çizgi düz kalır.
     * (Gerçek mikrofon genliği için RECORD_AUDIO + Visualizer gerekir; bu
     * sürümde bilinçli olarak kelime ilerlemesi kullanılıyor.)
     */
    /**
     * v21 — GERÇEK SES SEVİYESİ. ReadService/TtsManager, çalan mp3'ün
     * oynatma konumundan PCM RMS değerini verir; çubuklar YALNIZ bu
     * değerlerle hareket eder. Ses durunca yeni seviye gelmez → söner.
     */
    private var lastLevel = -1f

    fun pushLevel(level: Float) {
        if (!active) return
        val v = level.coerceIn(0f, 1f)
        lastPulseAt = android.os.SystemClock.uptimeMillis()
        // Şekil ancak seviye değişince yenilenir → donuk "sahte" oynama yok
        if (kotlin.math.abs(v - lastLevel) > 0.02f) phase += 0.30f
        lastLevel = v
        for (i in amps.indices) {
            // v33 — gerçek spektrum görünümü: her çubuk FARKLI yükseklikte
            // (eskiden hepsi aynı boyda "tarak" gibi görünüyordu).
            val target = (v * shapeFor(i, phase)).coerceIn(0f, 1f)
            amps[i] = amps[i] * 0.22f + target * 0.78f
        }
        if (!ticking) {
            ticking = true
            post(ticker)
        }
        postInvalidateOnAnimation()
    }

    fun pulse(strength: Float = 1f) {
        if (!active) return
        lastPulseAt = android.os.SystemClock.uptimeMillis()
        phase += 0.55f
        // v21 — daha gerçekçi spektrum: geniş zarf (alçak/yüksek bant) ×
        // ince detay. Tekdüze "çit" görünümü yerine konuşmaya benzer tepe.
        val s = strength.coerceIn(0.2f, 1f)
        for (i in amps.indices) {
            val target = (0.18f + 0.82f * shapeFor(i, phase)) * s
            amps[i] = (amps[i] * 0.35f + target * 0.65f).coerceIn(0f, 1f)
        }
        if (!ticking) {
            ticking = true
            post(ticker)
        }
        postInvalidateOnAnimation()
    }

    fun isActive(): Boolean = active

    /**
     * v33 — çubuk başına zarf: üç farklı frekansın çarpımı sayesinde
     * komşu çubuklar asla aynı boyda olmaz (gerçek spektrum hissi).
     */
    private fun shapeFor(i: Int, ph: Float): Float {
        val a = abs(sin(i * 0.55f + ph * 0.90f))
        val b = abs(sin(i * 0.17f - ph * 0.45f))
        val c = abs(sin(i * 1.31f + ph * 1.70f))
        return (0.12f + 0.52f * a * b + 0.36f * c).coerceIn(0f, 1f)
    }

    private fun buildSpectrum(w: Float) {
        spectrum = android.graphics.LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(
                0xFFFF3B30.toInt(), // kırmızı
                0xFFFF9500.toInt(), // turuncu
                0xFFFFD60A.toInt(), // sarı
                0xFF34C759.toInt(), // yeşil
                0xFF32D7E0.toInt(), // camgöbeği
                0xFF0A84FF.toInt(), // mavi
                0xFFBF5AF2.toInt()  // mor
            ),
            null,
            android.graphics.Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildSpectrum(w.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (spectrum == null) buildSpectrum(w)

        // Duraklatıldı → düz sabit çizgi (sade, dikkat çekmeyen)
        if (!active || amps.all { it <= 0.001f }) {
            barPaint.shader = null
            barPaint.color = COLOR_IDLE
            barPaint.strokeWidth = 2f * density
            canvas.drawLine(0f, h / 2f, w, h / 2f, barPaint)
            return
        }

        // Hafif ızgara (derinlik hissi; ses dalgasıyla yarışmaz)
        val rows = 4
        for (r in 1 until rows) {
            val y = h * r / rows
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val slot = w / barCount
        val radius = (slot * 0.30f).coerceAtLeast(1.4f * density)
        val maxHalf = (h / 2f - 1.5f * density).coerceAtLeast(radius)
        val sh = spectrum
        for (i in 0 until barCount) {
            val a = amps[i].coerceIn(0f, 1f)
            val half = (a * maxHalf).coerceAtLeast(radius * 0.6f)
            val cx = slot * i + slot / 2f
            val bw = radius * 1.7f
            // 1) parıltı katmanı (geniş, soluk)
            glowPaint.shader = sh
            glowPaint.strokeWidth = bw * 2.5f
            canvas.drawLine(cx, h / 2f - half, cx, h / 2f + half, glowPaint)
            // 2) keskin renkli çubuk
            barPaint.shader = sh
            barPaint.strokeWidth = bw
            canvas.drawLine(cx, h / 2f - half, cx, h / 2f + half, barPaint)
        }
        barPaint.shader = null
        glowPaint.shader = null
    }

    override fun onDetachedFromWindow() {
        active = false
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    companion object {
        /** Son darbeden sonra bu süre sessizlik → çubuklar söner. */
        private const val SILENCE_MS = 700L
        private const val FRAME_MS = 33L
        /** Duraklatıldı çizgisi (soluk gri). */
        private const val COLOR_IDLE = 0x558B949E
    }
}
