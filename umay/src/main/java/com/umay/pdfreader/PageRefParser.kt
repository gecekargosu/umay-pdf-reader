package com.umay.pdfreader

/**
 * v36.1 — SOHBET YANITINDAKİ SAYFA BAĞLANTILARINI BULMA (saf çekirdek).
 *
 * AI yanıtları kaynak gösterirken "(s. 42)", "p. 42" veya "Sayfa 42" yazar.
 * Bu bağlantıların **nerede** olduğunu bulmak `ChatActivity` içine gömülüydü;
 * cihaz olmadan test edilemiyordu. Artık ayrı ve saf.
 *
 * Kurallar:
 *  • Yalnız gerçekten var olan sayfaya bağlantı kurulur (kitapta 100 sayfa
 *    varsa "s. 140" tıklanabilir olmaz — uydurma numara tıklanmaz).
 *  • Toplam sayfa bilinmiyorsa (0) sınır uygulanmaz.
 *  • Aynı aralık iki kez döndürülmez.
 */
object PageRefParser {

    /** Metindeki tıklanabilir sayfa aralığı. [end] hariçtir. */
    data class Ref(val start: Int, val end: Int, val page: Int)

    private val PATTERN = Regex("(?i)\\(?\\b(?:s|p)\\.\\s?(\\d{1,4})\\)?|\\bsayfa\\s+(\\d{1,4})")

    /**
     * @param text yanıt metni (markdown çıktısı da olur)
     * @param totalPages kitabın sayfa sayısı; 0 = bilinmiyor
     */
    fun find(text: CharSequence, totalPages: Int = 0): List<Ref> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Ref>()
        var lastEnd = -1
        for (m in PATTERN.findAll(text.toString())) {
            val page = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() ?: continue
            if (page <= 0) continue
            if (totalPages > 0 && page > totalPages) continue
            if (m.range.first <= lastEnd) continue
            out.add(Ref(m.range.first, m.range.last + 1, page))
            lastEnd = m.range.last
        }
        return out
    }
}
