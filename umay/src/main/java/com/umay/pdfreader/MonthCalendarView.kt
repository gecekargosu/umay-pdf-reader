package com.umay.pdfreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Calendar

/**
 * v14 (V5.0 §5) — İNTERAKTİF AY TAKVİMİ.
 *
 *  • ← / → oklarıyla ileri/geri ay gezinmesi (çok eski / çok ileri tarih).
 *  • Sınav olan günler SICAK TURUNCU noktayla işaretli.
 *  • Günü tıklayınca `onDayClick(millis, examCount)` çağrılır —
 *    CourseDetailActivity o günün sınavlarını listeler/not ekler.
 *
 * Saf çizim (custom View) — kütüphane bağımlılığı yok; try/catch korumalı.
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Gün başına sınav işaretleri (gün-of-ay → sınav sayısı). */
    private var marks: Map<Int, Int> = emptyMap()

    /** Ay başlangıcı (o ayın 1'i, takvim alanıyla). */
    private val shown = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }

    /** Seçilen güne tıklandığında (o ayın 1'i 00:00 milis + gün offset'i). */
    var onDayClick: ((dayMillis: Long, examCount: Int) -> Unit)? = null

    // Renkler (mat gece paleti)
    private val cText = Color.parseColor("#C9D1D9")
    private val cDim = Color.parseColor("#6E7681")
    private val cAccent = Color.parseColor("#FF6B00")
    private val cToday = Color.parseColor("#00E5FF")

    private val pHead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cText; textSize = 34f; textAlign = Paint.Align.CENTER
    }
    private val pDay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cText; textSize = 30f; textAlign = Paint.Align.CENTER
    }
    private val pMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cAccent }
    private val pTodayRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cToday; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val pArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cAccent; textSize = 44f; textAlign = Paint.Align.CENTER
    }

    private val detector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                try {
                    // Oklara basıldı mı?
                    val pad = 16f * resources.displayMetrics.density
                    if (e.x < pad + 60 && e.y < pad + 70) { prevMonth(); return true }
                    if (e.x > width - pad - 60 && e.y < pad + 70) { nextMonth(); return true }
                    // Gün ızgarası
                    val cols = 7
                    val gridTop = pad + 110f
                    val cellW = (width - pad * 2) / cols
                    val cellH = 62f * resources.displayMetrics.density
                    val col = ((e.x - pad) / cellW).toInt()
                    val row = ((e.y - gridTop) / cellH).toInt()
                    if (col in 0..6 && row in 0..5) {
                        val dayOffset = row * cols + col
                        val firstDow = shown.get(Calendar.DAY_OF_WEEK) - 1 // Pzt=0
                        val day = dayOffset - firstDow + 1
                        val maxDay = shown.getActualMaximum(Calendar.DAY_OF_MONTH)
                        if (day in 1..maxDay) {
                            val g = shown.clone() as Calendar
                            g.set(Calendar.DAY_OF_MONTH, day)
                            onDayClick?.invoke(g.timeInMillis, marks[day] ?: 0)
                            return true
                        }
                    }
                } catch (_: Throwable) {
                }
                return false
            }
        })

    /** Sınav işaretlerini tazele + çiz. */
    fun setExams(millisList: List<Long>) {
        try {
            val m = HashMap<Int, Int>(8)
            millisList.forEach { ms ->
                val c = Calendar.getInstance().apply { timeInMillis = ms }
                if (c.get(Calendar.YEAR) == shown.get(Calendar.YEAR) &&
                    c.get(Calendar.MONTH) == shown.get(Calendar.MONTH)
                ) {
                    val d = c.get(Calendar.DAY_OF_MONTH)
                    m[d] = (m[d] ?: 0) + 1
                }
            }
            marks = m
        } catch (_: Throwable) {
            marks = emptyMap()
        }
        invalidate()
    }

    fun shownMonthMillis(): Long = shown.timeInMillis

    fun prevMonth() {
        shown.add(Calendar.MONTH, -1)
        marks = emptyMap()
        invalidate()
        onMonthChange?.invoke(shown.timeInMillis)
    }

    fun nextMonth() {
        shown.add(Calendar.MONTH, 1)
        marks = emptyMap()
        invalidate()
        onMonthChange?.invoke(shown.timeInMillis)
    }

    /** Ay değişince çağrılır (CourseDetailActivity işaretleri tazeler). */
    var onMonthChange: ((Long) -> Unit)? = null

    /** v16 (GÖREV 6) — Takvimde SEÇİLİ gün (vurgu halkası); 0 = seçim yok. */
    var selectedMillis: Long = 0L
        set(value) {
            field = value
            invalidate()
        }

    /** Seçili günün ay içindeki gün numarası (aynı ay değilse 0). */
    private fun selectedDayOfShown(): Int {
        if (selectedMillis <= 0L) return 0
        val c = Calendar.getInstance().apply { timeInMillis = selectedMillis }
        return if (c.get(Calendar.YEAR) == shown.get(Calendar.YEAR) &&
            c.get(Calendar.MONTH) == shown.get(Calendar.MONTH)
        ) c.get(Calendar.DAY_OF_MONTH) else 0
    }

    override fun onDraw(canvas: Canvas) {
        try {
            val pad = 16f * resources.displayMetrics.density
            val w = width.toFloat()

            // Ay başlığı + oklar
            // v16 (GÖREV 3) — ay/gün adları AKTİF DİLE göre (TR/EN).
            // Önceden sabit Türkçeydi → İngilizce modda "Ocak/Nisan" görünüyordu.
            val en = LangHelper.isEnglish(context)
            val months = if (en) arrayOf(
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"
            ) else arrayOf(
                "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
                "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"
            )
            val title = "${months[shown.get(Calendar.MONTH)]} ${shown.get(Calendar.YEAR)}"
            canvas.drawText("‹", pad + 24f, pad + 44f, pArrow)
            canvas.drawText(title, w / 2f, pad + 40f, pHead)
            canvas.drawText("›", w - pad - 24f, pad + 44f, pArrow)

            // Hafta günleri (v16: aktif dile göre)
            val days = if (en) arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            else arrayOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
            val cols = 7
            val cellW = (w - pad * 2) / cols
            val rowTop = pad + 70f
            for (i in 0 until cols) {
                pDay.color = cDim
                canvas.drawText(days[i], pad + cellW * i + cellW / 2f, rowTop + 26f, pDay)
            }

            // Günler
            val gridTop = pad + 110f
            val cellH = 62f * resources.displayMetrics.density
            val firstDow = shown.get(Calendar.DAY_OF_WEEK) - 1
            val maxDay = shown.getActualMaximum(Calendar.DAY_OF_MONTH)
            val today = Calendar.getInstance()
            val isCurrentMonth = today.get(Calendar.YEAR) == shown.get(Calendar.YEAR) &&
                today.get(Calendar.MONTH) == shown.get(Calendar.MONTH)
            val todayDay = today.get(Calendar.DAY_OF_MONTH)

            for (day in 1..maxDay) {
                val pos = firstDow + day - 1
                val row = pos / cols
                val col = pos % cols
                val cx = pad + cellW * col + cellW / 2f
                val cy = gridTop + cellH * row + cellH / 2f
                pDay.color = if (isCurrentMonth && day == todayDay) cToday else cText
                canvas.drawText("$day", cx, cy + 10f, pDay)
                // Sınav işareti (turuncu nokta)
                if ((marks[day] ?: 0) > 0) {
                    canvas.drawCircle(cx, cy + 26f, 5f, pMark)
                }
                // Bugün halkası
                if (isCurrentMonth && day == todayDay) {
                    canvas.drawCircle(cx, cy, 24f, pTodayRing)
                }
                // v16 (GÖREV 6) — SEÇİLİ gün halkası (dolgulu, belirgin)
                if (day == selectedDayOfShown()) {
                    val pSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = cAccent
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                    }
                    canvas.drawCircle(cx, cy, 24f, pSel)
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return detector.onTouchEvent(event) || super.onTouchEvent(event)
    }
}
