package com.umay.pdfreader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicReference

/**
 * v36.5 — LAMBA SESLİ UYARISI (tek yer).
 *
 * Kullanıcı isteği: kırmızı lamba yanınca sesli uyarı, sarı lamba yanınca
 * **farklı** sesli uyarı ve süre **~3 saniye** (eski 0,2 sn'lik bip "çok
 * kısaydı").
 *
 * Ses [LampToneBuilder] tarafından üretilir (test edilebilir, saf Kotlin) ve
 * bildirim ses akışında çalınır. Ses yalnız durum değişince çalar; 2 dakikalık
 * otomatik denetim aynı durumu bulduğunda sessiz kalır.
 */
object LampSound {

    /** Aynı anda tek uyarı: yenisi gelirse eskisi kesilir. */
    private val current = AtomicReference<AudioTrack?>(null)

    fun play(context: Context, critical: Boolean) {
        try {
            if (!AppPrefs.getBool(context, AppPrefs.KEY_LAMP_SOUND, true)) return
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val stream = AudioManager.STREAM_NOTIFICATION
            val cur = am?.getStreamVolume(stream) ?: 5
            val max = am?.getStreamMaxVolume(stream) ?: 7
            // Kullanıcı sesi tamamen kısmışsa çalma.
            if (cur <= 0) return
            val ratio = (cur.toFloat() / max.coerceAtLeast(1)).coerceIn(0.15f, 1f)
            val samples = LampToneBuilder.build(critical, volume = 0.32f * ratio)

            stopCurrent()
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(LampToneBuilder.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            current.set(track)
            track.play()
            // 3 sn sonra kendiliğinden dur ve kaynağı bırak.
            Thread {
                try {
                    Thread.sleep(LampToneBuilder.DURATION_MS + 400L)
                    if (current.compareAndSet(track, null)) {
                        runCatching { track.stop() }
                        runCatching { track.release() }
                    }
                } catch (_: Throwable) {
                }
            }.start()
        } catch (t: Throwable) {
            // Ses çalmak uygulamayı asla düşürmesin
            try {
                BookStore.logError(context, "LampSound.play", t)
            } catch (_: Throwable) {
            }
        }
    }

    /** Ekran kapanırken / uygulama kapanırken çağrılabilir. */
    fun stopCurrent() {
        val old = current.getAndSet(null) ?: return
        runCatching { old.stop() }
        runCatching { old.release() }
    }
}
