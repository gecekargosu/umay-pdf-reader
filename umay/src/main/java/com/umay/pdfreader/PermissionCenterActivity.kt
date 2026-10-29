package com.umay.pdfreader

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

/**
 * v5.5 (AŞAMA 1) — BİLDİRİM & ALARM İZİN MERKEZİ
 *
 * Neden gerekli: HyperOS/MIUI'de hatırlatıcıların gerçekten çalışması için
 * Android'in standart izinlerine EK olarak Xiaomi'ye özel iki ayar gerekiyor
 * (otomatik başlatma + pil kısıtlaması). Kullanıcı bunları menülerde
 * bulamıyordu. Bu ekran:
 *  • her iznin GERÇEK durumunu okur (tahmin etmez),
 *  • doğru sistem ekranını açar (marka bazlı, çözümlenemezse uygulama
 *    ayrıntısına düşer),
 *  • ve "test et" ile bildirimi + 10 sn sonrasına kesin alarmı kurar —
 *    böylece izin gerçekten çalışıyor mu görülür.
 */
class PermissionCenterActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        list = findViewById(R.id.permList)
        status = findViewById(R.id.permStatus)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnPermTest).setOnClickListener { runTest() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ── Durum okuma ──────────────────────────────────────────

    private fun notificationsAllowed(): Boolean {
        val enabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val ch = getSystemService(NotificationManager::class.java)
            .getNotificationChannel(Reminder.CHANNEL_ID)
        val channelBlocked = ch != null &&
            ch.importance == NotificationManager.IMPORTANCE_NONE
        return enabled && !channelBlocked
    }

    private fun exactAlarmAllowed(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else true

    private fun batteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)

    // ── Ekranı çiz ───────────────────────────────────────────

    private fun refresh() {
        list.removeAllViews()
        val nOk = notificationsAllowed()
        val aOk = exactAlarmAllowed()
        val bOk = batteryUnrestricted()

        status.text = if (nOk && aOk && bOk) {
            "✅ Zorunlu izinler tamam — hatırlatıcılar çalışabilir."
        } else {
            "⚠ Eksik izin var: aşağıdaki satırlardan tamamla."
        }

        addRow(
            "1) Bildirimler", nOk,
            "Bildirim gelmezse alarm sessiz kalır. Kanal: \"Ders / Sınav hatırlatıcıları\".",
            "İzin ver"
        ) { openNotificationSettings() }

        addRow(
            "2) Alarmlar ve hatırlatıcılar (tam alarm)", aOk,
            "Android 12+ bu izin olmadan alarmı dakikalarca geciktirebilir. " +
                "Ders alarmı tam saatinde çalsın istiyorsan şart.",
            "İzin ver"
        ) { openExactAlarmSettings() }

        addRow(
            "3) Pil kısıtlaması", bOk,
            "HyperOS arka plandaki uygulamayı uyutursa alarm hiç gelmez. " +
                "\"Kısıtlama yok\" seçilmeli.",
            "Ayarla"
        ) { openBatterySettings() }

        addRow(
            "4) Otomatik başlatma (Xiaomi/MIUI)", null,
            "Xiaomi'ye özel: kapalıysa uygulama yeniden başlatmada alarmları " +
                "kuramaz. Durumu okunamıyor — açman gerekiyor.",
            "Aç"
        ) { openAutostartSettings() }

        addRow(
            "5) Bildirim sesi (kısa bip/zil)", null,
            "Hatırlatıcı sesini seç. Kısa (2–3 sn) ücretsiz sistem sesleri kullanılır; " +
                "sessiz de seçilebilir.",
            "Ses seç"
        ) { pickNotificationSound() }

        status.post { }
    }

    private fun addRow(
        title: String,
        ok: Boolean?,
        desc: String,
        actionText: String,
        action: () -> Unit
    ) {
        val item = layoutInflater.inflate(R.layout.item_permission_row, list, false)
        val mark = when (ok) {
            true -> "✅"
            false -> "⚠"
            null -> "•"
        }
        item.findViewById<TextView>(R.id.permTitle).text = "$mark  $title"
        item.findViewById<TextView>(R.id.permDesc).text = desc
        val btn = item.findViewById<TextView>(R.id.permAction)
        btn.text = actionText
        val current = when {
            title.startsWith("5)") -> AppPrefs.getString(this, KEY_NOTIF_SOUND, "")
                ?.takeIf { it.isNotBlank() }?.let {
                    "  (seçili: " + uriLabel(it) + ")"
                } ?: "  (varsayılan)"
            else -> ""
        }
        if (current.isNotEmpty()) {
            item.findViewById<TextView>(R.id.permDesc).append(current)
        }
        btn.setOnClickListener { action() }
        list.addView(item)
    }

    private fun uriLabel(uri: String): String = try {
        RingtoneManager.getRingtone(this, Uri.parse(uri))?.getTitle(this) ?: "özel ses"
    } catch (_: Throwable) {
        "özel ses"
    }

    // ── Sistem ekranları (marka bazlı, güvenli düşüşlü) ─────

    private fun safeStart(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: Throwable) {
                // sıradaki adayı dene
            }
        }
        Toast.makeText(this, "Sistem ekranı açılamadı — Ayarlar'dan elle aç", Toast.LENGTH_LONG)
            .show()
    }

    private fun openNotificationSettings() {
        safeStart(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safeStart(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } else {
            Toast.makeText(this, "Bu Android sürümünde ayrı izin gerekmiyor", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun openBatterySettings() {
        // NOT (HyperOS doğrulaması): ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        // MIUI tarafından "otomatik başlatma" ekranına yönlendiriliyor — pil
        // ayarıyla karışmasın diye listeden ÇIKARILDI. Sıra: pil optimizasyon
        // listesi → MIUI pil tasarrufu → uygulama ayrıntısı.
        safeStart(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            ).putExtra("package_name", packageName)
                .putExtra("package_label", getString(R.string.app_name)),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    private fun openAutostartSettings() {
        safeStart(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    /**
     * Kısa (2–3 sn) ücretsiz bildirim sesi seçimi.
     * Sistem zil listesi kullanılır — ücretli servis yok. "Sessiz" seçeneği
     * ayrıca sunulur (mobil sırasında rahatsız etmesin).
     */
    private fun pickNotificationSound() {
        val current = AppPrefs.getString(this, KEY_NOTIF_SOUND, "")
        val labels = arrayOf("🔕 Sessiz (yalnız titreşim)", "🔔 Sistem sesleri (kısa bip/zil)")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Bildirim sesi")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    AppPrefs.putString(this, KEY_NOTIF_SOUND, SILENT)
                    applySoundToChannel(SILENT)
                    Toast.makeText(this, "Sessiz seçildi", Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Bildirim sesi seç")
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        putExtra(
                            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            current?.takeIf { it.startsWith("content:") }?.let { Uri.parse(it) }
                                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        )
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_SOUND)
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    @Deprecated("Eski API — sistem zil seçici hâlâ startActivityForResult ile çalışıyor")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SOUND && resultCode == RESULT_OK) {
            val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val value = uri?.toString() ?: SILENT
            AppPrefs.putString(this, KEY_NOTIF_SOUND, value)
            applySoundToChannel(value)
            Toast.makeText(this, "Bildirim sesi kaydedildi", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    /** Seçilen sesi bildirim kanalına uygular (kanal ayarı kullanıcıya aittir). */
    private fun applySoundToChannel(value: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = nm.getNotificationChannel(Reminder.CHANNEL_ID) ?: return
            ch.setSound(
                if (value == SILENT) null else Uri.parse(value),
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE.let {
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
            )
            ch.enableVibration(value != SILENT)
            nm.createNotificationChannel(ch)
        } catch (_: Throwable) {
        }
    }

    // ── Test ─────────────────────────────────────────────────

    private fun runTest() {
        Reminder.ensureChannel(this)
        Reminder.notify(
            this, 90_001,
            "🔔 UMAY test bildirimi",
            "Bu bildirimi görüyorsan bildirimler çalışıyor. 10 saniye sonra " +
                "tam alarm testi gelecek."
        )
        Reminder.testExactAlarm(this, 10_000L)
        Toast.makeText(
            this,
            "Test gönderildi — 10 sn sonra alarm bildirimi gelecek",
            Toast.LENGTH_LONG
        ).show()
        status.postDelayed({ refresh() }, 500)
    }

    companion object {
        private const val REQ_SOUND = 4021
        const val KEY_NOTIF_SOUND = "notif_sound_uri"
        const val SILENT = "silent"
    }
}
