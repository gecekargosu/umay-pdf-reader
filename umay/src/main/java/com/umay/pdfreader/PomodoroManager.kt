package com.umay.pdfreader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import androidx.core.app.NotificationCompat

/**
 * v5.1 (ADIM B.1 / #19) — Pomodoro Odak Modu
 *
 * Reader ekranındaki "🍅 Pomodoro" rozetine bağlı 25 dakikalık odak sayacı:
 *  - Geri sayım boyunca rozet "mm:ss" gösterir.
 *  - Süre bitince NAZİK bildirim (sessiz kanal, alarm yok — okuma bölünmesin).
 *  - 1 pomodoro = +25 dk okuma süresi (AnalyticsStore) + 1 streak tik (StreakStore).
 *
 * Kabul:
 *  - Süre bitince nazik uyarı gelir.
 *  - Süre AnalyticsStore'a yazılır.
 */
object PomodoroManager {

    /** Varsayılan odak süresi (dakika). */
    const val DEFAULT_MINUTES = 25

    private const val CHANNEL_ID = "pomodoro_focus"
    private const val NOTIF_ID = 1919

    private var timer: CountDownTimer? = null
    private var remainingMs: Long = 0L
    private var running: Boolean = false

    fun isRunning(): Boolean = running

    /** Kalan süreyi "mm:ss" (ya da "h:mm:ss") olarak üretir. */
    fun formatRemaining(ms: Long): String {
        val totalSec = (ms + 999) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    /**
     * Pomodoro başlatır. Çalışan bir sayaç varsa hiçbir şey yapmaz.
     * @param onTick her saniye kalan süre (ms) bildirilir (rozet güncellemesi).
     * @param onEnd   sayaç bittiğinde (true) ya da iptal edildiğinde (false) çağrılır.
     */
    fun start(
        ctx: Context,
        minutes: Int = DEFAULT_MINUTES,
        onTick: (Long) -> Unit,
        onEnd: (completed: Boolean) -> Unit
    ) {
        if (running) return
        running = true
        val totalMs = minutes.toLong() * 60_000L
        remainingMs = totalMs
        timer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMs = millisUntilFinished
                onTick(millisUntilFinished)
            }

            override fun onFinish() {
                running = false
                timer = null
                remainingMs = 0L
                complete(ctx, minutes)
                onEnd(true)
            }
        }.also { it.start() }
    }

    /** Aktif sayacı iptal eder (bitirilen pomodoro sayılmaz). */
    fun cancel(onEnd: (completed: Boolean) -> Unit) {
        val t = timer
        if (t != null) {
            t.cancel()
            timer = null
        }
        if (running) {
            running = false
            onEnd(false)
        }
    }

    /** Süre doldu: +25 dk analitiğe, streak tik, nazik bildirim. */
    private fun complete(ctx: Context, minutes: Int) {
        try {
            AnalyticsStore.addMinutes(ctx, minutes)
        } catch (_: Throwable) {
        }
        try {
            StreakStore.recordDay(ctx)
        } catch (_: Throwable) {
        }
        notifyDone(ctx, minutes)
    }

    /** Sessiz (alarm sesi yok) "nazik" tamamlandı bildirimi. */
    private fun notifyDone(ctx: Context, minutes: Int) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.pomodoro_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                // Nazik: okuma bölünmesin diye alarm sesi yok, yalnızca bildirim sesi.
                ch.setSound(
                    null,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
                nm.createNotificationChannel(ch)
            }
            val intent = Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                ctx, 19, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(ctx.getString(R.string.pomodoro_done_title))
                .setContentText(ctx.getString(R.string.pomodoro_done_text, minutes))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (_: Throwable) {
        }
    }
}
