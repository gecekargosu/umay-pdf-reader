package com.umay.pdfreader

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * v39 — MÜKERRER (birebir aynı) KİTAPLARI TEMİZLE.
 *
 * Aynı dosya birden çok kez içe aktarıldığında kütüphanede kopyalar oluşuyor
 * (ör. Hafta_02_PointerlarVeDiziler.docx iki kez). Aynı ada sahip OLMAK
 * mükerrer demek DEĞİLDİR: farklı baskı/sayfa sayısı olabilir. Bu yüzden karar
 * yalnızca **dosya içeriğinin SHA-256 özeti** ile verilir.
 *
 * Aynı özete sahip kitaplardan **en eskisi korunur** (okuma yüzdesi, ders bağı,
 * raf yeri onda kalır); diğerleri `BookStore.deleteBook` ile silinir — bu da
 * raflardan ve ders bağlarından düşürür.
 */
object Dedup {

    data class Group(val title: String, val keep: Book, val remove: List<Book>)

    data class Report(
        var groups: Int = 0,
        var removed: Int = 0,
        val details: MutableList<String> = mutableListOf()
    )

    /** Silinecek grupları bulur (dosya yoksa/boşsa atlanır). */
    fun scan(ctx: Context, books: List<Book>): List<Group> {
        val byHash = LinkedHashMap<String, MutableList<Book>>()
        books.forEach { b ->
            val f = source(ctx, b.id) ?: return@forEach
            if (f.length() <= 0L) return@forEach
            val h = try { sha256(f) } catch (_: Throwable) { return@forEach }
            byHash.getOrPut(h) { mutableListOf() }.add(b)
        }
        val out = ArrayList<Group>()
        byHash.values.forEach { list ->
            if (list.size < 2) return@forEach
            val sorted = list.sortedBy { it.createdAt }
            out.add(Group(sorted.first().title, sorted.first(), sorted.drop(1)))
        }
        return out
    }

    /** Mükerrerleri siler. */
    fun run(ctx: Context, onProgress: ((String, Int, Int) -> Unit)? = null): Report {
        val report = Report()
        val groups = scan(ctx, BookStore.loadBooks(ctx))
        report.groups = groups.size
        groups.forEachIndexed { gi, g ->
            g.remove.forEachIndexed { ri, b ->
                onProgress?.invoke(b.title, gi, groups.size)
                try {
                    BookStore.deleteBook(ctx, b.id)
                    report.removed++
                    if (report.details.size < 10) {
                        report.details.add("silindi: ${b.title} (#${ri + 2}, kopya)")
                    }
                } catch (t: Throwable) {
                    BookStore.logError(ctx, "Dedup.delete:${b.id}", t)
                }
            }
        }
        return report
    }

    private fun source(ctx: Context, id: String): File? =
        when {
            BookStore.sourceFile(ctx, id).exists() -> BookStore.sourceFile(ctx, id)
            else -> BookStore.sourceDoc(ctx, id)
        }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun summary(r: Report): String = buildString {
        append("Mükerrer grup: ${r.groups} · silinen kopya: ${r.removed}")
        if (r.details.isNotEmpty()) {
            append("\n\n")
            append(r.details.joinToString("\n"))
        }
    }
}
