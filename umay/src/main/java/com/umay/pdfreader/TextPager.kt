package com.umay.pdfreader

/**
 * v38 — BELGE SAYFALAMA (dönüştürmeden).
 *
 * DOCX/PPTX/DOC/PPT artık **PDF'e çevrilmiyor**; orijinal dosya olduğu gibi
 * saklanıyor. Sesli okuma ve ilerleme için metin sayfalara bölünür; böylece
 * dosyanın kendisi (grafikler, renkler, şekiller) hiç değişmez.
 *
 * Sayfa boyu ~1400 karakter: TTS bir sayfayı makul sürede okur, yüzde/konum
 * hesabı da anlamlı kalır.
 */
object TextPager {

    fun paginate(paragraphs: List<String>, maxChars: Int = 1400): List<String> {
        val pages = ArrayList<String>()
        val sb = StringBuilder()

        fun flush() {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) pages.add(t)
            sb.setLength(0)
        }

        for (raw in paragraphs) {
            val p = raw.trimEnd()
            if (p.isBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                continue
            }
            // v39 — SLAYTLI BELGE: her slayt yeni sayfa başlatır. Böylece sayfa
            // numarası/okunma yüzdesi slaytla birebir eşleşir (37 slaytlık sunum
            // artık "2 sayfa" görünmez) ve görsel görünümdeki slayt numarasıyla
            // aynı yere gider.
            if (p.startsWith("— Slayt ")) flush()
            if (p.length > maxChars) {
                flush()
                var i = 0
                while (i < p.length) {
                    val end = minOf(i + maxChars, p.length)
                    pages.add(p.substring(i, end))
                    i = end
                }
                continue
            }
            if (sb.length + p.length + 1 > maxChars) flush()
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(p)
        }
        flush()
        return if (pages.isEmpty()) listOf("") else pages
    }
}
