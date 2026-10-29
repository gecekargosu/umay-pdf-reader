package com.umay.pdfreader

import android.content.Context

/**
 * v5.2 (ADIM C.1 / #23) — SAHA / SINIF MODU altyapısı.
 *
 * Ders sırasında tek dokunuşla "sınıf modu"na girilir:
 *  - Mod süresi takip edilir (odak oturumu istatistiklerine yazılabilir),
 *  - Alarm/okuma ekranları bu bayraktan okuyup davranabilir (Adım C devamı),
 *  - Kalıcıdır (AppPrefs) — uygulama yeniden açılımda durumu korur.
 *
 * Bu tur yalnızca ALTYAPI: giriş/çıkış + süre + özet (saf matematik testli).
 * UI bağı sonraki turda (Ayarlar/Reader rozeti) bağlanacak.
 */
object ClassroomModeManager {

    /** Sınıf moduna gir (idempotent — zaten aktifse süreyi sıfırlamaz). */
    fun enter(ctx: Context) {
        try {
            if (AppPrefs.getBool(ctx, AppPrefs.KEY_CLASSROOM_ACTIVE, false)) return
            AppPrefs.putBool(ctx, AppPrefs.KEY_CLASSROOM_ACTIVE, true)
            AppPrefs.putString(
                ctx, AppPrefs.KEY_CLASSROOM_SINCE,
                System.currentTimeMillis().toString()
            )
        } catch (_: Throwable) {
        }
    }

    /** Sınıf modundan çıkar (süre bilgisi özet için saklanmaz — istatistik ayrı). */
    fun exit(ctx: Context) {
        try {
            AppPrefs.putBool(ctx, AppPrefs.KEY_CLASSROOM_ACTIVE, false)
        } catch (_: Throwable) {
        }
    }

    fun isActive(ctx: Context): Boolean = try {
        AppPrefs.getBool(ctx, AppPrefs.KEY_CLASSROOM_ACTIVE, false)
    } catch (_: Throwable) {
        false
    }

    /** Modun başladığı zaman damgası (ms); yoksa 0. */
    fun sinceMillis(ctx: Context): Long = try {
        AppPrefs.getString(ctx, AppPrefs.KEY_CLASSROOM_SINCE, "0").toLongOrNull() ?: 0L
    } catch (_: Throwable) {
        0L
    }

    /**
     * Kaç ms sürdüğü (başlangıç → now). Saf — Context gerekmez, test edilebilir.
     * Başlangıç geçersizse 0 döner (asla negatif olmaz).
     */
    fun sessionMillis(startMs: Long, nowMs: Long): Long =
        if (startMs <= 0L || nowMs < startMs) 0L else nowMs - startMs

    /**
     * Kullanıcıya gösterilecek özet ("45 dk" / "1s 5dk"). Saf — test edilebilir.
     * 0 → "0 dk".
     */
    fun sessionSummary(startMs: Long, nowMs: Long): String {
        val totalMin = sessionMillis(startMs, nowMs) / 60_000L
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            totalMin <= 0L -> "0 dk"
            h > 0 -> if (m > 0) "${h}s ${m}dk" else "${h}s"
            else -> "${totalMin}dk"
        }
    }
}
