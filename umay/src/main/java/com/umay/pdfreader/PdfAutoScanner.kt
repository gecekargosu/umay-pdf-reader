package com.umay.pdfreader

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * v21 — CİHAZ PDF KEŞFİ (salt OKUNUR — İÇE AKTARMA YOK).
 *
 * GEÇMİŞ: v11.1'de bu nesne MediaStore'daki son 2 ayın TÜM PDF'lerini
 * bulup `BookStore.importPdf` ile OTOMATİK kütüphaneye ekliyordu ve
 * MainActivity.onResume her oturumda çalıştırıyordu. Kullanıcı onayı
 * olmadan 50'ye kadar dosya toplu aktarılıyor, aynı dosyalar her taramada
 * çoğalıyor, seçilmeyen dosyalar rafalara dağılıyordu (bkz. BUG-001/002).
 *
 * v21 KURAL (denetim gereği): Tarama keşiftir; İÇE AKTARMA yalnızca
 * kullanıcı "Kütüphaneye Aktar"a bastığında olur. Bu nesne artık salt
 * `findRecentPdfs()` → URI listesi döndürür; hiçbir zaman kitap eklemez.
 * Gelecekteki "yeni dosyalar var — içe aktar?" kartı burayı kullanabilir.
 *
 * İZİN:
 *  - API 29-32: READ_EXTERNAL_STORAGE (Manifest'te zaten var).
 *  - API 33+  : READ_MEDIA_IMAGES / MANAGE_EXTERNAL_STORAGE.
 *  - İzin yoksa boş liste döner (uygulama çalışmaya devam eder).
 *
 * GÜVENLİK: MediaStore sorgusu salt OKUNUR; dosyalar silinmez/taşınmaz.
 */
object PdfAutoScanner {

    /**
     * Keşif aralığı: 2026-08-01 … 2026-11-01 (UTC ms).
     * (Tarih aralığı korundu; yalnızca artık import YAPMIYOR.)
     */
    private const val START_MILLIS = 1_785_561_600_000L   // 2026-08-01 00:00 UTC
    private const val END_MILLIS = 1_793_510_400_000L     // 2026-11-01 00:00 UTC

    /** Keşif üst sınırı (ANI/ANR koruması). */
    private const val MAX_FOUND = 200

    /**
     * Tarih aralığındaki PDF'lerin content:// URI'leri (salt OKUNUR).
     * İzin yoksa veya hata olursa boş liste — asla fırlatmaz.
     */
    fun findRecentPdfs(ctx: Context): List<Uri> = try {
        findPdfs(ctx)
    } catch (t: Throwable) {
        BookStore.logError(ctx, "PdfAutoScanner.findRecentPdfs", t)
        emptyList()
    }

    /** Kütüphanede hâlâ kayıtlı olmayan adların filtresi (göreli fark). */
    fun filterNewNames(ctx: Context, uris: List<Uri>): List<Uri> {
        val existing = try {
            BookStore.loadBooks(ctx).map { it.title.lowercase() }.toSet()
        } catch (_: Throwable) {
            emptySet<String>()
        }
        return uris.filter { uri ->
            val name = (queryName(ctx, uri) ?: "").lowercase()
            name.isNotEmpty() && name !in existing
        }
    }

    /** MediaStore'da tarih aralığındaki PDF dosyalarının content:// URI'leri. */
    private fun findPdfs(ctx: Context): List<Uri> {
        val out = ArrayList<Uri>(64)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        // DATE_MODIFIED * 1000 = epoch millis
        val selection = (
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} BETWEEN ? AND ? " +
                "AND ${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
            )
        val args = arrayOf(
            (START_MILLIS / 1000L).toString(),
            (END_MILLIS / 1000L).toString(),
            "application/pdf"
        )
        val sort = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection, selection, args, sort
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            while (c.moveToNext() && out.size < MAX_FOUND) {
                val id = c.getLong(idIdx)
                out.add(ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"), id
                ))
            }
        }
        return out
    }

    private fun queryName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(
            uri, arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Throwable) {
        null
    }
}
