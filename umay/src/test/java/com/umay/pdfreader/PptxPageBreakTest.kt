package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v37 — PPTX → PDF: HER SLAYT AYRI SAYFA.
 *
 * Gerçek olay: onarım sonrası 60 slaytlık dersler \"1 sayfa\" görünüyordu
 * (tüm slayt metni tek A4'e sığdığı için). Sayfa numarası, okunma yüzdesi ve
 * ilerleme anlamsızlaşıyordu.
 */
class PptxPageBreakTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildPptx(slides: Int): File {
        val f = File(tmp.root, "sunum.pptx")
        ZipOutputStream(f.outputStream()).use { zos ->
            fun put(entry: String, content: String) {
                zos.putNextEntry(ZipEntry(entry))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("[Content_Types].xml", "<Types/>")
            for (i in 1..slides) {
                val xml = """<?xml version="1.0" encoding="UTF-8"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld><p:spTree><p:sp><p:txBody>
    <a:p><a:r><a:t>Slayt $i basligi</a:t></a:r></a:p>
    <a:p><a:r><a:t>Slayt $i icerigi</a:t></a:r></a:p>
  </p:txBody></p:sp></p:spTree></p:cSld>
</p:sld>"""
                put("ppt/slides/slide$i.xml", xml)
            }
        }
        return f
    }

    /**
     * v39 — BELGE MODU: PPTX PDF'e çevrilmez; slayt başına bir METİN sayfası
     * üretilir. Sayfa sayısı yine slayt sayısına eşit olmalı (ilerleme/yüzde).
     */
    @Test
    fun `her slayt yeni sayfada baslar`() {
        val src = buildPptx(5)
        val pages = TextPager.paginate(DocumentConverter.paragraphs(src, "sunum.pptx"))
        assertEquals("Slayt sayısı kadar sayfa olmalı", 5, pages.size)
        assertTrue("İlk sayfa 1. slaytı içermeli", pages[0].contains("Slayt 1"))
        assertTrue("Son sayfa 5. slaytı içermeli", pages[4].contains("Slayt 5"))
    }

    /** v39 — Ofis belgesi PDF'e çevrilmez; orijinal dosya korunur. */
    @Test
    fun `pptx pdf'e cevrilmez`() {
        val src = buildPptx(2)
        val out = File(tmp.root, "cikti.pdf")
        try {
            DocumentConverter.convert(src, out, "sunum.pptx")
            fail("PPTX çevrilmemeliydi")
        } catch (_: IllegalStateException) {
            // beklenen: kilit devrede
        }
        assertFalse("Çıktı dosyası oluşmamalı", out.exists())
    }

    @Test
    fun `paragraflarda sayfa isareti sizmaz`() {
        val src = buildPptx(3)
        val paras = DocumentConverter.paragraphs(src, "sunum.pptx")
        assertEquals(
            "Sayfa işareti metne sızmış",
            0,
            paras.count { it.contains("UMAY_SAYFA") }
        )
    }
}
