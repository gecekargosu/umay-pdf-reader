package com.umay.pdfreader.data.repository

import android.content.Context
import com.umay.pdfreader.StreakStore
import com.umay.pdfreader.domain.repository.StreakRepository

/**
 * v5.2 — StreakRepository uygulaması: StreakStore ince sarmalayıcısı.
 */
class StreakRepositoryImpl : StreakRepository {

    override fun recordDay(ctx: Context) = StreakStore.recordDay(ctx)

    override fun currentStreak(ctx: Context): Int = StreakStore.currentStreak(ctx)

    override fun streakText(ctx: Context): String = StreakStore.streakText(ctx)
}
