package com.umay.pdfreader

import org.junit.Test
import java.io.File

/**
 * GEÇİCİ TEŞHİS TESTİ (v36.8) — gerçek dosyalarla dönüştürücüyü dener.
 * Soru: PPTX/DOCX → PDF dönüşümü metni doğru çıkarıyor mu, yoksa ZIP
 * baytlarını mı metin sanıyor? Sonuç stdout'a yazılır.
 */
class RealDocProbeTest {

    private fun probe(path: String) {
        val f = File(path)
        if (!f.exists()) {
            println("PROBE yok: $path")
            return
        }
        val name = f.name
        try {
            val paras = DocumentConverter.paragraphs(f, name)
            val text = paras.joinToString("\n")
            val letters = text.count { it.isLetter() }
            val ratio = if (text.isEmpty()) 0.0 else letters.toDouble() / text.length
            println("PROBE OK  $name  paragraf=${paras.size} harfOrani=%.2f".format(ratio))
            println("PROBE ILK: " + text.take(220).replace("\n", " | "))
        } catch (t: Throwable) {
            println("PROBE HATA $name -> ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @Test
    fun probeRealDocuments() {
        probe("C:/Users/isitm/Desktop/3.DÖNEM/Makine Öğrenmesi/ML 2.pptx")
        probe("C:/Users/isitm/Desktop/3.DÖNEM/Nesnel Tabanlı Programlama JAVA ile/BLG205_NTP_Donem_Projesi_Yonergesi.docx")
    }
}
