package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v15 (ADIM B.2 / #4) — EPUB → paragraf çıkarma testi.
 *
 * Geçici dizinde minimal ama GERÇEK bir EPUB paketi üretilir
 * (container.xml → OPF → spine sırası) ve DocumentConverter'ın
 * metin çıkarmasının spine sırasına uyduğunu doğrular.
 */
class EpubConverterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildEpub(): File {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <manifest>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>"""
        val container = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
        val ch1 = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><body>
  <h1>Bolum Bir</h1>
  <p>Ilk paragraf metni.</p>
  <p style="color:red">Ikinci paragraf &amp; ikinci satir.</p>
  <img src="cover.png" alt="atlanmali"/>
</body></html>"""
        val ch2 = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><body>
  <h2>Bolum Iki</h2>
  <li>Oge bir</li>
  <li>Oge iki</li>
</body></html>"""

        val f = File(tmp.root, "ornek.epub")
        ZipOutputStream(ByteArrayOutputStream().let { bos ->
            f.outputStream().also { out ->
                val zos = ZipOutputStream(out)
                fun put(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                put("mimetype", "application/epub+zip")
                put("META-INF/container.xml", container)
                put("OEBPS/content.opf", opf)
                put("OEBPS/ch1.xhtml", ch1)
                put("OEBPS/ch2.xhtml", ch2)
                zos.close()
            }
            bos
        })
        return f
    }

    @Test
    fun `epub paragraflari spine sirasina gore cikarilir`() {
        val epub = buildEpub()
        val paragraphs = DocumentConverter.epubParagraphsForTest(epub)

        assertTrue("Paragraf bulunamadı", paragraphs.isNotEmpty())
        // ch1 spine'de önce → "Ilk paragraf metni." ch2'den önce gelmeli
        val i1 = paragraphs.indexOf("Ilk paragraf metni.")
        val i2 = paragraphs.indexOf("Oge bir")
        assertTrue("1. bölüm paragrafı yok: $paragraphs", i1 >= 0)
        assertTrue("2. bölüm paragrafı yok: $paragraphs", i2 >= 0)
        assertTrue("Spine sırası bozuk", i1 < i2)
    }

    @Test
    fun `html varliklari cozulur ve etiketler temizlenir`() {
        val paragraphs = DocumentConverter.epubParagraphsForTest(buildEpub())
        assertTrue(
            "&amp; çözülmemiş: $paragraphs",
            paragraphs.contains("Ikinci paragraf & ikinci satir.")
        )
        // <img> atlanmalı, etiket metni sızmamalı
        assertTrue(
            "Etiket/alt metni sızmış: $paragraphs",
            paragraphs.none { it.contains("<img") || it.contains("atlanmali") }
        )
    }

    @Test
    fun `epub isSupported listesinde`() {
        assertTrue(DocumentConverter.isSupported("ders_kitabi.epub"))
        assertEquals("epub", DocumentConverter.extension("ders_kitabi.epub"))
    }
}
