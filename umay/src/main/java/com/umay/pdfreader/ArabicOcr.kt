package com.umay.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * v5.12 — ARAPÇA SAYFA OKUMA (OCR köprüsü).
 *
 * Durum: meal/tefsir PDF'lerinde Arapça satırlar **görsel olarak** var ama
 * metin katmanında yok (font → Unicode eşlemesi bozuk; `page_0030.txt` içinde
 * tek bir Arapça karakter bile yok). ML Kit'te Arapça OCR modeli bulunmuyor,
 * bu yüzden sayfa görüntüsü Gemini Vision'a gönderilip düz metne çevriliyor
 * (programda ders programı görseli için zaten kullanılan ücretsiz yol).
 *
 * Tasarım kuralları:
 *  - **Önbellek şart**: sayfa başına tek istek. `ocr_0030.txt` yazıldıktan
 *    sonra o sayfa bir daha API'ye gitmez (internet olmadan da okunur).
 *  - **Asla çökmez**: render/ağ/anahtar hatası → null döner, okuma mevcut
 *    metin katmanıyla devam eder.
 *  - **Bellek güvenli**: bitmap ~1500 px genişliğe ölçeklenir ve iş bitince
 *    geri dönüştürülür.
 */
object ArabicOcr {

    /** OCR için sayfa genişliği: 1500 px, Arapça harekeleri korumaya yeter. */
    private const val MAX_WIDTH = 1500

    /** En fazla bu kadar büyük JPEG gönderilir (bellek/istek sınırı). */
    private const val MAX_JPEG_BYTES = 3_500_000

    /** Son denemede başarısız olan sayfalar — aynı oturumda sonsuz tekrar yok. */
    private val recentlyFailed = ConcurrentHashMap<String, Long>()

    /** Başarısız sayfa için yeniden deneme bekleme süresi (ms). */
    private const val RETRY_AFTER_MS = 45_000L

    /** Arka plan OCR işleri (aynı sayfa iki kez istenmesin). */
    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Sayfa başına önbellek dosyası. */
    fun cacheFile(ctx: Context, bookId: String, page: Int): File =
        File(BookStore.booksDir(ctx), "$bookId/ocr_${"%04d".format(Locale.US, page)}.txt")

    /** Önbellekte varsa metin, yoksa null. */
    fun cached(ctx: Context, bookId: String, page: Int): String? = try {
        val f = cacheFile(ctx, bookId, page)
        if (f.exists() && f.length() > 0) f.readText().trim().ifBlank { null } else null
    } catch (_: Throwable) {
        null
    }

    /** OCR metnini kaydeder (boş metin yazılmaz). */
    private fun save(ctx: Context, bookId: String, page: Int, text: String) {
        try {
            if (text.isBlank()) return
            val f = cacheFile(ctx, bookId, page)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ArabicOcr.save", t)
        }
    }

    /**
     * Sayfanın Arapça+Türkçe metnini döndürür: önbellek → yoksa OCR.
     * @return metin (boşsa null)
     */
    /**
     * Sayfa metnini döndürür: önbellek → yoksa OCR.
     *
     * v31 — `waitMs`: OCR önbellekte yoksa en fazla bu kadar beklenir.
     * 0 → hiç beklemez (okuma akışı takılmasın); OCR arka planda başlatılır
     * ve sayfa bir sonraki okumada hazır olur.
     */
    suspend fun cachedOrBuild(ctx: Context, bookId: String, page: Int, waitMs: Long = 0L): String? {
        cached(ctx, bookId, page)?.let { return it }
        val key = "$bookId:$page"
        // v31 — yakın zamanda başarısız olduysa ağı boşa yorma.
        val failedAt = recentlyFailed[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return null

        // İş HER DURUMDA arka plan kapsamında çalışır: bekleme süresi dolsa da
        // istek sürer ve sonuç önbelleğe yazılır. Böylece okuma akışı
        // engellenmez (önceden bloklayan ağ çağrısı iptal edilemiyordu).
        val deferred = jobs.getOrPut(key) {
            scope.async {
                try {
                    build(ctx, bookId, page).also { t ->
                        if (t == null) recentlyFailed[key] = System.currentTimeMillis()
                        else recentlyFailed.remove(key)
                    }
                } catch (t: Throwable) {
                    if (t !is kotlinx.coroutines.CancellationException) {
                        BookStore.logError(ctx, "ArabicOcr.async", t)
                    }
                    recentlyFailed[key] = System.currentTimeMillis()
                    null
                } finally {
                    jobs.remove(key)
                }
            }
        }
        if (waitMs <= 0L) return null              // hemen dön, arka planda hazırlanır
        return withTimeoutOrNull(waitMs) { deferred.await() }
            ?: cached(ctx, bookId, page)
    }

    /** OCR'ı arka planda başlatır (aynı sayfa için tek iş). */
    fun prefetch(ctx: Context, bookId: String, page: Int) {
        val key = "$bookId:$page"
        if (cached(ctx, bookId, page) != null) return
        val failedAt = recentlyFailed[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return
        if (jobs.containsKey(key)) return
        scope.launch { cachedOrBuild(ctx, bookId, page, waitMs = 120_000L) }
    }

    /** Sayfa görüntüsünü okuyup metne çevirir ve önbelleğe yazar. */
    private suspend fun build(ctx: Context, bookId: String, page: Int): String? {
        val jpeg = withContext(Dispatchers.IO) {
            try {
                renderJpeg(ctx, bookId, page)
            } catch (t: Throwable) {
                BookStore.logError(ctx, "ArabicOcr.render", t)
                null
            }
        } ?: return null

        // v31 — 1 hızlı yeniden deneme: kısa ağ kesintisi okumayı çöp metne
        // düşürmesin ("internet yok" hatası tek seferlik olabiliyor).
        var lastError: Throwable? = null
        for (attempt in 0..1) {
            try {
                val text = ApiClient.ocrPage(jpeg)
                    .replace("\r\n", "\n")
                    .trim()
                if (text.length < 8) return null
                save(ctx, bookId, page, text)
                return text
            } catch (t: Throwable) {
                lastError = t
                if (attempt == 0) delay(1_500L)
            }
        }
        lastError?.let { BookStore.logError(ctx, "ArabicOcr.api", it) }
        return null
    }

    /** PDF sayfasını JPEG'e çevirir. PDF değilse/aralık dışıysa null. */
    private fun renderJpeg(ctx: Context, bookId: String, page: Int): ByteArray? {
        val src = BookStore.sourceFile(ctx, bookId)
        if (!src.exists() || src.length() == 0L) return null
        val pfd = ParcelFileDescriptor.open(
            src, ParcelFileDescriptor.MODE_READ_ONLY
        )
        val renderer = try {
            PdfRenderer(pfd)
        } catch (t: Throwable) {
            try { pfd.close() } catch (_: Throwable) {}
            return null   // PDF değil (docx/pptx) → OCR atlanır
        }
        var bitmap: Bitmap? = null
        try {
            renderer.use { r ->
                if (page < 0 || page >= r.pageCount) return null
                r.openPage(page).use { p ->
                    val scale = (MAX_WIDTH.toFloat() / p.width).coerceIn(0.2f, 3f)
                    val w = (p.width * scale).toInt().coerceAtLeast(1)
                    val h = (p.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap = bmp
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ArabicOcr.renderer", t)
            return null
        } finally {
            try { pfd.close() } catch (_: Throwable) {}
        }

        val bmp = bitmap ?: return null
        return try {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            val bytes = out.toByteArray()
            if (bytes.size > MAX_JPEG_BYTES) null else bytes
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ArabicOcr.jpeg", t)
            null
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }
}
