package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v36 — Boşta ön hazırlık: sayfa kalite denetimi testleri.
 *
 * Kural: bir sayfa "sorunlu" damgası alıyorsa nedeni ölçülebilir olmalı;
 * sağlam metin yanlışlıkla suçlanmamalı (yoksa kullanıcı gereksiz uyarı görür).
 */
class PrepAuditTest {

    @Test
    fun `saglam metin sayfasi okunabilir sayilir`() {
        val r = PrepAudit.audit(
            "b1",
            listOf(
                "Veri yapıları dersinde yığın ve kuyruk konuları işlenir. " +
                    "Yığın son giren ilk çıkar mantığıyla çalışır."
            )
        )
        assertEquals(1, r.readable)
        assertEquals(0, r.empty)
        assertTrue(r.issues.isEmpty())
        assertEquals(100, r.readablePercent)
    }

    @Test
    fun `metinsiz sayfa bos olarak isaretlenir ve nedeni yazilir`() {
        val r = PrepAudit.audit("b1", listOf("", "   ", "kısa"))
        assertEquals(3, r.empty)
        assertEquals(0, r.readable)
        assertEquals(PrepAudit.Kind.EMPTY, r.issues.first().kind)
        assertTrue(r.issues.first().note.contains("metin yok"))
    }

    @Test
    fun `yalniz kod satirlarindan olusan sayfa ayri sayilir`() {
        val code = """
            public static void main(String[] args) {
                int x = 5;
                System.out.println(x);
                return;
            }
        """.trimIndent()
        val r = PrepAudit.audit("b1", listOf(code))
        assertEquals(1, r.codeOnly)
        assertEquals(PrepAudit.Kind.CODE_ONLY, r.issues.first().kind)
    }

    @Test
    fun `karisik sayfa kod orani dusukse okunabilir kalir`() {
        val mixed = """
            İkili arama ağacı, düğümlerin solunda küçük değerler tutar.
            Bu yapı sayesinde arama ortalama logaritmik sürede tamamlanır.
            Aşağıdaki örnekte bu durum gösterilmiştir.
            int x = 1;
        """.trimIndent()
        val r = PrepAudit.audit("b1", listOf(mixed))
        assertEquals("tek kod satırı sayfayı ele geçirmemeli", 1, r.readable)
        assertEquals(0, r.codeOnly)
    }

    @Test
    fun `bos liste cokmez`() {
        val r = PrepAudit.audit("b1", emptyList())
        assertEquals(0, r.total)
        assertEquals(0, r.readablePercent)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `bozuk sayfalar listelenir ve kurtarilan sayilir`() {
        val broken = "\uE000\uE001 metin \uFFFDbilgi " + "dolgu metni burada".repeat(3)
        val r = PrepAudit.audit("b1", listOf("Sağlam cümle burada yeterince uzun duruyor.", broken))
        assertEquals(listOf(1), r.brokenPages)
        assertEquals("AR önbelleğinde yok → 0", 0, r.recoveredCount(emptySet()))
        assertEquals("AR önbelleğinde var → 1", 1, r.recoveredCount(setOf(1)))
    }

    @Test
    fun `ozet satiri sayilari ve yuzdeyi tasir`() {
        val r = PrepAudit.audit("b1", listOf("Sağlam bir cümle burada duruyor ve yeterince uzun.", ""))
        val s = r.summary()
        assertTrue(s.contains("1/2 sayfa okunabilir"))
        assertTrue(s.contains("%50"))
        assertTrue(s.contains("1 boş"))
    }
}
