package com.umay.pdfreader.domain.repository

import android.content.Context

/**
 * v5.2 (ADIM C temizliği) — Okuma istatistikleri domain sözleşmesi.
 * Altında yatan: AnalyticsStore (SharedPreferences, günlük dakika satırları).
 */
interface ReadingStatsRepository {

    /** Verilen güne ait okuma süresi (dakika). */
    fun minutesOn(ctx: Context, day: String): Int

    /** Okuma süresi ekle (dakika; store tarafında 1..15 arası kırpılır). */
    fun addMinutes(ctx: Context, minutes: Int)

    /** Son N günün (bugün dahil) listesi: gün anahtarı toplam dakika. */
    fun lastDays(ctx: Context, n: Int): List<Pair<String, Int>>

    /** Toplam okunan dakika (son 90 gün penceresi). */
    fun totalMinutes(ctx: Context): Int
}
