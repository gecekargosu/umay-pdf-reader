package com.umay.pdfreader

/**
 * v5.4 (ONARIM) — İKİ SÜTUNLU SAYFALARDA OKUMA SIRASI.
 *
 * Sorun: slayt/kitap sayfaları iki sütunlu olduğunda PDFBox metni
 * satır satır (y eksenine göre) diziyor → okuyucu "1. madde, 5. madde,
 * 2. madde…" diye atlıyordu. Sesli okuma ve transkript anlaşılmaz oluyordu.
 *
 * Çözüm: elimizde her satırın normalize (0..1) kutusu var ([LineBox]).
 * Sayfa gerçekten iki sütuna ayrılmışsa (satırların hemen hepsi ya sol ya
 * sağ yarıda, ortayı kesen satır yok) satırlar ÖNCE sol sütun, SONRA sağ
 * sütun olacak biçimde yeniden dizilir. Tam genişlikteki satırlar (başlık,
 * alt bilgi) bölüm sınırı sayılır; bulundukları yerde korunurlar.
 *
 * Tek sütunlu sayfalarda hiçbir şey değişmez (metin aynen döner).
 */
object ColumnOrder {

    /** Orta çizgiyi kesen satır = tam genişlik (başlık/alt bilgi). */
    private const val MID = 0.5f
    private const val TOL = 0.04f

    /**
     * Sayfa metnini sütun sırasına göre yeniden üretir.
     * @param raw dosyadan okunan ham metin (hiçbir zaman null değil)
     * @param boxes o sayfanın satır kutuları (yoksa boş liste)
     * @return sıralanmış metin; sütun tespiti yoksa [raw] aynen döner
     */
    fun reorderText(raw: String, boxes: List<LineBox>): String {
        if (boxes.size < 6) return raw
        val ordered = reorder(boxes) ?: return raw
        val rebuilt = ordered
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        // Aşırı metin kaybı olduysa (kutu metinleri eksik/bozuksa) ham metne dön
        if (rebuilt.length < (raw.trim().length * 0.6f)) return raw
        return rebuilt
    }

    /**
     * @return sütun sırasına dizilmiş satırlar; sayfa iki sütunlu DEĞİLSE null
     */
    fun reorder(lines: List<LineBox>): List<LineBox>? {
        if (lines.size < 6) return null

        val left = ArrayList<LineBox>()
        val right = ArrayList<LineBox>()
        val full = ArrayList<LineBox>()

        for (l in lines) {
            val spansMiddle = l.x0 < MID - TOL && l.x1 > MID + TOL
            when {
                spansMiddle -> full.add(l)
                l.x1 <= MID + TOL -> left.add(l)
                l.x0 >= MID - TOL -> right.add(l)
                else -> full.add(l)   // ortada duran kısa satır: sınır say
            }
        }
        // İki taraf da "gerçek sütun" olacak kadar dolu değilse dokunma
        if (left.size < 3 || right.size < 3) return null

        val out = ArrayList<LineBox>(lines.size)
        left.sortBy { it.y0 }
        right.sortBy { it.y0 }
        val boundaries = full.sortedBy { it.y0 }

        var prevY = -1f
        for (f in boundaries) {
            out += left.filter { it.y0 >= prevY && it.y0 < f.y0 }.sortedBy { it.y0 }
            out += right.filter { it.y0 >= prevY && it.y0 < f.y0 }.sortedBy { it.y0 }
            out += f
            prevY = f.y0
        }
        out += left.filter { it.y0 >= prevY }.sortedBy { it.y0 }
        out += right.filter { it.y0 >= prevY }.sortedBy { it.y0 }

        return if (out.size == lines.size) out else null
    }
}
