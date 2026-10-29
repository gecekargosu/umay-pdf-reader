package com.umay.pdfreader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v17 (ONARIM) regresyon testi — cihazdaki GERÇEK hata (umay_error.log):
 *
 *   java.lang.IllegalArgumentException: No glyph for U+0643 (ك) in font
 *   Roboto-Regular at PDPageContentStream.showText
 *   at DocumentConverter.writePdf → BookStore.importPdf
 *
 * Yani Arapça/Farsça içerikli metin PDF'e çevrilirken İÇE AKTARMA
 * TAMAMEN düşüyordu (dosya kütüphaneye hiç girmiyordu).
 *
 * Bu test, çevirinin asla glyph hatasıyla çökmemesini ve PDF'in yazılmasını
 * guarantee eder (desteklenmeyen karakterler font adayı/ayarla aşılır).
 */
class DocumentConverterGlyphTest {

    @Test
    fun `arapca metin pdf cevrilir glyph hatasi firlatmaz`() {
        val dir = createTempDir(prefix = "umay-glyph")
        try {
            val src = File(dir, "ders.txt")
            src.writeText(
                buildString {
                    appendLine("Arapça ders notları")
                    appendLine("السلام عليكم ورحمة الله")
                    appendLine("ك گ ب ث — Arapça alfabe örnekleri")
                    appendLine("Türkçe satır: ğüzel şçö İ")
                    appendLine("Karışık satır:conomics السلام world")
                },
                Charsets.UTF_8
            )
            val dest = File(dir, "cikti.pdf")

            // Eski kodda burada IllegalArgumentException fırlıyordu:
            val pages = DocumentConverter.convert(src, dest, "ders.txt")

            assertTrue("En az 1 sayfa üretilmeli (aldı: $pages)", pages > 0)
            assertTrue("PDF dosyası diske yazılmalı", dest.exists() && dest.length() > 0L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `sadece arapca icerikli dosya da cevrilir`() {
        val dir = createTempDir(prefix = "umay-glyph2")
        try {
            val src = File(dir, "kuran.txt")
            src.writeText("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ\n", Charsets.UTF_8)
            val dest = File(dir, "kuran.pdf")
            val pages = DocumentConverter.convert(src, dest, "kuran.txt")
            assertTrue(pages > 0)
            assertTrue(dest.length() > 0L)
        } finally {
            dir.deleteRecursively()
        }
    }
}
