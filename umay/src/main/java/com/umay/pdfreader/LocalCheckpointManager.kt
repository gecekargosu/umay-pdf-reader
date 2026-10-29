package com.umay.pdfreader

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v30 — Checkpoint sistemi: internet kesintisi, token bitimi,
 * eşzamanlama giriş/çıkışı ve kütüphane tarama ilerlemesini kaydeder.
 *
 * Kayıtlar `Umay/checkpoint/` altına JSON satır olarak yazar.
 * Eskimiş kayıtlar `KEY_CHECKPOINT_MAX_AGE_MINS` ötesinde temizlenir.
 *
 * Kullanım:
 *   val cp = LocalCheckpointManager(ctx)
 *   cp.checkIn(CheckpointType.NETWORK_DOWN, "wifi yok")
 *   ...
 *   cp.checkOut(CheckpointType.NETWORK_DOWN, false, "modem uyanmadı")
 */
object LocalCheckpointManager {

    private const val TAG = "LocalCheckpoint"
    private const val DIR_NAME = "checkpoint"

    /** checkpoint olay türleri */
    enum class CheckpointType(val code: String) {
        /** API token'ının tazelik / yenileme ihtimali */
        API_TOKEN_FRESHNESS("token_freshness"),
        /** eşzamanlama başlangıcı */
        SYNC_START("sync_start"),
        /** eşzamanlama sonlanışı */
        SYNC_FINISH("sync_finish"),
        /** ağ kopması anı */
        NETWORK_DOWN("network_down"),
        /** kütüphane tarama işlemi ilerlemesi */
        SHELF_IMPORT_PROGRESS("shelf_import_progress"),
    }

    /** tek satır kayıt */
    data class CheckpointRecord(
        val id: Long,
        val type: CheckpointType,
        val description: String,
        val timestamp: Long,
        val success: Boolean? = null,
        val message: String? = null,
    )

    @Volatile
    private var context: Context? = null

    fun attach(ctx: Context) {
        context = ctx.applicationContext
    }

    private fun dir(): File? {
        val c = context ?: return null
        val base = File(c.filesDir, DIR_NAME)
        if (!base.exists()) base.mkdirs()
        return base
    }

    // ── checkIn / checkOut ──────────────────────────────────────────

    /** Bir işlem başladı — checkpoint aç. */
    fun checkIn(type: CheckpointType, description: String) {
        val ts = System.currentTimeMillis()
        val id = ts
        val rec = CheckpointRecord(id, type, description, ts)
        append(rec)
        Log.d(TAG, "checkIn  ${type.code}  id=$id  $description")
    }

    /**
     * Bir işlem bitti — checkpoint kapat.
     * `success` true = başarılı, false = hata, null = yarı / belirsiz.
     */
    fun checkOut(type: CheckpointType, success: Boolean, message: String? = null) {
        val ts = System.currentTimeMillis()
        // Aynı tip için en son açılan checkpoint'ı kapatmayı hedefle.
        // Gerçek implementasyonda open list veya race-control gerekebilir.
        val id = ts
        val rec = CheckpointRecord(id, type, type.name, ts, success, message)
        append(rec)
        Log.d(TAG, "checkOut ${type.code}  success=$success  msg=${message ?: "-"}")
    }

    /** Her olay için vowed bir log - CrashLog ile entegre edilebilir. */
    fun logEvent(type: CheckpointType, message: String) {
        val ts = System.currentTimeMillis()
        val id = ts
        val rec = CheckpointRecord(id, type, message, ts)
        append(rec)
        Log.d(TAG, "logEvent ${type.code}  $message")
        // CrashLog ile entegrasyon (varsa):
        try {
            context?.let { c ->
                com.umay.pdfreader.CrashLog.record(
                    c,
                    "checkpoint:${type.code}",
                    RuntimeException(message)
                )
            }
        } catch (_: Throwable) {}
    }

    // ── disk yazma ──────────────────────────────────────────────────

    private fun append(rec: CheckpointRecord) {
        val d = dir() ?: return
        val file = File(d, "checkpoint.log")
        val line = buildString {
            append(rec.id)
            append('\t')
            append(rec.type.code)
            append('\t')
            append(rec.description.escaped())
            append('\t')
            append(rec.timestamp)
            append('\t')
            append(if (rec.success == null) "" else rec.success)
            append('\t')
            append(rec.message?.escaped() ?: "")
            append('\n')
        }
        try {
            FileOutputStream(file, true).use { os ->
                os.write(line.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkpoint yazılamadı: $e")
        }
        // yaş temizliği
        maybePurge()
    }

    private fun String.escaped(): String = this
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")

    // ── okuma ──────────────────────────────────────────────────────

    /** Belirli bir tipteki kayıtlarda son N kadar. */
    fun recentRecords(type: CheckpointType, limit: Int = 20): List<CheckpointRecord> {
        val d = dir() ?: return emptyList()
        val file = File(d, "checkpoint.log")
        if (!file.exists()) return emptyList()
        val out = mutableListOf<CheckpointRecord>()
        file.readLines().asReversed().forEach { line ->
            if (out.size >= limit) return@forEach
            val parts = line.split('\t')
            if (parts.size < 5) return@forEach
            val code = parts[1]
            if (code != type.code) return@forEach
            val id = parts[0].toLongOrNull() ?: return@forEach
            val desc = parts[2].unescape()
            val ts = parts[3].toLongOrNull() ?: return@forEach
            val success = parts.getOrNull(4)?.toBooleanOrNull()
            val msg = parts.getOrNull(5)?.unescape()
            out.add(CheckpointRecord(id, type, desc, ts, success, msg))
        }
        return out
    }

    /** checkpoint.log'da boş alan = null, "true"/"false" = değer. */
    private fun String.toBooleanOrNull(): Boolean? = when (this.trim().lowercase()) {
        "true" -> true
        "false" -> false
        "" -> null
        else -> null
    }

    private fun String.unescape(): String = this
        .replace("\\t", "\t")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")

    /** Son NETWORK_DOWN veya API_TOKEN_FRESHNESS olayını döndür. */
    fun lastNetworkOrTokenIssue(): CheckpointRecord? {
        val types = listOf(CheckpointType.NETWORK_DOWN, CheckpointType.API_TOKEN_FRESHNESS)
        val file = dir()?.let { File(it, "checkpoint.log") } ?: return null
        if (!file.exists()) return null
        var best: CheckpointRecord? = null
        file.readLines().asReversed().forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 5) return@forEach
            val code = parts[1]
            val type = types.find { it.code == code } ?: return@forEach
            val id = parts[0].toLongOrNull() ?: return@forEach
            val desc = parts[2].unescape()
            val ts = parts[3].toLongOrNull() ?: return@forEach
            best = CheckpointRecord(id, type, desc, ts)
            return@forEach
        }
        return best
    }

    // ── temizlik ───────────────────────────────────────────────────

    fun maybePurge() {
        val d = dir() ?: return
        val ctx = context ?: return
        val maxAge = AppPrefs.getInt(ctx, AppPrefs.KEY_CHECKPOINT_MAX_AGE_MINS, 1440) // 1 gün
        val cutoff = System.currentTimeMillis() - (maxAge * 60_000L)
        val file = File(d, "checkpoint.log")
        if (!file.exists()) return
        val lines = file.readLines()
        val kept = lines.filter { line ->
            if (line.isBlank()) return@filter false
            val parts = line.split('\t')
            parts.size >= 4 && (parts[3].toLongOrNull()?.let { it > cutoff } ?: true)
        }
        if (kept.size != lines.size) {
            try {
                FileOutputStream(file, false).use { os ->
                    (kept.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8).let { os.write(it) }
                }
            } catch (_: Exception) {}
        }
    }

    /** test / raporlama için tüm kayıtların metin koleksiyonu. */
    fun dumpAll(): List<String> {
        val d = dir() ?: return emptyList()
        val file = File(d, "checkpoint.log")
        return if (file.exists()) file.readLines() else emptyList()
    }
}
