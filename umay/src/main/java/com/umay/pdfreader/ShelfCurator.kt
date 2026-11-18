package com.umay.pdfreader

import android.content.Context

/**
 * v5.22 — RAF KÜRATÖRÜ.
 *
 * Kullanıcı geri bildirimi: "Dini kitaplar rafında Hafta 7 Ağaçlar var,
 * Tübitak rafında lineer cebir var; her raf kendi konusunu içermeli."
 *
 * Kural: yalnızca ADI belirgin bir konuya işaret eden raflar süzülür.
 * Kitap SİLİNMEZ; yalnızca yanlış raftan çıkarılır (doğru rafında kalır).
 * Bir kez çalışır (`shelf_curator_v2`), kullanıcının sonraki taşımalarına
 * dokunmaz.
 */
object ShelfCurator {

    private const val PREF = "shelf_curator_v2"

    fun runOnce(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences("umay_prefs", 0)
            if (prefs.getBoolean(PREF, false)) return
            val books = BookStore.loadBooks(ctx).associateBy { it.id }
            if (books.isEmpty()) return
            val shelves = ShelfManager.shelvesOf(ctx)
            var moved = 0

            shelves.forEach { sh ->
                val name = TextUtil.normalize(sh.name)
                val keepSet: Set<String> = when {
                    name.contains("dini") || name.contains("kuran") ||
                        name.contains("islam") -> religious(books)
                    name.contains("tubitak") || name.contains("2209") ||
                        name.contains("tübitak") -> tubitak(books)
                    sh.id.startsWith("shelf_blg") || sh.id.startsWith("shelf_mat") ||
                        sh.id.startsWith("shelf_ypz") -> courseShelf(ctx, sh, books)
                    else -> return@forEach
                }

                val before = sh.bookIds.size
                sh.bookIds.retainAll { it in keepSet }
                moved += before - sh.bookIds.size
            }

            if (moved > 0) {
                ShelfManager.save(ctx, shelves)
                SeyirDefteri.yaz(ctx, "RAF", "raf kürasyonu", "$moved yanlış kayıt çıkarıldı")
            }
            prefs.edit().putBoolean(PREF, true).apply()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ShelfCurator.runOnce", t)
        }
    }

    private fun religious(books: Map<String, Book>): Set<String> {
        val keys = listOf(
            "kuran", "kur'an", "kerim", "meal", "tefsir", "hadis", "siyer",
            "islam", "dini", "dua", "ilmihal", "peygamber", "sahabe"
        )
        return books.values.filter { b ->
            val t = TextUtil.normalize(b.title)
            keys.any { t.contains(TextUtil.normalize(it)) }
        }.map { it.id }.toSet()
    }

    private fun tubitak(books: Map<String, Book>): Set<String> {
        val keys = listOf(
            "tubitak", "tübitak", "2209", "proje", "makale", "rapor", "ödev",
            "odev", "nmf", "girişim", "girisim", "bigG", "teknofest", "başvuru",
            "basvuru", "araştırma", "arastirma", "literatür", "literatur",
            "kapsam", "şablon", "sablon", "cv", "sunum"
        )
        return books.values.filter { b ->
            val t = TextUtil.normalize(b.title)
            keys.any { t.contains(TextUtil.normalize(it)) }
        }.map { it.id }.toSet()
    }

    /** Ders rafı: yalnız o derse bağlı belgeler + ders kodunu içeren başlıklar. */
    private fun courseShelf(
        ctx: Context,
        shelf: ShelfManager.Shelf,
        books: Map<String, Book>
    ): Set<String> {
        val code = ShelfManager.courseCodeIn(shelf.name)
            ?: shelf.id.removePrefix("shelf_").uppercase()
        val course = CourseStore.courses(ctx).firstOrNull {
            TextUtil.normalize(it.name).startsWith(TextUtil.normalize(code))
        }
        val linked = if (course != null) {
            CourseStore.linkedBookIds(ctx, course.id)
        } else emptySet()
        val codeKey = TextUtil.normalize(code)
        return books.values.filter { b ->
            b.id in linked || TextUtil.normalize(b.title).contains(codeKey)
        }.map { it.id }.toSet()
    }
}
