package com.jarvis.mobile

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Point
import android.net.wifi.WifiInfo

class MainActivity : AppCompatActivity() {

    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvBatteryPercent: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvBatteryTemp: TextView
    private lateinit var tvBatteryHealth: TextView
    private lateinit var progressBattery: ProgressBar
    private lateinit var progressRam: ProgressBar
    private lateinit var tvRamUsage: TextView
    private lateinit var progressCpu: ProgressBar
    private lateinit var tvCpuInfo: TextView
    private lateinit var tvCpuModel: TextView
    private lateinit var dotWifi: View
    private lateinit var tvWifiStatus: TextView
    private lateinit var tvWifiSsid: TextView
    private lateinit var tvWifiSignal: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvDeviceModel: TextView
    private lateinit var tvManufacturer: TextView
    private lateinit var tvAndroidVersion: TextView
    private lateinit var tvSdkLevel: TextView
    private lateinit var tvBuildId: TextView
    private lateinit var tvResolution: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var cpuUsagePrevIdle = 0L
    private var cpuUsagePrevTotal = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateBatteryInfo(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initViews()
        populateDeviceInfo()
        startClockUpdates()
        startCpuUpdates()
        registerBatteryReceiver()
    }

    private lateinit var btnLockScreen: Button

    private fun initViews() {
        tvTime = findViewById(R.id.tvTime)
        btnLockScreen = findViewById(R.id.btnLockScreen)
        btnLockScreen.setOnClickListener {
            startActivity(Intent(this, LockScreenActivity::class.java))
        }
        tvDate = findViewById(R.id.tvDate)
        tvBatteryPercent = findViewById(R.id.tvBatteryPercent)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvBatteryTemp = findViewById(R.id.tvBatteryTemp)
        tvBatteryHealth = findViewById(R.id.tvBatteryHealth)
        progressBattery = findViewById(R.id.progressBattery)
        progressRam = findViewById(R.id.progressRam)
        tvRamUsage = findViewById(R.id.tvRamUsage)
        progressCpu = findViewById(R.id.progressCpu)
        tvCpuInfo = findViewById(R.id.tvCpuInfo)
        tvCpuModel = findViewById(R.id.tvCpuModel)
        dotWifi = findViewById(R.id.dotWifi)
        tvWifiStatus = findViewById(R.id.tvWifiStatus)
        tvWifiSsid = findViewById(R.id.tvWifiSsid)
        tvWifiSignal = findViewById(R.id.tvWifiSignal)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvDeviceModel = findViewById(R.id.tvDeviceModel)
        tvManufacturer = findViewById(R.id.tvManufacturer)
        tvAndroidVersion = findViewById(R.id.tvAndroidVersion)
        tvSdkLevel = findViewById(R.id.tvSdkLevel)
        tvBuildId = findViewById(R.id.tvBuildId)
        tvResolution = findViewById(R.id.tvResolution)
    }

    // ═══════════════════════════════════════════════════
    //  DEVICE INFO — collected once
    // ═══════════════════════════════════════════════════

    private fun populateDeviceInfo() {
        tvDeviceModel.text = "${Build.MANUFACTURER} ${Build.MODEL}"
        tvManufacturer.text = Build.MANUFACTURER
        tvAndroidVersion.text = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        tvSdkLevel.text = "API ${Build.VERSION.SDK_INT}"
        tvBuildId.text = Build.ID

        // Screen resolution
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val display = wm.defaultDisplay
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        tvResolution.text = "${size.x} × ${size.y}"

        // CPU model from /proc/cpuinfo
        tvCpuModel.text = getCpuModel()

        // Initial RAM info
        updateRamInfo()

        // Initial Wi-Fi info
        updateWifiInfo()
    }

    private fun getCpuModel(): String {
        return try {
            val reader = RandomAccessFile("/proc/cpuinfo", "r")
            var line: String?
            var cpuPart = ""
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains("Hardware") || line!!.contains("model name") || line!!.contains("Processor")) {
                    cpuPart = line!!.split(":").getOrElse(1) { "" }.trim()
                    break
                }
            }
            reader.close()
            cpuPart.ifEmpty { "${Runtime.getRuntime().availableProcessors()} Cores" }
        } catch (e: Exception) {
            "${Runtime.getRuntime().availableProcessors()} Cores"
        }
    }

    // ═══════════════════════════════════════════════════
    //  REAL-TIME CLOCK
    // ═══════════════════════════════════════════════════

    private fun startClockUpdates() {
        val clockRunnable = object : Runnable {
            override fun run() {
                val now = Date()
                tvTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
                tvDate.text = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault()).format(now)
                handler.postDelayed(this, 1000)
            }
        }
        clockRunnable.run()
    }

    // ═══════════════════════════════════════════════════
    //  BATTERY INFO
    // ═══════════════════════════════════════════════════

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = registerReceiver(batteryReceiver, filter)
        batteryIntent?.let { updateBatteryInfo(it) }
    }

    @SuppressLint("DefaultLocale")
    private fun updateBatteryInfo(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        tvBatteryPercent.text = "$batteryPct"
        progressBattery.progress = batteryPct

        // Battery status
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        tvBatteryStatus.text = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "⚡ Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "↓ Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "✓ Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "— Idle"
            else -> "Unknown"
        }

        // Temperature
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempCelsius = tempTenths / 10.0f
        tvBatteryTemp.text = String.format("%.1f°C / %.1f°F", tempCelsius, tempCelsius * 9 / 5 + 32)

        // Health
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        tvBatteryHealth.text = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat!"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }
    }

    // ═══════════════════════════════════════════════════
    //  RAM INFO
    // ═══════════════════════════════════════════════════

    private fun updateRamInfo() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalMB = memInfo.totalMem / (1024 * 1024)
        val availMB = memInfo.availMem / (1024 * 1024)
        val usedMB = totalMB - availMB
        val usagePercent = ((usedMB.toFloat() / totalMB.toFloat()) * 100).toInt()

        progressRam.progress = usagePercent
        tvRamUsage.text = "${usedMB}MB / ${totalMB}MB"
    }

    // ═══════════════════════════════════════════════════
    //  CPU INFO
    // ═══════════════════════════════════════════════════

    private fun startCpuUpdates() {
        val cpuRunnable = object : Runnable {
            override fun run() {
                updateCpuUsage()
                updateRamInfo()
                updateWifiInfo()
                handler.postDelayed(this, 2000)
            }
        }
        cpuRunnable.run()
    }

    private fun updateCpuUsage() {
        try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine()
            reader.close()

            val parts = line.split("\\s+".toRegex())
            val idle = parts[4].toLong()
            val total = parts.drop(1).take(7).sumOf { it.toLong() }

            val diffIdle = idle - cpuUsagePrevIdle
            val diffTotal = total - cpuUsagePrevTotal

            if (diffTotal > 0) {
                val usage = ((diffTotal - diffIdle).toFloat() / diffTotal.toFloat() * 100).toInt()
                progressCpu.progress = usage.coerceIn(0, 100)
                tvCpuInfo.text = "${Runtime.getRuntime().availableProcessors()} Cores • ${usage}%"
            }

            cpuUsagePrevIdle = idle
            cpuUsagePrevTotal = total
        } catch (e: Exception) {
            tvCpuInfo.text = "${Runtime.getRuntime().availableProcessors()} Cores"
        }
    }

    // ═══════════════════════════════════════════════════
    //  NETWORK / WIFI INFO
    // ═══════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private fun updateWifiInfo() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        val isWifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        if (isWifiConnected) {
            dotWifi.setBackgroundResource(R.drawable.status_dot_active)
            tvWifiStatus.text = "Connected"
            tvWifiStatus.setTextColor(getColor(R.color.jarvis_green))

            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // For Android 12+, networkCapabilities must be used
                    val ssid = wifiManager.connectionInfo?.ssid ?: "N/A"
                    wifiManager.connectionInfo
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.connectionInfo
                }

                if (wifiInfo != null) {
                    @Suppress("DEPRECATION")
                    val ssid = wifiInfo.ssid?.replace("\"", "") ?: "N/A"
                    @Suppress("DEPRECATION")
                    val rssi = wifiInfo.rssi
                    @Suppress("DEPRECATION")
                    val ip = wifiInfo.ipAddress

                    tvWifiSsid.text = ssid
                    tvWifiSignal.text = "${rssi} dBm (${getSignalLevel(rssi)})"
                    tvIpAddress.text = formatIpAddress(ip)
                }
            } catch (e: Exception) {
                tvWifiSsid.text = "N/A"
                tvWifiSignal.text = "N/A"
                tvIpAddress.text = "N/A"
            }
        } else {
            dotWifi.setBackgroundResource(R.drawable.status_dot_inactive)
            tvWifiStatus.text = "Disconnected"
            tvWifiStatus.setTextColor(getColor(R.color.jarvis_red))
            tvWifiSsid.text = "—"
            tvWifiSignal.text = "—"
            tvIpAddress.text = "—"
        }
    }

    private fun getSignalLevel(rssi: Int): String {
        return when {
            rssi >= -50 -> "Excellent"
            rssi >= -60 -> "Good"
            rssi >= -70 -> "Fair"
            rssi >= -80 -> "Weak"
            else -> "Very Weak"
        }
    }

    private fun formatIpAddress(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) { }
        handler.removeCallbacksAndMessages(null)
    }
}
