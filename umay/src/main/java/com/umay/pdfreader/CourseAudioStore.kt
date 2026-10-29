package com.umay.pdfreader

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v33 — DERS SES KAYITLARI (raf).
 *
 * Kullanıcı derste ses kaydı alıyor (telefon kayıt uygulaması) ve bu kayıtları
 * dersin rafına koymak istiyor. Tasarım kuralları:
 *  • Cihazdan seçilen kayıt **uygulama deposuna kopyalanır**
 *    (`filesDir/audio/<id>.<uzantı>`) → orijinal dosya SİLİNMEZ, taşınmaz.
 *  • Kayıt silinirse yalnız uygulama içindeki kopya silinir; cihazdaki
 *    orijinal kayıt yerinde kalır (kullanıcı isteği).
 *  • Süre/başlık metadata'dan okunur; raf kartında gösterilir.
 */
object CourseAudioStore {

    private const val PREFS = "umay_course_audio"

    data class Clip(
        val id: String,
        val title: String,
        val fileName: String,
        val durationMs: Long = 0L,
        val addedAt: Long = 0L
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clips(ctx: Context, courseId: String): MutableList<Clip> {
        val out = mutableListOf<Clip>()
        try {
            val raw = prefs(ctx).getString(courseId, null) ?: return out
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Clip(
                        id = o.getString("id"),
                        title = o.optString("title", "Ses kaydı"),
                        fileName = o.optString("file", ""),
                        durationMs = o.optLong("duration", 0L),
                        addedAt = o.optLong("addedAt", 0L)
                    )
                )
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CourseAudioStore.clips", t)
        }
        return out
    }

    private fun save(ctx: Context, courseId: String, list: List<Clip>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id).put("title", it.title)
                        .put("file", it.fileName)
                        .put("duration", it.durationMs)
                        .put("addedAt", it.addedAt)
                )
            }
            prefs(ctx).edit().putString(courseId, arr.toString()).apply()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CourseAudioStore.save", t)
        }
    }

    fun audioDir(ctx: Context): File = File(ctx.filesDir, "audio").apply { mkdirs() }

    fun localFile(ctx: Context, fileName: String): File = File(audioDir(ctx), fileName)

    /**
     * v34 — Uygulama içinde alınan kaydı (audio/ klasöründe zaten duran dosya)
     * rafa ekler. Dosya kopyalanmaz, yerinde kalır.
     */
    fun addExisting(ctx: Context, courseId: String, file: File, title: String?): Clip? {
        return try {
            if (!file.exists() || file.length() <= 0L) return null
            val clip = Clip(
                id = "a" + java.util.UUID.randomUUID().toString().replace("-", "").take(12),
                title = title?.takeIf { it.isNotBlank() } ?: file.name,
                fileName = file.name,
                durationMs = durationOf(file),
                addedAt = System.currentTimeMillis()
            )
            val list = clips(ctx, courseId)
            list.add(clip)
            save(ctx, courseId, list)
            clip
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CourseAudioStore.addExisting", t)
            null
        }
    }

    /** Cihazdan seçilen ses kaydını kopyalar (orijinal dosyaya dokunmaz). */
    fun importLocal(ctx: Context, courseId: String, uri: Uri, displayName: String?): Clip? {
        return try {
            val name = displayName?.takeIf { it.isNotBlank() }
                ?: "kayit_${System.currentTimeMillis()}.m4a"
            val ext = name.substringAfterLast('.', "m4a").lowercase().ifBlank { "m4a" }
            val id = "a" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            val fileName = "$id.$ext"
            val dst = localFile(ctx, fileName)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
            if (dst.length() <= 0L) {
                dst.delete()
                return null
            }
            val clip = Clip(
                id = id,
                title = name,
                fileName = fileName,
                durationMs = durationOf(dst),
                addedAt = System.currentTimeMillis()
            )
            val list = clips(ctx, courseId)
            list.add(clip)
            save(ctx, courseId, list)
            clip
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CourseAudioStore.importLocal", t)
            null
        }
    }

    /** Raftan kaldırır (yalnız uygulama kopyası silinir). */
    fun remove(ctx: Context, courseId: String, clipId: String) {
        val list = clips(ctx, courseId)
        list.firstOrNull { it.id == clipId }?.let {
            try { localFile(ctx, it.fileName).delete() } catch (_: Throwable) { }
        }
        list.removeAll { it.id == clipId }
        save(ctx, courseId, list)
    }

    fun existingFile(ctx: Context, clip: Clip): File? {
        val f = localFile(ctx, clip.fileName)
        return if (f.exists() && f.length() > 0) f else null
    }

    fun durationOf(f: File): Long = try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(f.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            try { mmr.release() } catch (_: Throwable) { }
        }
    } catch (_: Throwable) {
        0L
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
