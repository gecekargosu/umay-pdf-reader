package com.umay.pdfreader

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v12 (YENİ ÖZELLİK) — OKUMA İSTATİSTİKLERİ.
 *
 * ReadService seslendirme sürerken saniyeleri toplar (tickEveryMinute).
 * Veri SharedPreferences'ta günlük satırlar halinde tutulur:
 *   "2026-10-11" -> 34 (dakika)
 *
 * Bitirilen kitaplar: ilerleme %100 olan kitapların sayısı okuma
 * ekranından `markFinished` ile işaretlenir (tek seferlik rozet).
 *
 * ANR KORUMASI: tüm yazma işlemleri küçük ve atomik; ana thread disk I/O
 * yapmaz (tick çağıran taraf servis thread'inden gelir).
 */
object AnalyticsStore {

    private const val PREFS = "umay_analytics"
    private const val KEY_MINUTES = "minutes_by_day"   // "2026-10-11=34;..."
    private const val KEY_FINISHED = "finished_books"  // "id1,id2,..."

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Bugünün anahtarını üretir (test edilebilir). */
    fun dayKey(timeMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timeMillis))

    /** Verilen güne ait okuma süresini DAKİKA cinsinden döndürür. */
    fun minutesOn(ctx: Context, day: String): Int {
        val raw = prefs(ctx).getString(KEY_MINUTES, "") ?: ""
        if (raw.isBlank()) return 0
        return raw.split(';').firstNotNullOfOrNull { pair ->
            val kv = pair.split('=')
            if (kv.size == 2 && kv[0] == day) kv[1].toIntOrNull() else null
        } ?: 0
    }

    /**
     * Okuma süresi ekle (dakika). `minutes` 1..15 arası olmalı —
     * servis başına bir tick çağrılır, taşma koruması vardır.
     */
    fun addMinutes(ctx: Context, minutes: Int) {
        try {
            val m = minutes.coerceIn(1, 15)
            val today = todayKey()
            val raw = prefs(ctx).getString(KEY_MINUTES, "") ?: ""
            val map = LinkedHashMap<String, Int>()
            raw.split(';').filter { it.isNotBlank() }.forEach { pair ->
                val kv = pair.split('=')
                if (kv.size == 2) kv[1].toIntOrNull()?.let { map[kv[0]] = it }
            }
            map[today] = (map[today] ?: 0) + m
            // Son 90 günü tut (bellek şişmesin)
            val trimmed = map.entries.toList().sortedByDescending { it.key }.take(90)
            val out = trimmed.joinToString(";") { "${it.key}=${it.value}" }
            prefs(ctx).edit().putString(KEY_MINUTES, out).apply()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "AnalyticsStore.addMinutes", t)
        }
    }

    /** Son N günün (bugün dahil) dakika listesi — gün anahtarıyla. */
    fun lastDays(ctx: Context, n: Int): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>(n)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = java.util.Calendar.getInstance()
        repeat(n) {
            val key = fmt.format(cal.time)
            out.add(key to minutesOn(ctx, key))
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return out
    }

    /** Toplam okunan dakika (tüm günler). */
    fun totalMinutes(ctx: Context): Int =
        lastDays(ctx, 90).sumOf { it.second }

    /** Kitap bitirildi olarak işaretler (tekrar işaretlemesi zararsız). */
    fun markFinished(ctx: Context, bookId: String) {
        try {
            val set = finishedIds(ctx).toMutableSet()
            if (set.add(bookId)) {
                prefs(ctx).edit().putString(KEY_FINISHED, set.joinToString(",")).apply()
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "AnalyticsStore.markFinished", t)
        }
    }

    /** Bitirilen kitap kimlikleri. */
    fun finishedIds(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString(KEY_FINISHED, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(',').filter { it.isNotBlank() }.toSet()
    }

    /** Bitirilen kitap sayısı. */
    fun finishedCount(ctx: Context): Int = finishedIds(ctx).size
}
