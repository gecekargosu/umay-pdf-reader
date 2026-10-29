package com.umay.pdfreader.data.repository

import com.umay.pdfreader.domain.repository.CacheBudgetRepository
import com.umay.pdfreader.domain.repository.FocusRepository
import com.umay.pdfreader.domain.repository.LibraryRepository
import com.umay.pdfreader.domain.repository.ReadingStatsRepository
import com.umay.pdfreader.domain.repository.StreakRepository

/**
 * v5.2 (ADIM C temizliği) — REPOSITORY LOCATOR.
 *
 * Hilt DI gelene kadar köprüleri tek yerden sağlayan service locator.
 * Kullanım (yeni kod):
 *   Repositories.focus.start(this, 25, onTick, onEnd)
 * Eski kod (ReaderActivity vb.) çalışmaya devam eder — kural gereği
 * mevcut akışlar BOZULMAZ, yalnızca YENİ kod bu locator'ı kullanır.
 */
object Repositories {

    /** Odak (Pomodoro) — PomodoroManager köprüsü. */
    val focus: FocusRepository by lazy { FocusRepositoryImpl() }

    /** Okuma istatistikleri — AnalyticsStore köprüsü. */
    val readingStats: ReadingStatsRepository by lazy { ReadingStatsRepositoryImpl() }

    /** Okuma serisi — StreakStore köprüsü. */
    val streak: StreakRepository by lazy { StreakRepositoryImpl() }

    /** Önbellek bütçesi — CacheKeeper köprüsü. */
    val cacheBudget: CacheBudgetRepository by lazy { CacheBudgetRepositoryImpl() }

    /** Kütüphane — BookStore okuma-yönetim köprüsü. */
    val library: LibraryRepository by lazy { LibraryRepositoryImpl() }
}
