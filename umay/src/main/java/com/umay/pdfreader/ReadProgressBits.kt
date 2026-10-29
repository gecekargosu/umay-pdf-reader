package com.umay.pdfreader

/**
 * v36 — OKUMA İLERLEMESİNİN SAF (Android'siz) ÇEKİRDEĞİ.
 *
 * `ReadProgressStore` okunan sayfaları `"0101…"` bit dizisi olarak tutar.
 * Bu mantık önceden `Context`e bağlıydı, bu yüzden **birim testi yazılamıyordu**
 * (bkz. 31-ELESTIRI.md · test eksikliği). Artık saf fonksiyonlar burada;
 * yüzde hesabı cihaz olmadan test edilebiliyor.
 *
 * Kural hatırlatması: yüzde = **gerçekten okunan sayfa / toplam sayfa**.
 * 100 sayfalık kitapta 99. sayfaya atlamak %99 değil %1 demektir.
 */
object ReadProgressBits {

    /** Sayfa sınırı dışındaki gürültü işaretlemeleri yok say (bozuk metadata). */
    private const val TOLERANCE = 5

    /**
     * [page] (0 tabanlı) sayfasını okundu olarak işaretler.
     * Değişiklik yoksa **aynı** metin döner (gereksiz diske yazma olmasın).
     */
    fun mark(bits: String, page: Int, pageCount: Int): String {
        if (page < 0 || pageCount <= 0) return bits
        if (page > pageCount + TOLERANCE) return bits
        val target = maxOf(pageCount, bits.length)
        val sb = StringBuilder(bits)
        while (sb.length < target) sb.append('0')
        if (page >= sb.length) return bits
        if (sb[page] == '1') return bits
        sb.setCharAt(page, '1')
        return sb.toString()
    }

    /** Okundu işaretli sayfa sayısı (toplam sayfa sınırını aşmaz). */
    fun count(bits: String, pageCount: Int): Int {
        if (bits.isEmpty() || pageCount <= 0) return 0
        var n = 0
        val limit = minOf(bits.length, pageCount)
        for (i in 0 until limit) if (bits[i] == '1') n++
        return n
    }

    /** Okunma yüzdesi (0..100). Hiç okunmadıysa 0. */
    fun percent(bits: String, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        val n = count(bits, pageCount)
        if (n == 0) return 0
        return ((n * 100) / pageCount).coerceIn(0, 100)
    }
}
