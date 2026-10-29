package com.umay.pdfreader

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Akademik asistan modülü — veri modelleri + SAF hesaplama yardımcıları.
 *
 * Buradaki fonksiyonlar Android'e bağımlı DEĞİLDİR (sadece java.util),
 * bu yüzden `AcademyTest` ile birim test edilirler:
 *  - gün farkı takvim günü olarak (DST güvenli, ms bölme ile değil)
 *  - haftalık ders programının bir sonraki gerçekleşmesi
 *  - geri sayım metinleri ("4 gün kaldı" / "bugün" / "yarın")
 *
 * Kalıcılık (SQLite tabloları) `CourseStore` içindedir; burada yalnız model.
 */

data class Course(
    val id: String,
    val name: String,
    val createdAt: Long,
    /** v16 (GÖREV 6.2) — AKTS kredisi (AGNO ağırlığı). 0 = girilmemiş. */
    val ects: Int = 0,
    /** v16 (GÖREV 6) — Öğretim üyesi adı (ör. "Doç. Dr. Aykut DİKER"). */
    val instructor: String = ""
)

/** Derse bağlı kitap kaynağı (documents tablosu → books.id). */
data class CourseDoc(
    val id: String,
    val courseId: String,
    val bookId: String,
    val addedAt: Long,
    // v14 (V5.0 §4): kaynak sınıflandırması —
    // book | slide | helper | doc (Ders Kitapları / Slide / Yardımcı / Belge)
    val category: String = "book"
)

data class Exam(
    val id: String,
    val courseId: String,
    val title: String,
    val atMillis: Long,
    // v14 (V5.0 §5): sınav hatırlatma notu (kullanıcı ekleyip silebilir)
    val note: String = "",
    // v16 (YENİ GÖREV 2): sınav türü (Vize/Quiz/Final/Bütünleme),
    // not etki oranı (%0-100) ve hatırlatma/alarm bayrağı
    val type: String = "Vize",
    val weight: Int = 0,
    val alarm: Boolean = true
)

data class Homework(
    val id: String,
    val courseId: String,
    val title: String,
    val description: String,
    val dueMillis: Long,
    val done: Boolean,
    // v16 (YENİ GÖREV 2): teslim tarihi hatırlatıcısı (ödev/proje alarmı)
    val alarm: Boolean = true
)

/**
 * Haftalık ders programı satırı.
 * @param day 0 = Pazartesi … 6 = Pazar
 * @param minuteOfDay gün içi dakika (9:30 → 570)
 * @param remindMin ders başlangıcından kaç dakika önce hatırlatılsın (0 = kapalı)
 */
data class ScheduleSlot(
    val id: String,
    val courseId: String,
    val courseName: String,
    val day: Int,
    val minuteOfDay: Int,
    val room: String,
    val remindMin: Int,
    /** v5.8 — ders süresi (dakika). Program bloğu bu aralık boyunca boyanır. */
    val durationMin: Int = 120
)

/** Saf hesaplama yardımcıları (test edilebilir). */
object Academy {

    val DAY_NAMES = listOf(
        "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar"
    )

    /** v16 (GÖREV 3 — LOKALİZASYON) — İngilizce gün adları. */
    val DAY_NAMES_EN = listOf(
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    )

    /** Aktif dile göre gün adları listesi (spinner/tablo başlıkları). */
    fun dayNames(english: Boolean): List<String> = if (english) DAY_NAMES_EN else DAY_NAMES

    /** Varsayılan hatırlatma: ders başlangıcından 20 dakika önce (v5 — melodili alarm). */
    const val REMIND_DEFAULT = 20

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /** day 0..6 → gün adı (varsayılan TR; `english=true` → İngilizce). */
    fun dayName(day: Int, english: Boolean = false): String =
        dayNames(english)[((day % 7) + 7) % 7]

    /** Aynı günün KISA adı — matris tablo başlıkları için (Pzt / Mon). */
    fun dayShort(day: Int, english: Boolean = false): String {
        val d = ((day % 7) + 7) % 7
        return if (english) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[d]
        } else {
            listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")[d]
        }
    }

    /** 540 → "09:30" biçiminde saat (24 saat, sıfır dolgulu). */
    fun formatClock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        return "%02d:%02d".format(Locale.US, m / 60, m % 60)
    }

    /** Takvim günü sırası (DST'ten etkilenmez): yıl*1000 + yılın günü. */
    private fun dayOrdinal(millis: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Takvim günü farkı: `at` gününe kaç gün kaldı?
     * Dün → -1, bugün → 0, yarın → 1.
     */
    fun daysLeft(atMillis: Long, nowMillis: Long): Int =
        (dayOrdinal(atMillis) - dayOrdinal(nowMillis)).toInt()

    /** "4 gün kaldı" / "yarın" / "bugün" / "süresi geçti" (TR) — v16: EN seçeneği. */
    fun countdownPhrase(days: Int, english: Boolean = false): String = if (english) {
        when {
            days < 0 -> "expired"
            days == 0 -> "today"
            days == 1 -> "tomorrow"
            else -> "$days days left"
        }
    } else {
        when {
            days < 0 -> "süresi geçti"
            days == 0 -> "bugün"
            days == 1 -> "yarın"
            else -> "$days gün kaldı"
        }
    }

    /** "Ayrık Matematik sınavına 4 gün kaldı!" gibi tam cümle (v16: EN). */
    fun examPhrase(subject: String, days: Int, english: Boolean = false): String = if (english) {
        when {
            days < 0 -> "$subject exam has passed"
            days == 0 -> "$subject exam is today!"
            days == 1 -> "$subject exam is tomorrow"
            else -> "$subject exam in $days days"
        }
    } else {
        when {
            days < 0 -> "$subject sınavının süresi geçti"
            days == 0 -> "$subject sınavı bugün!"
            days == 1 -> "$subject sınavına yarın kaldı"
            else -> "$subject sınavına $days gün kaldı"
        }
    }

    /** "Ödev teslimine 2 gün kaldı" / "teslimi bugün" … (v16: EN). */
    fun homeworkPhrase(subject: String, days: Int, english: Boolean = false): String = if (english) {
        when {
            days < 0 -> "$subject deadline has passed"
            days == 0 -> "$subject is due today!"
            days == 1 -> "$subject is due tomorrow"
            else -> "$subject due in $days days"
        }
    } else {
        when {
            days < 0 -> "$subject teslim tarihi geçti"
            days == 0 -> "$subject teslimi bugün!"
            days == 1 -> "$subject teslimine yarın kaldı"
            else -> "$subject teslimine $days gün kaldı"
        }
    }

    /** Aktif dile göre SimpleDateFormat yereli (v16 / GÖREV 3). */
    private fun locale(english: Boolean): Locale =
        if (english) Locale.ENGLISH else Locale("tr", "TR")

    /** "8 Ekim 2026 14:30" · İngilizce: "8 October 2026 14:30" */
    fun formatDateTime(millis: Long, english: Boolean = false): String =
        SimpleDateFormat("d MMM yyyy HH:mm", locale(english)).format(millis)

    /** "8 Ekim 2026" (saat olmadan) */
    fun formatDate(millis: Long, english: Boolean = false): String =
        SimpleDateFormat("d MMM yyyy", locale(english)).format(millis)

    /** "14:30" — saat + dakika (takvim hücresi/liste başlığı). */
    fun formatClockRange(startMinute: Int, durationMin: Int = 60): String =
        "${formatClock(startMinute)} - ${formatClock(startMinute + durationMin)}"

    /** Millis → haftanın günü (0 = Pazartesi … 6 = Pazar). */
    fun weekDayOf(millis: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6
        }
    }

    /**
     * Haftalık programda `day` gününün `minuteOfDay` saatinin bir sonraki
     * gerçekleşmesi (her zaman `now`'dan ileridedir; bugünün saati geçtiyse
     * gelecek hafta).
     */
    fun nextOccurrence(day: Int, minuteOfDay: Int, nowMillis: Long): Long {
        val target = ((day % 7) + 7) % 7
        val c = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val current = weekDayOf(nowMillis)
        c.set(Calendar.HOUR_OF_DAY, (((minuteOfDay / 60) % 24) + 24) % 24)
        c.set(Calendar.MINUTE, ((minuteOfDay % 60) + 60) % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)

        var delta = (target - current + 7) % 7
        if (delta == 0 && c.timeInMillis <= nowMillis) delta = 7
        if (delta != 0) c.add(Calendar.DAY_OF_MONTH, delta)
        return c.timeInMillis
    }

    /**
     * Alarm tetik anı: olaydan `remindMin` dakika önce.
     * Tetik `now`'ı geçtiyse bir sonraki haftaya kaydırılır (uygulama kapalıyken
     * eski bir alarma asla ateşlenmez).
     */
    fun triggerAt(occurrence: Long, remindMin: Int, nowMillis: Long): Long {
        var occ = occurrence
        var trigger = occ - remindMin * 60_000L
        if (trigger <= nowMillis) {
            // Hatırlatma anı çoktan geçti (ör. derse 5 dk var, 15 dk önce diye
            // ayarlanmış) → alarma bir sonraki hafta için ateşlensin.
            val c = Calendar.getInstance().apply {
                timeInMillis = occ
                add(Calendar.DAY_OF_MONTH, 7)
            }
            occ = c.timeInMillis
            trigger = occ - remindMin * 60_000L
        }
        return trigger
    }

    /** Millis → gün içi dakika (0..1439). */
    fun minutesOfDayOf(millis: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }
}
