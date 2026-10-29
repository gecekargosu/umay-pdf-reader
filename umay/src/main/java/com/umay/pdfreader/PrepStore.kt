package com.umay.pdfreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v36 — ÖN HAZIRLIK KAYITLARI (`prep_issues.json`) ve açma/kapama ayarı.
 *
 * Tasarım kuralları:
 *  • Dosya **atomik** yazılır (`.tmp` → rename): yazma sırasında uygulama
 *    kapanırsa yarım JSON kalıp tüm kayıtları kaybettirmez (internet/pil
 *    kesintisi dersi — v34).
 *  • En fazla [MAX_ENTRIES] kayıt tutulur; eskiler atılır.
 *  • Hiçbir kayıt kullanıcı onayı olmadan silinmez.
 */
object PrepStore {

    private const val PREFS = "umay_prep"
    private const val MAX_ENTRIES = 200

    const val KEY_ENABLED = "prep_enabled"          // varsayılan: AÇIK
    const val KEY_LAST_AT = "prep_last_at"
    const val KEY_SIGS = "prep_sigs"                // bookId → imza (JSON)

    fun enabled(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    } catch (_: Throwable) {
        true
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply()
            if (!on) PrepScheduler.cancel(ctx) else PrepScheduler.schedule(ctx)
        } catch (t: Throwable) {
            BookStore.logError(ctx, "PrepStore.setEnabled", t)
        }
    }

    fun lastRunAt(ctx: Context): Long = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_AT, 0L)
    } catch (_: Throwable) {
        0L
    }

    fun issuesFile(ctx: Context): File = File(ctx.filesDir, "prep_issues.json")

    /** Kayıtlı sorunları oku (bozuk dosyada boş liste — çökme yok). */
    fun issues(ctx: Context): List<PrepAudit.Issue> {
        val out = ArrayList<PrepAudit.Issue>()
        try {
            val f = issuesFile(ctx)
            if (!f.exists() || f.length() == 0L) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = runCatching {
                    PrepAudit.Kind.valueOf(o.optString("kind", "EMPTY"))
                }.getOrDefault(PrepAudit.Kind.EMPTY)
                out.add(
                    PrepAudit.Issue(
                        page = o.optInt("page", 0),
                        kind = kind,
                        note = o.optString("note", "")
                    )
                )
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "PrepStore.issues", t)
        }
        return out
    }

    /** Yeni denetim sonuçlarını **ekler** (önceki kayıtlar silinmez). */
    fun appendIssues(ctx: Context, bookId: String, issues: List<PrepAudit.Issue>) {
        if (issues.isEmpty()) return
        try {
            val f = issuesFile(ctx)
            val arr = try {
                if (f.exists() && f.length() > 0L) JSONArray(f.readText()) else JSONArray()
            } catch (_: Throwable) {
                JSONArray()
            }
            val now = System.currentTimeMillis()
            issues.forEach { it ->
                arr.put(
                    JSONObject()
                        .put("book", bookId)
                        .put("page", it.page + 1)
                        .put("kind", it.kind.name)
                        .put("note", it.note)
                        .put("at", now)
                )
            }
            // Taşma koruması: en eski kayıtlar atılır
            val trimmed = if (arr.length() > MAX_ENTRIES) {
                JSONArray().also { fresh ->
                    for (i in (arr.length() - MAX_ENTRIES) until arr.length()) fresh.put(arr.get(i))
                }
            } else arr
            atomicWrite(f, trimmed.toString())
        } catch (t: Throwable) {
            BookStore.logError(ctx, "PrepStore.appendIssues", t)
        }
    }

    fun markRun(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_AT, System.currentTimeMillis()).apply()
        } catch (_: Throwable) {
        }
    }

    /** Aynı kitabı her turda yeniden denetlememek için imza (sayfa sayısı + mtime). */
    fun signatureOf(ctx: Context, book: Book): String {
        val src = BookStore.sourceFile(ctx, book.id)
        return "${book.id}:${book.pageCount}:${src.length()}:${src.lastModified()}"
    }

    /**
     * KİTAP BAŞINA imza (v36.1 düzeltmesi).
     *
     * İlk sürümde tek bir global imza tutuluyordu: 4 kitap varsa ilk kitabın
     * imzası yazılıyor, diğerleri her turda yeniden işleniyordu. Artık
     * "bookId → imza" eşlemesi JSON olarak saklanıyor.
     */
    private fun signatures(ctx: Context): JSONObject = try {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SIGS, null)
        if (raw.isNullOrBlank()) JSONObject() else runCatching { JSONObject(raw) }
            .getOrDefault(JSONObject())
    } catch (_: Throwable) {
        JSONObject()
    }

    fun signatureOfBook(ctx: Context, bookId: String): String =
        signatures(ctx).optString(bookId, "")

    fun rememberSignature(ctx: Context, bookId: String, sig: String) {
        try {
            val all = signatures(ctx).put(bookId, sig)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SIGS, all.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    /** Kitap silinince imzası da gitsin (depolama şişmesin). */
    fun forgetSignature(ctx: Context, bookId: String) {
        try {
            val all = signatures(ctx)
            if (!all.has(bookId)) return
            all.remove(bookId)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SIGS, all.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    /** Atomik yazma: `.tmp` dosyaya yaz → rename. Yarıda kesilirse eski dosya durur. */
    private fun atomicWrite(target: File, body: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(target)) {
            // Windows/Android'de hedef varsa rename başarısız olabilir
            target.delete()
            tmp.renameTo(target)
        }
    }
}
