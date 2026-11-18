package com.umay.pdfreader

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * v5.22 — TÜBİTAK 2209-A dokümanı.
 *
 * Kullanıcının verdiği Google Docs bağlantısı PDF olarak indirilip
 * `assets/tubitak_2209a.pdf` içine kondu (8 sayfa). Uygulama ilk açılışta
 * bunu kütüphaneye alır ve "Tübitak" rafına ekler. Bir kez çalışır.
 */
object TubitakDoc {

    private const val PREF = "tubitak_doc_imported_v5"
    private const val ASSET = "tubitak_2209a.pdf"
    private const val TITLE_HINT = "tubitak_2209a"

    fun ensureImported(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences("umay_prefs", 0)
            if (prefs.getBoolean(PREF, false)) return
            // Not: başlık kontrolü YAPILMAZ — mevcut "TUBITAK_2209A_..." kitapları
            // bu dokümanla karışıyordu. Yalnız pref bayrağına güvenilir.
            // FileProvider yalnız tanımlı alt dizinlere izin veriyor (cache/share).
            val shareDir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val out = File(shareDir, ASSET)
            if (!out.exists() || out.length() <= 0L) {
                ctx.assets.open(ASSET).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            if (out.length() <= 0L) return
            val uri: Uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", out
            )
            BookStore.importPdf(ctx, uri) { result ->
                try {
                    val book = result.getOrNull()
                    if (book != null) {
                        attachToTubitakShelf(ctx, book.id)
                        prefs.edit().putBoolean(PREF, true).apply()
                        SeyirDefteri.yaz(ctx, "TÜBİTAK", "doküman eklendi", book.title)
                    }
                } catch (t: Throwable) {
                    BookStore.logError(ctx, "TubitakDoc.attach", t)
                }
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "TubitakDoc.ensureImported", t)
        }
    }

    private fun attachToTubitakShelf(ctx: Context, bookId: String) {
        try {
            val shelves = ShelfManager.shelvesOf(ctx)
            val target = shelves.firstOrNull {
                TextUtil.normalize(it.name).contains("tubitak") ||
                    TextUtil.normalize(it.name).contains("2209")
            }
            if (target != null) {
                ShelfManager.addBookToShelf(ctx, bookId, target.id)
            } else {
                val id = ShelfManager.addShelf(ctx, "Tübitak 2209-A")
                ShelfManager.addBookToShelf(ctx, bookId, id)
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "TubitakDoc.attachToShelf", t)
        }
    }
}
