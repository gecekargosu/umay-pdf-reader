package com.umay.pdfreader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * v5.21 — ANA EKRAN HAVA EFEKTLERİ.
 *
 * Hava durumuna göre tüm ekranda çok hafif bir katman çizer:
 *  • A / PB  → güneş ışınları + süzülen bulutlar
 *  • CB / KB → bulutlar
 *  • Y / ÇY  → yağmur damlaları + bulutlar (çisenti)
 *  • G       → yağmur + ara ara şimşek
 *  • K / KKY → kar taneleri
 *
 * Dokunmayı engellemez (clickable=false), pil dostudur: yalnız görünürken
 * 30 fps'lik tek bir ValueAnimator çalışır.
 */
enum class WeatherMode { SUNNY, PARTLY, CLOUDY, RAIN, STORM, SNOW }

object WeatherModeFx {
    fun of(code: String?): WeatherMode = when (code?.uppercase()?.trim()) {
        "A" -> WeatherMode.SUNNY
        "PB" -> WeatherMode.PARTLY
        "CB", "KB", "S", "PUS", "R" -> WeatherMode.CLOUDY
        "G" -> WeatherMode.STORM
        "K", "KY", "KKY" -> WeatherMode.SNOW
        "Y", "SY", "MS", "HY", "DY", "ÇY" -> WeatherMode.RAIN
        else -> WeatherMode.PARTLY
    }
}

class WeatherFxView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var mode: WeatherMode = WeatherMode.PARTLY
        set(value) {
            if (field == value) return
            field = value
            seed()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = Random(7)
    private var phase = 0f

    private val drops = ArrayList<FloatArray>()   // x, y, hız, uzunluk
    private val flakes = ArrayList<FloatArray>()  // x, y, hız, yarıçap
    private val clouds = ArrayList<FloatArray>()  // x, y, ölçek, hız
    private var flash = 0f
    private var flashAt = 0L

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 8000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase += 0.018f
            if (phase > 1000f) phase = 0f
            invalidate()
        }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        seed()
    }

    private fun seed() {
        val w = if (width > 0) width.toFloat() else 1080f
        val h = if (height > 0) height.toFloat() else 2200f
        drops.clear(); flakes.clear(); clouds.clear()
        repeat(if (mode == WeatherMode.STORM) 70 else 55) {
            drops.add(floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h,
                900f + rnd.nextFloat() * 700f, 16f + rnd.nextFloat() * 26f))
        }
        repeat(46) {
            flakes.add(floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h,
                40f + rnd.nextFloat() * 70f, 2f + rnd.nextFloat() * 3.5f))
        }
        repeat(5) {
            clouds.add(floatArrayOf(rnd.nextFloat() * w, h * (0.05f + rnd.nextFloat() * 0.55f),
                90f + rnd.nextFloat() * 70f, 6f + rnd.nextFloat() * 10f))
        }
        flash = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        seed()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    /** Ekran arka plana geçince animasyon durur (pil). */
    fun pauseFx() { animator.cancel() }

    /** Ekrana dönünce animasyon yeniden başlar. */
    fun resumeFx() { if (!animator.isStarted) animator.start() }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        when (mode) {
            WeatherMode.SUNNY -> drawLightWaves(canvas, w, h, 1f)
            WeatherMode.PARTLY -> {
                drawLightWaves(canvas, w, h, 0.55f)
                drawClouds(canvas, w, h, 3, 0.050f)
            }
            WeatherMode.CLOUDY -> drawClouds(canvas, w, h, 5, 0.075f)
            WeatherMode.RAIN -> { drawClouds(canvas, w, h, 4, 0.060f); drawRain(canvas, w, h, 0.42f) }
            WeatherMode.STORM -> { drawClouds(canvas, w, h, 5, 0.080f); drawRain(canvas, w, h, 0.55f); drawFlash(canvas, h) }
            WeatherMode.SNOW -> { drawClouds(canvas, w, h, 3, 0.045f); drawSnow(canvas, w, h) }
        }
    }

    /**
     * v5.22 — GÜNEŞ ARTIK EKRANA ÇAKILI DEĞİL.
     *
     * Kullanıcı: "güneş sabit kalırsa sayfayı kaydırdığımda orda duruyor".
     * Bu yüzden güneş yalnızca hava kartındaki ikonda parlar; sayfada ise
     * çapraz ışık dalgaları soldan sağa geçer, parlar ve söner.
     */
    private fun drawLightWaves(canvas: Canvas, w: Float, h: Float, intensity: Float) {
        paint.style = Paint.Style.FILL
        val now = phase
        for (i in 0 until 3) {
            val cycle = ((now * 0.06f + i * 0.37f) % 1f)
            val alpha = (sin(cycle * Math.PI).toFloat().coerceAtLeast(0f)) * 52f * intensity
            if (alpha < 2f) continue
            val bandWidth = w * 0.42f
            val cx = -bandWidth + (w + bandWidth * 2f) * cycle
            paint.shader = LinearGradient(
                cx - bandWidth / 2f, 0f, cx + bandWidth / 2f, h,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(alpha.toInt(), 255, 226, 140),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.rotate(-18f, w / 2f, h / 2f)
            canvas.drawRect(cx - bandWidth, -h, cx + bandWidth, h * 2f, paint)
            canvas.restore()
        }
        paint.shader = null
        // hafif parıltılar (soldan sağa süzülür, söner)
        repeat(5) { i ->
            val cycle = ((now * 0.035f + i * 0.21f) % 1f)
            val a = (sin(cycle * Math.PI).toFloat().coerceAtLeast(0f)) * 120f * intensity
            if (a < 4f) return@repeat
            val x = (w * (0.1f + 0.8f * ((cycle + i * 0.13f) % 1f)))
            val y = h * (0.18f + 0.55f * ((i * 0.19f + cycle * 0.3f) % 1f))
            paint.color = Color.argb(a.toInt(), 255, 240, 190)
            canvas.drawCircle(x, y, 2.5f + 2f * sin(now + i), paint)
        }
    }

    private fun drawClouds(canvas: Canvas, w: Float, h: Float, count: Int, alpha: Float) {
        paint.style = Paint.Style.FILL
        for (i in 0 until min(count, clouds.size)) {
            val c = clouds[i]
            val x = (c[0] + phase * c[3] * (if (i % 2 == 0) 1f else -0.7f)) % (w + 400f) - 200f
            cloud(canvas, x, c[1], c[2], alpha * (if (i % 2 == 0) 1f else 0.7f))
        }
    }

    private fun cloud(canvas: Canvas, x: Float, y: Float, s: Float, a: Float) {
        paint.color = Color.argb((a * 255).toInt().coerceIn(0, 255), 200, 220, 245)
        canvas.drawCircle(x, y, s * 0.55f, paint)
        canvas.drawCircle(x + s * 0.5f, y + s * 0.1f, s * 0.42f, paint)
        canvas.drawCircle(x - s * 0.5f, y + s * 0.12f, s * 0.36f, paint)
        canvas.drawCircle(x + s * 0.15f, y - s * 0.25f, s * 0.34f, paint)
    }

    private fun drawRain(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb((alpha * 255).toInt(), 150, 200, 245)
        val dt = 0.016f
        for (d in drops) {
            d[1] += d[2] * dt
            d[0] += 60f * dt
            if (d[1] > h) { d[1] = -d[3]; d[0] = rnd.nextFloat() * w }
            if (d[0] > w) d[0] = 0f
            canvas.drawLine(d[0], d[1], d[0] - 3f, d[1] + d[3], paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawSnow(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(210, 240, 250, 255)
        val dt = 0.016f
        for (f in flakes) {
            f[1] += f[2] * dt
            f[0] += sin(phase + f[1] * 0.01f) * 18f * dt
            if (f[1] > h) { f[1] = -6f; f[0] = rnd.nextFloat() * w }
            if (f[0] < 0) f[0] = w else if (f[0] > w) f[0] = 0f
            canvas.drawCircle(f[0], f[1], f[3], paint)
        }
    }

    private fun drawFlash(canvas: Canvas, h: Float) {
        val now = System.currentTimeMillis()
        if (now - flashAt > 5200) {
            flashAt = now
            flash = 1f
        }
        if (flash > 0.02f) {
            paint.color = Color.argb((flash * 50).toInt(), 210, 225, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), h, paint)
            flash *= 0.86f
        }
    }
}

/**
 * v5.21 — CANLI TV kutusundaki minik çizgi film.
 *
 * Gerçek video değil; pil dostu, 12 fps'lik küçük bir sahne:
 * gökyüzü, güneş, tepeler, zıplayan karakter ve tarama çizgisi.
 */
class TvMiniView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t += 0.035f
            if (t > 628f) t = 0f
            invalidate()
        }
    }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!animator.isStarted) animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#1B2A5E"), Color.parseColor("#3B2A6E")), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, w, h, 8f, 8f, paint)
        paint.shader = null

        // güneş
        paint.color = Color.parseColor("#FFCF5C")
        canvas.drawCircle(w * 0.78f, h * 0.28f, h * 0.13f, paint)

        // tepeler
        paint.color = Color.parseColor("#2E7D5B")
        canvas.drawCircle(w * 0.2f, h * 1.05f, h * 0.55f, paint)
        paint.color = Color.parseColor("#3B9E72")
        canvas.drawCircle(w * 0.75f, h * 1.1f, h * 0.5f, paint)

        // zıplayan karakter
        val jump = abs(sin(t * 2.4f)) * h * 0.16f
        val cx = w * (0.18f + 0.6f * ((t * 0.05f) % 1f))
        val cy = h * 0.82f - jump
        paint.color = Color.parseColor("#FF8A3D")
        canvas.drawCircle(cx, cy, h * 0.08f, paint)
        paint.color = Color.parseColor("#FFE0B2")
        canvas.drawCircle(cx, cy - h * 0.02f, h * 0.045f, paint)
        paint.color = Color.parseColor("#2B1B12")
        canvas.drawCircle(cx - h * 0.015f, cy - h * 0.025f, h * 0.008f, paint)
        canvas.drawCircle(cx + h * 0.015f, cy - h * 0.025f, h * 0.008f, paint)

        // tarama çizgisi
        paint.color = Color.argb(45, 255, 255, 255)
        val sy = (t * 40f) % h
        canvas.drawRect(0f, sy, w, sy + 2f, paint)

        // cam kenarı
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(120, 160, 200, 255)
        canvas.drawRoundRect(1f, 1f, w - 1f, h - 1f, 8f, 8f, paint)
        paint.style = Paint.Style.FILL
    }
}

/**
 * v5.21 — RADYO kutusunda yükselip kaybolan melodi çubukları ve notalar.
 */
class RadioMiniView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val bars = 7
    private val notes = ArrayList<FloatArray>()  // x, y, hız

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t += 0.06f
            if (t > 628f) t = 0f
            invalidate()
        }
    }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        repeat(4) { notes.add(floatArrayOf(0f, 0f, 0f)) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!animator.isStarted) animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#3A1E5C"), Color.parseColor("#1A1233")), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, w, h, 8f, 8f, paint)
        paint.shader = null

        val bw = w / (bars * 1.8f)
        for (i in 0 until bars) {
            val amp = (0.35f + 0.65f * abs(sin(t * (1f + i * 0.17f) + i))) * h * 0.55f
            val x = w * 0.1f + i * (bw * 1.6f)
            paint.color = Color.parseColor(if (i % 3 == 0) "#FFB347" else "#C084FC")
            canvas.drawRoundRect(x, h - amp, x + bw, h - 2f, bw / 2f, bw / 2f, paint)
        }

        // yükselen notalar
        for (n in notes) {
            if (n[1] <= 0f) {
                n[0] = w * (0.15f + Random.nextFloat() * 0.7f)
                n[1] = h * 0.9f
                n[2] = 0.45f + Random.nextFloat() * 0.5f
            }
            n[1] -= n[2]
            if (n[1] < -8f) n[1] = 0f
            val alpha = (n[1] / (h * 0.9f)).coerceIn(0f, 1f)
            paint.color = Color.argb((alpha * 200).toInt(), 255, 220, 160)
            paint.textSize = h * 0.18f
            canvas.drawText("\u266A", n[0], n[1], paint)
        }
    }
}
