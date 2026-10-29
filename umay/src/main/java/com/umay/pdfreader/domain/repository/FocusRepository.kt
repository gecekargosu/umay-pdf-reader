package com.umay.pdfreader.domain.repository

import android.content.Context

/**
 * v5.2 (ADIM C temizliği — 14 Ekim, Buffy) — CLEAN ARCHITECTURE KÖPRÜSÜ.
 *
 * Domain katmanının odak (Pomodoro) için tek kapısı. Uygulama kodu artık
 * (yeni akışlarda) bu interface üzerinden PomodoroManager'a ulaşır; böylece
 * ileride yöneticinin yerine test çifti (fake) konabilir.
 *
 * Mevcut uygulama akışları (ReaderActivity) şimdilik doğrudan
 * PomodoroManager kullanmaya devam eder — kural gereği çalışan özellik
 * BOZULMAZ; köprü yalnızca hazır ve testli durur.
 */
interface FocusRepository {

    /** Aktif odak sayacı çalışıyor mu? */
    fun isRunning(): Boolean

    /** Kalan süreyi kullanıcıya gösterilecek biçimde ("mm:ss" / "h:mm:ss") üretir. */
    fun formatRemaining(ms: Long): String

    /**
     * Odak sayacını başlatır.
     * @param onTick her saniye kalan süre (ms)
     * @param onEnd  completed=true → süre doldu; false → iptal
     */
    fun start(
        ctx: Context,
        minutes: Int,
        onTick: (Long) -> Unit,
        onEnd: (completed: Boolean) -> Unit
    )

    /** Çalışan sayacı iptal eder (bitirilen sayılmaz). */
    fun cancel(onEnd: (completed: Boolean) -> Unit)
}
