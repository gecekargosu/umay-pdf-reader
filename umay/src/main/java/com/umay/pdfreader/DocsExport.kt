package com.umay.pdfreader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Uygulamanın oluşturduğu PDF'lerin kopyasını kullanıcının görebildiği
 * **Documents/UmayPDF** klasörüne yazar (ADIM 1 gereği).
 *
 * Kurallar:
 *  - Android 10+ (API 29): `MediaStore.Files` ile İZİN GEREKMEDEN yazılır
 *    (uygulamanın kendi dosyası olduğu için).
 *  - Android 8/9 (API 26–28): doğrudan `Documents/UmayPDF` yoluna yazılır;
 *    yazma izni yoksa `SecurityException` yakalanır, **kopyalama atlanır**
 *    (manifestte `maxSdkVersion="28"` ile izin isteniyor) ve hata günlüğüne
 *    düşülür.
 *  - Buradaki bir HATA ASLA içe aktarmayı iptal etmez: kitaplık her koşulda
 *    dolar, kopyalama en iyi niyetli bir ikincil adımdır.
 */
object DocsExport {

    /** Public Documents altındaki klasör adı. */
    const val DIR_NAME = "UmayPDF"

    /** Dosya adını dosya sistemi için güvenli hale getirir. */
    fun safeName(title: String): String {
        var base = title.substringBeforeLast('.')
        base = base.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim { it <= ' ' }.trim('.')
        if (base.isBlank()) base = "umay_kitap"
        if (base.length > 80) base = base.take(80).trimEnd()
        if (base.isBlank()) base = "umay_kitap"
        return "$base.pdf"
    }

    /**
     * `src` dosyasını Documents/UmayPDF altına kopyalar.
     * @return kopyalandı mı (false = atlandı, sebep log'a yazıldı)
     */
    fun savePdf(ctx: Context, src: File, displayName: String): Boolean {
        if (!src.exists() || src.length() <= 0L) return false
        return try {
            val name = safeName(displayName)
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(ctx, src, name)
            } else {
                saveLegacy(ctx, src, name)
            }
            if (!ok) {
                BookStore.logError(
                    ctx, "DocsExport",
                    IllegalStateException("Documents/UmayPDF kopyası yazılamadı: $name")
                )
            }
            ok
        } catch (t: Throwable) {
            // İzin yok / depolama dolu → içe aktarma bozulmasın
            BookStore.logError(ctx, "DocsExport:$displayName", t)
            false
        }
    }

    // ── Android 10+ ──────────────────────────────────────────

    private fun saveViaMediaStore(ctx: Context, src: File, name: String): Boolean {
        // v31 — DÜZELTME: aynı adlı dosya diskde kayıtlı değilse Android
        // "Failed to build unique file" (IllegalStateException) fırlatıyordu ve
        // dışa aktarma tümden başarısız oluyordu. Artık ad çakışması kendi
        // adımızla çözülür (") ve son çare olarak benzersiz adla tekrar denenir.
        val stem = name.removeSuffix(".pdf")
        var attempt = 0
        while (attempt < 3) {
            val tryName = if (attempt == 0) name else "$stem-${System.currentTimeMillis() % 100000}.pdf"
            try {
                return insertViaMediaStore(ctx, src, tryName)
            } catch (t: IllegalStateException) {
                // ad çakışması → benzersiz adla yeniden dene
                attempt++
            }
        }
        BookStore.logError(
            ctx, "DocsExport",
            IllegalStateException("MediaStore benzersiz dosya adı üretemedi: $name")
        )
        return false
    }

    private fun insertViaMediaStore(ctx: Context, src: File, name: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOCUMENTS + File.separator + DIR_NAME
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: return false
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null) > 0
        } catch (t: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw t
        }
    }

    // ── Android 8/9 (izinli) ─────────────────────────────────

    private fun saveLegacy(ctx: Context, src: File, name: String): Boolean {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            ?: return false
        val dir = File(docs, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return false

        // Aynı isimde dosya varsa " (1)", " (2)" ekle
        var dst = File(dir, name)
        val stem = name.removeSuffix(".pdf")
        var i = 1
        while (dst.exists() && i < 100) {
            dst = File(dir, "$stem ($i).pdf")
            i++
        }
        src.copyTo(dst, overwrite = true)
        MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), arrayOf("application/pdf"), null)
        return true
    }
}
