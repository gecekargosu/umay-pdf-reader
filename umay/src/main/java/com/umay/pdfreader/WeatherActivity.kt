package com.umay.pdfreader

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.umay.pdfreader.databinding.ActivityWeatherBinding
import kotlin.math.roundToInt

/**
 * v5.19 — Hava durumu (MGM): anlık ölçüm, 5 günlük tahmin, yağış uyarısı.
 * Şehir listesi namaz vakitleriyle ortaktır (18 il).
 */
class WeatherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWeatherBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWeatherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowCity.setOnClickListener {
            val cities = WeatherStore.CITIES
            AlertDialog.Builder(this)
                .setTitle(R.string.prayer_city)
                .setItems(cities.map { it.name }.toTypedArray()) { _, i ->
                    AppPrefs.putString(this, WeatherStore.KEY_CITY, cities[i].id)
                    binding.statusText.text = ""
                    load(force = true)
                }
                .show()
        }
        binding.btnRefresh.setOnClickListener { load(force = true) }

        for ((row, alert) in listOf(
            binding.rowWarnRain to WeatherAlert.RAIN,
            binding.rowWarnStorm to WeatherAlert.STORM,
            binding.rowWarnSnow to WeatherAlert.SNOW
        )) {
            row.setOnClickListener {
                AppPrefs.putBool(this, alert.key, !WeatherStore.isAlertEnabled(this, alert))
                paintAlerts()
                WeatherStore.cached(this, WeatherStore.cityId(this))?.let { paint(it) }
            }
        }
        paintAlerts()
        load(force = false)
    }

    private fun load(force: Boolean) {
        val id = WeatherStore.cityId(this)
        val cached = WeatherStore.cached(this, id)
        if (cached != null) paint(cached)
        if (!force && !WeatherStore.isStale(cached)) return

        binding.btnRefresh.isEnabled = false
        if (cached == null) binding.statusText.text = getString(R.string.weather_loading)
        WeatherStore.fetch(this, id) { data, err ->
            runOnUiThread {
                binding.btnRefresh.isEnabled = true
                if (data != null) {
                    binding.statusText.text = ""
                    paint(data)
                } else {
                    binding.statusText.text = getString(
                        if (cached != null) R.string.weather_stale else R.string.weather_error
                    ) + (err?.let { " · $it" } ?: "")
                }
            }
        }
    }

    private fun paintAlerts() {
        val on = getString(R.string.weather_warn_on)
        val off = getString(R.string.weather_warn_off)
        binding.rowWarnRain.text = getString(R.string.weather_warn_rain_row) + ": " +
            if (WeatherStore.isAlertEnabled(this, WeatherAlert.RAIN)) on else off
        binding.rowWarnStorm.text = getString(R.string.weather_warn_storm_row) + ": " +
            if (WeatherStore.isAlertEnabled(this, WeatherAlert.STORM)) on else off
        binding.rowWarnSnow.text = getString(R.string.weather_warn_snow_row) + ": " +
            if (WeatherStore.isAlertEnabled(this, WeatherAlert.SNOW)) on else off
    }

    private fun paint(d: WeatherData) {
        binding.rowCity.text = getString(R.string.weather_city_fmt, d.cityName)
        binding.updatedText.text = getString(
            R.string.weather_updated_fmt,
            WeatherStore.updatedText(d.fetchedAt).removePrefix("MGM · "),
            d.center
        )

        val now = d.now
        val today = d.days.firstOrNull()
        val temp = now?.temp?.roundToInt() ?: today?.max
        binding.currentTemp.text = temp?.let { "$it°" } ?: "—"
        val code = now?.conditionCode?.takeIf { it.isNotBlank() } ?: today?.conditionCode.orEmpty()
        binding.currentIcon.text = WeatherStore.icon(code)
        binding.currentCond.text = WeatherStore.condition(code)

        val details = ArrayList<String>()
        now?.humidity?.let { details.add("Nem %$it") }
        now?.wind?.let { details.add("Rüzgar ${it.roundToInt()} km/sa") }
        if (details.isEmpty()) today?.let { details.add("${it.min}° / ${it.max}°") }
        binding.currentDetails.text = details.joinToString(" · ")

        val alerts = if (today != null) WeatherStore.activeAlerts(this, today) else emptyList()
        if (alerts.isNotEmpty()) {
            binding.warningText.visibility = View.VISIBLE
            binding.warningText.text = getString(
                R.string.weather_warn_fmt,
                alerts.joinToString(" · ") { it.label } + " · " + WeatherStore.condition(today?.conditionCode.orEmpty())
            )
        } else {
            binding.warningText.visibility = View.GONE
        }

        binding.daysList.removeAllViews()
        for ((i, day) in d.days.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
            val left = TextView(this).apply {
                text = WeatherStore.dayLabel(day.date)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@WeatherActivity, R.color.umay_text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val mid = TextView(this).apply {
                text = "${WeatherStore.icon(day.conditionCode)} ${WeatherStore.condition(day.conditionCode)}"
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(this@WeatherActivity, R.color.umay_text))
                alpha = 0.85f
            }
            val right = TextView(this).apply {
                text = "${day.min}° / ${day.max}°"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@WeatherActivity, R.color.umay_accent))
                setPadding(dp(10), 0, 0, 0)
            }
            row.addView(left)
            row.addView(mid)
            row.addView(right)
            binding.daysList.addView(row)
            if (i < d.days.size - 1) {
                binding.daysList.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(0x1AFFFFFF)
                })
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
