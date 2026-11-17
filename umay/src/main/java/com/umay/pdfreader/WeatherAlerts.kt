package com.umay.pdfreader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** v5.20 — Hava uyarısı türleri; her biri ayarlardan kapatılabilir. */
enum class WeatherAlert(val label: String, val key: String) {
    RAIN("Yağmur", "weather_warn_rain"),
    STORM("Fırtına", "weather_warn_storm"),
    SNOW("Kar", "weather_warn_snow")
}

private val RAIN_CODES = setOf("SY", "Y", "MS", "HY", "ÇY")
private val SNOW_CODES = setOf("K", "KY", "KKY")
private const val STORM_WIND_KMH = 50

/** Bir günün tahmininden uyarı türlerini çıkarır (MGM hadise kodu + rüzgar). */
fun WeatherStore.alertsFor(day: WeatherDay): List<WeatherAlert> {
    val code = day.conditionCode.uppercase()
    val out = ArrayList<WeatherAlert>()
    if (code == "G" || (day.wind ?: 0) >= STORM_WIND_KMH) out.add(WeatherAlert.STORM)
    if (code in SNOW_CODES) out.add(WeatherAlert.SNOW)
    if (code in RAIN_CODES) out.add(WeatherAlert.RAIN)
    return out
}

fun WeatherStore.isAlertEnabled(ctx: Context, alert: WeatherAlert): Boolean =
    AppPrefs.getBool(ctx, alert.key, true)

fun WeatherStore.activeAlerts(ctx: Context, day: WeatherDay): List<WeatherAlert> =
    alertsFor(day).filter { isAlertEnabled(ctx, it) }

/** Günlük uyarı bildirimi (WeatherAlertWorker kullanır). */
fun WeatherStore.notifyAlerts(ctx: Context, data: WeatherData, alerts: List<WeatherAlert>) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_WEATHER) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_WEATHER,
                        "Hava durumu uyarıları",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "Yağmur, fırtına ve kar uyarıları" }
                )
            }
        }
        val names = alerts.joinToString(", ") { it.label }
        val title = "${data.cityName}: $names uyarısı"
        val text = buildString {
            append(WeatherStore.condition(data.days.firstOrNull()?.conditionCode.orEmpty()))
            data.days.firstOrNull()?.let { append(" · ${it.min}°/${it.max}°") }
            append(" · MGM")
        }
        val pi = PendingIntent.getActivity(
            ctx, 9550,
            Intent(ctx, WeatherActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_WEATHER)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setColor(ctx.getColor(R.color.umay_orange))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(9550, n)
    } catch (t: Throwable) {
        BookStore.logError(ctx, "WeatherStore.notifyAlerts", t)
    }
}

const val CHANNEL_WEATHER = "umay_weather"
