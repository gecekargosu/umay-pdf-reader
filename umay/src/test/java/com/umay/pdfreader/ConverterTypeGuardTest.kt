package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v37 — BOZUK KİTAP SALGINI REGRESYONU.
 *
 * Gerçek olay: SAF/file:// aktarımında dosya adı okunamayınca ad "source.pdf"
 * oluyordu; dönüştürücü türü yalnız uzantıdan seçtiği için `else` dalı çalışıp
 * **PPTX'in ZIP baytları düz metin** sayılıyordu. 60 slaytlık sunum 1460
 * sayfalık, sesli okumada "PK ! … ppt/slides/slide2.xml" diyen bir kitaba
 * dönüşüyordu.
 *
 * Bu testler içeriğe bakan tür tespitini ve "ikili dosya metin değildir"
 * kapısını kilitler.
 */
class ConverterTypeGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Uzantısı ".pdf" olan ama içi PPTX paketi olan dosya üretir. */
    private fun pptxNamedAs(name: String): File {
        val slide = """<?xml version="1.0" encoding="UTF-8"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld><p:spTree><p:sp><p:txBody>
    <a:p><a:r><a:t>Lineer Cebir Dersi</a:t></a:r></a:p>
    <a:p><a:r><a:t>Matris carpani</a:t></a:r></a:p>
  </p:txBody></p:sp></p:spTree></p:cSld>
</p:sld>"""
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { zos ->
            fun put(entry: String, content: String) {
                zos.putNextEntry(ZipEntry(entry))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("[Content_Types].xml", "<Types/>")
            put("ppt/slides/slide1.xml", slide)
        }
        return f
    }

    @Test
    fun `uzantisi pdf olan pptx icerikten taninir`() {
        val f = pptxNamedAs("source.pdf")
        assertEquals("pptx", DocumentConverter.effectiveExtension(f, "source.pdf"))
        val paras = DocumentConverter.paragraphs(f, "source.pdf")
        val text = paras.joinToString("\n")
        assertTrue("Slayt metni yok: $paras", text.contains("Lineer Cebir Dersi"))
        assertTrue("ZIP baytı metne sızmış", !text.contains("ppt/slides/"))
    }

    @Test
    fun `uzantisiz pptx de icerikten taninir`() {
        val f = pptxNamedAs("kitap")
        val paras = DocumentConverter.paragraphs(f, "kitap")
        assertTrue(paras.joinToString("\n").contains("Matris carpani"))
    }

    @Test
    fun `desteklenmeyen zip paketi cop metin uretmez`() {
        val f = File(tmp.root, "tablo.xlsx")
        ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write("<workbook/>".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write("<worksheet/>".toByteArray())
            zos.closeEntry()
        }
        var failed = false
        try {
            DocumentConverter.paragraphs(f, "tablo.xlsx")
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue("XLSX metin gibi okunmamalı", failed)
    }

    @Test
    fun `ikili dosya metin sanilmaz`() {
        val f = File(tmp.root, "bozuk.txt")
        val rnd = java.util.Random(42)
        val bytes = ByteArray(8192).also { rnd.nextBytes(it) }
        f.writeBytes(bytes)
        var failed = false
        try {
            DocumentConverter.paragraphs(f, "bozuk.txt")
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Rastgele baytlar metin olarak kabul edildi", failed)
    }

    @Test
    fun `gercek turkce metin hala okunur`() {
        val f = File(tmp.root, "not.txt")
        f.writeText("Veri yapıları dersinde ağaç yapıları işlendi.\nİkinci satır: yığın ve kuyruk.")
        val paras = DocumentConverter.paragraphs(f, "not.txt")
        assertTrue(paras.joinToString("\n").contains("ağaç yapıları"))
    }
}
