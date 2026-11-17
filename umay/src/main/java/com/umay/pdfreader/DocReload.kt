package com.umay.pdfreader

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v37 — BELGELERİ SIFIRDAN YÜKLE (DOCX / PPTX / DOC / PPT).
 *
 * Neden: v37 öncesinde DOCX/PPTX dosyaları yanlış dönüştürülüp kütüphaneye
 * "bozuk kitap" olarak girmişti (sayfaları ham ZIP baytlarıydı). Onarım bu
 * kitapları yerinde düzeltir; bu işlev ise kullanıcının istediği **temiz
 * sağlamayı** yapar: belge kitaplarını kütüphaneden kaldırır ve orijinal
 * klasörden yeniden içe aktarır.
 *
 * PDF'ler ve diğer kitaplar ELLENMEZ.
 *
 * Ayrıntılar:
 *  • Klavuz: yalnız .docx/.pptx/.doc/.ppt uzantılı kitaplar silinir.
 *  • Ders bağlantıları `BookStore.deleteBook` ile temizlenir; yeniden aktarım
 *    `FolderImporter` üzerinden yapıldığı için dosya adı/klasör/kategori
 *    eşleştirmesi yeniden kurulur.
 *  • Okuma konumu/yüzdesi bu kitaplar için sıfırlanır (yeni kitap kimliği).
 */
object DocReload {

    /** Yeniden yüklenecek uzantılar. */
    private val DOC_EXTS = setOf("docx", "pptx", "doc", "ppt")

    data class Report(
        var removed: Int = 0,
        var imported: Int = 0,
        var duplicates: Int = 0,
        var failed: Int = 0,
        val details: MutableList<String> = mutableListOf()
    )

    fun isDocBook(title: String): Boolean =
        DocumentConverter.extension(title) in DOC_EXTS

    /** Kütüphanedeki belge (DOCX/PPTX/DOC/PPT) kitaplarının sayısı. */
    fun docBookCount(ctx: Context): Int =
        try { BookStore.loadBooks(ctx).count { isDocBook(it.title) } } catch (_: Throwable) { 0 }

    /**
     * v37 — TAM SIFIRLAMA: kütüphaneyi baştan kurar.
     *
     * Belirtilen anahtar sözcüğü başlığında taşıyan kitaplar (varsayılan:
     * "kuran") KORUNUR; diğer TÜM kitaplar (PDF dâhil) silinir ve klasörden
     * yeniden aktarılır. Amaç: "hocanın slaytıyla birebir aynı" PDF'lerle
     * temiz bir kütüphane kurmak.
     */
    fun runFull(
        ctx: Context,
        root: File,
        keepKeyword: String = "kuran",
        onProgress: (text: String, done: Int, total: Int) -> Unit,
        onDone: (Report) -> Unit
    ) {
        Thread({
            val report = Report()
            try {
                val keep = keepKeyword.trim().lowercase()
                val books = BookStore.loadBooks(ctx)
                val doomed = books.filter { keep.isBlank() || !it.title.lowercase().contains(keep) }
                doomed.forEachIndexed { i, b ->
                    onProgress("kaldırılıyor: ${b.title}", i, doomed.size)
                    try {
                        BookStore.deleteBook(ctx, b.id)
                        report.removed++
                        if (report.details.size < 8) report.details.add("kaldırıldı: ${b.title}")
                    } catch (t: Throwable) {
                        report.failed++
                        BookStore.logError(ctx, "DocReload.full.delete:${b.id}", t)
                    }
                }
                if (!root.exists() || !root.isDirectory) {
                    report.details.add("klasör yok: ${root.absolutePath}")
                } else {
                    importFolder(ctx, root, report, onProgress)
                }
            } catch (t: Throwable) {
                report.failed++
                BookStore.logError(ctx, "DocReload.runFull", t)
            }
            // v38 — raflar/kategoriler yeniden kurulur (silme sırasında boşalmıştı)
            try { ShelfRepair.run(ctx) } catch (_: Throwable) {}
            onDone(report)
        }, "umay-full-reload").apply { isDaemon = true }.start()
    }

    private fun importFolder(
        ctx: Context,
        root: File,
        report: Report,
        onProgress: (text: String, done: Int, total: Int) -> Unit
    ) {
        onProgress("yeniden yükleniyor…", 0, 0)
        val latch = CountDownLatch(1)
        FolderImporter.run(
            ctx,
            root,
            onProgress = { name, done, total ->
                onProgress(
                    if (name.isBlank()) "yeniden yükleniyor…" else "ekleniyor: $name",
                    done, total
                )
            },
            forcePaths = emptySet()
        ) { rep ->
            report.imported = rep.imported
            report.duplicates = rep.existing
            report.failed += rep.failed
            rep.createdGroups.forEach { c ->
                if (report.details.size < 16) report.details.add("ders: $c")
            }
            rep.unmatched.take(5).forEach { u ->
                if (report.details.size < 20) report.details.add("eşleşmedi: $u")
            }
            latch.countDown()
        }
        latch.await(30, TimeUnit.MINUTES)
    }

    fun summary(r: Report): String = buildString {
        append("Kaldırılan: ${r.removed} · yeniden eklenen: ${r.imported}")
        if (r.duplicates > 0) append(" · zaten vardı: ${r.duplicates}")
        if (r.failed > 0) append(" · olmadı: ${r.failed}")
    }

    /**
     * Belgeleri kaldırır, sonra klasörden yeniden aktarır.
     * Ağır iş: kendi thread'inde çalışır; sonuç `onDone` ile döner.
     */
    fun run(
        ctx: Context,
        root: File,
        onProgress: (text: String, done: Int, total: Int) -> Unit,
        onDone: (Report) -> Unit
    ) {
        Thread({
            val report = Report()
            try {
                val books = BookStore.loadBooks(ctx)
                val docs = books.filter { isDocBook(it.title) }
                docs.forEachIndexed { i, b ->
                    onProgress("kaldırılıyor: ${b.title}", i, docs.size)
                    try {
                        BookStore.deleteBook(ctx, b.id)
                        report.removed++
                        if (report.details.size < 10) report.details.add("kaldırıldı: ${b.title}")
                    } catch (t: Throwable) {
                        report.failed++
                        BookStore.logError(ctx, "DocReload.delete:${b.id}", t)
                    }
                }
                onProgress("yeniden yükleniyor…", docs.size, docs.size)
                if (!root.exists() || !root.isDirectory) {
                    report.details.add("klasör yok: ${root.absolutePath}")
                } else {
                    importFolder(ctx, root, report, onProgress)
                }
            } catch (t: Throwable) {
                report.failed++
                BookStore.logError(ctx, "DocReload.run", t)
            }
            // v38 — belgeler yeniden yüklendikten sonra raflar tamamlanır
            try { ShelfRepair.run(ctx) } catch (_: Throwable) {}
            onDone(report)
        }, "umay-doc-reload").apply { isDaemon = true }.start()
    }
}
