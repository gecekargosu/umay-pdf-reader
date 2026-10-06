package com.umay.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
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

    /**
     * Kitaplığı okur (ADIM 1): kaynak doğruluk artık **SQLite** (`LibraryDb`).
     *
     * - DB boşken eski `books.json` bir kez içeri alınır (geriye uyum geçişi).
     * - JSON dosyası silinmez; her kayıtta ayna olarak da yazılır (yedek).
     * - Okuma hatasında kitaplık asla boşalmaz: hata loglanır, JSON'a düşülür.
     */
    fun loadBooks(ctx: Context): List<Book> {
        val fromDb = try {
            LibraryDb.get(ctx).readAll()
        } catch (t: Throwable) {
            logError(ctx, "LibraryDb.readAll", t)
            emptyList()
        }
        if (fromDb.isNotEmpty()) return fromDb

        val legacy = readJsonIndex(ctx)
        if (legacy.isNotEmpty()) {
            try {
                LibraryDb.get(ctx).replaceAll(legacy)
            } catch (t: Throwable) {
                logError(ctx, "LibraryDb.migrate(json→sqlite)", t)
            }
        }
        return legacy
    }

    /** Eski JSON indeksini okur (bozulursa boş liste — hiçbir zaman çökmez). */
    private fun readJsonIndex(ctx: Context): List<Book> {
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
            logError(ctx, "readJsonIndex", e)
            emptyList()
        }
    }

    /** Kitaplığı kaydeder: SQLite ana kayıt, `books.json` yedek aynası. */
    private fun saveBooks(ctx: Context, books: List<Book>) {
        try {
            LibraryDb.get(ctx).replaceAll(books)
        } catch (t: Throwable) {
            // DB yazımı tutmadı → yine de JSON aynasını yaz, kütüphane kaybolmasın
            logError(ctx, "LibraryDb.replaceAll", t)
        }
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

    /** SAF'ten seçilen dosyayı (PDF/TXT/DOCX/MD/CSV) kütüphaneye alır. */
    fun importPdf(ctx: Context, uri: Uri, onDone: (Result<Book>) -> Unit) {
        executor.execute {
            try {
                val id = System.currentTimeMillis().toString(36)
                val dir = bookDir(ctx, id)
                val dest = File(dir, "source.pdf")
                val name = queryDisplayName(ctx, uri) ?: dest.name
                var convertedDoc = false   // PDF'e çevrilen belge mi? (Documents kopyası için)

                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Dosya açılamadı")

                // PDF ise olduğu gibi kalır; değilse (TXT/DOCX/MD/CSV) PDF'e çevrilir
                val pageCount = if (name.endsWith(".pdf", ignoreCase = true) ||
                    DocumentConverter.extension(name).isEmpty()
                ) {
                    PDDocument.load(dest).use { it.numberOfPages }
                } else if (DocumentConverter.isSupported(name)) {
                    val converted = File(dir, "converted.pdf")
                    val n = DocumentConverter.convert(dest, converted, name)
                    dest.delete()
                    converted.renameTo(dest)
                    require(n > 0) { "Dosya PDF'e çevrilemedi" }
                    convertedDoc = true
                    n
                } else {
                    throw IllegalArgumentException(
                        "'$name' desteklenmiyor. Kullanılabilir: PDF, DOCX, TXT, MD, CSV"
                    )
                }

                val book = Book(id, name, pageCount, System.currentTimeMillis())
                val books = loadBooks(ctx).toMutableList()
                books.add(book)
                saveBooks(ctx, books)

                ensureCover(ctx, id)   // kitaplıkta kapak görünsün
                ensureExtracting(ctx, id)

                // Dönüştürmeden oluşan PDF'in (TXT/DOCX/MD/CSV) kamuya açık
                // kopyası: Documents/UmayPDF (ADIM 1). Hata içe aktarmayı
                // iptal ETMEZ — sadece loglanır.
                if (convertedDoc) {
                    DocsExport.savePdf(ctx, dest, book.title)
                }
                onDone(Result.success(book))
            } catch (t: Throwable) {
                logError(ctx, "importPdf", t)
                onDone(Result.failure(Exception(t.message ?: t.javaClass.simpleName, t)))
            }
        }
    }

    /**
     * Seçilen fotoğrafları sırayla tek bir PDF'e çevirir (A4, beyaz zemin,
     * EXIF yönü uygulanır) ve kütüphaneye kitap olarak ekler.
     * Taranmış sayfalar olduğu için çıkarma otomatik olarak OCR'e düşer.
     *
     * Güvenlik dersleri (4 Ekim çökmesinden sonra):
     *  - Her resim AYRI ayrı korunur: bozuk/desteklenmeyen bir resim
     *    tüm aktarımı durdurmaz, atlanır ve loglanır.
     *  - Bellek disiplini: çözünürlük A4 için 1600px ile sınırlı,
     *    JPEG 78; her resimden sonra bitmap hemen serbest.
     *  - Kayıttan SONRA PDF geri açılıp doğrulanır (bozuk yazım
     *    kitaplıkta "sonsuz çıkarma"ya yol açıyordu).
     *  - Hata olursa yarım dosyalar temizlenir + last_error.txt'ye yazılır.
     */
    fun importImages(
        ctx: Context,
        uris: List<Uri>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        onDone: (Result<Book>) -> Unit
    ) {
        executor.execute {
            var dir: File? = null
            try {
                require(uris.isNotEmpty()) { "En az bir resim seç" }

                // Kalıcı okuma izni (ACTION_OPEN_DOCUMENT kalıcı grant'ı için);
                // başarısız olursa mevcut geçici izin zaten yeterli.
                uris.forEach { u ->
                    try {
                        ctx.contentResolver.takePersistableUriPermission(
                            u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}
                }

                // Sayfa numarasına göre doğal sıralama: "sayfa_9" → "sayfa_10"
                // (kullanıcı kitabı fotoğraflarken dosya adları sırayı verir)
                val ordered = uris
                    .map { u -> u to (queryDisplayName(ctx, u) ?: "") }
                    .sortedBy { (_, name) -> TextUtil.naturalKey(name) }
                    .map { (u, _) -> u }

                val id = System.currentTimeMillis().toString(36)
                dir = bookDir(ctx, id)
                val dest = File(dir, "source.pdf")

                var added = 0
                var skipped = 0
                var firstError: String? = null
                PDDocument().use { doc ->
                    ordered.forEachIndexed { index, uri ->
                        try {
                            val bmp = loadBitmapForPdf(ctx, uri)
                                ?: throw IllegalStateException("resim çözülemedi (format/boyut)")
                            val jpeg = java.io.ByteArrayOutputStream().also { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
                            }
                            bmp.recycle()

                            val img = PDImageXObject.createFromByteArray(
                                doc, jpeg.toByteArray(), "img"
                            )
                            val page = PDPage(PDRectangle.A4)
                            doc.addPage(page)
                            PDPageContentStream(doc, page).use { cs ->
                                val scale = minOf(
                                    PDRectangle.A4.width / img.width,
                                    PDRectangle.A4.height / img.height
                                )
                                val w = img.width * scale
                                val h = img.height * scale
                                cs.drawImage(
                                    img,
                                    (PDRectangle.A4.width - w) / 2f,
                                    (PDRectangle.A4.height - h) / 2f,
                                    w, h
                                )
                            }
                            added++
                        } catch (t: Throwable) {
                            // Tek bozuk resim (CMYK JPEG, HEIC…) tüm PDF'i
                            // çökertmesin: atla, logla, devam et.
                            skipped++
                            if (firstError == null) firstError = t.message ?: t.javaClass.simpleName
                            logError(ctx, "importImages[${index + 1}/${ordered.size}]", t)
                        }
                        onProgress?.invoke(index + 1, ordered.size)
                    }
                    require(added > 0) {
                        "Resimler çevrilemedi ($skipped/" + ordered.size +
                            " hata). İlk hata: " + (firstError ?: "bilinmiyor")
                    }
                    doc.save(dest)
                }

                // Kaydedilen PDF'i geri açıp doğrula
                val pageCount = PDDocument.load(dest).use { d ->
                    require(d.numberOfPages > 0) { "PDF sayfa içermiyor" }
                    d.numberOfPages
                }
                require(dest.length() > 0L) { "PDF yazılamadı" }

                val title = if (skipped == 0) "Fotoğraflar ($pageCount sayfa)"
                else "Fotoğraflar ($pageCount sayfa • $skipped resim atlandı)"
                val book = Book(id, title, pageCount, System.currentTimeMillis())
                val books = loadBooks(ctx).toMutableList()
                books.add(book)
                saveBooks(ctx, books)

                ensureCover(ctx, id)   // kitaplıkta kapak görünsün
                ensureExtracting(ctx, id)

                // Oluşturulan PDF'in kamuya açık kopyası: Documents/UmayPDF (ADIM 1)
                DocsExport.savePdf(ctx, dest, book.title)
                onDone(Result.success(book))
            } catch (t: Throwable) {
                // Yarım kalan klasörü temizle (kitaplığa da girmedi)
                try { dir?.deleteRecursively() } catch (_: Exception) {}
                logError(ctx, "importImages", t)
                onDone(Result.failure(Exception(t.message ?: t.javaClass.simpleName, t)))
            }
        }
    }

    /** Son hataları filesDir/logs/last_error.txt'ye yazar (cihazda teşhis için). */
    fun logError(ctx: Context, where: String, t: Throwable) {
        try {
            val f = File(File(ctx.filesDir, "logs").apply { mkdirs() }, "last_error.txt")
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText(buildString {
                appendLine("=== $stamp | $where ===")
                appendLine(t.toString())
                t.stackTrace.take(18).forEach { appendLine("  at $it") }
                appendLine()
            })
            // Günlük büyümesin: 64KB'ı aşarsa son 16KB'ı tut
            if (f.length() > 64 * 1024) {
                val all = f.readText()
                f.writeText(all.takeLast(16 * 1024))
            }
        } catch (_: Exception) {}
    }

    /**
     * Resmi (yön bilgisiyle) PDF'e sığacak boyutta çözer.
     *
     * DİKKAT (5 Ekim "hiçbiri okunamadı" hatası): `inJustDecodeBounds = true`
     * ile `decodeStream` **null** döner — bu yüzden boyut okuma ifadesinin
     * sonuna `?: return null` konursa fonksiyon HER ZAMAN null döner ve
     * bütün resimler "okunamadı" sanılır. Boyut okuması artık ayrı ifade.
     */
    private fun loadBitmapForPdf(ctx: Context, uri: Uri): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = ctx.contentResolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, opts) }  // null döner, normal
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null        // format çözülemedi

        // A4 sayfaya 1600px yeter (≈150 dpi); 2200+ görsellerde
        // PDFBox + OCR birlikte bellek taşırıyordu (çökme kaynağı).
        var sample = 1
        val maxSide = maxOf(opts.outWidth, opts.outHeight)
        while (maxSide / sample > 1600) sample *= 2

        val dec = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp: Bitmap? = null
        // OOM vb. durumlarda örneklemeyi artırıp bir kez daha dene
        for (attempt in 0 until 3) {
            try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    bmp = BitmapFactory.decodeStream(it, null, dec)
                }
            } catch (oom: OutOfMemoryError) {
                bmp = null
            }
            if (bmp != null) break
            dec.inSampleSize *= 2
        }
        val decoded = bmp ?: return null

        // EXIF yönü (fotoğraf makinesi döndürmesi)
        return try {
            val stream = ctx.contentResolver.openInputStream(uri) ?: return decoded
            val exif = androidx.exifinterface.media.ExifInterface(stream)
            val orientation = exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )
            val rotation = when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotation != 0f) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
                val rotated = Bitmap.createBitmap(
                    decoded, 0, 0, decoded.width, decoded.height, matrix, true
                )
                if (rotated != decoded) decoded.recycle()
                return rotated
            }
            decoded
        } catch (e: Exception) {
            decoded   // EXIF okunamadı → döndürmeden devam et
        }
    }

    fun deleteBook(ctx: Context, id: String) {
        val books = loadBooks(ctx).filterNot { it.id == id }
        saveBooks(ctx, books)
        File(booksDir(ctx), id).deleteRecursively()
        // İlerleme + son okuma kayıtları da temizlensin (büyüklenme sızıntısı)
        AppPrefs.clearBookMeta(ctx, id)
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

    /**
     * Doğal sıralama `TextUtil.naturalKey` ile yapılır (test edilebilir).
     */

    // ── Metin çıkarma ─────────────────────────────────────────

    /** Çıkarılmamış sayfalar varsa arka planda çıkarmaya başlar. */
    fun ensureExtracting(ctx: Context, bookId: String) {
        if (extracting.contains(bookId)) return
        val book = loadBooks(ctx).firstOrNull { it.id == bookId } ?: return
        val missing = (0 until book.pageCount).count { !pageFile(ctx, bookId, it).exists() }
        if (missing == 0 && !needsOcr(ctx, bookId) && !needsBoxes(ctx, bookId)) {
            ExtractionBus.finished(bookId)
            return
        }
        if (extracting.add(bookId)) {
            executor.execute {
                try {
                    extractBook(ctx, bookId)
                } catch (t: Throwable) {
                    // Exception YAKA Error'lar da var (ör. ExceptionInInitializerError);
                    // uygulama çökmesin, çıkarma bir sonraki açılışta tekrar denensin.
                    t.printStackTrace()
                    logError(ctx, "extractBook:$bookId", t)
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

    /**
     * Eski kitaplarda: metin dosyası var ama karaoke satır kutuları yok mu?
     * Varsa arka planda yalnız kutular üretilir (metin yeniden yazılmaz).
     */
    private fun needsBoxes(ctx: Context, bookId: String): Boolean {
        val book = loadBooks(ctx).firstOrNull { it.id == bookId } ?: return false
        for (i in 0 until book.pageCount) {
            val f = pageFile(ctx, bookId, i)
            if (f.exists() && f.length() > 0) {
                val bf = boxesFile(ctx, bookId, i)
                if (!bf.exists() || bf.length() == 0L) return true
            }
        }
        return false
    }

    /** Bu kitap için çıkarma/OCR hâlâ arka planda sürüyor mu? */
    fun isExtracting(ctx: Context, bookId: String): Boolean = extracting.contains(bookId)

    private fun extractBook(ctx: Context, bookId: String) {
        val book = loadBooks(ctx).firstOrNull { it.id == bookId } ?: return
        val src = sourceFile(ctx, bookId)
        if (!src.exists()) return

        // Güvenlik: PDFBox resource'ları hazırlı değilse şimdi hazırla
        // (Application onCreate'de yapılsa da savunma olarak).
        UmayApp.initPdfBox(ctx)

        var renderer: PdfRenderer? = null
        var rendererPfd: ParcelFileDescriptor? = null

        try {
            PDDocument.load(src).use { doc ->
                // BoxStripper: metinle AYNI geçişte satır kutularını da
                // toplar (karaoke) — metin çıkarma davranışı değişmez.
                val stripper = BoxStripper().apply { sortByPosition = true }
                val total = book.pageCount
                var done = 0

                for (i in 0 until total) {
                    val f = pageFile(ctx, bookId, i)
                    val bf = boxesFile(ctx, bookId, i)
                    val hasText = f.exists() && f.length() > 0
                    val hasBoxes = bf.exists() && bf.length() > 0

                    if (hasText && hasBoxes) {
                        done++
                        ExtractionBus.progress(bookId, done, total, "text")
                        continue
                    }

                    // Eski kitap: metin var, yalnız karaoke kutuları eksik
                    if (hasText) {
                        try {
                            stripper.beginPageCapture()
                            stripper.startPage = i + 1
                            stripper.endPage = i + 1
                            stripper.getText(doc)
                            val boxes = stripper.finishCapture()
                            if (boxes.isNotEmpty()) {
                                bf.writeText(LineBoxes.encodeAll(boxes))
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        done++
                        ExtractionBus.progress(bookId, done, total, "text")
                        continue
                    }

                    var text = ""
                    var boxes: List<LineBox> = emptyList()
                    try {
                        stripper.beginPageCapture()
                        stripper.startPage = i + 1
                        stripper.endPage = i + 1
                        text = stripper.getText(doc) ?: ""
                        boxes = stripper.finishCapture()
                    } catch (e: Exception) {
                        // metin çıkarılamadı, OCR'a düşer
                        boxes = try { stripper.finishCapture() } catch (x: Exception) { emptyList() }
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
                            val ocr = ocrPage(renderer!!, i)
                            if (ocr.text.isNotBlank()) {
                                text = ocr.text
                                // Kutular da OCR metniyle eşleşmeli
                                boxes = ocr.lines
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    f.writeText(text)
                    // Karaoke kutuları (varsa) — boş metinde üretilmez,
                    // bir sonraki açılışta tekrar denenir.
                    if (text.isNotBlank() && boxes.isNotEmpty()) {
                        bf.writeText(LineBoxes.encodeAll(boxes))
                    }
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

    /** OCR sonucu: metin + satır kutuları (karaoke için, normalize). */
    private data class OcrPage(val text: String, val lines: List<LineBox>)

    /** Sayfayı görüntüye render edip ML Kit OCR ile metne çevirir (offline). */
    private fun ocrPage(renderer: PdfRenderer, pageIndex: Int): OcrPage {
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

                // ML Kit satır kutularını normalize koordinata çevir
                val lines = ArrayList<LineBox>(64)
                try {
                    for (block in text.textBlocks) {
                        for (line in block.lines) {
                            val r = line.boundingBox ?: continue
                            lines.add(
                                LineBox(
                                    r.left.toFloat() / w,
                                    r.top.toFloat() / h,
                                    r.right.toFloat() / w,
                                    r.bottom.toFloat() / h,
                                    line.text ?: ""
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    // kutular yoksa vurgu olmadan devam
                }
                return OcrPage(text.text ?: "", lines)
            } finally {
                bmp.recycle()
                recognizer.close()
            }
        } finally {
            try { page.close() } catch (_: Exception) {}
        }
    }

    // ── Kapak önizlemesi ────────────────────────────

    fun coverFile(ctx: Context, id: String) = File(bookDir(ctx, id), "cover.jpg")

    /**
     * Kitaplık için küçük kapak görüntüsü (1. sayfadan, 180px).
     * Üretilmemişse PdfRenderer ile üretir; hata olursa null (liste
     * yine de simgeyle çalışır). Arka plan thread'inden çağır.
     */
    fun ensureCover(ctx: Context, id: String): File? {
        val f = coverFile(ctx, id)
        if (f.exists() && f.length() > 0L) return f
        val src = sourceFile(ctx, id)
        if (!src.exists()) return null

        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) return null

            val page = renderer.openPage(0)
            try {
                val targetW = 180
                val scale = targetW.toFloat() / page.width
                val bmp = Bitmap.createBitmap(
                    targetW,
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                java.io.ByteArrayOutputStream().also { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    f.writeBytes(out.toByteArray())
                }
                bmp.recycle()
                return f
            } finally {
                try { page.close() } catch (_: Exception) {}
            }
        } catch (t: Throwable) {
            logError(ctx, "ensureCover:$id", t)
            return null
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    // ── Sayfa metinleri ───────────────────────────────────────

    fun pageFile(ctx: Context, bookId: String, index: Int) =
        File(bookDir(ctx, bookId), "page_%04d.txt".format(index))

    /** Karaoke satır kutuları dosyası. */
    fun boxesFile(ctx: Context, bookId: String, index: Int) =
        File(bookDir(ctx, bookId), "boxes_%04d.txt".format(index))

    /** Satır kutularını okur (yoksa boş — vurgu olmadan çalışır). */
    fun readBoxes(ctx: Context, bookId: String, index: Int): List<LineBox> {
        val f = boxesFile(ctx, bookId, index)
        if (!f.exists()) return emptyList()
        return try {
            LineBoxes.decode(f.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

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
