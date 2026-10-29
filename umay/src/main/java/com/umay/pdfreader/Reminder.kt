package com.umay.pdfreader

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Ders öncesi alarm / sınav / ödev hatırlatıcıları (ADIM 3 + ADIM 4).
 *
 * Mekanizma: `AlarmManager` (WorkManager KULLANILMADI — yeni bağımlılık yok).
 *  - Ders: her slot için tek seferlik alarm; tetiklenince bildirim atılır ve
 *    bir sonraki hafta için yeniden planlanır.
 *  - Sınav/ödev: günde bir kez `setRepeating` ile kontrol; gün sayısına göre
 *    bildirim atılır (aynı gün tekrar atmamak için prefs'ta iz bırakılır).
 *  - Yeniden planlama: uygulama açılışında, `Boot_COMPLETED`'da ve
 *    program değiştiğinde.
 *
 * Tam alarm izni (`SCHEDULE_EXACT_ALARM`) yoksa `SecurityException` yakalanıp
 * bilinçsiz (inexact) alarma düşülür — hatırlatıcı asla sessizce kaybolmaz.
 */
object Reminder {

    const val CHANNEL_ID = "umay_reminders"
    const val EXTRA_TYPE = "type"
    const val TYPE_CLASS = "class"
    const val TYPE_DAILY = "daily"
    const val TYPE_TEST = "test"
    const val EXTRA_SLOT_ID = "slot_id"

    private const val DAILY_REQUEST = 900_001
    private const val PREFS = "umay_prefs"

    // v5 — 20 dk önceden melodili ders alarmı (MediaPlayer)
    private const val ALARM_MAX_MS = 15_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var alarmPlayer: MediaPlayer? = null

    /** Günlük kontrol saati (varsayılan 09:00). */
    const val KEY_DAILY_HOUR = "daily_reminder_hour"
    const val KEY_DAILY_MIN = "daily_reminder_min"

    // ── Bildirimler ─────────────────────────────────────────

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Ders / Sınav hatırlatıcıları",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "Ders öncesi alarm, sınav ve ödev hatırlatmaları" }
                )
            }
        }
    }

    fun notify(ctx: Context, id: Int, title: String, text: String, silent: Boolean = false) {
        ensureChannel(ctx)
        val contentIntent = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            // v5: ders alarmı melodisi MediaPlayer ile ayrıca çalınır → çifte ses olmasın
            .setSilent(silent)
            // v5: ana vurgu rengi = sıcak turuncu (spec: alarmlar ANA renkte)
            .setColor(ctx.getColor(R.color.umay_orange))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    // ── Ders alarmları ──────────────────────────────────────

    private fun classPendingIntent(ctx: Context, slotId: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            slotId.hashCode(),
            Intent(ctx, ReminderReceiver::class.java).apply {
                putExtra(EXTRA_TYPE, TYPE_CLASS)
                putExtra(EXTRA_SLOT_ID, slotId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Tek bir ders slotu için bir sonraki haftayı planlar. */
    fun scheduleClassSlot(ctx: Context, slot: ScheduleSlot) {
        val now = System.currentTimeMillis()
        val occurrence = Academy.nextOccurrence(slot.day, slot.minuteOfDay, now)
        val trigger = Academy.triggerAt(occurrence, slot.remindMin, now)
        setAlarm(ctx, trigger, classPendingIntent(ctx, slot.id))
    }

    /** Program değiştirildiğinde/uygulama açıldığında tüm dersleri yeniden planlar. */
    fun scheduleAllClasses(ctx: Context) {
        CourseStore.slotsOf(ctx).forEach { scheduleClassSlot(ctx, it) }
    }

    fun cancelSlot(ctx: Context, slotId: String) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(classPendingIntent(ctx, slotId))
    }

    /**
     * v5.5 (AŞAMA 1) — İZİN TESTİ.
     * [delayMs] sonra bildirim üreten bir alarm kurar. Tam alarm izni yoksa
     * sessizce inexact'a düşer (kullanıcı izin merkezinde bunu görür).
     */
    fun testExactAlarm(ctx: Context, delayMs: Long) {
        val pi = PendingIntent.getBroadcast(
            ctx,
            99_001,
            Intent(ctx, ReminderReceiver::class.java).putExtra(EXTRA_TYPE, TYPE_TEST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + delayMs
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "testExactAlarm", t)
            try {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Throwable) {
            }
        }
    }

    private fun setAlarm(ctx: Context, triggerAt: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (_: SecurityException) {
            // SCHEDULE_EXACT_ALARM yok/iptal edilmiş → bilinçsiz alarm
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * v5 — Ders alarmı melodisi: sistem alarm zil sesi `MediaPlayer` ile
     * `USAGE_ALARM` üstünden ~15 saniye çalınır (uygulama arka planda olsa
     * bile hoparör hoparlörden duyulur). Uri yoksa/ hata olursa `false`
     * döner → bildirim kendi sesiyle uyarır (çifte ses olmaz).
     */
    fun playAlarmMelody(ctx: Context): Boolean {
        stopAlarmMelody()
        val customUriStr = AppPrefs.getString(ctx, AppPrefs.KEY_ALARM_URI)
        val customUri = if (customUriStr.isNotBlank()) try { Uri.parse(customUriStr) } catch (_: Throwable) { null } else null
        val uri: Uri = customUri
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            ?: return false
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(ctx, uri)
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.isLooping = true
            mp.setOnCompletionListener { stopAlarmMelody() }
            mp.setOnErrorListener { _, _, _ ->
                stopAlarmMelody()
                true
            }
            // v5.4 (ANR ÖNLEMİ): Alarm bir BroadcastReceiver içinde (ANA
            // THREAD) çalışır. Senkron `prepare()` büyük/uzak ses dosyasında
            // bloke edip ANR üretiyordu → artık `prepareAsync()`.
            mp.setOnPreparedListener { player ->
                try {
                    player.start()
                    mainHandler.postDelayed({ stopAlarmMelody() }, ALARM_MAX_MS)
                } catch (_: Throwable) {
                    stopAlarmMelody()
                }
            }
            alarmPlayer = mp
            mp.prepareAsync()
            true
        } catch (t: Throwable) {
            BookStore.logError(ctx, "playAlarmMelody", t)
            false
        }
    }

    fun stopAlarmMelody() {
        mainHandler.removeCallbacksAndMessages(null)
        alarmPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
        alarmPlayer = null
    }

    /** Ders alarmı ateşlendi: bildir + bir sonraki hafta için yeniden planla. */
    fun onClassFired(ctx: Context, slotId: String) {
        val slot = CourseStore.slot(ctx, slotId) ?: return
        val course = slot.courseName.ifBlank { "Ders" }
        val whenText = if (slot.remindMin > 0) "${slot.remindMin} dakika sonra" else "Şimdi"
        val room = slot.room.ifBlank { "derslik" }
        // v5: 20 dk önce melodili alarm — ses MediaPlayer'da, bildirim sessiz
        val melodyPlaying = slot.remindMin > 0 && playAlarmMelody(ctx)
        notify(
            ctx,
            2000 + (slotId.hashCode() and 0x0FFF),
            "Ders hatırlatıcısı",
            "$whenText [$room] $course dersiniz var!",
            silent = melodyPlaying
        )
        // Bir sonraki hafta için yeniden planla
        scheduleClassSlot(ctx, slot)
    }

    // ── Günlük kontrol (sınav + ödev) ───────────────────────

    private fun dailyPendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            DAILY_REQUEST,
            Intent(ctx, ReminderReceiver::class.java).apply { putExtra(EXTRA_TYPE, TYPE_DAILY) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Günlük kontrolü (varsa) bir sonraki güne yeniden kurar. */
    fun scheduleDailyCheck(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hour = prefs.getInt(KEY_DAILY_HOUR, 9).coerceIn(0, 23)
        val minute = prefs.getInt(KEY_DAILY_MIN, 0).coerceIn(0, 59)

        val first = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis

        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setRepeating(
                AlarmManager.RTC_WAKEUP, first, AlarmManager.INTERVAL_DAY,
                dailyPendingIntent(ctx)
            )
        } catch (_: Exception) {
        }
    }

    /** Günün kontrolü: sınavlara/ödevlere kalan güne göre bildirim. */
    fun runDailyChecks(ctx: Context) {
        val now = System.currentTimeMillis()
        val today = Academy.daysLeft(now, now)   // 0 — bugünün izi için
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun once(key: String): Boolean {
            val k = "notif_$key"
            if (prefs.getInt(k, -1) == today) return false
            prefs.edit().putInt(k, today).apply()
            return true
        }

        // Sınavlar: 0..7 gün kaldı — v16 (YENİ GÖREV 2): yalnız alarmı AÇIK olanlar
        CourseStore.allExams(ctx).forEach { (exam, courseName) ->
            if (!exam.alarm) return@forEach
            val days = Academy.daysLeft(exam.atMillis, now)
            if (days in 0..7) {
                val label = courseName.ifBlank { exam.title }
                val subject = if (courseName.isBlank()) exam.title
                else "$courseName – ${exam.title}"
                // v16 (GÖREV 3) — bildirim metni aktif dile göre (TR/EN)
                val en = LangHelper.isEnglish(ctx)
                if (once("exam_${exam.id}_$days")) {
                    // v5.4 — SON 2 GÜN: seçilen alarm melodisi de çalsın
                    // (Ayarlar → Alarm sesi). Böylece "sınav yaklaşıyor"
                    // uyarısı sessiz bildirim olarak kaybolmuyor.
                    val melody = days <= 1 && playAlarmMelody(ctx)
                    notify(
                        ctx, 3000 + (exam.id.hashCode() and 0x0FFF),
                        if (en) "Exam countdown ($label)" else "Sınav geri sayım ($label)",
                        Academy.examPhrase(subject, days, en),
                        silent = melody
                    )
                }
            }
        }

        // Ödevler: teslimine 0..3 gün kaldı ve henüz teslim edilmedi
        // v16 (YENİ GÖREV 2): yalnız alarmı AÇIK olan ödev/proje hatırlatılır
        CourseStore.allHomework(ctx).forEach { (hw, courseName) ->
            if (hw.done) return@forEach
            if (!hw.alarm) return@forEach
            val days = Academy.daysLeft(hw.dueMillis, now)
            if (days in 0..3) {
                val label = courseName.ifBlank { hw.title }
                val subject = if (courseName.isBlank()) hw.title
                else "$courseName – ${hw.title}"
                if (once("hw_${hw.id}_$days")) {
                    notify(
                        ctx, 4000 + (hw.id.hashCode() and 0x0FFF),
                        if (LangHelper.isEnglish(ctx)) "Homework/Project reminder ($label)"
                        else "Ödev/Proje hatırlatıcısı ($label)",
                        Academy.homeworkPhrase(subject, days, LangHelper.isEnglish(ctx))
                    )
                }
            }
        }
    }

    /** Her şeyi tazele (açılış / boot / program değişikliği). */
    fun rescheduleAll(ctx: Context) {
        ensureChannel(ctx)
        scheduleAllClasses(ctx)
        scheduleDailyCheck(ctx)
    }

    /** Günlük hatırlatma saatini kaydeder ve kontrolü yeniden kurar. */
    fun setDailyTime(ctx: Context, hour: Int, minute: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DAILY_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_DAILY_MIN, minute.coerceIn(0, 59))
            .apply()
        scheduleDailyCheck(ctx)
    }

    fun dailyTime(ctx: Context): Pair<Int, Int> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Pair(p.getInt(KEY_DAILY_HOUR, 9), p.getInt(KEY_DAILY_MIN, 0))
    }
}

/** Ders alarmı ve günlük kontrol yayınları. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            when (intent.getStringExtra(Reminder.EXTRA_TYPE)) {
                Reminder.TYPE_CLASS ->
                    intent.getStringExtra(Reminder.EXTRA_SLOT_ID)?.let {
                        Reminder.onClassFired(ctx, it)
                    }
                Reminder.TYPE_DAILY -> Reminder.runDailyChecks(ctx)
                Reminder.TYPE_TEST -> Reminder.notify(
                    ctx, 99_002,
                    "⏰ Test alarmı çalıştı",
                    "Alarm 10 saniye sonra sorunsuz tetiklendi. " +
                        "Hatırlatıcıların çalışıyor ✅"
                )
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ReminderReceiver", t)
        }
    }
}

/** Cihaz yeniden başladığında hatırlatıcıları geri kurdurur. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            Reminder.rescheduleAll(ctx)
        } catch (t: Throwable) {
            BookStore.logError(ctx, "BootReceiver", t)
        }
        // v13 (T7): canlı sınav geri sayım bildirimini yeniden kur
        try {
            if (ExamCountdownService.hasUpcomingExam(ctx)) {
                ExamCountdownService.refresh(ctx)
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "BootReceiver.examCountdown", t)
        }
    }
}
