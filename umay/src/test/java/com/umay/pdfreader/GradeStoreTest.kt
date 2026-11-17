package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v16 (GÖREV 6.2) — Not / yüzde / AGNO matematiğinin birim testleri.
 *
 * Notlar saf fonksiyonlardır (Android'e bağımlı değil); bu yüzden JVM'de
 * doğrulanır. Kapsanan kurallar:
 *  - ağırlıklı ders ortalaması (yüzdesi 0 olan satır HARİÇ),
 *  - harf notu aralıkları (AA…FF) ve 4.00 puan karşılıkları,
 *  - AKTS ağırlıklı AGNO (AKTS'si 0 olan ders HARİÇ),
 *  - giriş sınırlama (0..100) ve 2 haneli gösterim.
 */
class GradeStoreTest {

    private fun row(kind: String, score: Double, weight: Double) =
        GradeStore.GradeRow(kind, score, weight)

    // ── Ağırlıklı ortalama ─────────────────────────────────

    @Test
    fun courseAverage_agirlikliOrtalama() {
        // (80*30 + 90*10 + 70*50 + 100*10) / 100 = 78.00
        val avg = GradeStore.courseAverage(
            listOf(
                row("vize", 80.0, 30.0),
                row("quiz", 90.0, 10.0),
                row("final", 70.0, 50.0),
                row("proje", 100.0, 10.0)
            )
        )
        assertEquals(78.0, avg!!, 0.0001)
    }

    @Test
    fun courseAverage_yuzdesiSifirOlanSatirHariç() {
        // proje yüzdesi 0 → ortalamaya girmez: (50*40 + 100*60)/100 = 80
        val avg = GradeStore.courseAverage(
            listOf(
                row("vize", 50.0, 40.0),
                row("final", 100.0, 60.0),
                row("proje", 0.0, 0.0)
            )
        )
        assertEquals(80.0, avg!!, 0.0001)
    }

    @Test
    fun courseAverage_bosListeNull() {
        assertNull(GradeStore.courseAverage(emptyList()))
        // tüm yüzdeler 0 → hesaplanamaz
        assertNull(GradeStore.courseAverage(listOf(row("vize", 70.0, 0.0))))
    }

    @Test
    fun courseAverage_yuzdeToplamiYuzOlmadan() {
        // Toplam yüzde 60 olsa da formül Σ(not×yüzde)/Σ(yüzde) → 75
        val avg = GradeStore.courseAverage(
            listOf(row("vize", 50.0, 20.0), row("final", 90.0, 40.0))
        )
        assertEquals((50.0 * 20 + 90.0 * 40) / 60.0, avg!!, 0.0001)
    }

    @Test
    fun courseAverage_quiz1_quiz2_ve_butunleme() {
        // v45 — kullanıcının gerçek dağılımı:
        // vize %20 · quiz1 %2,5 · quiz2 %2,5 · final %25 · proje %50
        val rows = listOf(
            row("vize", 80.0, 20.0),
            row("quiz", 100.0, 2.5),
            row("quiz2", 60.0, 2.5),
            row("final", 90.0, 25.0),
            row("proje", 70.0, 50.0)
        )
        val expected = (80 * 20 + 100 * 2.5 + 60 * 2.5 + 90 * 25 + 70 * 50) / 100.0
        assertEquals(expected, GradeStore.courseAverage(rows)!!, 0.0001)
        // Bütünleme yüzdesi 0 → ortalamaya girmez (varsayılan davranış)
        assertEquals(
            expected,
            GradeStore.courseAverage(rows + row("butunleme", 100.0, 0.0))!!, 0.0001
        )
        // Bütünleme yüzdesi verilirse hesaba katılır
        val withResit = rows + row("butunleme", 100.0, 100.0)
        assertEquals(
            (80 * 20 + 100 * 2.5 + 60 * 2.5 + 90 * 25 + 70 * 50 + 100 * 100) / 200.0,
            GradeStore.courseAverage(withResit)!!, 0.0001
        )
    }

    @Test
    fun notTuru_listesi_quiz2_ve_butunleme_icerir() {
        assertTrue("quiz2 yok", "quiz2" in GradeStore.KINDS)
        assertTrue("butunleme yok", "butunleme" in GradeStore.KINDS)
        assertEquals(0.0, GradeStore.DEFAULT_WEIGHTS["butunleme"]!!, 0.0001)
    }

    // ── Harf notu ──────────────────────────────────────────

    @Test
    fun letterOf_araliklar() {
        assertEquals("AA", GradeStore.letterOf(95.0))
        assertEquals("AA", GradeStore.letterOf(90.0))
        assertEquals("BA", GradeStore.letterOf(87.0))
        // v44 — resmî transkript tablosu: BB 75-84 · CB 65-74 · CC 60-64 ·
        // DC 55-59 · DD 50-54 · FD 45-49 · FF 0-44
        assertEquals("BB", GradeStore.letterOf(82.0))
        assertEquals("BB", GradeStore.letterOf(77.0))
        assertEquals("CB", GradeStore.letterOf(72.0))
        assertEquals("CB", GradeStore.letterOf(65.0))
        assertEquals("CC", GradeStore.letterOf(62.0))
        assertEquals("DC", GradeStore.letterOf(57.0))
        assertEquals("DD", GradeStore.letterOf(52.0))
        assertEquals("FD", GradeStore.letterOf(47.0))
        assertEquals("FF", GradeStore.letterOf(44.0))
    }

    @Test
    fun letterOf_nullTire() {
        assertEquals("—", GradeStore.letterOf(null))
    }

    @Test
    fun pointOf_harfPuanlari() {
        assertEquals(4.0, GradeStore.pointOf("AA"), 0.0001)
        assertEquals(3.5, GradeStore.pointOf("BA"), 0.0001)
        assertEquals(2.0, GradeStore.pointOf("CC"), 0.0001)
        assertEquals(0.0, GradeStore.pointOf("FF"), 0.0001)
        assertEquals(0.0, GradeStore.pointOf("ZZ"), 0.0001)   // bilinmeyen
    }

    // ── AGNO ───────────────────────────────────────────────

    @Test
    fun agno_aktsAgirlikli() {
        // AA (4.0) × 6 AKTS + CB (2.5) × 4 AKTS → (24 + 10) / 10 = 3.4
        val g = GradeStore.agno(listOf(95.0 to 6, 72.0 to 4))
        assertEquals(3.4, g!!, 0.0001)
    }

    /**
     * v44 — GERÇEK 3. YARIYIL VERİSİYLE TAM SINAMA.
     *
     * Ders/AKTS listesi öğrencinin “Tüm dersler.pdf” belgesinden alındı
     * (3. Yarıyıl, toplam 30 AKTS). Notlar uydurma; amaç hem ders
     * ortalamasının hem de AKTS ağırlıklı AGNO'nun doğru çıkmasını görmek.
     */
    @Test
    fun ucuncuDonem_gercekAKTS_ile_ano_ve_agno() {
        data class C(val name: String, val ects: Int, val rows: List<GradeStore.GradeRow>)
        // Vize %20 · Quiz %2,5 + %2,5 · Final %25 · Proje %50 (örnek dağılım)
        val courses = listOf(
            C("BLG203 Veri Yapıları", 5, listOf(row("vize", 80.0, 20.0), row("quiz", 100.0, 5.0), row("final", 95.0, 25.0), row("proje", 90.0, 50.0))),
            C("BLG205 NTP", 6, listOf(row("vize", 100.0, 20.0), row("quiz", 90.0, 5.0), row("final", 85.0, 25.0), row("proje", 95.0, 50.0))),
            C("BLG207 Ayrık Matematik", 5, listOf(row("vize", 70.0, 40.0), row("final", 72.0, 60.0))),
            C("MAT203 Olasılık", 5, listOf(row("vize", 60.0, 40.0), row("final", 65.0, 60.0))),
            C("MAT205 Lineer Cebir", 4, listOf(row("vize", 50.0, 40.0), row("final", 60.0, 60.0))),
            C("YPZ201 Makine Öğrenmesi", 5, listOf(row("vize", 40.0, 40.0), row("final", 52.0, 60.0)))
        )
        val pairs = courses.map { c -> GradeStore.courseAverage(c.rows) to c.ects }
        // ANO: (89,75*5 + 93,25*6 + 71,2*5 + 63*5 + 56*4 + 47,2*5) / 30 = 71,31
        val anoSelf = pairs.sumOf { (avg, ects) -> (avg ?: 0.0) * ects } / 30.0
        assertEquals(71.31, anoSelf, 0.01)
        // Harf puanları: BA 3,5 · AA 4 · CB 2,5 · CC 2 · DC 1,5 · FD 0,5
        val g = GradeStore.agno(pairs)
        assertEquals((3.5 * 5 + 4.0 * 6 + 2.5 * 5 + 2.0 * 5 + 1.5 * 4 + 0.5 * 5) / 30.0, g!!, 0.0001)
        assertEquals("2.42", GradeStore.format2(g))
    }

    @Test
    fun agno_aktsSifirOlanDersHariç() {
        // Ortalaması 100 olan dersin AKTS'si 0 → sayılmaz (72 → CB = 2,5)
        val g = GradeStore.agno(listOf(100.0 to 0, 72.0 to 4))
        assertEquals(2.5, g!!, 0.0001)
    }

    @Test
    fun agno_notsuzDersHariç() {
        // Notu olmayan (null) ders AGNO'ya girmez
        val g = GradeStore.agno(listOf(null to 10, 87.0 to 5))
        assertEquals(3.5, g!!, 0.0001)
    }

    @Test
    fun agno_veriYoksaNull() {
        assertNull(GradeStore.agno(emptyList()))
        assertNull(GradeStore.agno(listOf(null to 5)))
    }

    // ── Giriş sınırlama + gösterim ─────────────────────────

    @Test
    fun clampPercent_sinirlar() {
        assertEquals(100.0, GradeStore.clampPercent(150.0), 0.0001)
        assertEquals(0.0, GradeStore.clampPercent(-5.0), 0.0001)
        assertEquals(42.5, GradeStore.clampPercent(42.5), 0.0001)
    }

    @Test
    fun format2_ikiHane() {
        assertEquals("3.46", GradeStore.format2(3.456))
        assertEquals("3.20", GradeStore.format2(3.2))
        assertEquals("—", GradeStore.format2(null))
    }
}
