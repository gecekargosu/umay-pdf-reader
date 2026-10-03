package com.umay.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/** Bir PDF kitabın künyesi. */
data class Book(
    val id: String,
    val title: String,
    val pageCount: Int,
    val createdAt: Long
)

/** Metin çıkarma / OCR aşamaları için ilerleme olayları. */
object ExtractionBus {
    interface Listener {
        fun onProgress(bookId: String, done: Int, total: Int, stage: String)
        fun onFinished(bookId: String)
    }

    val listeners: MutableList<Listener> = Collections.synchronizedList(mutableListOf())

    fun progress(bookId: String, done: Int, total: Int, stage: String) {
        listeners.toList().forEach { it.onProgress(bookId, done, total, stage) }
    }

    fun finished(bookId: String) {
        listeners.toList().forEach { it.onFinished(bookId) }
    }
}

/**
 * Kitap kütüphanesi + metin çıkarma motoru.
 *
 * - PDF içeri aktarılırken filesDir/books/<id>/source.pdf'e kopyalanır.
 * - Arka planda tek iş parçacığında PdfBox ile sayfa metinleri çıkarılır.
 * - Metni olmayan (taranmış) sayfalar PdfRenderer + ML Kit OCR ile okunur.
 * - Sayfa metinleri page_NNNN.txt dosyalarında saklanır.
 */
object BookStore {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "umay-extract").apply { isDaemon = true }
    }
    private val extracting: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun booksDir(ctx: Context) = File(ctx.filesDir, "books").apply { mkdirs() }

    fun bookDir(ctx: Context, id: String) = File(booksDir(ctx), id).apply { mkdirs() }

    fun sourceFile(ctx: Context, id: String) = File(bookDir(ctx, id), "source.pdf")

    private fun indexFile(ctx: Context) = File(booksDir(ctx), "books.json")

    // ── Kütüphane ──────────────────────────────────────────────

    fun loadBooks(ctx: Context): List<Book> {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Book(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    pageCount = o.getInt("pageCount"),
                    createdAt = o.optLong("createdAt", 0L)
                )
            }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveBooks(ctx: Context, books: List<Book>) {
        val arr = JSONArray()
        books.forEach { b ->
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("title", b.title)
                    .put("pageCount", b.pageCount)
                    .put("createdAt", b.createdAt)
            )
        }
        indexFile(ctx).writeText(arr.toString())
    }

    /** SAF'ten seçilen PDF'i kütüphaneye kopyalar ve çıkarmayı başlatır. */
    fun importPdf(ctx: Context, uri: Uri, onDone: (Result<Book>) -> Unit) {
        executor.execute {
            try {
                val id = System.currentTimeMillis().toString(36)
                val dir = bookDir(ctx, id)
                val dest = File(dir, "source.pdf")

                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Dosya açılamadı")

                val pageCount = PDDocument.load(dest).use { it.numberOfPages }
                val title = queryDisplayName(ctx, uri) ?: dest.name

                val book = Book(id, title, pageCount, System.currentTimeMillis())
                val books = loadBooks(ctx).toMutableList()
                books.add(book)
                saveBooks(ctx, books)

                ensureExtracting(ctx, id)
                onDone(Result.success(book))
            } catch (e: Exception) {
                onDone(Result.failure(e))
            }
        }
    }

    fun deleteBook(ctx: Context, id: String) {
        val books = loadBooks(ctx).filterNot { it.id == id }
        saveBooks(ctx, books)
        File(booksDir(ctx), id).deleteRecursively()
        // Kitaba ait okuma ilerlemesi de temizlensin (büyüklenme sızıntısı)
        AppPrefs.clearProgress(ctx, id)
        if (AppPrefs.getString(ctx, AppPrefs.KEY_LAST_BOOK) == id) {
            AppPrefs.putString(ctx, AppPrefs.KEY_LAST_BOOK, "")
        }
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }

    // ── Metin çıkarma ─────────────────────────────────────────

    /** Çıkarılmamış sayfalar varsa arka planda çıkarmaya başlar. */
    fun ensureExtracting(ctx: Context, bookId: String) {
        if (extracting.contains(bookId)) return
        val book = loadBooks(ctx).firstOrNull { it.id == bookId } ?: return
        val missing = (0 until book.pageCount).count { !pageFile(ctx, bookId, it).exists() }
        if (missing == 0 && !needsOcr(ctx, bookId)) {
            ExtractionBus.finished(bookId)
            return
        }
        if (extracting.add(bookId)) {
            executor.execute {
                try {
                    extractBook(ctx, bookId)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    extracting.remove(bookId)
                    ExtractionBus.finished(bookId)
                }
            }
        }
    }

    private fun needsOcr(ctx: Context, bookId: String): Boolean {
        val book = loadBooks(ctx).firstOrNull { it.id == bookId } ?: return false
        // Hiç dosyası OLMAYAN ya da BOŞ metni olan (OCR başarısız kalmış)
        // sayfa var mı? Boş sayfalar bir sonraki açılışta tekrar denenir.
        for (i in 0 until book.pageCount) {
            val f = pageFile(ctx, bookId, i)
            if (!f.exists() || f.length() == 0L) return true
        }
        return false
    }

    /** Bu kitap için çıkarma/OCR hâlâ arka planda sürüyor mu? */
    fun isExtracting(ctx: Context, bookId: String): Boolean = extracting.contains(bookId)

    private fun extractBook(ctx: Context, bookId: String) {
        val book = loadBooks(ctx).firstOrNull { it.id == bookId } ?: return
        val src = sourceFile(ctx, bookId)
        if (!src.exists()) return

        var renderer: PdfRenderer? = null
        var rendererPfd: ParcelFileDescriptor? = null

        try {
            PDDocument.load(src).use { doc ->
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                val total = book.pageCount
                var done = 0

                for (i in 0 until total) {
                    val f = pageFile(ctx, bookId, i)
                    if (f.exists() && f.length() > 0) {
                        done++
                        ExtractionBus.progress(bookId, done, total, "text")
                        continue
                    }

                    var text = ""
                    try {
                        stripper.startPage = i + 1
                        stripper.endPage = i + 1
                        text = stripper.getText(doc) ?: ""
                    } catch (e: Exception) {
                        // metin çıkarılamadı, OCR'a düşer
                    }

                    // Taranmış sayfa (metin yok) VEYA bozuk kodlama
                    // (Türkçe karakterler '?' çıkmış) => OCR ile oku
                    val trimmed = text.trim()
                    val badRatio = if (trimmed.isNotEmpty()) {
                        text.count { it == '?' }.toFloat() / text.length
                    } else 1f
                    val needsOcr = trimmed.length < 20 || badRatio > 0.12f

                    if (needsOcr) {
                        try {
                            if (renderer == null) {
                                rendererPfd = ParcelFileDescriptor.open(
                                    src, ParcelFileDescriptor.MODE_READ_ONLY
                                )
                                renderer = PdfRenderer(rendererPfd!!)
                            }
                            val ocrText = ocrPage(renderer!!, i)
                            if (ocrText.isNotBlank()) text = ocrText
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    f.writeText(text)
                    done++
                    // OCR aşaması ayrı bir aşama olarak bildirilsin ki
                    // ekranlar "OCR: n/m sayfa" gösterebilsin.
                    ExtractionBus.progress(bookId, done, total, if (needsOcr) "ocr" else "text")
                }
            }
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { rendererPfd?.close() } catch (_: Exception) {}
        }
    }

    /** Sayfayı görüntüye render edip ML Kit OCR ile metne çevirir (offline). */
    private fun ocrPage(renderer: PdfRenderer, pageIndex: Int): String {
        val page = renderer.openPage(pageIndex)
        try {
            val scale = 2.0f
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            val input = InputImage.fromBitmap(bmp, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val task = recognizer.process(input)
                val text = com.google.android.gms.tasks.Tasks.await(task)
                return text.text ?: ""
            } finally {
                bmp.recycle()
                recognizer.close()
            }
        } finally {
            try { page.close() } catch (_: Exception) {}
        }
    }

    // ── Sayfa metinleri ───────────────────────────────────────

    fun pageFile(ctx: Context, bookId: String, index: Int) =
        File(bookDir(ctx, bookId), "page_%04d.txt".format(index))

    /** Sayfa metni; henüz çıkarılmamışsa null döner. */
    fun pageText(ctx: Context, bookId: String, index: Int): String? {
        val f = pageFile(ctx, bookId, index)
        if (!f.exists()) return null
        return f.readText()
    }

    /** Tüm sayfaların metinlerini sırayla döndürür (sohbet araması için). */
    fun allPages(ctx: Context, bookId: String): List<String> {
        val book = loadBooks(ctx).firstOrNull { it.id == bookId } ?: return emptyList()
        return (0 until book.pageCount).map { pageText(ctx, bookId, it) ?: "" }
    }
}
