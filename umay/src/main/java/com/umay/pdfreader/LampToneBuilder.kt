package com.umay.pdfreader

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * v36.5 — LAMBA UYARI SESİ ÜRETİCİ (saf Kotlin, Android'siz → test edilebilir).
 *
 * Kullanıcı geri bildirimi: eski `ToneGenerator` beep'i 0,2 sn sürüyordu,
 * "çok kısa" dedi. Artık uyarı **3 saniye** sürüyor ve iki durum birbirinden
 * net ayrılıyor:
 *
 *  • UYARI (sarı)  : tek, yumuşak, sabit 900 Hz ton — 3,0 sn
 *  • HATA (kırmızı): 700/950 Hz iki tonlu "siren" — 0,5 sn'lik 6 dilim = 3,0 sn
 *
 * Her dilimde 40 ms yükselme/alçalma zarfı var: hoparlörde "tak" sesi olmaz.
 * Ses seviyesi çağıran tarafından `volume` (0..1) ile ölçeklenir.
 */
object LampToneBuilder {

    /** 16-bit mono PCM. */
    const val SAMPLE_RATE = 22_050

    /** Toplam uyarı süresi (ms) — kullanıcı isteği: ~3 saniye. */
    const val DURATION_MS = 3_000

    private const val FADE_MS = 40

    fun build(critical: Boolean, sampleRate: Int = SAMPLE_RATE, volume: Float = 0.32f): ShortArray {
        val total = sampleRate * DURATION_MS / 1000
        val out = ShortArray(total)
        val amp = (Short.MAX_VALUE * volume.coerceIn(0f, 1f))
        val fade = (sampleRate * FADE_MS / 1000).coerceAtLeast(1)

        if (!critical) {
            // Tek uzun ton + zarflar
            for (i in 0 until total) {
                val env = envelope(i, total, fade)
                out[i] = (sin(2 * PI * 900.0 * i / sampleRate) * amp * env).toInt().toShort()
            }
            return out
        }

        // İki tonlu siren: 6 dilim × 500 ms
        val segLen = total / 6
        for (seg in 0 until 6) {
            val freq = if (seg % 2 == 0) 700.0 else 950.0
            val start = seg * segLen
            val end = if (seg == 5) total else start + segLen
            val n = end - start
            for (i in 0 until n) {
                // Dilim başı/sonu yumuşak; dilimler arasında kısa boşluk yok (siren gibi)
                val env = envelope(i, n, fade.coerceAtMost(n / 4).coerceAtLeast(1))
                out[start + i] =
                    (sin(2 * PI * freq * i / sampleRate) * amp * env).toInt().toShort()
            }
        }
        return out
    }

    private fun envelope(i: Int, n: Int, fade: Int): Double {
        if (n <= 2 * fade) return 1.0
        val up = (i.toDouble() / fade).coerceAtMost(1.0)
        val down = ((n - i).toDouble() / fade).coerceAtMost(1.0)
        return minOf(up, down)
    }

    /** Test/ölçüm yardımı: bir dilimin tepe genliği. */
    fun peakOf(samples: ShortArray, from: Int = 0, to: Int = samples.size): Int {
        var p = 0
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(samples.size)) {
            val v = abs(samples[i].toInt())
            if (v > p) p = v
        }
        return p
    }
}
