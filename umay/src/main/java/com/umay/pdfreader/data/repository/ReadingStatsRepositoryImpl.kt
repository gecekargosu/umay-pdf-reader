package com.umay.pdfreader.data.repository

import android.content.Context
import com.umay.pdfreader.AnalyticsStore
import com.umay.pdfreader.domain.repository.ReadingStatsRepository

/**
 * v5.2 — ReadingStatsRepository uygulaması: AnalyticsStore ince sarmalayıcısı.
 */
class ReadingStatsRepositoryImpl : ReadingStatsRepository {

    override fun minutesOn(ctx: Context, day: String): Int =
        AnalyticsStore.minutesOn(ctx, day)

    override fun addMinutes(ctx: Context, minutes: Int) =
        AnalyticsStore.addMinutes(ctx, minutes)

    override fun lastDays(ctx: Context, n: Int): List<Pair<String, Int>> =
        AnalyticsStore.lastDays(ctx, n)

    override fun totalMinutes(ctx: Context): Int =
        AnalyticsStore.totalMinutes(ctx)
}
