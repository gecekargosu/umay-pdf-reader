package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v17 — Evrensel parser birim testleri (GÖREV 1).
 *
 * Bunlar JVM unit testleri: `android.util.*` kullanılmaz, saf mantık sınanır.
 * Amaç: (1) kodlama çözümü (UTF-8/UTF-16/İngilizce), (2) içerik analizi
 * (Arapça/LaTeX/kod), (3) magic-byte sınıflandırma, (4) güvenli parse
 * (parser patlasa bile asla fırlatmaz) güvenceleri.
 */
class UniversalParserTest {

    // ── 1) Kodlama çözümü ─────────────────────────────────────

    @Test
    fun decode_utf8_bom_soyler() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "Merhaba dünya".toByteArray(Charsets.UTF_8)
        val d = UniversalParser.decode(bytes)
        assertEquals("Merhaba dünya", d.text)
        assertTrue(d.charset.contains("UTF-8"))
    }

    @Test
    fun decode_utf16le_bom_acar() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Türkçe okuma".toByteArray(Charsets.UTF_16LE)
        val d = UniversalParser.decode(bytes)
        assertEquals("Türkçe okuma", d.text)
        assertTrue(d.charset.startsWith("UTF-16LE"))
    }

    @Test
    fun decode_utf16le_bomsuz_sezgi() {
        // BOM yok ama her ikinci bayt NUL → UTF-16LE sezgisi
        val bytes = "Kur'an metni".toByteArray(Charsets.UTF_16LE)
        val d = UniversalParser.decode(bytes)
        assertEquals("Kur'an metni", d.text)
    }

    @Test
    fun decode_utf8_arapca_karakterler_kayipsiz() {
        val ayet = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ"  // Besmele (UTF-8)
        val d = UniversalParser.decode(ayet.toByteArray(Charsets.UTF_8))
        assertEquals(ayet, d.text)
        assertEquals("UTF-8", d.charset)
    }

    @Test
    fun decode_turkce_oxiler_utf8() {
        val t = "Çığlık, şafak, ğüzel, İSTANBUL — öâîû"
        val d = UniversalParser.decode(t.toByteArray(Charsets.UTF_8))
        assertEquals(t, d.text)
    }

    @Test
    fun decode_ascii_saf_ascii() {
        val d = UniversalParser.decode("plain ascii".toByteArray())
        assertEquals("plain ascii", d.text)
    }

    // ── 2) İçerik analizi ─────────────────────────────────────

    @Test
    fun arapca_blok_taninir() {
        assertTrue(UniversalParser.features("بِسْمِ ٱللَّهِ").arabic)
        assertFalse(UniversalParser.features("tamamen türkçe metin").arabic)
    }

    @Test
    fun latex_taninir() {
        assertTrue(UniversalParser.features("\\frac{a}{b} + \\sqrt{x}").latex)
        assertTrue(UniversalParser.features("burada \$E=mc^2\$ formülü var").latex)
        assertFalse(UniversalParser.features("matematik yok").latex)
    }

    @Test
    fun kod_indentasyonu_taninir() {
        val code = """
            fun main() {
                val x = 1
                val y = 2
                println(x + y)
            }
        """.trimIndent()
        assertTrue(UniversalParser.features(code).code)
        assertFalse(UniversalParser.features("paragraf metni.\nikinci satır.").code)
    }

    @Test
    fun describe_tek_satirlik_ozet() {
        val f = UniversalParser.features("x", "utf-8")
        val s = UniversalParser.describe("txt", f)
        assertTrue(s.contains("tur=txt"))
        assertTrue(s.contains("kodlama=utf-8"))
    }

    // ── 3) Magic-byte sınıflandırma ───────────────────────────

    private fun temp(name: String, bytes: ByteArray): File =
        File.createTempFile("umay", name).also { it.writeBytes(bytes); it.deleteOnExit() }

    @Test
    fun isPdf_magic_bakar_uzantiya_degil() {
        val fakePdf = temp(".txt", "%PDF-1.7 sahte ama metin".toByteArray())
        assertTrue(UniversalParser.isPdf(fakePdf))          // içerik PDF → true
        val misnamed = temp(".pdf", "sadece duz metin".toByteArray())
        assertFalse(UniversalParser.isPdf(misnamed))        // uzantı PDF, içerik değil
    }

    @Test
    fun isZip_pk_magic() {
        val zip = temp(".docx", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0))
        assertTrue(UniversalParser.isZip(zip))
        val notZip = temp(".docx", "bu zip degil".toByteArray())
        assertFalse(UniversalParser.isZip(notZip))
    }

    @Test
    fun looksLikeText_ikili_dosyayi_eler() {
        val binary = ByteArray(4096) { i -> if (i % 7 == 0) 0x00 else (i % 251).toByte() }
        val bin = temp(".xyz", binary)
        assertFalse(UniversalParser.looksLikeText(bin))

        val txt = temp(".quran", "الفاتحة\n".toByteArray(Charsets.UTF_8))
        assertTrue(UniversalParser.looksLikeText(txt))
    }

    // ── 4) Güvenli parse (asla fırlatmaz) ─────────────────────

    @Test
    fun safeParagraphs_txt_satirlari_korur() {
        val src = temp(
            ".tex",
            "satır1\n\tgirintili satır\n    dört boşluk\n\t ikinci girinti\n"
                .toByteArray()
        )
        val out = UniversalParser.safeParagraphs(src, "ornek.tex")
        assertTrue(out.ok)
        assertEquals(5, out.paragraphs.size)      // son \n → boş satır
        assertEquals("\tgirintili satır", out.paragraphs[1])  // indent korunur
        assertTrue(out.features.code)
    }

    @Test
    fun safeParagraphs_bozuk_docx_firlatmaz_hata_doner() {
        // PK magic var ama içerik bozuk → DocumentConverter.patlar,
        // ama safeParagraphs yutar ve ok=false döner (uygulama çökmez).
        val src = temp(".docx", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4))
        var logged = false
        val out = UniversalParser.safeParagraphs(src, "bozuk.docx") { _, _ -> logged = true }
        assertFalse(out.ok)
        assertTrue(out.error != null)
        assertTrue("hata çağıran kodu bilgilendirilmeli", logged)
    }

    @Test
    fun safeParagraphs_pdf_icip_sadece_siniflandirir() {
        val src = temp(".pdf", "%PDF-1.4 içerk".toByteArray())
        val out = UniversalParser.safeParagraphs(src, "a.pdf")
        assertTrue(out.ok)
        assertTrue(out.paragraphs.isEmpty())      // PDF metni PDFBox/OCR ile çıkar
        assertEquals("pdf", out.features.charset)
    }

    @Test
    fun safeParagraphs_varolmayan_dosya_firlatmaz_hata_doner() {
        var logged = false
        val out = UniversalParser.safeParagraphs(
            File("/yok/boyle/dosya.txt"), "yok.txt"
        ) { _, _ -> logged = true }
        assertFalse(out.ok)                       // çökmeden hata döndü
        assertTrue(out.error != null)
        assertTrue(out.paragraphs.isEmpty())
        assertTrue(logged)                        // çağıran kod bilgilendirildi
    }
}
