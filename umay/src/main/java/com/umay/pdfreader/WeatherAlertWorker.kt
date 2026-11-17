package com.umay.pdfreader

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v5.20 — Günde bir kez (yaklaşık 08:30) MGM tahminini kontrol eder; seçili
 * uyarı türü varsa bildirim atar. Aynı gün ikinci kez bildirim atmaz.
 * Ağ yoksa iş başarısız sayılmaz; ertesi gün yeniden dener.
 */
class WeatherAlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val app = applicationContext
        return try {
            val city = WeatherStore.cityId(app)
            val latch = CountDownLatch(1)
            var data: WeatherData? = null
            WeatherStore.fetch(app, city) { d, _ ->
                data = d
                latch.countDown()
            }
            latch.await(25, TimeUnit.SECONDS)
            val d = data ?: return Result.success()
            val day = d.days.firstOrNull() ?: return Result.success()
            val alerts = WeatherStore.activeAlerts(app, day)
            if (alerts.isEmpty()) return Result.success()

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            if (AppPrefs.getString(app, KEY_LAST_DATE) == today) return Result.success()
            WeatherStore.notifyAlerts(app, d, alerts)
            AppPrefs.putString(app, KEY_LAST_DATE, today)
            Result.success()
        } catch (t: Throwable) {
            BookStore.logError(app, "WeatherAlertWorker", t)
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "umay_weather_alert"
        private const val KEY_LAST_DATE = "weather_alert_last_date"

        fun schedule(ctx: Context) {
            try {
                val now = Calendar.getInstance()
                val target = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 8)
                    set(Calendar.MINUTE, 30)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
                }
                val delay = (target.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
                val req = PeriodicWorkRequestBuilder<WeatherAlertWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
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
                BookStore.logError(ctx, "WeatherAlertWorker.schedule", t)
            }
        }
    }
}
