package com.umay.pdfreader

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * v36.9 — BOZUK BELGE ONARIMI.
 *
 * Neden: kitaplıktaki DOCX/PPTX dosyalarının bir bölümü eski bir sürümde
 * **ZIP baytları metin sanılarak** PDF'e çevrilmişti. O kitaplar açılıyor ama
 * sesli okuma "rà ?§[Np®ùq > f …" gibi anlamsız şeyler söylüyordu.
 * (Kaynak dosyalar telefonun içinde değil; bu yüzden onarım, orijinal
 * belgelerin bulunduğu bir klasörden yapılır.)
 *
 * Onarım, kitabı SİLMEZ: aynı `id` korunur (okuma yüzdesi, ders bağı, raf
 * yeri durur), yalnız `source.pdf` yeniden üretilir ve sayfa metinleri
 * baştan çıkarılır.
 */
object BookRepair {

    data class Report(
        var checked: Int = 0,
        var repaired: Int = 0,
        var alreadyOk: Int = 0,
        var noSource: Int = 0,
        var failed: Int = 0,
        val details: MutableList<String> = mutableListOf()
    )

    /** Kitabın sayfa metinleri çöp mü? (çıkarım bitmemişse false) */
    fun needsRepair(ctx: Context, book: Book): Boolean {
        val pages = (0 until book.pageCount)
            .asSequence()
            .take(40)
            .filter { it % 5 == 0 }
            .mapNotNull { i ->
                val f = BookStore.pageFile(ctx, book.id, i)
                if (f.exists()) runCatching { f.readText() }.getOrNull() else null
            }
            .take(8)
            .toList()
        val garbage = TextQuality.mostlyGarbage(pages) || TextQuality.mostlyMojibake(pages)
        if (garbage) {
            android.util.Log.d("BookRepair", "bozuk: ${book.title} (örnek ${pages.size} sayfa)")
            return true
        }
        // v37 — sayfa dosyaları henüz çıkarılmamış olabilir; bu durumda
        // kaynak PDF'in kendi metin katmanına bakılır (çıkarımdan bağımsız).
        val src = BookStore.sourceFile(ctx, book.id)
        val pdfBad = src.exists() && probeGarbage(src, pages = 4)
        android.util.Log.d(
            "BookRepair",
            "kontrol: ${book.title} sayfa=${pages.size} cop=$garbage pdfVar=${src.exists()} pdfCop=$pdfBad"
        )
        return pdfBad
    }

    /**
     * v37 — TANILAMA: sayfa metinleri bozuk (mojibake) kitapların başlıkları.
     *
     * Lambanın bu tür sorunları GÖSTERMESİ için: bozuk dönüşüm varken yeşil
     * lamba yanıp "her şey yolunda" dememeli. Hızlıdır: kitap başına yalnız
     * ilk 3 sayfa dosyası okunur (PDF açılmaz).
     */
    fun brokenBooks(ctx: Context, books: List<Book>, max: Int = 5): List<String> {
        val out = mutableListOf<String>()
        for (b in books) {
            val texts = (0 until minOf(3, b.pageCount)).mapNotNull { i ->
                val f = BookStore.pageFile(ctx, b.id, i)
                if (f.exists()) runCatching { f.readText() }.getOrNull() else null
            }
            // v39 — Metin katmanı hiç yoksa (taranmış/OCR bekleyen PDF) bu
            // "belge bozuk" DEĞİLDİR; onarım da yapılamaz. Yalnız gerçekten
            // metin olup ÇÖP olan sayfalar bozuk sayılır.
            if (texts.isEmpty()) continue
            if (texts.all { it.isBlank() }) continue
            if (TextQuality.mostlyMojibake(texts) || TextQuality.mostlyGarbage(texts)) {
                out.add(b.title)
                if (out.size >= max) break
            }
        }
        return out
    }

    /**
     * Klasördeki orijinal belgelerle eşleşen bozuk kitapları onarır.
     * Eşleşme: dosya adı (uzantısız) ↔ kitap başlığı (uzantısız), normalize.
     */
    fun runFolder(
        ctx: Context,
        root: File,
        onProgress: (name: String, done: Int, total: Int) -> Unit
    ): Report {
        val report = Report()
        if (!root.exists() || !root.isDirectory) return report
        val files = FolderImporter.collect(root)
        if (files.isEmpty()) return report
        val baseByFile = files.associateWith { TextUtil.normalize(withoutExt(it.name)) }
        val books = try { BookStore.loadBooks(ctx) } catch (_: Throwable) { emptyList() }

        // v37 — KİTAP BAZLI onarım. Eski sürüm DOSYA bazlı geziyordu ve
        // kitapları `associateBy` ile tek isme indiriyordu: aynı adı taşıyan
        // ikinci kitap (ör. iki farklı klasördeki "Hafta_02_Pointerlar.docx")
        // hiç onarılmıyor, üstelik adı kısaltılmış kitaplar ("Hafta4_Meto")
        // hiç eşleşmiyordu. Artık her bozuk kitap için en uygun dosya aranır.
        books.forEachIndexed { index, book ->
            report.checked++
            onProgress(book.title, index, books.size)
            val corrupt = needsRepair(ctx, book)
            val src = bestSource(book, files, baseByFile)
            // v37 — slayt sayısı ile sayfa sayısı uyuşmuyorsa (60 slayt →
            // 1 sayfa sıkışması) kitap yeniden üretilir.
            val squeezed = src != null && isSqueezedPptx(book, src)
            if (!corrupt && !squeezed) {
                report.alreadyOk++
                return@forEachIndexed
            }
            if (src == null) {
                report.noSource++
                if (report.details.size < 12) report.details.add("eşleşme yok: ${book.title}")
                return@forEachIndexed
            }
            if (rebuild(ctx, book, src)) {
                report.repaired++
                report.details.add("onarıldı: ${book.title}")
            } else {
                report.failed++
                report.details.add("olmadı: ${book.title}")
            }
        }
        return report
    }

    /**
     * Kitap için en uygun orijinal dosya.
     *
     * 1) Ad tam eşleşmesi (normalize) — birden çok dosya varsa en büyüğü.
     * 2) Önek eşleşmesi: kitap adı dosya adının öneki ya da tersi (kısaltılmış
     *    başlıklar). Belirsizse (birden çok farklı aday) eşleşme YAPILMAZ;
     *    yanlış belgeyi yüklemektense dokunmamak yeğdir.
     */
    private fun bestSource(
        book: Book,
        files: List<File>,
        baseByFile: Map<File, String>
    ): File? {
        val key = TextUtil.normalize(withoutExt(book.title))
        if (key.isBlank()) return null

        val exact = files.filter { baseByFile[it] == key }
        if (exact.isNotEmpty()) return exact.maxByOrNull { it.length() }

        if (key.length < 8) return null
        val byKey = sortedMapOf<String, File>()
        files.forEach { f ->
            val k = baseByFile[f] ?: return@forEach
            if (k.length < 8) return@forEach
            if (k.startsWith(key) || key.startsWith(k)) {
                // Aynı öneke uyan dosya adları arasında en uzun ortak öneki tut
                val existing = byKey[k]
                if (existing == null || f.length() > existing.length()) byKey[k] = f
            }
        }
        if (byKey.size != 1) return null
        return byKey.values.first()
    }

    /** Tek kitabı verilen orijinal dosyadan yeniden üretir. */
    fun rebuild(ctx: Context, book: Book, src: File): Boolean {
        return try {
            val dir = BookStore.bookDir(ctx, book.id)
            val tmp = File(dir, "rebuild_tmp.pdf")
            if (tmp.exists()) tmp.delete()

            // v39 — BELGE MODU: DOCX/PPTX/DOC/PPT **PDF'e çevrilmez**.
            // Orijinal dosya olduğu gibi saklanır (grafikler/renkler korunur),
            // yalnızca okuma için metin sayfaları yeniden çıkarılır. Kitap
            // kimliği korunur → okuma yüzdesi, ders bağı ve raf yeri durur.
            val ext = DocumentConverter.effectiveExtension(src, src.name)
            if (ext in BookStore.DOC_EXTS) {
                val target = File(dir, "source.$ext")
                for (i in 0 until book.pageCount) {
                    BookStore.pageFile(ctx, book.id, i).delete()
                    BookStore.boxesFile(ctx, book.id, i).delete()
                }
                BookStore.sourceFile(ctx, book.id).delete()
                File(dir, "cover.jpg").delete()
                src.copyTo(target, overwrite = true)
                val pagesText = TextPager.paginate(DocumentConverter.paragraphs(target, src.name))
                pagesText.forEachIndexed { i, t ->
                    BookStore.pageFile(ctx, book.id, i).writeText(t)
                }
                if (pagesText.size != book.pageCount) {
                    BookStore.updatePageCount(ctx, book.id, pagesText.size)
                }
                BookStore.forgetVerified(book.id)
                BookStore.ensureExtracting(ctx, book)
                return true
            }

            val pages = if (UniversalParser.isPdf(src)) {
                src.copyTo(tmp, overwrite = true)
                PDDocument.load(tmp).use { it.numberOfPages }
            } else {
                DocumentConverter.convert(src, tmp, src.name)
            }
            if (pages <= 0) return false

            // Yeni PDF gerçekten okunabilir metin içeriyor mu? İçermiyorsa
            // mevcut (bozuk da olsa) dosyaya DOKUNMA.
            if (probeGarbage(tmp)) {
                tmp.delete()
                return false
            }

            // Eski metin/OCR parçalarını temizle
            for (i in 0 until book.pageCount) {
                BookStore.pageFile(ctx, book.id, i).delete()
                BookStore.boxesFile(ctx, book.id, i).delete()
            }
            File(dir, "cover.jpg").delete()
            BookStore.forgetVerified(book.id)

            val target = BookStore.sourceFile(ctx, book.id)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            if (pages != book.pageCount) BookStore.updatePageCount(ctx, book.id, pages)
            BookStore.ensureExtracting(ctx, book)
            true
        } catch (t: Throwable) {
            BookStore.logError(ctx, "BookRepair.rebuild:${book.id}", t)
            false
        }
    }

    /** PDF'in ilk sayfaları çöp mü? (mojibake dâhil — v37) */
    fun probeGarbage(pdf: File, pages: Int = 3): Boolean {
        return try {
            PDDocument.load(pdf).use { doc ->
                val n = minOf(pages, doc.numberOfPages)
                val texts = (0 until n).map { i ->
                    com.tom_roush.pdfbox.text.PDFTextStripper().apply {
                        startPage = i + 1
                        endPage = i + 1
                    }.getText(doc)
                }
                TextQuality.mostlyGarbage(texts) || TextQuality.mostlyMojibake(texts)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** PPTX: slayt sayısı, kitabın sayfa sayısından fazla mı? */
    private fun isSqueezedPptx(book: Book, src: File): Boolean {
        return try {
            if (DocumentConverter.extension(src.name) != "pptx") return false
            val slides = DocumentConverter.slideCount(src)
            slides > 0 && slides > book.pageCount
        } catch (_: Throwable) {
            false
        }
    }

    private fun withoutExt(name: String): String =
        name.substringBeforeLast('.', name).trim()

    /** Ekranda gösterilecek kısa özet. */
    fun summary(r: Report): String = buildString {
        append("Onarılan: ${r.repaired}")
        if (r.alreadyOk > 0) append(" · zaten sağlam: ${r.alreadyOk}")
        if (r.noSource > 0) append(" · eşleşmeyen dosya: ${r.noSource}")
        if (r.failed > 0) append(" · olmadı: ${r.failed}")
    }
}
