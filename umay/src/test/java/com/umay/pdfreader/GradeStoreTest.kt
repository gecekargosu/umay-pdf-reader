package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ── Harf notu ──────────────────────────────────────────

    @Test
    fun letterOf_araliklar() {
        assertEquals("AA", GradeStore.letterOf(95.0))
        assertEquals("AA", GradeStore.letterOf(90.0))
        assertEquals("BA", GradeStore.letterOf(87.0))
        assertEquals("BB", GradeStore.letterOf(82.0))
        assertEquals("CB", GradeStore.letterOf(77.0))
        assertEquals("CC", GradeStore.letterOf(72.0))
        assertEquals("DC", GradeStore.letterOf(65.0))
        assertEquals("DD", GradeStore.letterOf(55.0))
        assertEquals("FF", GradeStore.letterOf(49.9))
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
        // AA (4.0) × 6 AKTS + CC (2.0) × 4 AKTS → (24 + 8) / 10 = 3.2
        val g = GradeStore.agno(listOf(95.0 to 6, 72.0 to 4))
        assertEquals(3.2, g!!, 0.0001)
    }

    @Test
    fun agno_aktsSifirOlanDersHariç() {
        // Ortalaması 100 olan dersin AKTS'si 0 → sayılmaz
        val g = GradeStore.agno(listOf(100.0 to 0, 72.0 to 4))
        assertEquals(2.0, g!!, 0.0001)
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
