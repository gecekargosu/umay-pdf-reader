package com.umay.pdfreader

import android.content.Context
import java.io.File

/**
 * v5.22 — DERS PROJE ÖDEVLERİ (tek seferlik tohum).
 *
 * Kaynak: kullanıcının 3. dönem klasöründeki resmî yönergeler
 * (BLG203 / BLG205 Dönem Projesi Yönergesi) ve Makine Öğrenmesi NMF ödevi.
 *
 * Tarihler yönergelerden aynen alındı; Makine Öğrenmesi NMF dosyasında tarih
 * yazmadığı için dersin final teslim haftası (18.12.2026) kullanıldı ve
 * açıklamaya bu not düşüldü.
 *
 * Tohum yalnızca bir kez çalışır; aynı başlıklı ödev zaten varsa eklemez.
 */
object ProjectSeed {

    private const val PREF = "projects_seeded_v3"

    private data class Seed(
        val courseHints: List<String>,
        val title: String,
        val description: String,
        val due: String        // yyyy-MM-dd HH:mm (Türkiye saati)
    )

    private val seeds = listOf(
        Seed(
            listOf("BLG203", "VERI YAPILARI", "VERİ YAPILARI"),
            "Dönem Projesi — Final Teslimi (rapor + yazılım)",
            "BLG203 yönergesi: proje raporu ve yazılımı UZEM'e yüklenir. " +
                "Geç teslimde her gün 10 puan kesilir; 3 günden fazla gecikme değerlendirilmez.",
            "2026-12-18 17:00"
        ),
        Seed(
            listOf("BLG203", "VERI YAPILARI", "VERİ YAPILARI"),
            "Dönem Projesi — Sunum (Lab B101)",
            "Yapay Zekâ ve Makine Öğrenmesi bölümü sunumu: 23.12.2026, Lab B101. " +
                "10 dk sunum + 5 dk soru-cevap; sunuma katılmayan proje notunu alamaz.",
            "2026-12-23 09:00"
        ),
        Seed(
            listOf("BLG205", "NESNE TABANLI", "NESNE YÖNELİMLİ", "NTP"),
            "Dönem Projesi — Final Teslimi (UML + kod + rapor)",
            "BLG205 yönergesi: UML diyagramı, çalışan Java uygulaması ve rapor UZEM'e yüklenir. " +
                "Geç teslimde her gün 10 puan kesilir.",
            "2026-12-18 17:00"
        ),
        Seed(
            listOf("BLG205", "NESNE TABANLI", "NESNE YÖNELİMLİ", "NTP"),
            "Dönem Projesi — Sunum (Lab B101)",
            "Yapay Zekâ ve Makine Öğrenmesi bölümü sunumu: 23.12.2026, Lab B101. " +
                "UML üzerinden tasarım + canlı demo zorunlu.",
            "2026-12-23 09:00"
        ),
        Seed(
            listOf("MAKINE", "MAKİNE", "YPZ201"),
            "NMF Proje Ödevi — teslim",
            "Negatif Olmayan Matris Ayrıştırması (NMF) sıfırdan kodlanacak: kaynak kod, " +
                "çıktı/görseller ve en fazla 1 sayfalık rapor. " +
                "NOT: Ödev dosyasında tarih yazmıyor; final teslim haftası varsayıldı.",
            "2026-12-18 17:00"
        )
    )

    fun applyOnce(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences("umay_prefs", 0)
            if (prefs.getBoolean(PREF, false)) return
            val courses = CourseStore.courses(ctx)
            if (courses.isEmpty()) return
            var added = 0
            for (s in seeds) {
                val course = courses.firstOrNull { c ->
                    val n = TextUtil.normalize(c.name)
                    s.courseHints.any { h -> n.contains(TextUtil.normalize(h)) }
                } ?: continue
                val due = parse(s.due) ?: continue
                // v5.22 — yalnız NMF kaydı yanlış derse yazılmıştı; onu taşı.
                // Aynı başlık iki derste meşru olabilir (BLG203 ve BLG205 sunumu).
                if (s.title.contains("NMF")) {
                    for (other in courses) {
                        if (other.id == course.id) continue
                        CourseStore.homeworkOf(ctx, other.id)
                            .filter { TextUtil.normalize(it.title) == TextUtil.normalize(s.title) }
                            .forEach { CourseStore.deleteHomework(ctx, it.id) }
                    }
                }
                val exists = CourseStore.homeworkOf(ctx, course.id).any {
                    TextUtil.normalize(it.title) == TextUtil.normalize(s.title)
                }
                if (exists) continue
                if (CourseStore.addHomework(ctx, course.id, s.title, s.description, due, true)) {
                    added++
                }
            }
            prefs.edit().putBoolean(PREF, true).apply()
            if (added > 0) {
                try { Reminder.rescheduleAll(ctx) } catch (_: Throwable) { }
                SeyirDefteri.yaz(ctx, "PROJELER", "proje ödevleri eklendi", "$added kayıt")
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ProjectSeed.applyOnce", t)
        }
    }

    private fun parse(v: String): Long? = try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("Europe/Istanbul")
        fmt.parse(v)?.time
    } catch (_: Throwable) {
        null
    }

    /** Ana ekranda/ders detayında gösterilecek kısa etiket. */
    fun dueLabel(millis: Long): String = try {
        val fmt = java.text.SimpleDateFormat("d MMMM yyyy HH:mm", java.util.Locale("tr", "TR"))
        fmt.timeZone = java.util.TimeZone.getTimeZone("Europe/Istanbul")
        fmt.format(java.util.Date(millis))
    } catch (_: Throwable) {
        ""
    }

    fun assetFile(ctx: Context): File = File(ctx.cacheDir, "tubitak_2209a.pdf")
}
