package com.umay.pdfreader

import android.content.Context
import java.util.Calendar

/**
 * v13 (AGENT 22) — Okuma serisi (Streak).
 *
 * ReadService her dakika tik'inde [recordDay] çağırır: bugünün tarihi
 * kaydedilirse sayaç büyür, dün kaydedilmemişse 1'e düşer.
 * Ayarlar'da "🔥 N gün üst üste okudun" olarak gösterilir.
 */
object StreakStore {

    private const val PREFS = "umay_streak"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Bugünün gün anahtarı (yerel takvim): 20261011 */
    private fun todayKey(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 10000 +
            (c.get(Calendar.MONTH) + 1) * 100 +
            c.get(Calendar.DAY_OF_MONTH)
    }

    private fun dayOffsetKey(offsetDays: Int): Int {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -offsetDays)
        return c.get(Calendar.YEAR) * 10000 +
            (c.get(Calendar.MONTH) + 1) * 100 +
            c.get(Calendar.DAY_OF_MONTH)
    }

    /** Bugün okundu damgası vur (idempotent — dakikada bir çağrılabilir). */
    fun recordDay(ctx: Context) {
        try {
            val p = prefs(ctx)
            val today = todayKey()
            if (p.getInt("last_day", 0) == today) return
            val yesterday = p.getInt("prev_day", 0) == dayOffsetKey(1)
            val streak = if (yesterday) p.getInt("streak", 0) + 1 else 1
            p.edit()
                .putInt("prev_day", p.getInt("last_day", 0))
                .putInt("last_day", today)
                .putInt("streak", streak)
                .putInt("best", maxOf(streak, p.getInt("best", 0)))
                .apply()
        } catch (_: Throwable) {
        }
    }

    fun currentStreak(ctx: Context): Int = try {
        val p = prefs(ctx)
        // Bugün okunmadıysa seri "bugün" şartı sağlanmamış sayılır (dün'e kadar)
        if (p.getInt("last_day", 0) == todayKey() || p.getInt("last_day", 0) == dayOffsetKey(1)) {
            p.getInt("streak", 0)
        } else 0
    } catch (_: Throwable) {
        0
    }

    /** Ayarlar satırı metni. */
    fun streakText(ctx: Context): String {
        val s = currentStreak(ctx)
        val best = try { prefs(ctx).getInt("best", 0) } catch (_: Throwable) { 0 }
        return when {
            s <= 0 -> "🔥 Okuma serisi başlamadı — bugün 1 dakika oku, seriyi başlat!"
            else -> "🔥 $s gün üst üste okudun! (en iyi: ${maxOf(best, s)} gün)"
        }
    }
}
