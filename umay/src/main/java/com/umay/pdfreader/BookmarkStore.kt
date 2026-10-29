package com.umay.pdfreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v12 (YENİ ÖZELLİK) — YER İMİ & SAYFA NOTLARI.
 *
 * Okuma ekranındaki 📌 butonu ile:
 *  - Sayfaya yer imi (pin) koyulur / kaldırılır.
 *  - Sayfaya kısa ders notu eklenebilir (örn. "Bu kısım sınavda çıktı").
 *
 * Depolama: SharedPreferences içinde kitap başına JSON dizisi.
 * Her işlem try/catch içinde; bozuk JSON asla çökertmez (boş liste).
 *
 * Kayıt: { page: Int, note: String, at: Long }
 */
object BookmarkStore {

    private const val PREFS = "umay_bookmarks"

    /** Bir yer imi kaydı. */
    data class Bookmark(val page: Int, val note: String, val at: Long)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Kitabın tüm yer imilerini sayfa numarasına göre sıralı döndürür. */
    fun all(ctx: Context, bookId: String): List<Bookmark> {
        return try {
            val raw = prefs(ctx).getString(bookId, null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Bookmark(
                    page = o.optInt("page", -1),
                    note = o.optString("note", ""),
                    at = o.optLong("at", 0L)
                )
            }.filter { it.page >= 0 }.sortedBy { it.page }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Bu sayfa yer imili mi? */
    fun has(ctx: Context, bookId: String, page: Int): Boolean =
        all(ctx, bookId).any { it.page == page }

    /** Yer imini ekle (aynı sayfa zaten varsa notu güncelle). */
    fun add(ctx: Context, bookId: String, page: Int, note: String = "") {
        try {
            val list = all(ctx, bookId).filterNot { it.page == page } +
                Bookmark(page, note.trim(), System.currentTimeMillis())
            save(ctx, bookId, list)
        } catch (t: Throwable) {
            BookStore.logError(ctx, "BookmarkStore.add", t)
        }
    }

    /** Yer imini kaldır (notu da götürür). */
    fun remove(ctx: Context, bookId: String, page: Int) {
        try {
            save(ctx, bookId, all(ctx, bookId).filterNot { it.page == page })
        } catch (t: Throwable) {
            BookStore.logError(ctx, "BookmarkStore.remove", t)
        }
    }

    /** Bir sayfanın notunu günceller (yer imi yoksa ekler). */
    fun setNote(ctx: Context, bookId: String, page: Int, note: String) {
        val existing = all(ctx, bookId).firstOrNull { it.page == page }
        if (existing != null && note.isBlank()) {
            // Not silindi, yer imi duruyor → boş notla güncelle
            add(ctx, bookId, page, "")
        } else {
            add(ctx, bookId, page, note)
        }
    }

    private fun save(ctx: Context, bookId: String, list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(
                JSONObject()
                    .put("page", b.page)
                    .put("note", b.note)
                    .put("at", b.at)
            )
        }
        prefs(ctx).edit().putString(bookId, arr.toString()).apply()
    }
}
