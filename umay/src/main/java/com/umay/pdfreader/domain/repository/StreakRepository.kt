package com.umay.pdfreader.domain.repository

import android.content.Context

/**
 * v5.2 (ADIM C temizliği) — Okuma serisi (streak) domain sözleşmesi.
 * Altında yatan: StreakStore.
 */
interface StreakRepository {

    /** Bugün okundu damgası vur (idempotent). */
    fun recordDay(ctx: Context)

    /** Güncel seri (bugün/dün okunmadıysa 0). */
    fun currentStreak(ctx: Context): Int

    /** Ayarlar için hazır metin. */
    fun streakText(ctx: Context): String
}
