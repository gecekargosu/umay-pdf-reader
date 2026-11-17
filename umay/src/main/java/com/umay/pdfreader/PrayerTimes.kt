package com.umay.pdfreader

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.batoulapps.adhan.CalculationParameters
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.PrayerAdjustments
import com.batoulapps.adhan.PrayerTimes as AdhanPrayerTimes
import com.batoulapps.adhan.data.DateComponents
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Namaz vakti türleri (Güneş ayrı tutulur: uyarı almaz, imsakın bitişidir). */
enum class Vakit(val tr: String) {
    FAJR("İmsak"),
    SUNRISE("Güneş"),
    DHUHR("Öğle"),
    ASR("İkindi"),
    MAGHRIB("Akşam"),
    ISHA("Yatsı")
}

data class PrayerCity(val id: String, val name: String, val lat: Double, val lon: Double)

/**
 * v5.18 — NAMAZ VAKİTLERİ.
 *
 * Hesap tamamen çevrimdışıdır (Adhan, "Astronomical Algorithms" formülleri).
 * Diyanet yöntemi: imsak 18°, yatsı 17°, ikindi gölge 1; Türkiye için
 * kullanılan dakika düzeltmeleri uygulanır. İstanbul için referans
 * değerlerle ±2 dk içinde doğrulandı (17-18 Kasım 2026).
 *
 * Uyarılar:
 *  - Vakitten [preMin] dakika önce ("Öğle namazına 20 dakika")
 *  - Vakit çıkmadan [endMin] dakika önce ("Öğle vakti bitiyor")
 * Alarmlar 8 gün ileriye kurulur; uygulama açılışında ve cihaz açılışında
 * yenilenir. Pil dostu: sürekli servis/konum takibi yoktur.
 */
object PrayerTimes {

    const val KEY_ON = "prayer_on"
    const val KEY_CITY = "prayer_city"
    const val KEY_PRE = "prayer_pre_min"
    const val KEY_END = "prayer_end_min"
    const val KEY_SOUND = "prayer_sound"

    const val CHANNEL = "umay_prayer"
    const val CHANNEL_SILENT = "umay_prayer_silent"

    const val EXTRA_VAKIT = "prayer_vakit"
    const val EXTRA_KIND = "prayer_kind"
    const val EXTRA_MIN = "prayer_minutes"
    const val KIND_PRE = "pre"
    const val KIND_END = "end"

    private const val BASE_REQUEST = 1200
    private const val DAYS_AHEAD = 8

    val CITIES = listOf(
        PrayerCity("istanbul", "İstanbul", 41.0082, 28.9784),
        PrayerCity("ankara", "Ankara", 39.9334, 32.8597),
        PrayerCity("izmir", "İzmir", 38.4237, 27.1428),
        PrayerCity("bursa", "Bursa", 40.1885, 29.0610),
        PrayerCity("antalya", "Antalya", 36.8969, 30.7133),
        PrayerCity("konya", "Konya", 37.8746, 32.4932),
        PrayerCity("adana", "Adana", 37.0000, 35.3213),
        PrayerCity("gaziantep", "Gaziantep", 37.0662, 37.3833),
        PrayerCity("sanliurfa", "Şanlıurfa", 37.1591, 38.7969),
        PrayerCity("trabzon", "Trabzon", 41.0027, 39.7168),
        PrayerCity("diyarbakir", "Diyarbakır", 37.9144, 40.2306),
        PrayerCity("kayseri", "Kayseri", 38.7312, 35.4787),
        PrayerCity("samsun", "Samsun", 41.2867, 36.3300),
        PrayerCity("eskisehir", "Eskişehir", 39.7767, 30.5206),
        PrayerCity("denizli", "Denizli", 37.7765, 29.0864),
        PrayerCity("erzurum", "Erzurum", 39.9043, 41.2679),
        PrayerCity("van", "Van", 38.5012, 43.3729),
        PrayerCity("malatya", "Malatya", 38.3552, 38.3095)
    )

    private val named = listOf(Vakit.FAJR, Vakit.DHUHR, Vakit.ASR, Vakit.MAGHRIB, Vakit.ISHA)

    // ── Tercihler ───────────────────────────────────────────

    fun city(ctx: Context): PrayerCity {
        val id = AppPrefs.getString(ctx, KEY_CITY)
        return CITIES.firstOrNull { it.id == id } ?: CITIES[0]
    }

    fun isOn(ctx: Context): Boolean = AppPrefs.getBool(ctx, KEY_ON, false)
    fun preMinutes(ctx: Context): Int = AppPrefs.getInt(ctx, KEY_PRE, 20).coerceIn(0, 60)
    fun endMinutes(ctx: Context): Int = AppPrefs.getInt(ctx, KEY_END, 30).coerceIn(0, 90)
    fun soundOn(ctx: Context): Boolean = AppPrefs.getBool(ctx, KEY_SOUND, true)

    // ── Hesap ───────────────────────────────────────────────

    fun timesFor(day: Calendar, city: PrayerCity): Map<Vakit, Calendar> {
        val d = Calendar.getInstance().apply { timeInMillis = day.timeInMillis }
        val params = CalculationParameters(18.0, 17.0).apply {
            madhab = Madhab.SHAFI
            // Türkiye/Diyanet dakika düzeltmeleri (Adhan Turkey preset).
            adjustments = PrayerAdjustments(0, -7, 5, 5, 7, 0)
        }
        val t = AdhanPrayerTimes(
            Coordinates(city.lat, city.lon),
            DateComponents(d.get(Calendar.YEAR), d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH)),
            params
        )
        fun cal(x: Date) = Calendar.getInstance().apply { time = x }
        return linkedMapOf(
            Vakit.FAJR to cal(t.fajr),
            Vakit.SUNRISE to cal(t.sunrise),
            Vakit.DHUHR to cal(t.dhuhr),
            Vakit.ASR to cal(t.asr),
            Vakit.MAGHRIB to cal(t.maghrib),
            Vakit.ISHA to cal(t.isha)
        )
    }

    fun today(ctx: Context): Map<Vakit, Calendar> = timesFor(Calendar.getInstance(), city(ctx))

    /** Cuma günü öğle vakti "Cuma" olarak anılır. */
    fun label(ctx: Context, v: Vakit): String =
        if (v == Vakit.DHUHR && Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY) "Cuma" else v.tr

    data class Next(val vakit: Vakit, val at: Calendar, val tomorrow: Boolean)

    fun next(ctx: Context): Next? {
        val now = System.currentTimeMillis()
        val city = city(ctx)
        val today = timesFor(Calendar.getInstance(), city)
        named.forEach { v ->
            val at = today[v] ?: return@forEach
            if (at.timeInMillis > now + 30_000) return Next(v, at, false)
        }
        val tomorrowCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrow = timesFor(tomorrowCal, city)
        val fajr = tomorrow[Vakit.FAJR] ?: return null
        return Next(Vakit.FAJR, fajr, true)
    }

    fun fmt(c: Calendar): String = SimpleDateFormat("HH:mm", Locale("tr", "TR")).format(c.time)

    /** "1 sa 12 dk" / "18 dk" biçiminde kalan süre. */
    fun remaining(ms: Long): String {
        val total = (ms / 60_000L).coerceAtLeast(0)
        val h = total / 60
        val m = total % 60
        return if (h > 0) "$h sa $m dk" else "$m dk"
    }

    // ── Alarmlar ────────────────────────────────────────────

    private fun pending(ctx: Context, code: Int, v: Vakit, kind: String, minutes: Int): PendingIntent {
        val i = Intent(ctx, PrayerAlertReceiver::class.java).apply {
            putExtra(EXTRA_VAKIT, v.name)
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_MIN, minutes)
        }
        return PendingIntent.getBroadcast(
            ctx, code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        for (d in 0 until DAYS_AHEAD) {
            for (v in Vakit.values()) {
                for (k in 0..1) {
                    val code = BASE_REQUEST + d * 20 + v.ordinal * 2 + k
                    am.cancel(pending(ctx, code, v, if (k == 0) KIND_PRE else KIND_END, 0))
                }
            }
        }
    }

    /** Ayarlara göre önümüzdeki 8 günün uyarılarını kurar. */
    fun ensureScheduled(ctx: Context) {
        try {
            cancelAll(ctx)
            if (!isOn(ctx)) return
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val city = city(ctx)
            val pre = preMinutes(ctx)
            val endBefore = endMinutes(ctx)
            val now = System.currentTimeMillis()

            for (d in 0 until DAYS_AHEAD) {
                val day = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, d)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val times = timesFor(day, city)
                val nextDay = Calendar.getInstance().apply { timeInMillis = day.timeInMillis; add(Calendar.DAY_OF_YEAR, 1) }
                val tomorrowTimes = timesFor(nextDay, city)

                // 1) Vakitten önce
                if (pre > 0) {
                    for (v in named) {
                        val at = (times[v]?.timeInMillis ?: continue) - pre * 60_000L
                        if (at > now) setAlarm(am, at, pending(ctx, BASE_REQUEST + d * 20 + v.ordinal * 2, v, KIND_PRE, pre))
                    }
                }

                // 2) Vakit çıkmadan önce (vakit = sonraki vaktin girişi)
                if (endBefore > 0) {
                    for (v in named) {
                        val endAt = when (v) {
                            Vakit.FAJR -> times[Vakit.SUNRISE]
                            Vakit.DHUHR -> times[Vakit.ASR]
                            Vakit.ASR -> times[Vakit.MAGHRIB]
                            Vakit.MAGHRIB -> times[Vakit.ISHA]
                            Vakit.ISHA -> tomorrowTimes[Vakit.FAJR]
                            else -> null
                        } ?: continue
                        val at = endAt.timeInMillis - endBefore * 60_000L
                        if (at > now) setAlarm(am, at, pending(ctx, BASE_REQUEST + d * 20 + v.ordinal * 2 + 1, v, KIND_END, endBefore))
                    }
                }
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "PrayerTimes.ensureScheduled", t)
        }
    }

    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent) {
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Throwable) {
            }
        }
    }

    // ── Bildirim ────────────────────────────────────────────

    private fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Namaz vakti uyarıları", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Vakitten önce ve vakit çıkmadan önce uyarı"
                    enableVibration(true)
                    setSound(
                        sound,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_SILENT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SILENT, "Namaz vakti (sessiz)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Yalnızca ekranda görünür, ses çıkarmaz"
                    setSound(null, null)
                }
            )
        }
    }

    fun showAlert(ctx: Context, v: Vakit, kind: String, minutes: Int) {
        try {
            channels(ctx)
            val city = city(ctx)
            val times = today(ctx)
            val time = times[v]?.let { fmt(it) } ?: ""
            val title: String
            val text: String
            if (kind == KIND_END) {
                title = "${label(ctx, v)} vakti bitiyor"
                text = "${label(ctx, v)} vakti için $minutes dakika kaldı · ${city.name}"
            } else {
                title = "${label(ctx, v)} namazına $minutes dakika"
                text = "${city.name} · ${label(ctx, v)} $time"
            }
            val pi = PendingIntent.getActivity(
                ctx, 9400 + v.ordinal,
                Intent(ctx, PrayerActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val silent = !soundOn(ctx)
            val n = NotificationCompat.Builder(ctx, if (silent) CHANNEL_SILENT else CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setColor(ctx.getColor(R.color.umay_orange))
                .setAutoCancel(true)
                .setSilent(silent)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(9400 + v.ordinal, n)
        } catch (t: Throwable) {
            BookStore.logError(ctx, "PrayerTimes.showAlert", t)
        }
    }
}

/** AlarmManager tarafından tetiklenen namaz uyarısı. */
class PrayerAlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            val v = intent.getStringExtra(PrayerTimes.EXTRA_VAKIT)
                ?.let { runCatching { Vakit.valueOf(it) }.getOrNull() } ?: return
            val kind = intent.getStringExtra(PrayerTimes.EXTRA_KIND) ?: PrayerTimes.KIND_PRE
            val min = intent.getIntExtra(PrayerTimes.EXTRA_MIN, 20)
            PrayerTimes.showAlert(ctx, v, kind, min)
        } catch (t: Throwable) {
            BookStore.logError(ctx, "PrayerAlertReceiver", t)
        }
    }
}
