package com.umay.pdfreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class WeatherNow(
    val temp: Double?,
    val conditionCode: String,
    val humidity: Int?,
    val wind: Double?,
    val time: String
)

data class WeatherDay(
    val date: String,
    val min: Int,
    val max: Int,
    val conditionCode: String,
    val humidity: Int?,
    val wind: Int?
)

data class WeatherData(
    val cityId: String,
    val cityName: String,
    val center: String,
    val now: WeatherNow?,
    val days: List<WeatherDay>,
    val fetchedAt: Long
)

/**
 * v5.19 — HAVA DURUMU (MGM).
 *
 * Veri kaynağı Meteoroloji Genel Müdürlüğü'nün herkese açık servisidir:
 *  - /web/merkezler?il=<il>            → merkez/istasyon numarası
 *  - /web/tahminler/gunluk?istno=...   → 5 günlük tahmin
 *  - /web/sondurumlar?merkezid=...     → anlık ölçüm
 * MGM, Referer/Origin başlığı olmayan istekleri reddediyor; başlıklar
 * gönderiliyor. Anahtar gerekmez, ücret yoktur.
 *
 * Veri 30 dk önbelleklenir; internet yoksa son veri gösterilir.
 */
object WeatherStore {

    const val KEY_CITY = "weather_city"
    private const val CACHE_PREFIX = "weather_cache_"
    private const val MAX_AGE_MS = 30 * 60_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())

    /** Namaz vakitleriyle aynı 18 il listesi (id'ler MGM'nin il adlarıdır). */
    val CITIES = PrayerTimes.CITIES

    fun cityId(ctx: Context): String {
        val saved = AppPrefs.getString(ctx, KEY_CITY)
        return if (saved in CITIES.map { it.id }) saved else PrayerTimes.city(ctx).id
    }

    fun cityName(ctx: Context): String =
        CITIES.firstOrNull { it.id == cityId(ctx) }?.name ?: "İstanbul"

    // ── Önbellek ────────────────────────────────────────────

    fun cached(ctx: Context, cityId: String): WeatherData? {
        val raw = AppPrefs.getString(ctx, CACHE_PREFIX + cityId)
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            val daysArr = o.optJSONArray("days") ?: JSONArray()
            val days = ArrayList<WeatherDay>()
            for (i in 0 until daysArr.length()) {
                val d = daysArr.getJSONObject(i)
                days.add(
                    WeatherDay(
                        d.optString("date"),
                        d.optInt("min"),
                        d.optInt("max"),
                        d.optString("code"),
                        d.optInt("hum", -1).takeIf { it >= 0 },
                        d.optInt("wind", -1).takeIf { it >= 0 }
                    )
                )
            }
            val n = o.optJSONObject("now")
            val now = n?.let {
                WeatherNow(
                    if (it.has("temp")) it.optDouble("temp") else null,
                    it.optString("code"),
                    it.optInt("hum", -1).takeIf { h -> h >= 0 },
                    if (it.has("wind")) it.optDouble("wind") else null,
                    it.optString("time")
                )
            }
            WeatherData(
                o.optString("cityId", cityId),
                o.optString("cityName"),
                o.optString("center"),
                now,
                days,
                o.optLong("at")
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun isStale(d: WeatherData?): Boolean =
        d == null || System.currentTimeMillis() - d.fetchedAt > MAX_AGE_MS

    private fun save(ctx: Context, d: WeatherData) {
        try {
            val o = JSONObject()
            o.put("cityId", d.cityId)
            o.put("cityName", d.cityName)
            o.put("center", d.center)
            o.put("at", d.fetchedAt)
            val arr = JSONArray()
            for (day in d.days) {
                arr.put(
                    JSONObject().apply {
                        put("date", day.date)
                        put("min", day.min)
                        put("max", day.max)
                        put("code", day.conditionCode)
                        if (day.humidity != null) put("hum", day.humidity)
                        if (day.wind != null) put("wind", day.wind)
                    }
                )
            }
            o.put("days", arr)
            d.now?.let { n ->
                o.put(
                    "now",
                    JSONObject().apply {
                        if (n.temp != null) put("temp", n.temp)
                        put("code", n.conditionCode)
                        if (n.humidity != null) put("hum", n.humidity)
                        if (n.wind != null) put("wind", n.wind)
                        put("time", n.time)
                    }
                )
            }
            AppPrefs.putString(ctx, CACHE_PREFIX + d.cityId, o.toString())
        } catch (_: Throwable) {
        }
    }

    // ── Ağ ──────────────────────────────────────────────────

    private fun http(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Referer", "https://www.mgm.gov.tr/")
            .header("Origin", "https://www.mgm.gov.tr")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
            )
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("MGM HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /** Arka planda veriyi çeker; sonuç ana iş parçacığında gelir. */
    fun fetch(ctx: Context, city: String, cb: (WeatherData?, String?) -> Unit) {
        val app = ctx.applicationContext
        val name = CITIES.firstOrNull { it.id == city }?.name ?: city
        Thread {
            var result: WeatherData? = null
            var error: String? = null
            try {
                val merkez = JSONArray(http("https://servis.mgm.gov.tr/web/merkezler?il=$city"))
                if (merkez.length() == 0) throw IllegalStateException("MGM'de il bulunamadı")
                val c = merkez.getJSONObject(0)
                val merkezId = c.optInt("merkezId")
                val istNo = c.optString("gunlukTahminIstNo").ifBlank { merkezId.toString() }
                val center = c.optString("ilce")

                val daily = JSONArray(http("https://servis.mgm.gov.tr/web/tahminler/gunluk?istno=$istNo"))
                val d = if (daily.length() > 0) daily.getJSONObject(0) else JSONObject()
                val days = ArrayList<WeatherDay>()
                for (i in 1..5) {
                    val min = d.optInt("enDusukGun$i", -999)
                    val max = d.optInt("enYuksekGun$i", -999)
                    if (min == -999 || max == -999) continue
                    val lowHum = d.optInt("enDusukNemGun$i", -1)
                    val highHum = d.optInt("enYuksekNemGun$i", -1)
                    val hum = if (lowHum >= 0 && highHum >= 0) (lowHum + highHum) / 2 else null
                    days.add(
                        WeatherDay(
                            d.optString("tarihGun$i").take(10),
                            min,
                            max,
                            d.optString("hadiseGun$i"),
                            hum,
                            d.optInt("ruzgarHizGun$i", -1).takeIf { it >= 0 }
                        )
                    )
                }

                var now: WeatherNow? = null
                try {
                    val son = JSONArray(http("https://servis.mgm.gov.tr/web/sondurumlar?merkezid=$merkezId"))
                    if (son.length() > 0) {
                        val s = son.getJSONObject(0)
                        val temp = s.optDouble("sicaklik", Double.NaN)
                        val hum = s.optInt("nem", -1)
                        val wind = s.optDouble("ruzgarHiz", Double.NaN)
                        now = WeatherNow(
                            if (temp.isNaN() || temp < -90) null else temp,
                            s.optString("hadiseKodu"),
                            hum.takeIf { it in 0..100 },
                            if (wind.isNaN()) null else wind,
                            s.optString("veriZamani")
                        )
                    }
                } catch (_: Throwable) {
                    // Anlık ölçüm alınamazsa tahmin yine gösterilir.
                }

                result = WeatherData(city, name, center, now, days, System.currentTimeMillis())
                save(app, result)
            } catch (t: Throwable) {
                error = t.message ?: "Bağlantı hatası"
                BookStore.logError(app, "WeatherStore.fetch", t)
            }
            main.post { cb(result, error) }
        }.start()
    }

    // ── Görünüm yardımcıları ────────────────────────────────

    fun condition(code: String): String = when (code.uppercase(Locale.ROOT)) {
        "A" -> "Açık"
        "PB" -> "Parçalı bulutlu"
        "CB" -> "Çok bulutlu"
        "KB" -> "Kapalı"
        "S" -> "Sisli"
        "PUS" -> "Puslu"
        "R" -> "Rüzgarlı"
        "G" -> "Gökgürültülü sağanak"
        "K", "KY" -> "Kar yağışlı"
        "Y" -> "Yağmurlu"
        "SY" -> "Sağanak yağışlı"
        "MS" -> "Mevzii sağanak"
        "DY" -> "Dolu"
        "HY" -> "Hafif yağmur"
        "KKY" -> "Karla karışık yağmur"
        "ÇY" -> "Çisenti"
        "" -> "—"
        else -> code
    }

    fun icon(code: String): String = when (code.uppercase(Locale.ROOT)) {
        "A" -> "☀️"
        "PB" -> "⛅"
        "CB" -> "☁️"
        "KB" -> "☁️"
        "S", "PUS" -> "🌫️"
        "R" -> "💨"
        "G" -> "⛈️"
        "K", "KY" -> "🌨️"
        "Y", "SY", "MS", "HY" -> "🌧️"
        "DY" -> "🌧️"
        "KKY" -> "🌨️"
        "ÇY" -> "🌦️"
        else -> "🌡️"
    }

    fun isRain(code: String): Boolean =
        code.uppercase(Locale.ROOT) in setOf("SY", "Y", "G", "MS", "HY", "KKY", "K", "KY", "DY", "ÇY")

    /** 18.11.2026 → "Bugün" / "Yarın" / "Cuma". */
    fun dayLabel(date: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val parsed = try {
            fmt.parse(date)
        } catch (_: Throwable) {
            null
        } ?: return date
        val key = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = key.format(Date())
        val tomorrowStr = key.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.time
        )
        val targetStr = key.format(parsed)
        if (targetStr == todayStr) return "Bugün"
        if (targetStr == tomorrowStr) return "Yarın"
        return SimpleDateFormat("EEEE", Locale("tr", "TR")).format(parsed)
    }

    fun updatedText(at: Long): String =
        "MGM · " + SimpleDateFormat("HH:mm", Locale("tr", "TR")).format(Date(at))
}
