package com.umay.pdfreader

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * v43 — Kampüs akışını arka planda tazeler (6 saatte bir, ağ varsa).
 *
 * Kullanıcı isteği: "program açıkken internete bağlıysa uyarı alayım".
 * Uygulama açılışında da (20 dk sınırıyla) kontrol yapılır; bu işçi uygulama
 * kapalıyken de yeni duyuruları yakalar. Android zamanlamayı geciktirebilir.
 *
 * v43.2 — BOT KORUMASI: site düz HTTP isteklerini `403` ile engelliyor
 * (cihaz günlüğü: `HTTP 403 · /tr/haberler`). Arka plan işçisi WebView
 * açamadığı için bu yol sürekli başarısız olup Seyir Defteri'ni dolduruyordu.
 * Bu yüzden işçi `cancel()` ile durdurulur; kontrol uygulama açılırken
 * `CampusFeed.checkWithWebView` ile yapılır.
 */
class CampusWorker(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            CampusFeed.check(applicationContext, notify = true, force = true)
            Result.success()
        } catch (t: Throwable) {
            BookStore.logError(applicationContext, "CampusWorker", t)
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "umay_campus_feed"

        /** v43.2 — 403 veren düz HTTP işçisini kapatır (WebView yolu kullanılır). */
        fun cancel(ctx: Context) {
            try {
                WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
            } catch (t: Throwable) {
                BookStore.logError(ctx, "CampusWorker.cancel", t)
            }
        }

        fun schedule(ctx: Context) {
            try {
                val req = PeriodicWorkRequestBuilder<CampusWorker>(6, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    req
                )
            } catch (t: Throwable) {
                BookStore.logError(ctx, "CampusWorker.schedule", t)
            }
        }
    }
}
