package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.11 — BookRetriever birim testi.
 *
 * Amaç: "her sayfada geçen kelime" ile "sorunun cevabını taşıyan sayfa"
 * ayrımının gerçekten yapıldığını kanıtlamak. Eski arama (ham kelime sayımı)
 * bu testte 1. sayfayı seçiyordu, çünkü "veri" orada daha çok geçiyor.
 */
class BookRetrieverTest {

    private val pages = listOf(
        // 0 — "veri" çok geçiyor ama cevap yok (gürültü sayfası)
        "Veri veri veri. Veri tabanı veri yapıları veri akışı. " +
            "Veri veri veri veri veri veri veri veri veri veri veri veri.",
        // 1 — cevabın olduğu sayfa
        "İkili arama ağacında her düğümün solundaki değerler daha küçük, " +
            "sağındaki değerler daha büyüktür. Arama ortalama O(log n) sürer.",
        // 2 — alakasız
        "Sıralama algoritmaları arasında kabarcık sıralama en basit olanıdır."
    )

    @Test
    fun `nadir terimi tasiyan sayfa_one_cikar`() {
        val res = BookRetriever.passages(pages, "ikili arama ağacı nedir", 3000)
        assertTrue("sonuç boş olmamalı", res.isNotEmpty())
        assertEquals("cevap sayfası 2 olmalı", 1, res.first().page)
    }

    @Test
    fun `sik gecen kelime siralama ele gecirmez`() {
        val res = BookRetriever.passages(pages, "veri ağacı düğüm", 3000)
        assertEquals("sayfa 2 (düğüm) önde olmalı", 1, res.first().page)
    }

    @Test
    fun `bos ve kirli girdi cokmez`() {
        assertTrue(BookRetriever.passages(emptyList(), "soru", 1000).isEmpty())
        assertTrue(BookRetriever.passages(listOf("", "   "), "soru", 1000).isEmpty())
        assertEquals("", BookRetriever.contextFor(emptyList(), "soru"))
    }

    @Test
    fun `baglam sayfa numarasiyla ve butce icinde`() {
        val ctx = BookRetriever.contextFor(pages, "arama ağacı", 900)
        assertTrue("sayfa etiketi olmalı", ctx.contains("Sayfa 2:"))
        assertTrue("bütçe aşılmamalı", ctx.length <= 1400)
    }

    @Test
    fun `cevap bulunamazsa ilk sayfalar doner`() {
        val res = BookRetriever.passages(pages, "zzzz qqqq", 2000)
        assertTrue("yine de bağlam verilmeli", res.isNotEmpty())
    }

    @Test
    fun `acik sayfa baglamin basina eklenir`() {
        val ctx = BookRetriever.withOpenPage(pages, "arama", 2, 2000)
        assertTrue(ctx.startsWith("Sayfa 3"))
    }
}
