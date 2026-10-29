package com.umay.pdfreader

import android.content.Context
import org.json.JSONArray

/**
 * v5.5 (AŞAMA 1) — ⭐ FAVORİLER
 *
 * Kullanıcının "elimin altında olsun" dediği kitaplar (ders kitabı, Kur'an,
 * kişisel gelişim…) ana sayfanın EN ÜSTÜNDEKİ rafında görünür.
 *
 * Önemli: favori eklemek dosyayı TAŞIMAZ/KOPYALAMAZ — yalnız kitap kimliği
 * listeye yazılır (ShelfManager'daki raf mantığıyla aynı yaklaşım).
 * Sıra kullanıcıya aittir; ekleme sırası korunur.
 */
object FavoritesStore {

    private const val PREFS = "umay_prefs"
    private const val KEY = "favorite_book_ids"

    fun ids(ctx: Context?): MutableList<String> {
        if (ctx == null) return mutableListOf()
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
            if (raw.isNullOrBlank()) return mutableListOf()
            val arr = JSONArray(raw)
            MutableList(arr.length()) { arr.getString(it) }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "FavoritesStore.ids", t)
            mutableListOf()
        }
    }

    private fun save(ctx: Context, list: List<String>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "FavoritesStore.save", t)
        }
    }

    fun isFavorite(ctx: Context?, bookId: String): Boolean = ids(ctx).contains(bookId)

    /** Ekler; zaten varsa dokunmaz. */
    fun add(ctx: Context, bookId: String): Boolean {
        val list = ids(ctx)
        if (bookId in list) return false
        list.add(bookId)
        save(ctx, list)
        return true
    }

    fun remove(ctx: Context, bookId: String): Boolean {
        val list = ids(ctx)
        val removed = list.removeAll { it == bookId }
        if (removed) save(ctx, list)
        return removed
    }

    /** @return true → artık favori, false → favoriden çıkarıldı. */
    fun toggle(ctx: Context, bookId: String): Boolean =
        if (isFavorite(ctx, bookId)) {
            remove(ctx, bookId)
            false
        } else {
            add(ctx, bookId)
            true
        }

    /** Sıralamayı kullanıcının verdiği sıraya göre kaydeder. */
    fun reorder(ctx: Context, orderedIds: List<String>) = save(ctx, orderedIds)

    /** Kitap silindiğinde favori listesinden de düşür. */
    fun onBookDeleted(ctx: Context, bookId: String) = remove(ctx, bookId)
}
