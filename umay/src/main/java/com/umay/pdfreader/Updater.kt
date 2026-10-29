package com.umay.pdfreader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * v5.7 — TEK DOKUNUŞLA APK KURULUMU.
 *
 * Eski davranış yalnız `Download/UmayPDF_Guncel.apk` adını arıyordu; dosya
 * farklı adla indirildiğinde "bulunamadı" diyordu (kullanıcı raporu).
 * Artık:
 *  1. Önbelleğe alınmış güncelleme (cacheDir/update içindeki apk dosyaları)
 *  2. `Download` klasöründeki **en yeni** APK (adında "umay" geçenler öncelikli)
 *  3. Hiçbiri yoksa: İndirilenler klasörünü açar (kullanıcı dosyayı görsün)
 * Dosya önce önbelleğe birebir kopyalanır (FUSE yolundan doğrudan kurulum
 * "paket ayrıştırılamadı" hatası veriyordu), sonra sistem kurucusu açılır.
 * REQUEST_INSTALL_PACKAGES izni manifestte tanımlıdır.
 *
 * v5.8 — "APK SEÇ VE KUR": kullanıcı indirdiği dosyayı kendisi seçebilir
 * (WhatsApp/Telegram ile gelen APK'lar Download dışında olabiliyor).
 */
object Updater {

    private fun downloadsDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** Kurulacak APK dosyasını seçer (yoksa null). */
    fun findApk(ctx: Context): File? {
        val candidates = mutableListOf<File>()

        // 1) Önbellekteki hazır güncelleme
        File(ctx.cacheDir, "update").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk", true) && it.length() > 0 }
            ?.let { candidates.addAll(it) }

        // 2) Download klasörü — önce adında "umay" geçenler, sonra tüm APK'lar
        val download = downloadsDir()
        val apks = download.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk", true) && it.length() > 0 }
            .orEmpty()
        val named = apks.filter { it.name.contains("umay", ignoreCase = true) }
        candidates.addAll(if (named.isNotEmpty()) named else apks)

        return candidates.filter { it.exists() && it.length() > 0 }
            .maxByOrNull { it.lastModified() }
    }

    /** Seçilen APK'yı kurulum ekranında açar. Başarılıysa true. */
    fun installLatest(ctx: Context): Boolean {
        val target = findApk(ctx)
        if (target == null) {
            showNoApkDialog(ctx)
            return false
        }
        return installFile(ctx, target)
    }

    /** Bilinen bir dosyayı kurar (kopyalama + kurulum ekranı). */
    fun installFile(ctx: Context, target: File): Boolean {
        return try {
            val cacheDir = File(ctx.cacheDir, "update").apply { mkdirs() }
            val cached = File(cacheDir, "umay-update.apk")
            if (cached.exists()) cached.delete()
            target.inputStream().use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
            if (!cached.exists() || cached.length() != target.length()) {
                throw IOException("APK kopyalanamadı (${cached.length()} / ${target.length()} bayt)")
            }
            launchInstaller(ctx, cached)
            Toast.makeText(
                ctx,
                ctx.getString(R.string.ay_apk_kuruluyor, target.name),
                Toast.LENGTH_SHORT
            ).show()
            true
        } catch (t: Throwable) {
            BookStore.logError(ctx, "Updater.installFile", t)
            Toast.makeText(
                ctx,
                ctx.getString(R.string.apk_install_failed, t.message ?: "?"),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    /**
     * Dosya seçiciden dönen APK'yı kurar. SAF `content://` adresi doğrudan
     * kuruluma verilemediği için önce önbelleğe kopyalanır.
     */
    fun installFromUri(ctx: Context, uri: Uri?): Boolean {
        if (uri == null) return false
        return try {
            val cacheDir = File(ctx.cacheDir, "update").apply { mkdirs() }
            val cached = File(cacheDir, "umay-picked.apk")
            ctx.contentResolver.openInputStream(uri).use { input ->
                if (input == null) throw IOException("Dosya açılamadı")
                cached.outputStream().use { output -> input.copyTo(output) }
            }
            if (!cached.exists() || cached.length() == 0L) {
                throw IOException("Boş dosya")
            }
            launchInstaller(ctx, cached)
            true
        } catch (t: Throwable) {
            BookStore.logError(ctx, "Updater.installFromUri", t)
            Toast.makeText(
                ctx,
                ctx.getString(R.string.apk_install_failed, t.message ?: "?"),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    /** Dosya seçici açar (APK). */
    fun pickApk(activity: Activity, requestCode: Int) {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/vnd.android.package-archive")
                )
            }
            activity.startActivityForResult(intent, requestCode)
        } catch (t: Throwable) {
            BookStore.logError(activity, "Updater.pickApk", t)
            Toast.makeText(activity, R.string.apk_pick_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun launchInstaller(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        ctx.startActivity(intent)
    }

    /** Güncelleme dosyası yoksa: yolu göster + dosya seçiciyi öner. */
    private fun showNoApkDialog(ctx: Context) {
        val msg = ctx.getString(R.string.ay_guncelleme_yok)
        try {
            android.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.ay_apk_kur)
                .setMessage(msg)
                .setPositiveButton(R.string.apk_open_downloads) { _, _ ->
                    try {
                        ctx.startActivity(
                            Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (t: Throwable) {
                        Toast.makeText(ctx, R.string.apk_pick_failed, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.kapat, null)
                .show()
        } catch (_: Throwable) {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
}
