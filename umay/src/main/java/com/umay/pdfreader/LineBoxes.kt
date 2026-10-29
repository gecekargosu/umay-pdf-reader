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

    /** v5.4 (ONARIM 3) — Sütun ayırma için her satırın KARAKTER konumları.
     *  Bazı PDF'lerde iki sütun AYNI yükseklikte olduğu için PDFBox ikisini
     *  tek satıra birleştiriyor ("…açıklama5.Toplam"). Sütunları yalnız
     *  karakter x koordinatlarıyla ayırabiliyoruz. */
    private val lineChars = ArrayList<TextPosition>()
    private val lineRuns = ArrayList<List<TextPosition>>()

    private val lineText = StringBuilder()
    private var x0 = Float.MAX_VALUE
    private var y0 = Float.MAX_VALUE
    private var x1 = -Float.MAX_VALUE
    private var y1 = -Float.MAX_VALUE

    /** Her sayfanın ÇIKTISINDAN ÖN çağır: biriktiriciyi sıfırlar. */
    fun beginPageCapture() {
        lines.clear()
        lineChars.clear()
        lineRuns.clear()
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
            lineChars.add(p)
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
        if (lineChars.isNotEmpty()) lineRuns.add(ArrayList(lineChars))
        lineChars.clear()
        lineText.setLength(0)
        x0 = Float.MAX_VALUE; y0 = Float.MAX_VALUE
        x1 = -Float.MAX_VALUE; y1 = -Float.MAX_VALUE
    }

    /**
     * v5.4 (ONARIM 3) — Sayfayı SÜTUN SIRASINA göre metne çevirir.
     *
     * PDFBox, aynı yükseklikteki iki sütunu TEK satıra birleştiriyor
     * ("…açıklama5.Toplam"). Yöntem: karakter merkezlerinin x histogramında
     * sayfanın orta bölgesinde (0.30–0.68) en geniş BOŞ dikey bandı bulur;
     * bu band sütun sınırıdır. Her satır bu sınırdan sola/sağa bölünür;
     * önce tüm sol sütun, sonra tüm sağ sütun yazılır.
     *
     * @return sütunlu metin; sayfa tek sütunluysa [null] (ham metin kullanılır)
     */
    fun columnText(): String? {
        val runs = lineRuns
        if (runs.size < 6) return null
        val pageW = pageSize().first
        if (pageW <= 0f) return null
        val all = runs.sumOf { it.size }
        if (all < 40) return null

        val bins = 100
        // v5.4 (ONARIM 3d) — SÜTUN SINIRI "OYLAMA" İLE BULUNUR.
        // Eski yöntem tüm sayfanın karakter histogramında tek bir boş bant
        // arıyordu; başlık/alt bilgi satırı sütun boşluğunu doldurduğu için
        // başarısız oluyordu. Şimdi HER SATIR kendi içinde boş bölgelerini
        // oylar: gerçek sütun aralığı çoğu gövde satırında boştur.
        val gapVotes = IntArray(bins)
        val lineHist = IntArray(bins)
        var bodyLines = 0
        for (run in runs) {
            java.util.Arrays.fill(lineHist, 0)
            var any = false
            for (p in run) {
                if (p.unicode.isNullOrBlank()) continue
                val cx = p.xDirAdj + p.widthDirAdj / 2f
                val i = ((cx / pageW) * bins).toInt().coerceIn(0, bins - 1)
                lineHist[i]++
                any = true
            }
            if (!any) continue
            bodyLines++
            for (i in (bins * 0.30).toInt()..(bins * 0.68).toInt()) {
                if (lineHist[i] == 0) gapVotes[i]++
            }
        }
        if (bodyLines < 4) return null
        // En çok satırda boş kalan bant = sütun aralığı
        var bestBin = -1
        var bestVotes = 0
        var curStart = -1
        var curVotes = 0
        for (i in (bins * 0.30).toInt()..(bins * 0.68).toInt()) {
            if (gapVotes[i] >= (bodyLines * 0.4f).toInt().coerceAtLeast(2)) {
                if (curStart < 0) { curStart = i; curVotes = 0 }
                curVotes++
                if (curVotes > bestVotes) {
                    bestVotes = curVotes
                    bestBin = curStart + curVotes / 2
                }
            } else {
                curStart = -1; curVotes = 0
            }
        }
        android.util.Log.d(
            "UmayCol",
            "pageW=$pageW runs=${runs.size} body=$bodyLines all=$all bestBin=$bestBin bestVotes=$bestVotes"
        )
        // Sütun aralığı satırların en az %40'ında boş değilse: tek sütun
        if (bestBin < 0) return null
        val boundary = ((bestBin + 0.5f) / bins) * pageW

        val leftLines = ArrayList<String>(runs.size)
        val rightLines = ArrayList<String>(runs.size)
        var leftChars = 0
        var rightChars = 0
        var splitLines = 0

        for (run in runs) {
            val l = ArrayList<TextPosition>(run.size)
            val r = ArrayList<TextPosition>(run.size)
            // v5.4 (ONARIM 3e) — Sınır bölgesinde karakteri OLAN satır
            // (ör. tam genişlikli alt bilgi) bölünmez; olduğu gibi kalır.
            var crossesGutter = false
            for (p in run) {
                if (p.unicode.isNullOrBlank()) continue
                val cx = p.xDirAdj + p.widthDirAdj / 2f
                val i = ((cx / pageW) * bins).toInt().coerceIn(0, bins - 1)
                if (i == bestBin) { crossesGutter = true; break }
            }
            if (crossesGutter) {
                l.addAll(run)
            } else {
                for (p in run) {
                    if (p.unicode.isNullOrEmpty()) continue
                    val cx = p.xDirAdj + p.widthDirAdj / 2f
                    if (cx < boundary) l.add(p) else r.add(p)
                }
            }
            if (l.isNotEmpty() && r.isNotEmpty()) splitLines++
            leftChars += l.size
            rightChars += r.size
            lineOf(l)?.let { leftLines.add(it) }
            lineOf(r)?.let { rightLines.add(it) }
        }
        android.util.Log.d(
            "UmayCol",
            "boundary=$boundary splitLines=$splitLines left=$leftChars right=$rightChars all=$all"
        )
        // Gerçekten iki sütun mu? (en az 2 satır bölünmüş + iki taraf da anlamlı)
        if (splitLines < 2 || rightChars < all * 0.15f || leftChars < all * 0.15f) return null

        val text = (leftLines + rightLines).joinToString("\n").trim()
        return text.ifEmpty { null }
    }

    /** Karakter listesini, kelime aralarına boşluk koyarak metne çevirir. */
    private fun lineOf(chars: List<TextPosition>): String? {
        if (chars.isEmpty()) return null
        val sb = StringBuilder()
        var prevEnd = Float.NaN
        var prevW = 0f
        for (p in chars) {
            val u = p.unicode ?: continue
            if (!prevEnd.isNaN()) {
                val gap = p.xDirAdj - prevEnd
                val ref = if (prevW > 0f) prevW else p.widthDirAdj
                if (gap > 0.30f * ref) sb.append(' ')
            }
            sb.append(u)
            prevEnd = p.xDirAdj + p.widthDirAdj
            prevW = p.widthDirAdj
        }
        return sb.toString().trim().ifEmpty { null }
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
