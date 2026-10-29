package com.umay.pdfreader

import android.content.Context

/**
 * v32 — GERÇEK OKUNMA YÜZDESİ.
 *
 * Sorun: yüzde, "açık sayfa / toplam sayfa" ile hesaplanıyordu. 100 sayfalık
 * kitabın 99. sayfasına atlayınca " %99 okundu" görünüyordu — oysa tek bir
 * sayfa okunmuştu.
 *
 * Çözüm: kitap başına hangi sayfaların gerçekten AÇILDIĞI/OKUNDUĞU tutulur
 * (`0/1` dizisi, kitap başına ~1 KB). Yüzde = okunan sayfa / toplam sayfa.
 * Konum/yer imi bilgisi değişmez; yalnız gösterilen oran doğru olur.
 */
object ReadProgressStore {

    private const val MAX_PAGES = 20_000

    private fun key(bookId: String) = "read_pages_$bookId"

    /** Sayfa okundu olarak işaretlenir (0 tabanlı). Ucuz: yalnız değişince yazar. */
    fun markRead(ctx: Context, bookId: String, page: Int, pageCount: Int) {
        if (bookId.isBlank() || page < 0) return
        if (pageCount <= 0 || page > pageCount + 5) return
        try {
            if (page >= MAX_PAGES) return
            val bits = load(ctx, bookId)
            // v36 — hesap ReadProgressBits'te (saf, birim testli)
            val next = ReadProgressBits.mark(bits, page, pageCount)
            if (next == bits) return   // zaten işaretliydi → diske yazma
            AppPrefs.putString(ctx, key(bookId), next)
        } catch (_: Throwable) {
        }
    }

    /** Aynı anda birden çok sayfa işaretlenir (arka plan okuma / toplu). */
    fun markRange(ctx: Context, bookId: String, from: Int, to: Int, pageCount: Int) {
        if (to < from) return
        for (p in from..to) markRead(ctx, bookId, p, pageCount)
    }

    /**
     * Eski sürümden gelen kitaplar için tek seferlik tohumlama: kayıt yoksa
     * yalnız açık sayfa işaretlenir (geçmiş okumalar uydurulmaz).
     */
    fun seedIfEmpty(ctx: Context, bookId: String, currentPage: Int?, pageCount: Int) {
        if (pageCount <= 0) return
        val p = currentPage ?: return
        if (p < 0) return
        // v33 — ESKİ SÜRÜMDEN GELEN KİTAP: kullanıcı zaten sayfa p'ye kadar
        // okumuş görünüyor; tek sayfa işaretlemek "önce %34 → şimdi %0"
        // düşüşüne yol açıyordu. Kitap başına BİR KEZ 0..p aralığı tohumlanır.
        // (Sayfa tek tek okunmuş olsa bile ilk tohumlama tüm geçmişi kapsar.)
        val seedKey = "read_seeded_$bookId"
        if (AppPrefs.getBool(ctx, seedKey, false)) return
        val last = p.coerceAtMost(pageCount - 1)
        for (i in 0..last) markRead(ctx, bookId, i, pageCount)
        AppPrefs.putBool(ctx, seedKey, true)
    }

    /** Okunma yüzdesi (0..100). Hiç okunmadıysa 0. */
    fun percent(ctx: Context, bookId: String, pageCount: Int): Int =
        ReadProgressBits.percent(load(ctx, bookId), pageCount)

    /** Okunan sayfa sayısı (tanılama ve rozet için). */
    fun readCount(ctx: Context, bookId: String, pageCount: Int): Int =
        ReadProgressBits.count(load(ctx, bookId), pageCount)

    private fun load(ctx: Context, bookId: String): String =
        try {
            AppPrefs.getString(ctx, key(bookId))
        } catch (_: Throwable) {
            ""
        }
}
