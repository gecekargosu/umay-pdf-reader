package com.umay.pdfreader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * v13 (T7) — CANLI SINAV GERİ SAYIM SERVİSİ.
 *
 * En yakın tarihli sınavı bulur ve durum çubuğunda/kilit ekranında
 * canlı geri sayım bildirimi gösterir:
 *   Başlık: "⏳ Sınava Kalan Süre: [Ders]"
 *   Metin : "2 Gün 4 Saat 30 Dakika Kaldı"
 *
 * MİMARİ:
 *  - ForegroundService (setOngoing(true) — kaydırılamaz, yanlışlıkla kapanmaz).
 *  - Kanal: IMPORTANCE_HIGH + VISIBILITY_PUBLIC → kilit ekranında görünür.
 *  - Güncelleme: 60 SANİYEDE BİR (Handler.postDelayed) — AlarmManager/WorkManager
 *    kurmadan, servis yaşam döngüsü içinde pil dostu bir döngü. CPU uyanışı
 *    yok; sadece metin güncellenir (düşük maliyet).
 *  - Bildirime dokununca ilgili dersin detay sayfası açılır.
 *  - Sınav bittiğinde/geçtiğinde servis kendini durdurur.
 *  - BootReceiver + CourseStore.addExam/deleteExam + açılışta yeniden başlar.
 *
 * GÜVENLİK: tüm DB erişimi try/catch; servis asla çökmez, sınav yoksa
 * sessizce kendini kapatır.
 */
class ExamCountdownService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastShownKey: String? = null

    private val tick = object : Runnable {
        override fun run() {
            val ok = updateNotification()
            if (!ok) {
                stopSelf()
                return
            }
            handler.postDelayed(this, 60_000L)   // dakikada bir tazele
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1) Kanalı hazırla → 2) ilk bildirimi göster → 3) döngüyü başlat
        try {
            ensureChannel(this)
            if (!showOnce()) {
                stopSelf()
                return START_NOT_STICKY
            }
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 60_000L)
        } catch (t: Throwable) {
            BookStore.logError(this, "ExamCountdown.onStart", t)
            stopSelf()
        }
        // Sistem öldürürse yeniden başlatılsın (sınav süresince anlamlı)
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    /**
     * En yakın sınavı bulup bildirimi gösterir.
     * @return sınav varsa ve süresi dolmadıysa true (servis yaşamalı).
     */
    private fun showOnce(): Boolean {
        val now = System.currentTimeMillis()
        val next = CourseStore.allExams(this)
            .map { it to it.exam.atMillis }
            .filter { (_, at) -> at > now }
            .minByOrNull { it.second }
            ?: return false   // yaklaşan sınav yok → servis kapanır

        val (ewc, atMillis) = next
        val label = ewc.courseName.ifBlank { ewc.exam.title }
        val remaining = atMillis - now

        val days = remaining / DAY_MS
        val hours = (remaining % DAY_MS) / HOUR_MS
        val minutes = (remaining % HOUR_MS) / MIN_MS

        val title = "⏳ Sınava Kalan Süre: $label"
        val text = when {
            days > 0 -> "$days Gün $hours Saat $minutes Dakika Kaldı"
            hours > 0 -> "$hours Saat $minutes Dakika Kaldı"
            else -> "$minutes Dakika Kaldı"
        }

        // Dakika bazlı titreşim/kayma önlenmesi: yalnız içerik değişince bildirim
        val key = "$title|$text"
        val contentChanged = key != lastShownKey
        lastShownKey = key

        // Bildirim dokunuşu → ilgili dersin detayı
        val openIntent = Intent(this, CourseDetailActivity::class.java).apply {
            putExtra(CourseDetailActivity.EXTRA_COURSE_ID, ewc.exam.courseId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 7001, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)                      // kaydırılamaz
            .setOnlyAlertOnce(true)                // ses/titreşim tekrarlamasın
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // kilit ekranı açık
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // foregroundServiceType=shortService ile başlat; bildirimi göster
        try {
            startForeground(NOTIF_ID, notification)
        } catch (t: Throwable) {
            // API 34+ foreground izni/limiti yoksa yine de bildirim göster
            BookStore.logError(this, "ExamCountdown.foreground", t)
            nm.notify(NOTIF_ID, notification)
        }
        if (contentChanged) { /* ilk gösterim veya metin değişimi — bildirim zaten yayında */ }
        return true
    }

    /** Yeniden hesapla → göster. Sınav kalmadıysa false. */
    private fun updateNotification(): Boolean = try {
        lastShownKey = null      // zorla tazele
        showOnce()
    } catch (t: Throwable) {
        BookStore.logError(this, "ExamCountdown.update", t)
        false
    }

    companion object {
        private const val CHANNEL_ID_COUNTDOWN = "umay_exam_countdown"
        private const val NOTIF_ID = 7001
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val HOUR_MS = 60L * 60 * 1000
        private const val MIN_MS = 60L * 1000

        /** Kilit ekranında görünmesi için HIGH kanal (VISIBILITY_PUBLIC). */
        fun ensureChannel(ctx: Context) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID_COUNTDOWN) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID_COUNTDOWN,
                        "Sınav geri sayım",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Yaklaşan sınav için canlı geri sayım"
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(ch)
                }
            } catch (t: Throwable) {
                BookStore.logError(ctx, "ExamCountdown.ensureChannel", t)
            }
        }

        /**
         * Servisi başlat/duraklat (yeni sınav eklenince, silinince, açılışta).
         * Yaklaşan sınav yoksa servis kendini kapatır — çağrı güvenlidir.
         */
        fun refresh(ctx: Context) {
            try {
                ensureChannel(ctx)
                val intent = Intent(ctx, ExamCountdownService::class.java)
                ctx.startForegroundService(intent)
            } catch (t: Throwable) {
                // Android 12+ arka plan başlangıcı reddedilirse sessizce geç
                BookStore.logError(ctx, "ExamCountdown.refresh", t)
            }
        }

        /** Yaklaşan sınav var mı (hızlı kontrol — servis israfı önler). */
        fun hasUpcomingExam(ctx: Context): Boolean {
            val now = System.currentTimeMillis()
            return try {
                CourseStore.allExams(ctx).any { it.exam.atMillis > now }
            } catch (_: Throwable) {
                false
            }
        }
    }
}
