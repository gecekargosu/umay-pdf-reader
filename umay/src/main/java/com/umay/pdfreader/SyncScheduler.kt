package com.umay.pdfreader

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * v5.2 (ADIM C.3 / #25) — ARKA PLAN KÜTÜPHANE SENKRONİZASYONU.
 *
 * Günlük (yaklaşık 24 saatte bir, inexact — pil dostu) AlarmManager
 * tetiklemesiyle [LibrarySyncReceiver] çalışır ve Download/Documents
 * ağacındaki YENİ belgeleri [LocalDocumentIndexer] ile tarar. Sonuç
 * AppPrefs'e özet olarak yazılır (Settings/sonraki turda "içe aktar" kartı).
 *
 * ZAMANLAMA DEĞİL ALTYAPI: bu tur yalnızca tetikleyici + tarama + özet.
 */
object SyncScheduler {

    private const val TAG = "SyncScheduler"
    const val ACTION_LIBRARY_SYNC = "com.umay.pdfreader.LIBRARY_SYNC"
    private const val REQUEST_CODE = 84025
    private const val INTERVAL_MS = 24L * 60L * 60L * 1000L

    /** Günlük senkronu planlar (idempotent — aynı PendingIntent yenilenir). */
    fun scheduleDaily(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(ctx)
            // setInexactRepeating: kullanıcı için en pil-dostu yöntem (API 31+ yine OK)
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MS,
                INTERVAL_MS,
                pi
            )
            Log.d(TAG, "Günlük kütüphane senkronu planlandı")
        } catch (t: Throwable) {
            Log.w(TAG, "Senkron planlanamadı", t)
        }
    }

    fun cancel(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(ctx))
        } catch (_: Throwable) {
        }
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, LibrarySyncReceiver::class.java)
            .setAction(ACTION_LIBRARY_SYNC)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, flags)
    }
}

/**
 * Tetikleyici alıcı: taramayı kısa sürede yapar (ana thread'e dokunmaz;
 * tarama kendi iş parçacığında biter, receiver bağlamı güvenlidir).
 */
class LibrarySyncReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != SyncScheduler.ACTION_LIBRARY_SYNC) return
        try {
            val app = ctx.applicationContext
            Thread {
                try {
                    val start = System.currentTimeMillis()
                    // Not: Book modelinde kaynak dosya adı yok (yalnız title);
                    // ilk turda bilinen ad olarak başlıklar kullanılıyor —
                    // sonraki turda bookDir/sourceName eşlemesi eklenecek.
                    val known = BookStore.loadBooks(app).map { it.title }.toSet()
                    val roots = listOfNotNull(
                        app.getExternalFilesDir(null)?.parentFile?.parentFile, // /sdcard/Android/data
                        java.io.File("/storage/emulated/0/Download"),
                        java.io.File("/storage/emulated/0/Documents")
                    )
                    val found = LocalDocumentIndexer.scanNewFiles(roots, known, limit = 50)
                    val newOnes = LocalDocumentIndexer.diff(
                        known, found.map { it.name }
                    )
                    val elapsed = System.currentTimeMillis() - start
                    val summary =
                        "senkron: ${newOnes.size} yeni belge, ${elapsed}ms — " +
                            newOnes.take(5).joinToString(", ").ifEmpty { "temiz" }
                    AppPrefs.putString(app, AppPrefs.KEY_LAST_SYNC_SUMMARY, summary)
                    Log.i("LibrarySyncReceiver", summary)
                } catch (t: Throwable) {
                    Log.w("LibrarySyncReceiver", "Tarama hatası", t)
                }
            }.apply { isDaemon = true; start() }
        } catch (t: Throwable) {
            Log.w("LibrarySyncReceiver", "onReceive hatası", t)
        }
    }
}
