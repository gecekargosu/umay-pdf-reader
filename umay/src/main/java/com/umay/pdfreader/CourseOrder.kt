package com.umay.pdfreader

import android.content.Context
import org.json.JSONArray

/**
 * v40 — DERS SIRASI (kullanıcı isteği: "derslerin yerleri değişebilmeli").
 *
 * Sıra SharedPreferences'ta kimlik listesi olarak tutulur; dersler DB'de
 * (courses tablosu) kaldığı için şema değişmez, sıra bozulsa bile ders
 * kaybolmaz. Sırada bulunmayan dersler SONA eklenir.
 */
object CourseOrder {

    private const val PREFS = "umay_course_order"
    private const val KEY = "order"

    fun ids(ctx: Context): List<String> = try {
        val raw = ctx.getSharedPreferences(PREFS, 0).getString(KEY, null)
        if (raw.isNullOrBlank()) emptyList()
        else {
            val arr = JSONArray(raw)
            ArrayList<String>(arr.length()).also { out ->
                for (i in 0 until arr.length()) out.add(arr.getString(i))
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun save(ctx: Context, ids: List<String>) {
        try {
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY, arr.toString()).apply()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CourseOrder.save", t)
        }
    }

    /** Elle sırayı uygular; sırada olmayan dersler sona eklenir (kaybolmaz). */
    fun apply(ctx: Context, list: List<Course>): List<Course> {
        if (list.size < 2) return list
        val order = ids(ctx)
        if (order.isEmpty()) return list
        val pos = HashMap<String, Int>(order.size * 2)
        order.forEachIndexed { i, id -> if (!pos.containsKey(id)) pos[id] = i }
        return list.sortedBy { pos[it.id] ?: Int.MAX_VALUE }
    }
}
