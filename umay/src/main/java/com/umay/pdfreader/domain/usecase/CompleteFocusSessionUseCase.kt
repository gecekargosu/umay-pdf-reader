package com.umay.pdfreader.domain.usecase

import android.content.Context
import com.umay.pdfreader.data.repository.Repositories

/**
 * v5.2 (ADIM C temizliği) — Odak oturumu tamamlandığında TEK noktada
 * "istatistik + seriyi" birleştiren use case.
 *
 * Not: PomodoroManager.complete() içindeki aynı iş (AnalyticsStore +
 * StreakStore) korunmuştur (dokunulmadı); bu use case YENİ akışlar
 * (Adım D'deki puan/rozet ekonomisi, saha modu oturumları vb.) içindir:
 * tek sorumluluk = "odak oturumu bitti → veriyi kaydet".
 */
class CompleteFocusSessionUseCase {

    /**
     * Tamamlanan odak oturumunu kaydeder.
     * @return kaydedilen toplam dakika (bugün), hata halinde -1.
     */
    operator fun invoke(ctx: Context, minutes: Int): Int {
        return try {
            Repositories.readingStats.addMinutes(ctx, minutes)
            Repositories.streak.recordDay(ctx)
            Repositories.readingStats.totalMinutes(ctx)
        } catch (_: Throwable) {
            -1
        }
    }
}
