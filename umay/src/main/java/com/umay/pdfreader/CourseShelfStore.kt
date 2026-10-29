package com.umay.pdfreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v5.7 — DERS İÇİ KULLANICI RAFLARI.
 *
 * Ders detayında 4 yerleşik raf vardır: Ders Kitapları / Slayt Dosyaları /
 * Dokümanlar / Ödev & Projeler. Kullanıcı bunlara EK olarak kendi rafını
 * açabilir (ör. "Vize Notları", "Lab Föyleri") ve kaynakları raflar arasında
 * taşıyabilir.
 *
 * Yerleşik raflar `CourseStore.documents.category` alanıyla (book/slide/doc)
 * temsil edilir; özel raf üyeliği ise burada `docId` listesi olarak tutulur.
 * Bir kaynak özel rafa taşındığında yerleşik rafından DÜŞMEZ (kategorisi
 * korunur) ama listede yalnız özel rafta görünür — `shelfOfDoc` önceliği.
 *
 * Saklama: SharedPreferences ("umay_course_shelves"), ders başına JSON dizi.
 * Veri kaybı riski yok: bozuk JSON okunursa boş liste döner, dosya silinmez.
 */
object CourseShelfStore {

    private const val PREFS = "umay_course_shelves"

    data class Shelf(
        val id: String,
        val name: String,
        val docIds: List<String>
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(courseId: String) = "shelves_$courseId"

    fun shelves(ctx: Context, courseId: String): List<Shelf> {
        val raw = prefs(ctx).getString(key(courseId), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) return@mapNotNull null
                val ids = o.optJSONArray("docIds") ?: JSONArray()
                Shelf(
                    id = id,
                    name = o.optString("name", "Raf"),
                    docIds = (0 until ids.length()).map { ids.optString(it) }
                )
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CourseShelfStore.shelves", t)
            emptyList()
        }
    }

    private fun save(ctx: Context, courseId: String, list: List<Shelf>) {
        try {
            val arr = JSONArray()
            list.forEach { s ->
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("docIds", JSONArray(s.docIds))
                    }
                )
            }
            prefs(ctx).edit().putString(key(courseId), arr.toString()).apply()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CourseShelfStore.save", t)
        }
    }

    /** Yeni raf açar; aynı ad varsa sonuna sayı ekler. */
    fun add(ctx: Context, courseId: String, name: String): Shelf {
        val list = shelves(ctx, courseId).toMutableList()
        val clean = name.trim().ifBlank { "Yeni Raf" }
        var unique = clean
        var n = 2
        while (list.any { it.name.equals(unique, ignoreCase = true) }) {
            unique = "$clean $n"
            n++
        }
        val shelf = Shelf("cs_" + System.currentTimeMillis().toString(36), unique, emptyList())
        list.add(shelf)
        save(ctx, courseId, list)
        return shelf
    }

    fun rename(ctx: Context, courseId: String, shelfId: String, newName: String) {
        val list = shelves(ctx, courseId).map {
            if (it.id == shelfId) it.copy(name = newName.trim().ifBlank { it.name }) else it
        }
        save(ctx, courseId, list)
    }

    fun delete(ctx: Context, courseId: String, shelfId: String) {
        save(ctx, courseId, shelves(ctx, courseId).filterNot { it.id == shelfId })
    }

    /** Sıralamayı değiştirir (rafı bir öncekiyle yer değiştirir). */
    fun moveUp(ctx: Context, courseId: String, shelfId: String) {
        val list = shelves(ctx, courseId).toMutableList()
        val i = list.indexOfFirst { it.id == shelfId }
        if (i > 0) {
            val tmp = list[i]
            list[i] = list[i - 1]
            list[i - 1] = tmp
            save(ctx, courseId, list)
        }
    }

    /** Kaynağı özel rafa taşır (diğer özel raflardan çıkarır). */
    fun assign(ctx: Context, courseId: String, docId: String, shelfId: String?) {
        val list = shelves(ctx, courseId).map { s ->
            when {
                s.id == shelfId -> s.copy(docIds = (s.docIds + docId).distinct())
                docId in s.docIds -> s.copy(docIds = s.docIds - docId)
                else -> s
            }
        }
        save(ctx, courseId, list)
    }

    /** Kaynağın bulunduğu özel raf (yoksa null → yerleşik kategorisi geçerli). */
    fun shelfOfDoc(ctx: Context, courseId: String, docId: String): Shelf? =
        shelves(ctx, courseId).firstOrNull { docId in it.docIds }
}
