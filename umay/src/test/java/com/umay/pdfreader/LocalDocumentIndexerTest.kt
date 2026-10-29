package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v5.2 (ADIM C.2 / #22+#25) — Yerel indeksleyici saf çekirdek testleri.
 */
class LocalDocumentIndexerTest {

    @Test
    fun `bilinen dosyalar adaydan cikarilir`() {
        val known = setOf("Matematik.pdf", "Fizik.epub")
        val found = listOf("Matematik.pdf", "YeniDers.pdf", "Fizik.epub")
        assertEquals(listOf("YeniDers.pdf"), LocalDocumentIndexer.diff(known, found))
    }

    @Test
    fun `kucuk buyuk harf duyarsiz`() {
        val known = setOf("matematik.pdf")
        val found = listOf("MATEMATIK.PDF", "Yeni.pdf")
        assertEquals(listOf("Yeni.pdf"), LocalDocumentIndexer.diff(known, found))
    }

    @Test
    fun `tekrarli adlar ayıklanır`() {
        val found = listOf("a.pdf", "a.pdf", "b.pdf")
        assertEquals(listOf("a.pdf", "b.pdf"), LocalDocumentIndexer.diff(emptySet(), found))
    }

    @Test
    fun `desteklenen uzantilar epub dahil`() {
        assertTrue("epub" in LocalDocumentIndexer.INDEXED_EXTS)
        assertTrue("pdf" in LocalDocumentIndexer.INDEXED_EXTS)
        assertTrue("exe" !in LocalDocumentIndexer.INDEXED_EXTS)
    }

    @Test
    fun `bos klasor taramasi bos liste`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "umay-indexer-test")
        tmp.mkdirs()
        val out = LocalDocumentIndexer.scanNewFiles(listOf(tmp), emptySet())
        assertTrue(out.isEmpty())
        tmp.delete()
    }
}
