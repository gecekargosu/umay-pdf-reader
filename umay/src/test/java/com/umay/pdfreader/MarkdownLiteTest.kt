package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v13 (AGENT 22 — A2) — MarkdownLite saf dönüşüm testleri.
 * AI özet/sohbet çıktısı Markdown/LaTeX içerir; TTS ve dışa aktarım ÖNCESİ
 * düz metne çevrilmelidir (motorlar `**` ve `\alpha` gibi artıkları okumaz).
 */
class MarkdownLiteTest {

    @Test
    fun latex_sembolleri_unicodea_cevrilir() {
        assertEquals("α β γ θ λ π σ ω", MarkdownLite.latexToPlain("\\alpha \\beta \\gamma \\theta \\lambda \\pi \\sigma \\omega"))
        assertEquals("x × y ÷ z", MarkdownLite.latexToPlain("x \\times y \\div z"))
        assertEquals("a ≤ b ≥ c ≠ d ≈ e", MarkdownLite.latexToPlain("a \\leq b \\geq c \\neq d \\approx e"))
        assertEquals("∑∫∞√(x)", MarkdownLite.latexToPlain("\\sum\\int\\infty\\sqrt{x}"))
    }

    @Test
    fun latex_fonksiyonlari() {
        assertEquals("(a)/(b)", MarkdownLite.latexToPlain("\\frac{a}{b}"))
        assertEquals("(x + 1)/(y - 2)", MarkdownLite.latexToPlain("\\frac{x + 1}{y - 2}"))
        assertEquals("√(x + 1)", MarkdownLite.latexToPlain("\\sqrt{x + 1}"))
        assertEquals("x²", MarkdownLite.latexToPlain("x^{2}"))
        assertEquals("P(A|B)", MarkdownLite.latexToPlain("P(A|B)"))
    }

    @Test
    fun markdown_temizleme_tts_icin() {
        val md = "# Başlık\n\n**kalın** ve *italik* metin\n\n```python\nprint('merhaba')\n```\n\n- madde 1\n- madde 2"
        val plain = MarkdownLite.mdToPlain(md)
        assertFalse(plain.contains("#"))
        assertFalse(plain.contains("**"))
        assertFalse(plain.contains("```"))
        assertTrue(plain.contains("Başlık"))
        assertTrue(plain.contains("kalın"))
        assertTrue(plain.contains("print('merhaba')"))
        assertTrue(plain.contains("• madde 1"))
    }

    @Test
    fun dolar_icindeki_formul_duz_metne_cevrilir() {
        val md = "Kayıp \$L = -\\sum_{i} y_i \\log(p_i)\$ fonksiyonu"
        val plain = MarkdownLite.mdToPlain(md)
        assertFalse(plain.contains("\\sum"))
        assertFalse(plain.contains("\$"))
        assertTrue(plain.contains("∑"))
        assertTrue(plain.contains("fonksiyonu"))
    }

    @Test
    fun bos_ve_normal_metin_guvenli() {
        assertEquals("", MarkdownLite.mdToPlain(""))
        assertEquals("Normal metin a−b.", MarkdownLite.mdToPlain("Normal metin a−b."))
        // Bozuk/ayar artığı çıktı asla fırlatmaz
        MarkdownLite.mdToPlain("``` \${\$\$ } ``` \\")
        MarkdownLite.latexToPlain("\\frac{}{} ^^ _{ ")
    }

    @Test
    fun cache_keeper_butce_ustu_silinir() {
        val dir = java.nio.file.Files.createTempDirectory("umay_cache_test").toFile()
        try {
            // 512 KB × 3 = 1.5 MB → 1 MB bütçede en az biri silinmeli
            val chunk = ByteArray(512 * 1024) { 1 }
            val f1 = java.io.File(dir, "edge_1.mp3").apply { writeBytes(chunk); setLastModified(1000L) }
            val f2 = java.io.File(dir, "edge_2.mp3").apply { writeBytes(chunk); setLastModified(2000L) }
            val f3 = java.io.File(dir, "edge_3.mp3").apply { writeBytes(chunk); setLastModified(3000L) }
            val total = CacheKeeper.trim(dir, "edge_", 1024L * 1024L)
            assertTrue("Bütçe aşılmamalı: $total", total <= 1024L * 1024L)
            assertFalse("En eski dosya silinmeli", f1.exists())
            assertTrue("En yeni dosya kalmalı", f3.exists())
            assertTrue("Orta dosya kalmalı", f2.exists())
            // 0 baytlık yarım dosya anında silinir
            val empty = java.io.File(dir, "edge_empty.mp3").apply { createNewFile() }
            CacheKeeper.trim(dir, "edge_", 1024L * 1024L)
            assertFalse("0 baytlık dosya silinmeli", empty.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
