package com.umay.pdfreader

import android.content.ContentValues
import android.content.Context
import kotlin.math.roundToInt

/**
 * v16 (GÖREV 6.2) — NOT / YÜZDE / AGNO VERİ KATMANI.
 *
 * `library.db` içinde YENİ tablo (v3 → v4 migration, veri kaybı YOK):
 *
 *   grades (id, course_id, kind, score, weight)
 *     kind   : "vize" | "quiz" | "final" | "proje"
 *     score  : 0..100 (kullanıcı girer; boş bırakılırsa satır hiç yazılmaz)
 *     weight : yüzde (ör. Vize %30, Final %50). 0 → ağırlıksız (ortalamaya girmez)
 *
 *   courses.ects : AKTS kredisi (AGNO ağırlığı) — mevcut tabloya kolon eklendi.
 *
 * HESAP (saf fonksiyonlar, Android'siz → `GradeStoreTest` ile doğrulanır):
 *   ders ortalaması = Σ(not × yüzde) / Σ(yüzde)      [yüzde toplamı 100 olmasa da]
 *   harf notu       = 90+ AA · 85+ BA · 80+ BB · 75+ CB · 70+ CC · 60+ DC · 50+ DD · <50 FF
 *   AGNO            = Σ(harf puanı × AKTS) / Σ(AKTS)
 *
 * Tüm DB çağrıları `try/catch`'li (CourseStore ile aynı desen): akademik modül
 * bozulsa bile kütüphane/okuma etkilenmez.
 */
object GradeStore {

    /** Desteklenen not türleri (sıra UI'da da kullanılır). */
    val KINDS = listOf("vize", "quiz", "final", "proje")

    /**
     * DB'de "PUAN HENÜZ GİRİLMEDİ" işareti (-1).
     * Böylece kullanıcı yalnız YÜZDE girip puanı boş bırakabilir; ağırlık
     * kaybolmaz, puan girilmediği için de ortalamayı aşağı çekmez. */
    const val NO_SCORE = -1.0

    /** Varsayılan yüzdeler — kullanıcı her dersi için değiştirebilir. */
    val DEFAULT_WEIGHTS = mapOf(
        "vize" to 30.0, "quiz" to 10.0, "final" to 50.0, "proje" to 10.0
    )

    /** Harf notu → 4.00 sistemindeki puan. */
    private val LETTER_POINTS = mapOf(
        "AA" to 4.0, "BA" to 3.5, "BB" to 3.0, "CB" to 2.5,
        "CC" to 2.0, "DC" to 1.5, "DD" to 1.0, "FF" to 0.0
    )

    /** Tek not satırı (saf model). */
    data class GradeRow(
        val kind: String,
        val score: Double,
        val weight: Double
    )

    // ── SAF HESAPLAMA (birim testli) ────────────────────────

    /**
     * Ders ortalaması: Σ(not × yüzde) / Σ(yüzde).
     * Yüzdesi 0 olan satırlar ortalamaya GİRMEZ; hiç geçerli satır yoksa null.
     */
    fun courseAverage(rows: List<GradeRow>): Double? {
        val valid = rows.filter { it.weight > 0.0 && it.score >= 0.0 }
        val totalWeight = valid.sumOf { it.weight }
        if (valid.isEmpty() || totalWeight <= 0.0) return null
        val weighted = valid.sumOf { it.score * it.weight }
        return weighted / totalWeight
    }

    /** 0..100 ortalama → harf notu (Türk üniversite sistemi). */
    fun letterOf(average: Double?): String {
        val a = average ?: return "—"
        return when {
            a >= 90 -> "AA"
            a >= 85 -> "BA"
            a >= 80 -> "BB"
            a >= 75 -> "CB"
            a >= 70 -> "CC"
            a >= 60 -> "DC"
            a >= 50 -> "DD"
            else -> "FF"
        }
    }

    /** Harf notu → 4.00 puanı (bilinmeyen → 0.0). */
    fun pointOf(letter: String): Double = LETTER_POINTS[letter] ?: 0.0

    /**
     * AGNO = Σ(harf puanı × AKTS) / Σ(AKTS).
     * AKTS'si 0 olan dersler ağırlıklandırmaya girmez; hiç ders yoksa null.
     */
    fun agno(courses: List<Pair<Double?, Int>>): Double? {
        var num = 0.0
        var den = 0
        courses.forEach { (avg, ects) ->
            val letter = letterOf(avg)
            if (letter == "—" || ects <= 0) return@forEach
            num += pointOf(letter) * ects
            den += ects
        }
        if (den <= 0) return null
        return num / den
    }

    /** Çift haneli yuvarlanmış gösterim ("3.42"); null → "—". */
    fun format2(value: Double?): String {
        if (value == null) return "—"
        val scaled = (value * 100).roundToInt() / 100.0
        return String.format(java.util.Locale.US, "%.2f", scaled)
    }

    /** 1..100 yüzde aralığına sıkıştır (kullanıcı 150 yazarsa). */
    fun clampPercent(v: Double): Double = v.coerceIn(0.0, 100.0)

    // ── VERİ KATMANI ────────────────────────────────────────

    private fun db(ctx: Context): LibraryDb = LibraryDb.get(ctx)

    private inline fun <T> safe(ctx: Context, where: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "GradeStore.$where", t)
            fallback
        }

    /** Bir dersin not satırları (yalnız kayıtlı olanlar). */
    fun gradesOf(ctx: Context, courseId: String): List<GradeRow> =
        safe(ctx, "gradesOf", emptyList()) {
            val out = ArrayList<GradeRow>(4)
            db(ctx).readableDatabase.rawQuery(
                "SELECT kind, score, weight FROM grades WHERE course_id = ?",
                arrayOf(courseId)
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(GradeRow(c.getString(0), c.getDouble(1), c.getDouble(2)))
                }
            }
            out
        }

    /** Tüm derslerin satırları (AGNO ekranı için). */
    fun allGrades(ctx: Context): Map<String, List<GradeRow>> =
        safe(ctx, "allGrades", emptyMap()) {
            val out = HashMap<String, MutableList<GradeRow>>(8)
            db(ctx).readableDatabase.rawQuery(
                "SELECT course_id, kind, score, weight FROM grades", null
            ).use { c ->
                while (c.moveToNext()) {
                    out.getOrPut(c.getString(0)) { ArrayList(4) }
                        .add(GradeRow(c.getString(1), c.getDouble(2), c.getDouble(3)))
                }
            }
            out
        }

    /**
     * Not satırını yaz (varsa güncelle) — `id` = "<courseId>-<kind>" olduğu için
     * aynı tür tek satırdır (REPLACE = upsert).
     *
     *  • `score == null` → puan girilmemiş sayılır (DB'ye `NO_SCORE` yazılır):
     *    yüzde kaydedilir ama ortalama hesabına GİRMEZ.
     *  • Hem puan boş hem yüzde 0 → satır SİLİNİR (kullanıcı alanları temizledi).
     */
    fun setGrade(
        ctx: Context, courseId: String, kind: String, score: Double?, weight: Double
    ): Boolean = safe(ctx, "setGrade", false) {
        val d = db(ctx).writableDatabase
        if (score == null && weight <= 0.0) {
            d.delete("grades", "course_id = ? AND kind = ?", arrayOf(courseId, kind))
            return@safe true
        }
        val cv = ContentValues().apply {
            put("id", "$courseId-$kind")
            put("course_id", courseId)
            put("kind", kind)
            put("score", score?.let { clampPercent(it) } ?: NO_SCORE)
            put("weight", clampPercent(weight))
        }
        d.insertWithOnConflict(
            "grades", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        true
    }

    /** Kayıtlı yüzde; satır yoksa varsayılan yüzde (vize 30 / final 50 …). */
    fun weightOf(ctx: Context, courseId: String, kind: String): Double =
        gradesOf(ctx, courseId).firstOrNull { it.kind == kind }?.weight
            ?: DEFAULT_WEIGHTS[kind] ?: 0.0

    /** AKTS kredisi (0 = girilmemiş). */
    fun ectsOf(ctx: Context, courseId: String): Int = safe(ctx, "ectsOf", 0) {
        db(ctx).readableDatabase.rawQuery(
            "SELECT COALESCE(ects, 0) FROM courses WHERE id = ?", arrayOf(courseId)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun setEcts(ctx: Context, courseId: String, ects: Int): Boolean =
        safe(ctx, "setEcts", false) {
            val cv = ContentValues().apply { put("ects", ects.coerceIn(0, 60)) }
            db(ctx).writableDatabase.update("courses", cv, "id = ?", arrayOf(courseId)) > 0
        }

    /** Ders ortalaması (DB'den okuyup hesaplar). */
    fun averageOf(ctx: Context, courseId: String): Double? =
        courseAverage(gradesOf(ctx, courseId))

    /**
     * Tüm derslerin ortalaması + AKTS ile AGNO. UI ve test bu fonksiyonu kullanır.
     * Notu veya AKTS'si olmayan dersler null olarak döner (gösterim "—").
     */
    fun overview(ctx: Context): List<Triple<Course, Double?, Int>> =
        safe(ctx, "overview", emptyList()) {
            CourseStore.courses(ctx).map { c ->
                Triple(c, averageOf(ctx, c.id), ectsOf(ctx, c.id))
            }
        }

    /** Genel AGNO (null → henüz hesaplanamaz). */
    fun overallAgno(ctx: Context): Double? =
        agno(overview(ctx).map { it.second to it.third })
}
