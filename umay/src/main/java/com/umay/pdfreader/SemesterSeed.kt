package com.umay.pdfreader

import android.content.ContentValues
import android.content.Context

/**
 * v5.4 — 2026-2027 GÜZ DÖNEMİ GERÇEK VERİ TOHUMU.
 *
 * Kullanıcının bölüm belgelerinden (ders programı, vize programı, proje sunum
 * programı) birebir çıkarılmıştır:
 *
 *  • 6 ders (kod + ad + öğretim üyesi + AKTS)
 *  • 8 haftalık ders bloğu (Çarşamba / Perşembe / Cuma)
 *  • 5 vize + 2 proje sunumu
 *
 * Tohumlama YALNIZ BİR KEZ çalışır (SharedPreferences bayrağı). Kullanıcı
 * sonradan ders/sınav ekleyip silerse uygulama bunları geri getirmez.
 */
object SemesterSeed {

    private const val FLAG = "seed_2026_guz_v1"

    /** Ders tanımı: kod, ad, öğretim üyesi, AKTS. */
    private data class SeedCourse(
        val code: String,
        val name: String,
        val instructor: String,
        val ects: Int
    )

    private val COURSES = listOf(
        SeedCourse("BLG203", "Veri Yapıları", "Doç. Dr. Aykut DİKER", 6),
        SeedCourse("BLG205", "Nesne Tabanlı Programlamaya Giriş", "Doç. Dr. Aykut DİKER", 6),
        SeedCourse("BLG207", "Ayrık Matematik", "Dr. Öğr. Üyesi Yasin UCAKAN", 5),
        SeedCourse("MAT203", "Olasılık ve İstatistik", "Dr. Öğr. Üyesi Yasin UCAKAN", 5),
        SeedCourse("MAT205", "Lineer Cebir", "Dr. Öğr. Üyesi Yasin UCAKAN", 5),
        SeedCourse("YPZ201", "Makine Öğrenmesi", "Dr. Öğr. Üyesi Yosef Masoudi SOBHANZADEH", 6)
    )

    /** Haftalık blok başlangıçları (gün: 0=Pazartesi … 6=Pazar). */
    private data class SeedSlot(
        val code: String,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val room: String
    )

    private val SLOTS = listOf(
        // Çarşamba
        SeedSlot("BLG205", 2, 9, 0, "B101"),
        SeedSlot("BLG205", 2, 12, 0, "B101 (T&U)"),
        SeedSlot("BLG203", 2, 14, 0, "B101"),
        SeedSlot("BLG203", 2, 16, 0, "B101 (T&U)"),
        // Perşembe
        SeedSlot("BLG207", 3, 9, 0, "305"),
        SeedSlot("MAT203", 3, 13, 0, "305"),
        // Cuma
        SeedSlot("YPZ201", 4, 9, 0, "205"),
        SeedSlot("MAT205", 4, 14, 0, "305")
    )

    /** Sınav / sunum: kod, başlık, tür, yıl-ay-gün-saat, derslik. */
    private data class SeedExam(
        val code: String,
        val title: String,
        val type: String,
        val year: Int,
        val month: Int,   // 1-12
        val day: Int,
        val hour: Int,
        val minute: Int,
        val room: String
    )

    private val EXAMS = listOf(
        // ── VİZE HAFTASI (9-13 Kasım 2026) ──
        SeedExam("BLG205", "Vize", "Vize", 2026, 11, 11, 12, 0, "305-304"),
        SeedExam("BLG207", "Vize", "Vize", 2026, 11, 12, 10, 0, "305-304"),
        SeedExam("MAT203", "Vize", "Vize", 2026, 11, 12, 14, 0, "305-304"),
        SeedExam("YPZ201", "Vize", "Vize", 2026, 11, 13, 10, 0, "405"),
        SeedExam("MAT205", "Vize", "Vize", 2026, 11, 13, 14, 0, "305-304"),
        // ── PROJE SUNUMLARI (21-30 Aralık 2026) ──
        SeedExam("BLG203", "Proje Sunumu", "Proje", 2026, 12, 22, 9, 0, "Lab B101"),
        SeedExam("BLG205", "Proje Sunumu", "Proje", 2026, 12, 22, 14, 0, "Lab B101")
    )

    /**
     * Tohumlama gerekliyse yapar. Ağır değildir (birkaç DB satırı) ama yine de
     * ana ekran açılışında çağrıldığı için kendi try-catch'i içinde korunur.
     */
    fun seedIfNeeded(ctx: Context) {
        if (AppPrefs.getBool(ctx, FLAG, false)) return
        try {
            seed(ctx)
            AppPrefs.putBool(ctx, FLAG, true)
            Reminder.scheduleAllClasses(ctx)
            Reminder.scheduleDailyCheck(ctx)
            CrashLog.log(
                ctx,
                "seed tamam: ${COURSES.size} ders, ${SLOTS.size} blok, ${EXAMS.size} sınav"
            )
        } catch (t: Throwable) {
            BookStore.logError(ctx, "SemesterSeed.seedIfNeeded", t)
        }
    }

    private fun seed(ctx: Context) {
        val idByCode = HashMap<String, String>()
        COURSES.forEach { c ->
            val fullName = "${c.code} ${c.name}"
            val course = CourseStore.addCourse(ctx, fullName) ?: return@forEach
            idByCode[c.code] = course.id
            CourseStore.setInstructor(ctx, course.id, c.instructor)
            try {
                val cv = ContentValues().apply { put("ects", c.ects) }
                LibraryDb.get(ctx).writableDatabase
                    .update("courses", cv, "id = ?", arrayOf(course.id))
            } catch (_: Throwable) {
            }
            // Ders adına raf: kullanıcı kitabı sürükleyip bırakabilsin
            try { ShelfManager.ensureCourseShelf(ctx, c.code, fullName) } catch (_: Throwable) {}
        }

        // ÖNEMLİ: Ders programı saatleri TEK KAYNAKTAN gelir (ScheduleSeed).
        // Burada slot üretilirse aynı ders için ikinci bir alarm saati oluşur.
        // Kural: dersin HİÇ slotu yoksa bu dosyadaki blok eklenir (yedek yol);
        // varsa ScheduleSeed'in yazdığı saat korunur.
        SLOTS.groupBy { it.code }.forEach { (code, blocks) ->
            val cid = idByCode[code] ?: return@forEach
            val existing = CourseStore.slotsOf(ctx).filter { it.courseId == cid }
            if (existing.isNotEmpty()) return@forEach
            blocks.forEach { s ->
                val minuteOfDay = s.hour * 60 + s.minute
                if (!existing.any { it.day == s.day && it.minuteOfDay == minuteOfDay }) {
                    CourseStore.addSlot(ctx, cid, s.day, minuteOfDay, s.room, 20)
                }
            }
        }

        EXAMS.forEach { e ->
            val cid = idByCode[e.code] ?: return@forEach
            val cal = java.util.Calendar.getInstance().apply {
                clear()
                set(e.year, e.month - 1, e.day, e.hour, e.minute, 0)
            }
            val at = cal.timeInMillis
            val already = CourseStore.examsOf(ctx, cid).any {
                it.type.equals(e.type, true) && kotlin.math.abs(it.atMillis - at) < 60_000L
            }
            if (!already) {
                CourseStore.addExam(
                    ctx, cid,
                    title = "${e.title} • ${e.room}",
                    atMillis = at,
                    note = e.room,
                    type = e.type,
                    weight = if (e.type == "Proje") 30 else 40,
                    alarm = true
                )
            }
        }
    }
}
