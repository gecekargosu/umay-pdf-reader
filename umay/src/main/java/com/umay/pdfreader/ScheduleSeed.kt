package com.umay.pdfreader

import android.content.Context

/**
 * v16 (GÖREV 6) — DERS PROGRAMI TOHUM VERİSİ (SEED) ·
 * İSTANBUL RUMELİ ÜNİVERSİTESİ · YAPAY ZEKA VE MAKİNE ÖĞRENMESİ · 2. SINIF
 *
 * Kullanıcı onaylı kesin program (15 Ekim 2026 — v11 seed'indeki saatler
 * DÜZELTİLDİ, öğretim üyesi adları EKLENDİ):
 *
 *   Çarşamba 10:00–12:00  BLG205 Nesne Tabanlı Programlamaya Giriş (T&U)
 *                             Doç. Dr. Aykut DİKER · Lab B101
 *   Çarşamba 15:00–17:00  BLG203 Veri Yapıları (T&U)
 *                             Doç. Dr. Aykut DİKER · Lab B101
 *   Perşembe  10:00–12:00  BLG207 Ayrık Matematik
 *                             Dr. Öğr. Üyesi Yasin UÇAKAN · Derslik 305
 *   Perşembe  13:00–15:00  MAT203 Olasılık ve İstatistik
 *                             Dr. Öğr. Üyesi Yasin UÇAKAN · Derslik 305
 *   Cuma      09:00–11:00  YPZ201 Makine Öğrenmesi
 *                             Dr. Öğr. Üyesi Yosef MASOUDI-SOBHANZADEH · Lab 205
 *   Cuma      14:00–16:00  MAT205 Lineer Cebir
 *                             Dr. Öğr. Üyesi Yasin UÇAKAN · Derslik 305
 *
 * ÖZEL DAVRANIŞ (v16):
 *  • `KEY_SCHEDULE_SEED_V16` bayrağı bir kez çalışır; KULLANICIYA AİT olmayan,
 *    önceki seed'in yazdığı satırları **günceller** (eski yanlış saatler
 *    09:00/14:00 → doğru 10:00/15:00) — satır silinmez, ders kaybolmaz.
 *  • Aynı ders birden fazla saate giriyorsa SADECE ilgili gün/saatli satır
 *    hedeflenir; kullanıcının elle eklediği başka satırlara dokunulmaz.
 *  • Öğretim üyesi adı `courses.instructor` kolonuna yazılır.
 *  • Alarmlar `Academy.REMIND_DEFAULT` (20 dk) önceden kurulur.
 *  • Her adım `try/catch` içinde → seed başarısız olsa bile uygulama açılır.
 */
object ScheduleSeed {

    /** (daha önceki seed'in gün/saatine göre eşleşen kayıt) + yeni değerler */
    private data class Seed(
        val day: Int,
        val minute: Int,
        val name: String,
        val instructor: String,
        val room: String
    ) {
        /** Eski v11 seed'inde bu dersin saatleri (düzeltme eşleşmesi için). */
        val oldMinutes: IntArray
            get() = when (name.take(6)) {
                "BLG205" -> intArrayOf(9 * 60)
                "BLG203" -> intArrayOf(14 * 60)
                "BLG207" -> intArrayOf(9 * 60)
                "MAT203" -> intArrayOf(13 * 60)
                "YPZ201" -> intArrayOf(9 * 60)
                "MAT205" -> intArrayOf(14 * 60)
                else -> intArrayOf()
            }
    }

    /**
     * v5.4 — SAATLER KULLANICININ GÜNCEL PROGRAM GÖRSELİNE GÖRE DÜZELTİLDİ
     * (`3.DÖNEM/Ders Programı.jpeg`):
     *   Çarşamba 09:00 BLG205 · 14:00 BLG203
     *   Perşembe 09:00 BLG207 · 13:00 MAT203
     *   Cuma     09:00 YPZ201 · 14:00 MAT205
     * Böylece günde iki blok yerine tek alarm çalar (12:00/16:00 laboratuvar
     * blokları program ekranından elle eklenebilir).
     */
    private val SEEDS = listOf(
        Seed(2, 9 * 60, "BLG205 Nesne Tabanlı Programlamaya Giriş", "Doç. Dr. Aykut DİKER", "B101"),
        Seed(2, 14 * 60, "BLG203 Veri Yapıları", "Doç. Dr. Aykut DİKER", "B101"),
        Seed(3, 9 * 60, "BLG207 Ayrık Matematik", "Dr. Öğr. Üyesi Yasin UÇAKAN", "Derslik 305"),
        Seed(3, 13 * 60, "MAT203 Olasılık ve İstatistik", "Dr. Öğr. Üyesi Yasin UÇAKAN", "Derslik 305"),
        Seed(4, 9 * 60, "YPZ201 Makine Öğrenmesi", "Dr. Öğr. Üyesi Yosef MASOUDI-SOBHANZADEH", "Lab 205"),
        Seed(4, 14 * 60, "MAT205 Lineer Cebir", "Dr. Öğr. Üyesi Yasin UÇAKAN", "Derslik 305")
    )

    /** Uygulama açılışında çağrılır (UmayApp). Hata asla açılışı düşürmez. */
    fun seedOnce(ctx: Context) {
        try {
            if (AppPrefs.getBool(ctx, AppPrefs.KEY_SCHEDULE_SEED_V16, false)) return

            var changed = 0
            for (s in SEEDS) {
                // 1) Ders var mı? (prefix "BLG205 " / "MAT203 " ile tekilleştir)
                val code = s.name.substringBefore(' ')
                val course = CourseStore.courses(ctx).firstOrNull {
                    it.name.startsWith(code, ignoreCase = true)
                } ?: CourseStore.addCourse(ctx, s.name) ?: continue

                // 2) Öğretim üyesi bilgisini güncelle (yoksa yaz)
                if (course.instructor != s.instructor) {
                    CourseStore.setInstructor(ctx, course.id, s.instructor)
                    changed++
                }

                // 3) Bu ders için doğru (gün, saat) satırı var mı?
                val slots = CourseStore.slotsOf(ctx).filter { it.courseId == course.id }
                val exact = slots.firstOrNull { it.day == s.day && it.minuteOfDay == s.minute }
                if (exact != null) {
                    // Derslik yanlışsa düzelt (saat zaten doğru)
                    if (exact.room != s.room) {
                        CourseStore.updateSlot(
                            ctx, exact.id, s.day, s.minute, s.room, Academy.REMIND_DEFAULT
                        )
                        changed++
                    }
                    continue
                }

                // 4) Eski seed'in YANLIŞ saatli satırı var mı → ONU düzelt (silme)
                val legacy = slots.firstOrNull { slot ->
                    s.oldMinutes.any { it == slot.minuteOfDay } && slot.day == s.day
                }
                if (legacy != null) {
                    CourseStore.updateSlot(
                        ctx, legacy.id, s.day, s.minute, s.room, Academy.REMIND_DEFAULT
                    )
                    changed++
                    // Alarmları yeni saate göre yeniden kur
                    CourseStore.slot(ctx, legacy.id)?.let {
                        Reminder.cancelSlot(ctx, it.id)
                        Reminder.scheduleClassSlot(ctx, it)
                    }
                    continue
                }

                // 5) Bu ders hiç programda yoksa ekle
                val slotId = CourseStore.addSlot(
                    ctx, course.id, s.day, s.minute, s.room, Academy.REMIND_DEFAULT
                ) ?: continue
                changed++
                CourseStore.slot(ctx, slotId)?.let { Reminder.scheduleClassSlot(ctx, it) }
            }

            AppPrefs.putBool(ctx, AppPrefs.KEY_SCHEDULE_SEED_V16, true)
            if (changed > 0) {
                BookStore.logMessage(
                    ctx, "ScheduleSeed",
                    "$changed satır Rumeli Ünv. programa göre güncellendi (v16)"
                )
            }
        } catch (t: Throwable) {
            try { BookStore.logError(ctx, "ScheduleSeed", t) } catch (_: Throwable) {}
        }
    }
}
