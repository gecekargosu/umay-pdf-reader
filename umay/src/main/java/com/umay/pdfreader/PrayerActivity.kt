package com.umay.pdfreader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.umay.pdfreader.databinding.ActivityPrayerBinding

/**
 * v5.18 — Namaz vakitleri ekranı: aç/kapa, şehir, iki uyarı aralığı, ses,
 * bugünün vakitleri ve sıradaki vakte kalan süre.
 */
class PrayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrayerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        binding.rowOn.setOnClickListener {
            val on = !PrayerTimes.isOn(this)
            AppPrefs.putBool(this, PrayerTimes.KEY_ON, on)
            if (on) askNotificationPermission()
            PrayerTimes.ensureScheduled(this)
            paint()
        }

        binding.rowCity.setOnClickListener {
            val cities = PrayerTimes.CITIES
            AlertDialog.Builder(this)
                .setTitle(R.string.prayer_city)
                .setItems(cities.map { it.name }.toTypedArray()) { _, i ->
                    AppPrefs.putString(this, PrayerTimes.KEY_CITY, cities[i].id)
                    PrayerTimes.ensureScheduled(this)
                    paint()
                }
                .show()
        }

        binding.rowPre.setOnClickListener {
            val opts = intArrayOf(0, 10, 15, 20, 25, 30)
            val labels = opts.map { if (it == 0) getString(R.string.prayer_off) else getString(R.string.prayer_min_fmt, it) }
            AlertDialog.Builder(this)
                .setTitle(R.string.prayer_pre)
                .setItems(labels.toTypedArray()) { _, i ->
                    AppPrefs.putInt(this, PrayerTimes.KEY_PRE, opts[i])
                    PrayerTimes.ensureScheduled(this)
                    paint()
                }
                .show()
        }

        binding.rowEnd.setOnClickListener {
            val opts = intArrayOf(0, 15, 30, 45, 60)
            val labels = opts.map { if (it == 0) getString(R.string.prayer_off) else getString(R.string.prayer_min_fmt, it) }
            AlertDialog.Builder(this)
                .setTitle(R.string.prayer_end)
                .setItems(labels.toTypedArray()) { _, i ->
                    AppPrefs.putInt(this, PrayerTimes.KEY_END, opts[i])
                    PrayerTimes.ensureScheduled(this)
                    paint()
                }
                .show()
        }

        binding.rowSound.setOnClickListener {
            AppPrefs.putBool(this, PrayerTimes.KEY_SOUND, !PrayerTimes.soundOn(this))
            paint()
        }

        // Uyarının sesini/bildirimini yerinde denemek için
        binding.rowTest.setOnClickListener {
            PrayerTimes.showAlert(this, Vakit.DHUHR, PrayerTimes.KIND_PRE, PrayerTimes.preMinutes(this).coerceAtLeast(1))
        }

        paint()
    }

    override fun onResume() {
        super.onResume()
        paint()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7311)
            } catch (_: Throwable) {
            }
        }
    }

    private fun paint() {
        val on = PrayerTimes.isOn(this)
        binding.rowOn.text = getString(if (on) R.string.prayer_on else R.string.prayer_off)
        binding.rowOn.setTextColor(ContextCompat.getColor(this, if (on) R.color.umay_accent else R.color.umay_text))
        binding.rowCity.text = getString(R.string.prayer_city_fmt, PrayerTimes.city(this).name)

        val pre = PrayerTimes.preMinutes(this)
        binding.rowPre.text = if (pre <= 0) getString(R.string.prayer_pre_off)
        else getString(R.string.prayer_pre_fmt, pre)

        val end = PrayerTimes.endMinutes(this)
        binding.rowEnd.text = if (end <= 0) getString(R.string.prayer_end_off)
        else getString(R.string.prayer_end_fmt, end)

        binding.rowSound.text = getString(if (PrayerTimes.soundOn(this)) R.string.prayer_sound_on else R.string.prayer_sound_off)

        for (row in listOf(binding.rowCity, binding.rowPre, binding.rowEnd, binding.rowSound)) {
            row.alpha = if (on) 1f else 0.45f
            row.isEnabled = on
        }

        val next = if (on) PrayerTimes.next(this) else null
        binding.nextText.text = when {
            !on -> getString(R.string.prayer_next_off)
            next == null -> getString(R.string.prayer_next_none)
            else -> getString(
                R.string.prayer_next_fmt,
                PrayerTimes.label(this, next.vakit),
                (if (next.tomorrow) getString(R.string.prayer_tomorrow) + " " else "") + PrayerTimes.fmt(next.at),
                PrayerTimes.remaining(next.at.timeInMillis - System.currentTimeMillis())
            )
        }

        binding.timesList.removeAllViews()
        val times = PrayerTimes.today(this)
        val nowVakit = next?.vakit
        for (v in listOf(Vakit.FAJR, Vakit.SUNRISE, Vakit.DHUHR, Vakit.ASR, Vakit.MAGHRIB, Vakit.ISHA)) {
            val at = times[v] ?: continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            val name = TextView(this).apply {
                text = if (v == Vakit.SUNRISE) getString(R.string.prayer_sunrise) else PrayerTimes.label(this@PrayerActivity, v)
                textSize = 14.5f
                setTextColor(ContextCompat.getColor(this@PrayerActivity, R.color.umay_text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val time = TextView(this).apply {
                text = PrayerTimes.fmt(at)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@PrayerActivity, R.color.umay_accent))
            }
            if (v == nowVakit) name.alpha = 1f else name.alpha = 0.85f
            row.addView(name)
            row.addView(time)
            binding.timesList.addView(row)
        }
        binding.cityNote.text = getString(R.string.prayer_note, PrayerTimes.city(this).name)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
