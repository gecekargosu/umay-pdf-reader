package com.umay.pdfreader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * v36 — BOŞTA ÖN HAZIRLIK (Faz 1: metin + kalite + rapor).
 *
 * Neden: bir kitabı ilk açtığında gecikmenin kaynağı **metin çıkarma**nın
 * o an yapılması. Kullanıcı fikri: "program boşta hazırlasın, sorun bulursa
 * söylesin" (bkz. `33-ARKA-PLAN-HAZIRLIK-MODULU.md`).
 *
 * Faz 1 bilinçli olarak **ağ gerektirmez**:
 *  • eksik sayfaların metnini çıkarır (yeniden başlatılabilir; var olan
 *    sayfaları tekrar işlemez → `BookStore.ensureExtracting`),
 *  • sayfaları kalite açısından denetler (`PrepAudit`),
 *  • bulunan sorunları `prep_issues.json`'a yazar ve bildirim gösterir.
 *
 * Ses ön sentezi **Faz 2**'dir ve varsayılan kapalıdır (Edge TTS resmî
 * olmayan uç nokta; 700 sayfayı toplu sentezlemek hem ToS hem trafik riski).
 *
 * Android gerçeği: WorkManager işi **garanti anda** çalışmaz; sistem
 * erteler. Bu yüzden ilerleme "en iyi çaba"dır ve yarıda kalırsa bir sonraki
 * turda kaldığı yerden devam eder (sayfa dosyaları diskte kalır).
 */
class PrepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val UNIQUE_NAME = "umay_prep"
        private const val CHANNEL = "umay_prep_channel"
        private const val NOTIF_ID = 4201
        /** Bir turda en fazla bu kadar kitap elle tutulur. */
        private const val MAX_BOOKS = 3
        /** Tek kitap için çıkarma beklemesi üst sınırı (sistem bizi durdurabilir). */
        private const val EXTRACT_WAIT_MS = 4 * 60 * 1000L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!PrepStore.enabled(applicationContext)) return@withContext Result.success()
            val ctx = applicationContext
            val books = BookStore.loadBooks(ctx)
            if (books.isEmpty()) return@withContext Result.success()

            var prepared = 0
            var issueTotal = 0

            // Önce en son eklenenler (kullanıcı onları açacak)
            for (book in books.sortedByDescending { it.createdAt }.take(MAX_BOOKS)) {
                if (isStopped) break
                val sig = PrepStore.signatureOf(ctx, book)
                // v36.1: kitap BAŞINA imza — biri işlendi diye diğerleri atlanmaz
                if (sig == PrepStore.signatureOfBook(ctx, book.id)) continue

                // 1) Eksik sayfa metinleri (idempotent, kaldığı yerden devam)
                BookStore.ensureExtracting(ctx, book)
                var waited = 0L
                while (BookStore.isExtracting(ctx, book.id) && waited < EXTRACT_WAIT_MS && !isStopped) {
                    delay(1_500)
                    waited += 1_500
                }

                // 2) Kalite denetimi
                val pages = (0 until book.pageCount).map {
                    BookStore.pageText(ctx, book.id, it) ?: ""
                }
                val report = PrepAudit.audit(book.id, pages)
                if (report.issues.isNotEmpty()) {
                    PrepStore.appendIssues(ctx, book.id, report.issues)
                    issueTotal += report.issues.size
                }
                // 3) Bozuk sayfaların kaçı AR (Arapça OCR) önbelleğinde hazır?
                //    "269 bozuk" tek başına eksik bilgi: kurtarılanı da yaz.
                var recovered = 0
                if (report.issues.isNotEmpty()) {
                    val broken = report.brokenPages
                    val cached = HashSet<Int>()
                    for (p in broken.take(120)) {
                        if (isStopped) break
                        if (ArabicOcr.cached(ctx, book.id, p) != null) cached.add(p)
                    }
                    recovered = report.recoveredCount(cached)
                }

                PrepStore.rememberSignature(ctx, book.id, sig)
                prepared++
                BookStore.logInfo(
                    ctx, "prep/${book.id}",
                    report.summary() +
                        (if (recovered > 0) " · AR ile kurtarılmış $recovered" else "") +
                        " · " + book.title.take(40)
                )
            }

            PrepStore.markRun(ctx)
            if (issueTotal > 0) notifyIssues(ctx, issueTotal)
            Result.success()
        } catch (t: Throwable) {
            BookStore.logError(applicationContext, "PrepWorker", t)
            // Kalıcı hata değil: bir sonraki turda tekrar denenir
            Result.retry()
        }
    }

    private fun notifyIssues(ctx: Context, count: Int) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL, "Ön hazırlık", NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Kitaplar hazırlanırken bulunan sorunlar" }
                )
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("Ön hazırlık tamamlandı")
                .setContentText("$count sayfada okuma sorunu bulundu — dokun ve gör")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (t: Throwable) {
            BookStore.logError(ctx, "PrepWorker.notify", t)
        }
    }
}

/**
 * v36 — Ön hazırlık zamanlayıcısı.
 *
 * Koşullar (pil ve ısı koruması): **şarjda** + **cihaz boşta** + **internet
 * gerekmez**. 6 saatte bir denenir; sistem uygun anı kendi seçer.
 * Kullanıcı ayardan kapatabilir (`PrepStore.setEnabled`).
 */
object PrepScheduler {

    fun schedule(ctx: Context) {
        try {
            if (!PrepStore.enabled(ctx)) return
            val req = PeriodicWorkRequestBuilder<PrepWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PrepWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        } catch (t: Throwable) {
            BookStore.logError(ctx, "PrepScheduler.schedule", t)
        }
    }

    fun cancel(ctx: Context) {
        try {
            WorkManager.getInstance(ctx).cancelUniqueWork(PrepWorker.UNIQUE_NAME)
        } catch (t: Throwable) {
            BookStore.logError(ctx, "PrepScheduler.cancel", t)
        }
    }
}
