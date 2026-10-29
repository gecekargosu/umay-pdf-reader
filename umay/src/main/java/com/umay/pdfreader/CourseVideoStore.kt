package com.umay.pdfreader

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v24 — DERS VİDEOLARI (raf).
 *
 * İki tür kayıt:
 *  • `local`  : Cihazdan seçilen video **uygulama deposuna kopyalanır**
 *               (`filesDir/videos/<id>.<uzantı>`) → çevrimdışı oynar, dış
 *               uygulama silse bile kaybolmaz.
 *  • `link`   : YouTube / herhangi bir bağlantı. Uygulama **indirme yapmaz**;
 *               bağlantı YouTube uygulamasında ya da tarayıcıda açılır
 *               (platform kısıtı: YouTube indirme Play politikasına aykırı).
 *
 * Veri SharedPreferences "umay_course_videos" içinde ders kimliğine göre
 * JSON olarak tutulur; her okumada taze okunur → yeni ders/raf eklenince
 * kod kendini günceller.
 */
object CourseVideoStore {

    private const val PREFS = "umay_course_videos"

    data class Video(
        val id: String,
        val title: String,
        val kind: String,          // local | link
        val source: String,        // yerel dosya adı veya URL
        val addedAt: Long = 0L
    ) {
        val isLocal: Boolean get() = kind == "local"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun videos(ctx: Context, courseId: String): MutableList<Video> {
        val out = mutableListOf<Video>()
        try {
            val raw = prefs(ctx).getString(courseId, null) ?: return out
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Video(
                        id = o.getString("id"),
                        title = o.optString("title", "Video"),
                        kind = o.optString("kind", "link"),
                        source = o.optString("source", ""),
                        addedAt = o.optLong("addedAt", 0L)
                    )
                )
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CourseVideoStore.videos", t)
        }
        return out
    }

    private fun save(ctx: Context, courseId: String, list: List<Video>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id).put("title", it.title)
                        .put("kind", it.kind).put("source", it.source)
                        .put("addedAt", it.addedAt)
                )
            }
            prefs(ctx).edit().putString(courseId, arr.toString()).apply()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CourseVideoStore.save", t)
        }
    }

    fun add(ctx: Context, courseId: String, video: Video) {
        val list = videos(ctx, courseId)
        list.add(video)
        save(ctx, courseId, list)
    }

    fun remove(ctx: Context, courseId: String, videoId: String) {
        val list = videos(ctx, courseId)
        list.firstOrNull { it.id == videoId && it.kind == "local" }?.let {
            try { localFile(ctx, it.source).delete() } catch (_: Throwable) {}
        }
        list.removeAll { it.id == videoId }
        save(ctx, courseId, list)
    }

    fun videoDir(ctx: Context): File = File(ctx.filesDir, "videos").apply { mkdirs() }

    fun localFile(ctx: Context, fileName: String): File = File(videoDir(ctx), fileName)

    /**
     * Cihazdan seçilen videoyu uygulama deposuna kopyalar.
     * @return eklenen kayıt (hata olursa null)
     */
    fun importLocal(ctx: Context, courseId: String, uri: Uri, displayName: String?): Video? {
        return try {
            val name = displayName?.takeIf { it.isNotBlank() } ?: "video_${System.currentTimeMillis()}.mp4"
            val ext = name.substringAfterLast('.', "mp4").lowercase().ifBlank { "mp4" }
            val id = "v" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            val fileName = "$id.$ext"
            val dst = localFile(ctx, fileName)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
            if (dst.length() <= 0L) {
                dst.delete()
                return null
            }
            val video = Video(
                id = id,
                title = name,
                kind = "local",
                source = fileName,
                addedAt = System.currentTimeMillis()
            )
            add(ctx, courseId, video)
            video
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CourseVideoStore.importLocal", t)
            null
        }
    }

    /** Dosya yolundan video ekleme (cihaz testi / paylaş menüsü). */
    fun importLocalFile(ctx: Context, courseId: String, file: File): Video? {
        return try {
            if (!file.exists() || file.length() == 0L) return null
            val ext = file.extension.lowercase().ifBlank { "mp4" }
            val id = "v" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            val fileName = "$id.$ext"
            val dst = localFile(ctx, fileName)
            file.copyTo(dst, overwrite = true)
            val video = Video(
                id = id, title = file.name, kind = "local", source = fileName,
                addedAt = System.currentTimeMillis()
            )
            add(ctx, courseId, video)
            video
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CourseVideoStore.importLocalFile", t)
            null
        }
    }

    /** Bağlantı (YouTube) kaydı. Başlık boşsa bağlantıdan türetilir. */
    fun addLink(ctx: Context, courseId: String, url: String, title: String?): Video? {
        val clean = url.trim()
        if (clean.isBlank()) return null
        val id = "y" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val name = title?.trim()?.takeIf { it.isNotBlank() }
            ?: guessTitle(clean)
        val video = Video(
            id = id, title = name, kind = "link", source = clean,
            addedAt = System.currentTimeMillis()
        )
        add(ctx, courseId, video)
        return video
    }

    private fun guessTitle(url: String): String {
        // youtube.com/watch?v=XXX veya youtu.be/XXX → "YouTube videosu (XXX)"
        return try {
            val id = when {
                url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore('?')
                url.contains("v=") -> url.substringAfter("v=").substringBefore('&')
                else -> ""
            }
            if (id.isNotBlank()) "YouTube videosu ($id)" else "Video bağlantısı"
        } catch (_: Throwable) {
            "Video bağlantısı"
        }
    }
}
