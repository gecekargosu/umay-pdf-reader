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
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
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
     * v5.4 (ANR ONARIMI) — Kitaplık bellekte tutulur.
     *
     * Eski kod her `loadBooks()` çağrısında SQLite'tan 500+ satır okuyordu ve
     * ana ekran her açılışta kitap başına 3 kez bunu yapıyordu (582 kitap →
     * 1.700+ tam tablo okuması → ANR). Artık liste bir kez okunur; yazma
     * işlemleri (`saveBooks` / `deleteBook`) cache'i tazeler.
     */
    @Volatile
    private var cache: List<Book>? = null

    /** Kitaplığı okur (ADIM 1): kaynak doğruluk artık **SQLite** (`LibraryDb`).
     *
     * - DB boşken eski `books.json` bir kez içeri alınır (geriye uyum geçişi).
     * - JSON dosyası silinmez; her kayıtta ayna olarak da yazılır (yedek).
     * - Okuma hatasında kitaplık asla boşalmaz: hata loglanır, JSON'a düşülür.
     */
    fun loadBooks(ctx: Context): List<Book> {
        cache?.let { return it }
        val fromDb = try {
            LibraryDb.get(ctx).readAll()
        } catch (t: Throwable) {
            logError(ctx, "LibraryDb.readAll", t)
            emptyList()
        }
        if (fromDb.isNotEmpty()) {
            cache = fromDb
            return fromDb
        }

        val legacy = readJsonIndex(ctx)
        if (legacy.isNotEmpty()) {
            try {
                LibraryDb.get(ctx).replaceAll(legacy)
            } catch (t: Throwable) {
                logError(ctx, "LibraryDb.migrate(json→sqlite)", t)
            }
        }
        cache = legacy
        return legacy
    }

    /** Kitaplık bellekteki kopyayı geçersiz kılar (dosya değiştiyse). */
    fun invalidateCache() {
        cache = null
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
        cache = books
        try {
            LibraryDb.get(ctx).replaceAll(books)
        } catch (t: Throwable) {
            // DB yazımı tutmadı → yine de JSON aynasını yaz, kütüphane kaybolmasın
            logError(ctx, "LibraryDb.replaceAll", t)
        }
        // v8 (KATEGORİ 1): JSON aynası da DISK HATASINA KARLI — yazma hatası
        // UI thread'de fırlarsa (Sil/İçe aktar) çökerdi; artık sessizce loglanır.
        try {
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
            // v34 — ATOMİK YAZMA: internet/şarj kesintisinde yarım kalan JSON
            // dosyası tüm kütüphaneyi kaybettiriyordu. Önce .tmp'ye yazılır,
            // sonra tek adımda yerine taşınır; eski dosya bozulmaz.
            writeAtomic(indexFile(ctx), arr.toString())
        } catch (t: Throwable) {
            logError(ctx, "saveBooks(json mirror)", t)
        }
    }

    /** v34 — güvenli (atomik) metin yazımı: .tmp → rename. */
    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // Bazı dosya sistemleri var olan dosyanın üzerine rename'i reddeder
            try { target.delete() } catch (_: Throwable) { }
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                try { tmp.delete() } catch (_: Throwable) { }
            }
        }
    }

    // ── v21 — KAYNAK KİMLİĞİ & TEKİLLEŞTİRME (idempotent import) ──────
    //
    // Aynı dosyanın 10 kez eklenmesi 10 kütüphane kaydı OLUŞTURMAZ.
    // Kaynak kimliği: dosya yolu (file://) veya sağlayıcı belge/medya
    // kimliği (content://) + boyut. Eşleme `books/sources.json`'da tutulur;
    // kitap silinince bu kayıt da düşer (kullanıcı isterse yeniden ekleyebilir).
    // Kimlik çözülemezse tekilleştirme sessizce atlanır, içe aktarma devam eder.

    private fun sourceIndexFile(ctx: Context) = File(booksDir(ctx), "sources.json")

    /** Dosyanın kararlı kaynak kimliği (yol/belge kimliği + boyut). */
    fun sourceKeyOf(ctx: Context, uri: Uri): String? {
        val path0 = uri.path
        if (uri.scheme == "file") {
            return try {
                if (path0 == null) null
                else {
                    val f = File(path0)
                    if (!f.exists()) null else "f:${f.canonicalPath}|${f.length()}"
                }
            } catch (_: Throwable) { null }
        }
        return try {
            when (uri.scheme) {
            else -> {
                var size = -1L
                try {
                    ctx.contentResolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
                    )?.use { c -> if (c.moveToFirst()) size = c.getLong(0) }
                } catch (_: Throwable) {}
                // 1) MediaStore._data → gerçek dosya yolu (varsa en kararlı kimlik)
                val path = try {
                    ctx.contentResolver.query(
                        uri,
                        arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                        null, null, null
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                } catch (_: Throwable) { null }
                // 2) SAF belge kimliği (ör. "primary:Download/x.pdf" — kararlı)
                val docId = try {
                    android.provider.DocumentsContract.getDocumentId(uri)
                } catch (_: Throwable) { null }
                val base = when {
                    !path.isNullOrBlank() -> "f:" + File(path).absolutePath
                    !docId.isNullOrBlank() -> "d:$docId"
                    else -> "u:" + uri.toString()
                }
                "$base|$size"
            }
        }
    } catch (_: Throwable) {
            null
        }
    }

    private fun readSourceIndex(ctx: Context): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        try {
            val f = sourceIndexFile(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                o.keys().forEach { k -> out[k] = o.optString(k, "") }
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun writeSourceIndex(ctx: Context, map: Map<String, String>) {
        try {
            val o = JSONObject()
            map.forEach { (k, v) -> if (v.isNotBlank()) o.put(k, v) }
            sourceIndexFile(ctx).writeText(o.toString())
        } catch (t: Throwable) {
            logError(ctx, "writeSourceIndex", t)
        }
    }

    /** Kaynak kimliği zaten kütüphanede kayıtlıysa o kitabı döndürür. */
    fun findBySourceKey(ctx: Context, key: String): Book? {
        val id = readSourceIndex(ctx)[key] ?: return null
        return loadBooks(ctx).firstOrNull { it.id == id }
    }

    /**
     * v22 (AŞAMA 5.5) — Tarama ekranı için TÜM kaynak anahtarlarını tek
     * seferde okur. findBySourceKey'yi dosya başına çağırmak sources.json'ı
     * 800 kez parse ederdi; bu yol tek JSON okur (liste durumu için).
     */
    fun sourceKeysSnapshot(ctx: Context): Set<String> = readSourceIndex(ctx).keys.toSet()

    private fun rememberSourceKey(ctx: Context, key: String, bookId: String) {
        val map = readSourceIndex(ctx)
        map[key] = bookId
        writeSourceIndex(ctx, map)
    }

    /** SAF'ten seçilen dosyayı (PDF/TXT/DOCX/MD/CSV) kütüphaneye alır. */
    /**
     * @param allowDuplicate true ise mükerrer kontrolü ATLANIR (kullanıcı
     *   "yine de ekle" dediğinde kullanılır). Varsayılan false: aynı kaynak
     *   ikinci kez eklenmez, mevcut kayıt döner.
     */
    fun importPdf(
        ctx: Context,
        uri: Uri,
        allowDuplicate: Boolean = false,
        onDone: (Result<Book>) -> Unit
    ) {
        executor.execute {
            try {
                // v21 — TEKİLLEŞTİRME (kullanıcı onayıyla aşılabilir)
                val srcKey = sourceKeyOf(ctx, uri)
                if (srcKey != null && !allowDuplicate) {
                    val existing = findBySourceKey(ctx, srcKey)
                    if (existing != null) {
                        CrashLog.log(ctx, "importPdf: zaten ekli (${existing.title})")
                        onDone(Result.success(existing))
                        return@execute
                    }
                }
                val id = System.currentTimeMillis().toString(36)
                val dir = bookDir(ctx, id)
                val dest = File(dir, "source.pdf")
                val name = queryDisplayName(ctx, uri) ?: dest.name
                var convertedDoc = false   // PDF'e çevrilen belge mi? (Documents kopyası için)

                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Dosya açılamadı")

                // v17 (EVRENSEL PARSER — GÖREV 1): uzantıya GÜVENME, magic
                // byte'a bak (UniversalParser). Böylece:
                //  • "kitap.pdf" ama aslında metin olan dosya → PDF'e çevrilir
                //    (eski kodda PDDocument.load patlıyordu → "okunamadı").
                //  • uzantısı desteklenmeyen (.tex, .adoc, uzantısız Kur'an
                //    metni vb.) ama İÇERİĞİ düz metin olan dosya → çevrilir.
                //  • .docx/.pptx/.epub ZIP magic ile tanınır.
                val isRealPdf = UniversalParser.isPdf(dest)
                val pageCount = if (isRealPdf) {
                    PDDocument.load(dest).use { it.numberOfPages }
                } else if (DocumentConverter.isSupported(name) ||
                    UniversalParser.looksLikeText(dest) || UniversalParser.isZip(dest)
                ) {
                    // v17: düz metin dosyalarında ÖN KONTROL — UniversalParser
                    // kodlamayı çözer (Kur'an/UTF-16/1254), özellikleri çıkarır;
                    // parser patlarsa burada yakalanıp umay_error.log'a düşer.
                    // (ZIP'lerde (DOCX/PPTX/EPUB) çift işçilik olmasın diye
                    //  ön kontrol YAPILMAZ; hata dönüşümde yakalanır.)
                    if (UniversalParser.looksLikeText(dest) &&
                        !UniversalParser.isZip(dest)
                    ) {
                        val probe = UniversalParser.safeParagraphs(dest, name) { w, t ->
                            CrashLog.recordParse(ctx, w, name, t)
                        }
                        if (!probe.ok) {
                            throw IllegalArgumentException(
                                "'$name' okunamadı (" +
                                    (probe.error?.message ?: "bilinmeyen parser hatası") +
                                    "). Detay: Ayarlar → Hata Günlüğü (umay_error.log)"
                            )
                        }
                        // Tek satırlık özet: tür/kodlama/arapça/latex/kod izi
                        CrashLog.notePublic(
                            ctx, "importPdf",
                            name + " → " +
                                UniversalParser.describe(
                                    DocumentConverter.extension(name)
                                        .ifBlank { "text" },
                                    probe.features
                                )
                        )
                    }
                    val converted = File(dir, "converted.pdf")
                    val n = try {
                        DocumentConverter.convert(dest, converted, name)
                    } catch (t: Throwable) {
                        // v17 (GÖREV 2): hata asla sessiz kalmaz — yapılandırılmış
                        // olarak umay_error.log + crash_log.txt'ye yazılır, sonra
                        // kullanıcıya anlaşılır mesajla fırlatılır.
                        CrashLog.recordParse(ctx, "importPdf.convert", name, t)
                        throw IllegalArgumentException(
                            "'$name' okunamadı (${t.message ?: t.javaClass.simpleName}). " +
                                "Detay: Ayarlar → Hata Günlüğü (umay_error.log)", t
                        )
                    }
                    dest.delete()
                    converted.renameTo(dest)
                    require(n > 0) { "Dosya PDF'e çevrilemedi" }
                    convertedDoc = true
                    n
                } else {
                    // v17: desteklenmeyen BİÇİM (ikili) — net mesaj + log
                    CrashLog.recordParse(
                        ctx, "importPdf.format", name,
                        IllegalArgumentException("'$name' desteklenmeyen ikili biçim")
                    )
                    throw IllegalArgumentException(
                        "'$name' desteklenmiyor. Kullanılabilir: PDF, DOCX, PPTX, " +
                            "DOC, PPT, TXT, MD, CSV, EPUB, LaTeX (.tex), " +
                            "Kur'an/arapça metin dosyaları (UTF-8)"
                    )
                }

                val book = Book(id, name, pageCount, System.currentTimeMillis())
                val books = loadBooks(ctx).toMutableList()
                books.add(book)
                saveBooks(ctx, books)

                // v21: kaynak kimliğini kaydet (sonraki taramalarda tekilleştirme)
                if (srcKey != null) rememberSourceKey(ctx, srcKey, id)

                ensureCover(ctx, id)   // kitaplıkta kapak görünsün
                ensureExtracting(ctx, id)

                // v5.4 — Dosya adında ders kodu varsa derse + dersin rafına
                // otomatik bağla ("BLG205 Slayt3.pptx" → BLG205 dersi).
                // Kod yoksa hiçbir şey yapılmaz; hata içe aktarmayı durdurmaz.
                try { CourseStore.autoLinkByTitle(ctx, book) } catch (_: Throwable) {}

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
        ocrTexts: List<String?>? = null,
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
                                val dx = (PDRectangle.A4.width - w) / 2f
                                val dy = (PDRectangle.A4.height - h) / 2f
                                cs.drawImage(img, dx, dy, w, h)

                                // v5.1 (ADIM B.4 / #5) — KOPYALANABİLİR METİN KATMANI:
                                // OCR çıktısı sayfaya GÖRÜNMEZ (alpha 0) ama
                                // seçilebilir/kopyalanabilir olarak basılır. Böylece
                                // taranan PDF'te metin arama + kopyalama çalışır.
                                val ocr = ocrTexts?.getOrNull(index)?.trim()
                                if (!ocr.isNullOrEmpty()) {
                                    drawInvisibleTextLayer(cs, ocr, w, h)
                                }
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

    /**
     * v11 — Bilgilendirme satırı (hata değil) filesDir/logs/last_error.txt'ye.
     * Teşhis panelinde "Raporu Kopyala" için kullanılır; hatasız olaylar da
     * görülebilsin diye eklendi.
     */
    fun logMessage(ctx: Context, where: String, msg: String) {
        try {
            val f = File(File(ctx.filesDir, "logs").apply { mkdirs() }, "last_error.txt")
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("=== $stamp | $where | INFO ===\n")
            f.appendText(msg)
            f.appendText("\n\n")
            if (f.length() > 64 * 1024) {
                val all = f.readText()
                f.writeText(all.takeLast(16 * 1024))
            }
        } catch (_: Exception) {}
    }

    /**
     * v36 — Bilgi günlüğü (ön hazırlık raporu gibi). Hata günlüğüne `I/`
     * önekiyle yazılır; tanılama "E/" satırlarını saydığı için yeşil lambayı
     * yanlışlıkla sarıya çevirmez.
     */
    fun logInfo(ctx: Context, where: String, message: String) {
        try {
            val f = File(File(ctx.filesDir, "logs").apply { mkdirs() }, "last_error.txt")
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("I/ $stamp | $where | ${message.take(300)}\n")
            if (f.length() > 64 * 1024) {
                val all = f.readText()
                f.writeText(all.takeLast(16 * 1024))
            }
        } catch (_: Exception) {}
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
        // v6 (Aşama 6.2) — aynı olayı Crashlytics + crash_log.txt'ye de yaz
        try { CrashLog.record(ctx, where, t) } catch (_: Throwable) {}
    }

    /**
     * Resmi (yön bilgisiyle) PDF'e sığacak boyutta çözer.
     *
     * DİKKAT (5 Ekim "hiçbiri okunamadı" hatası): `inJustDecodeBounds = true`
     * ile `decodeStream` **null** döner — bu yüzden boyut okuma ifadesinin
     * sonuna `?: return null` konursa fonksiyon HER ZAMAN null döner ve
     * bütün resimler "okunamadı" sanılır. Boyut okuması artık ayrı ifade.
     */
    /**
     * v5.1 (ADIM B.4 / #5) — Kopyalanabilir metin katmanı.
     *
     * OCR metnini sayfanın ÇİZGİLERİNİN ustune, alfa 0 ile (tamamen gorunmez)
     * basar. Sonuc: kullanici metni goremez ama sec/kopyala, icinde arama
     * (karaoke dahil) calisir. Metin sayfa ustune hizalanir (dx/dy), A4
     * sutununa word-wrap uygulanir; karakterler PDF WinAnsi'ye sokulur.
     */
    private fun drawInvisibleTextLayer(
        cs: PDPageContentStream,
        text: String,
        pageW: Float,
        pageH: Float
    ) {
        try {
            val margin = 24f
            val size = 10f
            val lineH = size * 1.25f
            val font = PDType1Font.HELVETICA
            val maxWidth = pageW - margin * 2
            val lines = wrapText(text, font, size, maxWidth)
            if (lines.isEmpty()) return
            // Alfa 0 → gozle gorunmez (sec/kopyala hala calisir).
            // NOT: pdfbox-android 2.0.27.0 portunda setNonStrokingAlpha()
            // YOK → ExtGState alphaConstant ile ayni etki yapilir.
            try {
                val gs = PDExtendedGraphicsState()
                gs.setNonStrokingAlphaConstant(0f)
                cs.setGraphicsStateParameters(gs)
            } catch (_: Throwable) {
                cs.setNonStrokingColor(255, 255, 255)   // alfa yoksa beyaz fallback
            }
            // Portta newLineAt() (mutlak konum) YOK → her satir ayri
            // beginText/endText icinde newLineAtOffset ile cizilir
            // (beginText metin matrisini sifirlar → mutlak konum dogru olur).
            var y = pageH - margin - size
            for (line in lines) {
                if (y < margin) break   // sayfa doldu, taşma yok
                cs.beginText()
                cs.setFont(font, size)
                cs.newLineAtOffset(margin, y)
                cs.showText(line)
                cs.endText()
                y -= lineH
            }
        } catch (t: Throwable) {
            // Metin katmanı zorunlu değil — hata PDF'i bozmasın
            android.util.Log.e("BookStore", "drawInvisibleTextLayer", t)
        }
    }

    /** WinAnsi'de olmayan Türkçe karakterleri yakına çevirir + kelime wrap. */
    private fun wrapText(text: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
        val clean = text
            .replace('ı', 'i').replace('İ', 'I')
            .replace('ş', 's').replace('Ş', 'S')
            .replace('ğ', 'g').replace('Ğ', 'G')
            .replace('ç', 'c').replace('Ç', 'C')
            .replace('ö', 'o').replace('Ö', 'O')
            .replace('ü', 'u').replace('Ü', 'U')
            .replace('\u00A0', ' ')
        val out = mutableListOf<String>()
        for (raw in clean.replace('\r', '\n').split('\n')) {
            val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            var cur = StringBuilder()
            for (w in words) {
                val candidate = if (cur.isEmpty()) w else "$cur $w"
                val width = try { font.getStringWidth(candidate) / 1000f * size } catch (_: Throwable) { 0f }
                if (width > maxWidth && cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur = StringBuilder(w)
                } else {
                    cur = StringBuilder(candidate)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
        }
        return out
    }

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
        val current = loadBooks(ctx)
        // v17 (ONARIM): Kitaplık OKUNAMADIYSA (boş döndüyse) indeksi boş
        // listeyle ÜZERİNE YAZMA — tek kitap silerken tüm kütüphane
        // silinebiliyordu (DB geçici hatası + boş ayna). Güvenli durumda
        // yalnız bu kitabın kaydı düşülür, dosyalar yine temizlenir.
        if (current.isEmpty()) {
            try {
                CrashLog.log(ctx, "deleteBook: index bos/gunclenemedi id=$id — yazim atlanti")
            } catch (_: Throwable) {}
        } else {
            saveBooks(ctx, current.filterNot { it.id == id })
        }
        // Fiziksel PDF + page_NNNN.txt + boxes + kapak önbelleği (cover.jpg)
        File(booksDir(ctx), id).deleteRecursively()
        // İlerleme + son okuma kayıtları da temizlensin (büyüklenme sızıntısı)
        AppPrefs.clearBookMeta(ctx, id)
        if (AppPrefs.getString(ctx, AppPrefs.KEY_LAST_BOOK) == id) {
            AppPrefs.putString(ctx, AppPrefs.KEY_LAST_BOOK, "")
        }
        // v6 (Aşama 3) — ders kaynaklarındaki asılı bağlantıları da temizle
        try { CourseStore.unlinkBook(ctx, id) } catch (_: Throwable) {}
        // v20 (ADIM 2): kitap raftaysa raf listelerinde asılı kalmasın —
        // ShelfManager.onBookDeleted raf kayıtlarından kitap id'sini düşer
        try { ShelfManager.onBookDeleted(ctx, id) } catch (_: Throwable) {}
        // v21: kaynak indeksinden bu kitabın anahtarları da düşürülür —
        // silinen kayıt bir sonraki taramada kendiliğinden GERİ GELMEZ,
        // ama kullanıcı aynı dosyayı açıkça yeniden ekleyebilir.
        try {
            val map = readSourceIndex(ctx)
            val before = map.size
            map.entries.removeAll { it.value == id }
            if (map.size != before) writeSourceIndex(ctx, map)
        } catch (_: Throwable) {}
        // v6 — günlüğe iz bırak (sonraki açılışta teşhis)
        try { CrashLog.log(ctx, "book_deleted id=$id") } catch (_: Throwable) {}
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        // v21.2 — file:// URI'de ContentResolver.query ÇALIŞMAZ; eski kod
        // null dönüyor ve kitap adı "source.pdf" olarak kalıyordu (kullanıcı
        // raporu: bütün kitaplar aynı ad).
        if (uri.scheme == "file") {
            val name = uri.lastPathSegment
            if (!name.isNullOrBlank()) return name
        }
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }

    /**
     * v21.2 — ONARIM: file:// ile içe aktarılan kitapların adı "source.pdf"
     * kalmıştı. Kaynak dizinindeki (sources.json) dosya yollarından gerçek
     * adlar geri yazılır. Idempotent; her açılışta değil, sürüm bayrağıyla
     * bir kez çalışır (UmayApp).
     */
    fun repairGenericTitles(ctx: Context) {
        try {
            val books = loadBooks(ctx).toMutableList()
            if (books.isEmpty()) return
            val idToName = HashMap<String, String>()
            readSourceIndex(ctx).forEach { (key, id) ->
                if (!key.startsWith("f:")) return@forEach
                val path = key.removePrefix("f:").substringBefore('|')
                val name = File(path).name
                if (name.isNotBlank()) idToName[id] = name
            }
            var changed = 0
            for (i in books.indices) {
                val b = books[i]
                val generic = b.title.isBlank() ||
                    b.title.equals("source.pdf", true) ||
                    b.title.equals("converted.pdf", true)
                if (!generic) continue
                val real = idToName[b.id] ?: continue
                books[i] = b.copy(title = real)
                changed++
            }
            if (changed > 0) {
                saveBooks(ctx, books)
                invalidateCache()
                logMessage(ctx, "repairGenericTitles", "$changed kitap adı düzeltildi")
            }
        } catch (t: Throwable) {
            logError(ctx, "repairGenericTitles", t)
        }
    }

    /**
     * Doğal sıralama `TextUtil.naturalKey` ile yapılır (test edilebilir).
     */

    // ── Metin çıkarma ─────────────────────────────────────────

    /**
     * v5.4 (ANR ONARIMI) — Bu oturumda "tam" olduğu doğrulanmış kitaplar.
     * Doğrulama sayfa dosyalarının tek tek `exists()` kontrolünü gerektirir;
     * 42.000+ sayfada bu iş her açılışta tekrarlanınca ana ekran donuyordu.
     * Bir kez doğrulanan kitap, yeniden içe aktarılana kadar atlanır.
     */
    private val verified: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Kapak üretilemeyen kitaplar (bu oturumda yeniden denenmez). */
    private val coverFailed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Bir kitabın çıkarımı yeniden gerektiğinde doğrulama işaretini sil. */
    fun forgetVerified(bookId: String) {
        verified.remove(bookId)
        coverFailed.remove(bookId)
    }

    /** Çıkarılmamış sayfalar varsa arka planda çıkarmaya başlar. */
    fun ensureExtracting(ctx: Context, bookId: String) {
        val book = loadBooks(ctx).firstOrNull { it.id == bookId } ?: return
        ensureExtracting(ctx, book)
    }

    /** v5.4 — Kitap nesnesi zaten elimizdeyse DB'yi yeniden okumadan başlat. */
    fun ensureExtracting(ctx: Context, book: Book) {
        val bookId = book.id
        if (extracting.contains(bookId)) return
        if (verified.contains(bookId)) {
            ExtractionBus.finished(bookId)
            return
        }
        val hasMissing = (0 until book.pageCount).any { !pageFile(ctx, bookId, it).exists() }
        if (!hasMissing && !needsOcr(ctx, book) && !needsBoxes(ctx, book)) {
            verified.add(bookId)
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

    private fun needsOcr(ctx: Context, book: Book): Boolean {
        val bookId = book.id
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
    private fun needsBoxes(ctx: Context, book: Book): Boolean {
        val bookId = book.id
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
        verified.remove(bookId)
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
                        // v5.4 (ONARIM 3) — İKİ SÜTUNLU SAYFALAR:
                        // PDFBox, aynı yükseklikteki iki sütunu TEK satıra
                        // birleştiriyordu ("…açıklama5.Toplam"). Karakter
                        // koordinatlarıyla sütunlara ayırır; tek sütunlu
                        // sayfada null döner ve ham metin korunur.
                        try {
                            val colText = stripper.columnText()
                            if (!colText.isNullOrBlank() &&
                                colText.length >= (text.trim().length * 0.6f)
                            ) {
                                text = colText
                                android.util.Log.d(
                                    "BookStore", "sütun düzeni uygulandı: sayfa ${i + 1}"
                                )
                            }
                        } catch (_: Throwable) {
                            // sütun ayrımı başarısız → ham metinle devam
                        }
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
            // v5.4 (ONARIM): Taranan sayfa 2x (~144 DPI) render ediliyordu →
            // küçük punto metinlerde ML Kit çöp üretiyordu ("LGENE LCLER…").
            // 3x (~216 DPI) + uzun kenar 2600 px sınırı: hem doğruluk artar
            // hem de bellek taşmaz (A4 için ~1750x2480).
            var scale = 3.0f
            val longSide = maxOf(page.width, page.height) * scale
            if (longSide > 2600f) scale *= 2600f / longSide
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
        // v5.5 (ONARIM 9 / H10): kapak üretimi başarısız olan kitap bu oturumda
        // BİR DAHA denenmez. Eskiden "Unable to load the document!" hatası veren
        // kitap için her listede yeniden PdfRenderer açılıyordu (bozuk PDF'te
        // açılışta ek yük + log şişmesi).
        if (id in coverFailed) return null
        val src = sourceFile(ctx, id)
        if (!src.exists()) return null

        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) {
                coverFailed.add(id)
                return null
            }

            val page = renderer.openPage(0)
            try {
                // v7 — KAPAK RENDER FIX:
                //  • bozuk PDF'te sayfa ölçüleri 0 çıkabiliyor → 0 genişlikte
                //    bitmap create IllegalArgumentException fırlatıyordu.
                //  • ölçüler normalize edilir, hedef en 180px, boyut sınırı var.
                val pw = page.width
                val ph = page.height
                if (pw <= 0 || ph <= 0) {
                    coverFailed.add(id)
                    return null
                }
                val targetW = 180
                val scale = targetW.toFloat() / pw
                val bmp = Bitmap.createBitmap(
                    targetW,
                    (ph * scale).toInt().coerceIn(1, 1200),
                    Bitmap.Config.ARGB_8888
                )
                // v7 — RENK UZAYI: PDF sayfaları opaktır. alpha açıkken bazı
                // cihazlarda render yanlış renk uzayına düşüp koyu/turkuaz blok
                // üretiyordu; alpha kapatılır ve zemin açık beyaza boyanır.
                bmp.setHasAlpha(false)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                // v7 — Render boş/beyaz kaldıysa kapak diye gösterme (fallback'e düş)
                if (looksBlank(bmp)) {
                    bmp.recycle()
                    f.delete()
                    coverFailed.add(id)
                    return null
                }
                java.io.ByteArrayOutputStream().also { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    f.writeBytes(out.toByteArray())
                }
                bmp.recycle()
                return f
            } finally {
                try { page.close() } catch (_: Exception) {}
            }
        } catch (t: Throwable) {
            logError(ctx, "ensureCover:$id", t)
            coverFailed.add(id)
            return null
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * v7 — Örnekleyerek tarar: 40×40 = 1600 noktanın neredeyse tamamı beyazsa
     * render BOŞ kabul edilir (kapak yerine yedek şablon gösterilir).
     */
    private fun looksBlank(bmp: Bitmap): Boolean {
        return try {
            val g = 40
            var nonWhite = 0
            for (gy in 0 until g) {
                val y = (bmp.height - 1) * gy / (g - 1)
                for (gx in 0 until g) {
                    val x = (bmp.width - 1) * gx / (g - 1)
                    val c = bmp.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val gg = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r < 235 || gg < 235 || b < 235) nonWhite++
                }
            }
            nonWhite < 3
        } catch (_: Throwable) {
            false
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

    /**
     * v5.4 (ONARIM 3) — TEK SEFERLİK METİN YENİLEME.
     *
     * Eski sürümlerde çıkarılan sayfa metinleri iki sütunlu sayfalarda
     * karışıktı (kelimeler yapışık, sıra bozuk: "1. madde 5. madde 2…").
     * Bu yüzden bir kez tüm `page_*.txt` dosyaları silinir; kitap açıldığında
     * arka planda (tek iş parçacıklı kuyrukta) yeniden çıkarılır.
     * PDF kaynakları ve karaoke kutuları korunur.
     */
    fun migratePageTextV2(ctx: Context) {
        try {
            if (AppPrefs.getBool(ctx, "page_text_v2", false)) return
            val root = File(ctx.filesDir, "books")
            var deleted = 0
            root.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                dir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("page_") && f.name.endsWith(".txt")) {
                        if (f.delete()) deleted++
                    }
                }
            }
            AppPrefs.putBool(ctx, "page_text_v2", true)
            android.util.Log.i("BookStore", "page_text_v2 göçü: $deleted sayfa yenilenecek")
        } catch (t: Throwable) {
            logError(ctx, "migratePageTextV2", t)
        }
    }

    /**
     * Sayfa metni; henüz çıkarılmamışsa null döner.
     *
     * v5.4 (ONARIM): İki sütunlu sayfalarda satırlar PDFBox'tan y eksenine
     * göre geldiği için okuma sırası karışıyordu ("1. madde, 5. madde, 2…").
     * Metin, sayfanın satır kutuları varsa [ColumnOrder] ile sütun sırasına
     * dizilir. Böylece ESKİ kitaplar da yeniden çıkarma gerekmeden düzelir.
     */
    fun pageText(ctx: Context, bookId: String, index: Int): String? {
        val f = pageFile(ctx, bookId, index)
        if (!f.exists()) return null
        val raw = try {
            f.readText()
        } catch (_: Throwable) {
            return null
        }
        return try {
            ColumnOrder.reorderText(raw, readBoxes(ctx, bookId, index))
        } catch (_: Throwable) {
            raw
        }
    }

    /** Tüm sayfaların metinlerini sırayla döndürür (sohbet araması için). */
    fun allPages(ctx: Context, bookId: String): List<String> {
        val book = loadBooks(ctx).firstOrNull { it.id == bookId } ?: return emptyList()
        return (0 until book.pageCount).map { pageText(ctx, bookId, it) ?: "" }
    }
}
