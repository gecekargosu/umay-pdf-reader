package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v36.1 — Sohbet yanıtındaki sayfa bağlantısı (A5/F3).
 *
 * Kullanıcı isteği: "AI cevabında sayfa numarası olsun, tıklayınca o sayfaya gitsin".
 * Bu testler bağlantının **doğru yerde** ve **yalnız var olan sayfalar için**
 * kurulduğunu kanıtlar.
 */
class PageRefParserTest {

    @Test
    fun `s noktali bicim bulunur`() {
        val refs = PageRefParser.find("Bu konu (s. 42) sayfasında anlatılıyor.", 100)
        assertEquals(1, refs.size)
        assertEquals(42, refs.first().page)
    }

    @Test
    fun `ingilizce p bicimi de bulunur`() {
        val refs = PageRefParser.find("See p. 7 for details.", 20)
        assertEquals(7, refs.first().page)
    }

    @Test
    fun `sayfa kelimesiyle yazilmis bicim bulunur`() {
        val refs = PageRefParser.find("Ayrıntı Sayfa 12 altında.", 50)
        assertEquals(12, refs.first().page)
    }

    @Test
    fun `kitapta olmayan sayfa baglanti olmaz`() {
        val refs = PageRefParser.find("Bkz. (s. 140)", 100)
        assertTrue("100 sayfalık kitapta 140. sayfa yok", refs.isEmpty())
    }

    @Test
    fun `toplam sayfa bilinmiyorsa sinir uygulanmaz`() {
        val refs = PageRefParser.find("(s. 140)", 0)
        assertEquals(140, refs.first().page)
    }

    @Test
    fun `birden cok baglanti sirayla doner`() {
        val refs = PageRefParser.find("İlk (s. 3), sonra (s. 9) ve Sayfa 21.", 50)
        assertEquals(listOf(3, 9, 21), refs.map { it.page })
    }

    @Test
    fun `baglanti metindeki dogru araligi isaret eder`() {
        val text = "Cevap (s. 5) burada."
        val ref = PageRefParser.find(text, 10).first()
        assertEquals("(s. 5)", text.substring(ref.start, ref.end))
    }

    @Test
    fun `sayfa sifir veya metin bos ise sonuc yok`() {
        assertTrue(PageRefParser.find("(s. 0)", 10).isEmpty())
        assertTrue(PageRefParser.find("", 10).isEmpty())
    }

    @Test
    fun `sayfa gecmeyen cumlede baglanti yok`() {
        assertTrue(
            PageRefParser.find("Veri yapıları dersinde yığın anlatılır.", 100).isEmpty()
        )
    }
}
