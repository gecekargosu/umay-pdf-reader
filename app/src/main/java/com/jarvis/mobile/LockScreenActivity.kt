package com.jarvis.mobile

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.airbnb.lottie.LottieAnimationView
import com.jarvis.mobile.services.WeatherService
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LockScreenActivity : AppCompatActivity() {

    // ── UI References ──
    private lateinit var tvLockTime: TextView
    private lateinit var tvLockDate: TextView
    private lateinit var tvLockDay: TextView
    private lateinit var tvNetworkType: TextView
    private lateinit var tvNetworkSpeed: TextView
    private lateinit var tvRamStatus: TextView
    private lateinit var tvRamDetail: TextView
    private lateinit var tvCpuStatus: TextView
    private lateinit var tvCpuCores: TextView
    private lateinit var tvBatteryStatusTop: TextView
    private lateinit var tvBatteryTemp: TextView
    private lateinit var tvSignalStrength: TextView
    private lateinit var tvSignalDbm: TextView
    private lateinit var tvDndStatus: TextView
    private lateinit var tvDndLabel: TextView
    private lateinit var tvWeatherEmoji: TextView
    private lateinit var tvWeatherTemp: TextView
    private lateinit var tvWeatherDesc: TextView
    private lateinit var tvWeatherFeelsLike: TextView
    private lateinit var tvWeatherWind: TextView
    private lateinit var tvWeatherPrecip: TextView
    private lateinit var tvWeatherHighLow: TextView
    private lateinit var notifWhatsApp: LinearLayout
    private lateinit var tvNotifWhatsappCount: TextView
    private lateinit var notifInstagram: LinearLayout
    private lateinit var tvNotifInstagramCount: TextView
    private lateinit var notifGmail: LinearLayout
    private lateinit var tvNotifGmailCount: TextView
    private lateinit var notifSms: LinearLayout
    private lateinit var tvNotifSmsCount: TextView
    private lateinit var notifPhone: LinearLayout
    private lateinit var tvNotifPhoneCount: TextView
    private lateinit var tvNoNotifications: TextView
    private lateinit var musicPanel: View
    private lateinit var tvMusicTitle: TextView
    private lateinit var tvMusicArtist: TextView
    private lateinit var seekBarMusic: SeekBar
    private lateinit var btnMusicPlay: TextView
    private lateinit var lottieCore: LottieAnimationView
    private lateinit var lottieFingerprint: LottieAnimationView
    private lateinit var tvFingerprintLabel: TextView
    private lateinit var lottieNotifPulse: LottieAnimationView
    private lateinit var scanLine: View

    // ── Services ──
    private lateinit var weatherService: WeatherService
    private val handler = Handler(Looper.getMainLooper())

    // ── CPU tracking ──
    private var cpuPrevIdle = 0L
    private var cpuPrevTotal = 0L

    // ── Notification counts ──
    private val notifCounts = mutableMapOf(
        "whatsapp" to 0,
        "instagram" to 0,
        "gmail" to 0,
        "sms" to 0,
        "phone" to 0
    )

    // ── Battery receiver ──
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateBatteryFromIntent(intent)
        }
    }

    // ── Notification receiver ──
    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra("package_name") ?: return
            val count = intent.getIntExtra("count", 1)
            updateNotificationCount(pkg, count)
        }
    }

    // ── Media session for music ──
    private var mediaSessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on but don't show over keyguard
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Make it a lock screen replacement
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_lock_screen)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        initViews()
        weatherService = WeatherService(this)

        startClockUpdates()
        startSystemInfoUpdates()
        registerReceivers()
        loadWeather()
        setupMediaSession()
        startScanLineAnimation()
        startFingerprintAnimation()
    }

    private fun initViews() {
        tvLockTime = findViewById(R.id.tvLockTime)
        tvLockDate = findViewById(R.id.tvLockDate)
        tvLockDay = findViewById(R.id.tvLockDay)
        tvNetworkType = findViewById(R.id.tvNetworkType)
        tvNetworkSpeed = findViewById(R.id.tvNetworkSpeed)
        tvRamStatus = findViewById(R.id.tvRamStatus)
        tvRamDetail = findViewById(R.id.tvRamDetail)
        tvCpuStatus = findViewById(R.id.tvCpuStatus)
        tvCpuCores = findViewById(R.id.tvCpuCores)
        tvBatteryStatusTop = findViewById(R.id.tvBatteryStatusTop)
        tvBatteryTemp = findViewById(R.id.tvBatteryTemp)
        tvSignalStrength = findViewById(R.id.tvSignalStrength)
        tvSignalDbm = findViewById(R.id.tvSignalDbm)
        tvDndStatus = findViewById(R.id.tvDndStatus)
        tvDndLabel = findViewById(R.id.tvDndLabel)
        tvWeatherEmoji = findViewById(R.id.tvWeatherEmoji)
        tvWeatherTemp = findViewById(R.id.tvWeatherTemp)
        tvWeatherDesc = findViewById(R.id.tvWeatherDesc)
        tvWeatherFeelsLike = findViewById(R.id.tvWeatherFeelsLike)
        tvWeatherWind = findViewById(R.id.tvWeatherWind)
        tvWeatherPrecip = findViewById(R.id.tvWeatherPrecip)
        tvWeatherHighLow = findViewById(R.id.tvWeatherHighLow)
        notifWhatsApp = findViewById(R.id.notifWhatsApp)
        tvNotifWhatsappCount = findViewById(R.id.tvNotifWhatsappCount)
        notifInstagram = findViewById(R.id.notifInstagram)
        tvNotifInstagramCount = findViewById(R.id.tvNotifInstagramCount)
        notifGmail = findViewById(R.id.notifGmail)
        tvNotifGmailCount = findViewById(R.id.tvNotifGmailCount)
        notifSms = findViewById(R.id.notifSms)
        tvNotifSmsCount = findViewById(R.id.tvNotifSmsCount)
        notifPhone = findViewById(R.id.notifPhone)
        tvNotifPhoneCount = findViewById(R.id.tvNotifPhoneCount)
        tvNoNotifications = findViewById(R.id.tvNoNotifications)
        musicPanel = findViewById(R.id.musicPanel)
        tvMusicTitle = findViewById(R.id.tvMusicTitle)
        tvMusicArtist = findViewById(R.id.tvMusicArtist)
        seekBarMusic = findViewById(R.id.seekBarMusic)
        btnMusicPlay = findViewById(R.id.btnMusicPlay)
        lottieCore = findViewById(R.id.lottieCore)
        lottieFingerprint = findViewById(R.id.lottieFingerprint)
        tvFingerprintLabel = findViewById(R.id.tvFingerprintLabel)
        lottieNotifPulse = findViewById(R.id.lottieNotifPulse)
        scanLine = findViewById(R.id.scanLine)

        // Music controls
        btnMusicPlay.setOnClickListener { toggleMusicPlayback() }

        findViewById<TextView>(R.id.btnMusicPrev).setOnClickListener {
            currentController?.transportControls?.skipToPrevious()
            handler.postDelayed({ updateMusicInfo() }, 500)
        }

        findViewById<TextView>(R.id.btnMusicNext).setOnClickListener {
            currentController?.transportControls?.skipToNext()
            handler.postDelayed({ updateMusicInfo() }, 500)
        }

        seekBarMusic.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                val controller = currentController ?: return
                val state = controller.playbackState
                if (state != null && state.duration > 0) {
                    val pos = (sb?.progress ?: 0) * state.duration / 100
                    controller.transportControls.seekTo(pos.toLong())
                }
            }
        })
    }

    // ═══════════════════════════════════════════════════
    //  CLOCK
    // ═══════════════════════════════════════════════════

    private fun startClockUpdates() {
        val clockRunnable = object : Runnable {
            override fun run() {
                val now = Date()
                tvLockTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                tvLockDate.text = SimpleDateFormat("d MMMM yyyy", Locale("tr")).format(now)
                tvLockDay.text = SimpleDateFormat("EEEE", Locale("tr")).format(now).uppercase()
                handler.postDelayed(this, 1000)
            }
        }
        clockRunnable.run()
    }

    // ═══════════════════════════════════════════════════
    //  SYSTEM INFO UPDATES (every 2 seconds)
    // ═══════════════════════════════════════════════════

    private fun startSystemInfoUpdates() {
        val systemRunnable = object : Runnable {
            override fun run() {
                updateRamInfo()
                updateCpuInfo()
                updateNetworkInfo()
                updateSignalInfo()
                updateDndStatus()
                handler.postDelayed(this, 2000)
            }
        }
        systemRunnable.run()

        // Initial battery value (one-shot sticky broadcast query)
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = registerReceiver(null, batteryFilter)
        batteryIntent?.let { updateBatteryFromIntent(it) }
    }

    private fun updateRamInfo() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalMB = memInfo.totalMem / (1024 * 1024)
        val availMB = memInfo.availMem / (1024 * 1024)
        val usedMB = totalMB - availMB
        val usagePercent = ((usedMB.toFloat() / totalMB.toFloat()) * 100).toInt()

        tvRamStatus.text = "🧠 $usagePercent%"
        tvRamDetail.text = "${usedMB}MB / ${totalMB}MB"
    }

    private fun updateCpuInfo() {
        try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine()
            reader.close()

            val parts = line.split("\s+".toRegex())
            val idle = parts[4].toLong()
            val total = parts.drop(1).take(7).sumOf { it.toLong() }

            val diffIdle = idle - cpuPrevIdle
            val diffTotal = total - cpuPrevTotal

            if (diffTotal > 0) {
                val usage = ((diffTotal - diffIdle).toFloat() / diffTotal.toFloat() * 100).toInt()
                tvCpuStatus.text = "⚙️ ${usage.coerceIn(0, 100)}%"
            }

            cpuPrevIdle = idle
            cpuPrevTotal = total

            val cores = Runtime.getRuntime().availableProcessors()
            tvCpuCores.text = "$cores cores"
        } catch (e: Exception) {
            val cores = Runtime.getRuntime().availableProcessors()
            tvCpuStatus.text = "⚙️ $cores cores"
            tvCpuCores.text = ""
        }
    }

    private fun updateBatteryFromIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        val isCharging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
            BatteryManager.BATTERY_STATUS_CHARGING
        val chargeIcon = if (isCharging) "⚡" else "🔋"
        tvBatteryStatusTop.text = "$chargeIcon $pct%"

        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempC = tempTenths / 10.0f
        tvBatteryTemp.text = String.format("%.1f°C", tempC)
    }

    @SuppressLint("MissingPermission")
    private fun updateNetworkInfo() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }

        when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                tvNetworkType.text = "📶 Wi-Fi"
                try {
                    val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val info = wm.connectionInfo
                    val rssi = info?.rssi ?: 0
                    val speedMbps = info?.linkSpeed ?: 0
                    tvNetworkSpeed.text = "${speedMbps} Mbps • ${rssi} dBm"
                } catch (e: Exception) {
                    tvNetworkSpeed.text = "Connected"
                }
            }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                tvNetworkType.text = "📶 4G/5G"
                tvNetworkSpeed.text = "Mobile"
            }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> {
                tvNetworkType.text = "🔌 Ethernet"
                tvNetworkSpeed.text = "Wired"
            }
            else -> {
                tvNetworkType.text = "📶 Offline"
                tvNetworkSpeed.text = ""
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateSignalInfo() {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val signal = tm.signalStrength
            if (signal != null) {
                val level = signal.level
                val dbm = getDbmForLevel(level)
                val bars = "█".repeat(level.coerceAtMost(4)) + "░".repeat(4 - level.coerceAtMost(4))
                tvSignalStrength.text = "📡 $bars"
                tvSignalDbm.text = "$dbm dBm"
            } else {
                tvSignalStrength.text = "📡 N/A"
                tvSignalDbm.text = ""
            }
        } catch (e: Exception) {
            tvSignalStrength.text = "📡 N/A"
            tvSignalDbm.text = ""
        }
    }

    private fun getDbmForLevel(level: Int): Int {
        return when (level) {
            4 -> -70
            3 -> -85
            2 -> -100
            1 -> -115
            else -> -120
        }
    }

    private fun updateDndStatus() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val isDnd = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            if (isDnd) {
                tvDndStatus.text = "🔕"
                tvDndLabel.text = "DND"
                tvDndLabel.setTextColor(getColor(R.color.jarvis_orange))
            } else {
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                when (audioManager.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> {
                        tvDndStatus.text = "🔇"
                        tvDndLabel.text = "Silent"
                    }
                    AudioManager.RINGER_MODE_VIBRATE -> {
                        tvDndStatus.text = "📳"
                        tvDndLabel.text = "Vibrate"
                    }
                    else -> {
                        tvDndStatus.text = "🔊"
                        tvDndLabel.text = "Sound"
                    }
                }
                tvDndLabel.setTextColor(getColor(R.color.jarvis_text_secondary))
            }
        } catch (e: Exception) {
            tvDndStatus.text = "🔊"
            tvDndLabel.text = "Sound"
        }
    }

    // ═══════════════════════════════════════════════════
    //  WEATHER
    // ═══════════════════════════════════════════════════

    private fun loadWeather() {
        weatherService.getWeather { data ->
            handler.post {
                if (data.isLoaded) {
                    tvWeatherEmoji.text = data.getWeatherEmoji()
                    tvWeatherTemp.text = String.format("%.0f°C", data.temperature)
                    tvWeatherDesc.text = data.getWeatherDescription()
                    tvWeatherFeelsLike.text = String.format("Hissedilen: %.0f°", data.feelsLike)
                    tvWeatherWind.text = String.format("💨 %.0f km/h", data.windSpeed)
                    tvWeatherPrecip.text = String.format("💧 %d%%", data.precipitationProbability)
                    tvWeatherHighLow.text = String.format("H: %.0f° L: %.0f°", data.dailyMaxTemp, data.dailyMinTemp)
                } else {
                    tvWeatherDesc.text = "Hava verisi yükleniyor..."
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    //  NOTIFICATIONS
    // ═══════════════════════════════════════════════════

    private fun registerReceivers() {
        val notifFilter = IntentFilter("com.jarvis.mobile.NOTIFICATION_RECEIVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifReceiver, notifFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notifReceiver, notifFilter)
        }

        val battFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, battFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, battFilter)
        }
    }

    private fun updateNotificationCount(packageName: String, count: Int) {
        when {
            packageName.contains("whatsapp") -> {
                notifCounts["whatsapp"] = count
                notifWhatsApp.visibility = if (count > 0) View.VISIBLE else View.GONE
                tvNotifWhatsappCount.text = "$count yeni bildirim"
            }
            packageName.contains("instagram") -> {
                notifCounts["instagram"] = count
                notifInstagram.visibility = if (count > 0) View.VISIBLE else View.GONE
                tvNotifInstagramCount.text = "$count yeni bildirim"
            }
            packageName.contains("gmail") -> {
                notifCounts["gmail"] = count
                notifGmail.visibility = if (count > 0) View.VISIBLE else View.GONE
                tvNotifGmailCount.text = "$count yeni mail"
            }
            packageName.contains("messaging") -> {
                notifCounts["sms"] = count
                notifSms.visibility = if (count > 0) View.VISIBLE else View.GONE
                tvNotifSmsCount.text = "$count yeni mesaj"
            }
            packageName.contains("dialer") -> {
                notifCounts["phone"] = count
                notifPhone.visibility = if (count > 0) View.VISIBLE else View.GONE
                tvNotifPhoneCount.text = "$count cevapsız arama"
            }
        }

        // Show/hide "no notifications" message
        val hasAny = notifCounts.values.any { it > 0 }
        tvNoNotifications.visibility = if (hasAny) View.GONE else View.VISIBLE

        // Trigger pulse animation for new notifications
        if (count > 0) {
            triggerNotifPulse()
        }
    }

    private fun triggerNotifPulse() {
        lottieNotifPulse.removeAllAnimatorListeners()
        lottieNotifPulse.visibility = View.VISIBLE
        lottieNotifPulse.setAnimation(R.raw.notification_pulse)
        lottieNotifPulse.playAnimation()
        lottieNotifPulse.addAnimatorListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                lottieNotifPulse.visibility = View.GONE
            }
        })
    }

    // ═══════════════════════════════════════════════════
    //  MEDIA / MUSIC
    // ═══════════════════════════════════════════════════

    private fun setupMediaSession() {
        try {
            mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = mediaSessionManager?.activeSessions

            if (!controllers.isNullOrEmpty()) {
                currentController = controllers[0]
                updateMusicInfo()
                musicPanel.visibility = View.VISIBLE
            } else {
                musicPanel.visibility = View.GONE
            }
        } catch (e: Exception) {
            // Media session not available
            musicPanel.visibility = View.GONE
        }
    }

    private fun updateMusicInfo() {
        val controller = currentController ?: return
        val metadata = controller.metadata

        if (metadata != null) {
            val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "---"
            val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "---"

            tvMusicTitle.text = title
            tvMusicArtist.text = artist

            // Update play/pause button
            val isPlaying = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            btnMusicPlay.text = if (isPlaying) "⏸" else "▶"
        }
    }

    private fun toggleMusicPlayback() {
        val controller = currentController ?: return
        val isPlaying = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING

        if (isPlaying) {
            controller.transportControls.pause()
            btnMusicPlay.text = "▶"
        } else {
            controller.transportControls.play()
            btnMusicPlay.text = "⏸"
        }
    }

    // ═══════════════════════════════════════════════════
    //  SCAN LINE ANIMATION
    // ═══════════════════════════════════════════════════

    private fun startScanLineAnimation() {
        val scanRunnable = object : Runnable {
            override fun run() {
                scanLine.animate()
                    .translationY(0f)
                    .setDuration(0)
                    .withEndAction {
                        scanLine.animate()
                            .translationY(resources.displayMetrics.heightPixels.toFloat())
                            .setDuration(4000)
                            .withEndAction {
                                handler.postDelayed(this, 2000)
                            }
                            .start()
                    }
                    .start()
            }
        }
        handler.postDelayed(scanRunnable, 1000)
    }

    // ═══════════════════════════════════════════════════
    //  FINGERPRINT ANIMATION
    // ═══════════════════════════════════════════════════

    private fun startFingerprintAnimation() {
        // Lottie handles the animation via lottie_autoPlay and lottie_loop in XML
    }

    // ═══════════════════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════════════════

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
            unregisterReceiver(notifReceiver)
        } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        // Refresh media session when resuming
        setupMediaSession()
    }
}
