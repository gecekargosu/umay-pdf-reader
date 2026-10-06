package com.umay.pdfreader

import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Karaoke (cümle vurgusu) altyapısı.
 *
 * Bir metin satırının sayfa üzerindeki sınırlayıcı dikdörtgeni:
 *  - koordinatlar 0..1 arası normalize (sol-üst orijin) → PDF/OCR
 *    ve ekran çözünürlüğünden bağımsız
 *  - text: o satırın metni (eşleştirme için)
 *
 * PDF yolunda [BoxStripper] PDFTextStripper ile aynı geçişte üretilir;
 * OCR yolunda ML Kit satır kutularından. Sonuç `boxes_NNNN.txt` dosyasında:
 *     x0\ty0\tx1\ty1\tsatır metni   (her satır bir çizgi)
 */
data class LineBox(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val text: String
)

object LineBoxes {

    fun encodeAll(lines: List<LineBox>): String = lines.joinToString("\n") { b ->
        "%.3f\t%.3f\t%.3f\t%.3f\t%s".format(
            java.util.Locale.US, b.x0, b.y0, b.x1, b.y1,
            b.text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        )
    }

    fun decode(src: String): List<LineBox> {
        return try {
            src.lineSequence().mapNotNull { row ->
                if (row.isEmpty()) return@mapNotNull null
                val p = row.split('\t')
                if (p.size < 5) return@mapNotNull null
                LineBox(
                    p[0].toFloatOrNull() ?: return@mapNotNull null,
                    p[1].toFloatOrNull() ?: return@mapNotNull null,
                    p[2].toFloatOrNull() ?: return@mapNotNull null,
                    p[3].toFloatOrNull() ?: return@mapNotNull null,
                    p[4]
                )
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * PDFTextStripper'ın çıktı satırlarını KONUMLARIYLA birlikte kaydeder.
 *
 * DİKKAT: metin çıkarma davranışını değiştirmez — super çağrıları aynen
 * devam eder, sadece yana bilgi toplar. Böylece sayfa metni dosyaları
 * eskisiyle birebir aynı kalır.
 *
 * Koordinatlar: TextPosition'ın dirAdj (sol-üst orijin) değerleri,
 * sayfa mediaBox'ına bölünerek normalize edilir (90/270° döndürülmüş
 * sayfalarda en/boy takas edilir).
 */
class BoxStripper : PDFTextStripper() {

    val lines = mutableListOf<LineBox>()

    private val lineText = StringBuilder()
    private var x0 = Float.MAX_VALUE
    private var y0 = Float.MAX_VALUE
    private var x1 = -Float.MAX_VALUE
    private var y1 = -Float.MAX_VALUE

    /** Her sayfanın ÇIKTISINDAN ÖN çağır: biriktiriciyi sıfırlar. */
    fun beginPageCapture() {
        lines.clear()
        lineText.setLength(0)
        x0 = Float.MAX_VALUE; y0 = Float.MAX_VALUE
        x1 = -Float.MAX_VALUE; y1 = -Float.MAX_VALUE
    }

    /** Son satırı da damgalayıp kutuları döndürür. */
    fun finishCapture(): List<LineBox> {
        flushLine()
        return lines
    }

    override fun writeString(string: String, textPositions: List<TextPosition>) {
        super.writeString(string, textPositions)
        lineText.append(string)
        for (p in textPositions) {
            val px = p.xDirAdj
            val py = p.yDirAdj
            // Dönmemiş sayfalarda width/height yeterli (döndürme için
            // dirAdj x/y zaten düzeltilmiştir)
            val pw = p.widthDirAdj
            val ph = p.height
            if (px < x0) x0 = px
            if (py < y0) y0 = py
            if (px + pw > x1) x1 = px + pw
            if (py + ph > y1) y1 = py + ph
        }
    }

    override fun writeLineSeparator() {
        super.writeLineSeparator()
        flushLine()
    }

    private fun flushLine() {
        if (lineText.isNotEmpty() && x0 <= x1 && y0 <= y1) {
            val (w, h) = pageSize()
            if (w > 0f && h > 0f) {
                lines.add(
                    LineBox(
                        (x0 / w).coerceIn(0f, 1f),
                        (y0 / h).coerceIn(0f, 1f),
                        (x1 / w).coerceIn(0f, 1f),
                        (y1 / h).coerceIn(0f, 1f),
                        lineText.toString()
                    )
                )
            }
        }
        lineText.setLength(0)
        x0 = Float.MAX_VALUE; y0 = Float.MAX_VALUE
        x1 = -Float.MAX_VALUE; y1 = -Float.MAX_VALUE
    }

    /** Sayfa boyutu (döndürmeye göre en/boy takası). */
    private fun pageSize(): Pair<Float, Float> {
        return try {
            val page = currentPage ?: return Pair(0f, 0f)
            val mb = page.mediaBox ?: return Pair(0f, 0f)
            val rot = try { page.rotation } catch (e: Exception) { 0 }
            if (rot % 180 == 90) Pair(mb.height, mb.width)
            else Pair(mb.width, mb.height)
        } catch (e: Exception) {
            Pair(0f, 0f)
        }
    }
}
