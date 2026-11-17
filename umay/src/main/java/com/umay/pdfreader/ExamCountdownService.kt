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

/**
 * v13 (T7) — CANLI SINAV GERİ SAYIM SERVİSİ.
 *
 * En yakın tarihli sınavı bulur ve durum çubuğunda/kilit ekranında
 * canlı geri sayım bildirimi gösterir:
 *   Başlık: "⏳ Sınava Kalan Süre: [Ders]"
 *   Metin : "2 Gün 4 Saat 30 Dakika Kaldı"
 *
 * v5.14 — İKİ ÖNEMLİ DÜZELTME (kullanıcı şikâyeti: "sürekli ekrana geliyor,
 * kapatma yok"):
 *  1) KAPATMA ANAHTARI: Ayarlar › Bildirim › "Sınav geri sayımı" satırı.
 *     Kapalıyken [refresh], [hasUpcomingExam] ve servis başlangıcı hiçbir şey
 *     yapmaz; var olan bildirim de anında silinir ([stop]).
 *  2) SESSİZ KANAL: Eski kanal IMPORTANCE_HIGH idi → her metin güncellemesinde
 *     (dakikada bir) sesli/titreşimli "heads-up" balonu ekrana düşüyordu.
 *     Artık IMPORTANCE_LOW + sessiz kanal kullanılıyor; bildirim yalnızca
 *     durum çubuğunda sessizce durur, ekrana fırlamaz.
 *     Android'de bir kanalın önemi sonradan değiştirilemediği için kanal
 *     kimliği `umay_exam_countdown_v2` oldu; eski `umay_exam_countdown`
 *     kanalı siliniyor.
 *
 * MİMARİ:
 *  - ForegroundService (setOngoing(true) — kaydırılamaz).
 *  - VISIBILITY_PUBLIC → kilit ekranında görünür (ama sessiz).
 *  - Güncelleme: 60 SANİYEDE BİR (Handler.postDelayed) — pil dostu.
 *  - Bildirime dokununca ilgili dersin detay sayfası açılır.
 *  - Sınav bittiğinde/geçtiğinde servis kendini durdurur.
 *  - BootReceiver + CourseStore.addExam/deleteExam + açılışta yeniden başlar.
 *
 * GÜVENLİK: tüm DB erişimi try/catch; servis asla çökmez.
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
        // 0) Kullanıcı kapattıysa hiç bildirim gösterme (yeniden doğrulama).
        if (!enabled(this)) {
            stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
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
        if (!enabled(this)) return false

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
            .setSilent(true)                       // v5.14 — hiç ses çıkarma
            .setPriority(NotificationCompat.PRIORITY_LOW)   // v5.14 — heads-up yok
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // kilit ekranı açık
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        /*
         * v41 — API 34+ hatası: uygulama arka plandayken başlatılan serviste
         * `startForeground()` → ForegroundServiceStartNotAllowedException.
         * Bu beklenen bir kısıt: hata olarak günlüğe yazılmaz.
         */
        try {
            startForeground(NOTIF_ID, notification)
        } catch (t: Throwable) {
            val expected = android.os.Build.VERSION.SDK_INT >= 31 &&
                t is android.app.ForegroundServiceStartNotAllowedException
            if (expected) {
                CrashLog.log(this, "ExamCountdown: arka plan FGS kısıtı — bildirim olarak gösterildi")
            } else {
                BookStore.logError(this, "ExamCountdown.foreground", t)
            }
            try { nm.notify(NOTIF_ID, notification) } catch (_: Throwable) { }
            // Foreground'a geçemediysek canlı döngüyü sürdürmenin anlamı yok.
            if (expected) {
                stopSelf()
                return true
            }
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
        /** v5.14 — sessiz kanal (eski yüksek öncelikli kanal silinir). */
        private const val CHANNEL_ID_COUNTDOWN = "umay_exam_countdown_v2"
        private const val OLD_CHANNEL_ID_COUNTDOWN = "umay_exam_countdown"
        private const val NOTIF_ID = 7001
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val HOUR_MS = 60L * 60 * 1000
        private const val MIN_MS = 60L * 1000

        /** Kullanıcı sınav geri sayımını açık bıraktı mı? (varsayılan: açık) */
        fun enabled(ctx: Context): Boolean =
            try {
                AppPrefs.getBool(ctx, AppPrefs.KEY_EXAM_COUNTDOWN, true)
            } catch (_: Throwable) {
                true
            }

        /**
         * v5.14 — Sessiz kanal. LOW önemi: durum çubuğunda durur, ekrana
         * fırlamaz, ses/titreşim yok. Eski HIGH kanal silinir.
         */
        fun ensureChannel(ctx: Context) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                // Eski gürültülü kanalı kaldır (varsa)
                try { nm.deleteNotificationChannel(OLD_CHANNEL_ID_COUNTDOWN) } catch (_: Throwable) { }
                if (nm.getNotificationChannel(CHANNEL_ID_COUNTDOWN) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID_COUNTDOWN,
                        "Sınav geri sayım",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Yaklaşan sınav için sessiz geri sayım"
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                    nm.createNotificationChannel(ch)
                }
            } catch (t: Throwable) {
                BookStore.logError(ctx, "ExamCountdown.ensureChannel", t)
            }
        }

        /**
         * Servisi başlat (yeni sınav eklenince, silinince, açılışta).
         * Kullanıcı kapattıysa servis durdurulur — çağrı güvenlidir.
         */
        fun refresh(ctx: Context) {
            if (!enabled(ctx)) {
                stop(ctx)
                return
            }
            try {
                ensureChannel(ctx)
                val intent = Intent(ctx, ExamCountdownService::class.java)
                ctx.startForegroundService(intent)
            } catch (t: Throwable) {
                // Android 12+ arka plan başlangıcı reddedilirse sessizce geç.
                if (android.os.Build.VERSION.SDK_INT >= 31 &&
                    t is android.app.ForegroundServiceStartNotAllowedException
                ) {
                    CrashLog.log(ctx, "ExamCountdown.refresh: arka planda başlatma kısıtlı")
                } else {
                    BookStore.logError(ctx, "ExamCountdown.refresh", t)
                }
            }
        }

        /**
         * v5.14 — Servisi durdur + bildirimi kaldır.
         * Kullanıcı ayardan kapattığında ve sınav kalmadığında çağrılır.
         */
        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, ExamCountdownService::class.java))
            } catch (_: Throwable) {
            }
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_ID)
            } catch (_: Throwable) {
            }
        }

        /** Yaklaşan sınav var mı (hızlı kontrol — servis israfı önler). */
        fun hasUpcomingExam(ctx: Context): Boolean {
            if (!enabled(ctx)) return false
            val now = System.currentTimeMillis()
            return try {
                CourseStore.allExams(ctx).any { it.exam.atMillis > now }
            } catch (_: Throwable) {
                false
            }
        }
    }
}
