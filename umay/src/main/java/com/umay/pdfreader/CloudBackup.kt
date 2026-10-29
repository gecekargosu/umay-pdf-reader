package com.umay.pdfreader

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * CloudBackup — Gömme (nested zipped) tümü (books.json, profile.json, shelves.json)
 * içeren bir zip dosyası üzerinden kütüphane yapısını dışarıdan içe aktarır.
 * Tamamınes gölgesinde uzunluk/boyut kontrolü uygulanmıştır.
 */
object CloudBackup {

    private const val TAG = "CloudBackup"

    /** Park/kazanına ZIP'i indirip buraya bırakın — geri yükleme için. */
    const val BACKUP_FILE_NAME = "umay_backup.zip"

    /** Geri yükleme sırasında kullanılan geçici dosya dizini. */
    private const val TEMP_DIR_NAME = "umay_backup_tmp"

    /**
     * Yedek oluşturur: books.json + books/ (pdf + kapaklar) + profile.json +
     * shelves.json → `cacheDir/umay_backup.zip`. Oluşturulan dosya döner.
     * (v5.0 — SettingsActivity çağırıyor.)
     */
    fun create(ctx: Context): File? {
        return try {
            val zipFile = File(ctx.cacheDir, BACKUP_FILE_NAME)
            if (zipFile.exists()) zipFile.delete()
            val zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile)))
            try {
                // books.json (kitaplık indeksi)
                val index = File(BookStore.booksDir(ctx), "books.json")
                if (index.exists()) {
                    zos.putNextEntry(ZipEntry("books.json"))
                    index.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                // books/ — tüm kitap dizinleri (source.pdf, kapaklar vb.)
                BookStore.booksDir(ctx).listFiles()?.forEach { dir ->
                    if (!dir.isDirectory) return@forEach
                    dir.listFiles()?.forEach { f ->
                        if (!f.isFile) return@forEach
                        zos.putNextEntry(ZipEntry("books/${dir.name}/${f.name}"))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                // profile.json (AppPrefs.KEY_PROFILE)
                val profileJson = AppPrefs.getString(ctx, AppPrefs.KEY_PROFILE)
                if (profileJson.isNotBlank()) {
                    zos.putNextEntry(ZipEntry("profile.json"))
                    zos.write(profileJson.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                // shelves.json (ShelfManager SharedPreferences)
                val shelvesRaw = ctx.getSharedPreferences("umay_shelves", 0)
                    .getString("shelves", null)
                if (!shelvesRaw.isNullOrBlank()) {
                    zos.putNextEntry(ZipEntry("shelves.json"))
                    zos.write(shelvesRaw.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            } finally {
                zos.close()
            }
            zipFile.takeIf { it.exists() && it.length() > 0 }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "$TAG.create", t)
            null
        }
    }

    // ── v16 (YENİ GÖREV 8) — SAF JSON YEDEK AL / GERİ YÜKLE ───────────

    /**
     * JSON dışa aktarım (SAF CreateDocument → kullanıcı seçtiği yere yazar).
     * İçerik: kitaplık indeksi (books.json) + profil + raflar — tek `.json`.
     * PDF dosyalarının kendisi zip yedeğindedir (JSON taşınabilirlik/metadatadır).
     */
    fun exportJson(ctx: Context, out: java.io.OutputStream) {
        fun rawArray(s: String?): org.json.JSONArray =
            if (s.isNullOrBlank()) org.json.JSONArray()
            else try {
                org.json.JSONArray(s)
            } catch (_: Throwable) {
                try {
                    org.json.JSONArray().put(org.json.JSONObject(s))
                } catch (_: Throwable) {
                    org.json.JSONArray()
                }
            }

        val booksRaw = File(BookStore.booksDir(ctx), "books.json")
            .takeIf { it.exists() }?.readText()
        val shelvesRaw = ctx.getSharedPreferences("umay_shelves", 0)
            .getString("shelves", null)
        val profileRaw = AppPrefs.getString(ctx, AppPrefs.KEY_PROFILE)

        val root = org.json.JSONObject().apply {
            put("format", "umay-json-backup")
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("books", rawArray(booksRaw))
            put("shelves", rawArray(shelvesRaw))
            put(
                "profile",
                if (profileRaw.isNullOrBlank()) org.json.JSONObject()
                else try {
                    org.json.JSONObject(profileRaw)
                } catch (_: Throwable) {
                    org.json.JSONObject()
                }
            )
        }
        out.write(root.toString(2).toByteArray(Charsets.UTF_8))
    }

    /** JSON yedeğini geri yükler (books.json + profil + raflar). */
    fun importJson(ctx: Context, input: java.io.InputStream): Boolean = try {
        val root = org.json.JSONObject(input.bufferedReader().use { it.readText() })
        root.optJSONArray("books")?.let { arr ->
            File(BookStore.booksDir(ctx), "books.json").apply { parentFile?.mkdirs() }
                .writeText(arr.toString())
        }
        root.optJSONArray("shelves")?.let { arr ->
            ctx.getSharedPreferences("umay_shelves", 0).edit()
                .putString("shelves", arr.toString()).apply()
        }
        root.optJSONObject("profile")?.let { p ->
            if (p.length() > 0) AppPrefs.putString(ctx, AppPrefs.KEY_PROFILE, p.toString())
        }
        true
    } catch (t: Throwable) {
        BookStore.logError(ctx, "$TAG.importJson", t)
        false
    }

    /**
     * Zip'i park/kazanına indirirseniz, oradan geri yükleme yapabilirsiniz.
     * Örnek kullanım:
     *   val okundu = CloudBackup.restore(context, uri)
     *   if (okundu) { // kütüphane yeniden kuruldu }
     */
    fun restore(ctx: Context, uri: Uri): Boolean {
        val input: InputStream = try {
            ctx.contentResolver.openInputStream(uri) ?: return false
        } catch (t: Throwable) {
            BookStore.logError(ctx, "$TAG.restore(open)", t)
            return false
        }
        val zis = ZipInputStream(BufferedInputStream(input))
        return try {
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    // Zip-slip koruması: yalnız izin verilen köklere yaz
                    val target: File? = when {
                        name == "books.json" || name.startsWith("books/") -> {
                            val rel = name.removePrefix("books/")
                            File(File(ctx.filesDir, "books"), rel)
                        }
                        name == "profile.json" -> File(ctx.cacheDir, "bk_profile.json")
                        name == "shelves.json" -> File(ctx.cacheDir, "bk_shelves.json")
                        else -> null
                    }
                    if (target != null && !name.contains("..")) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zis.copyTo(out) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            zis.close()
            true
        } catch (t: Throwable) {
            try {
                zis.close()
            } catch (_: Throwable) {
            }
            BookStore.logError(ctx, "$TAG.restore", t)
            false
        }
    }
}
