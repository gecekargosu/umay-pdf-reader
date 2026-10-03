package com.umay.pdfreader

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ML Kit ile tamamen offline çalışan İngilizce -> Türkçe çeviri.
 * İlk kullanımda model (~35MB) bir kez indirilir, sonra internet gerekmez.
 */
object TrTranslator {

    private const val SRC = "en"
    private const val DST = "tr"

    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(SRC)
        .setTargetLanguage(DST)
        .build()

    private fun translator() = Translation.getClient(options)

    /** Model indirilmiş mi? (indirilmemişse false; çeviri denenince otomatik iner) */
    suspend fun isModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        try {
            val manager = com.google.mlkit.common.model.RemoteModelManager.getInstance()
            val models = Tasks.await(manager.getDownloadedModels(TranslateRemoteModel::class.java))
            models.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /** Modeli indirir (internet gerekir, bir kez). true = hazır. */
    suspend fun ensureModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val t = translator()
            try {
                val conditions = DownloadConditions.Builder().build()
                Tasks.await(t.downloadModelIfNeeded(conditions))
                true
            } finally {
                t.close()
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Metni İngilizceden Türkçeye çevirir.
     * ML Kit tek seferde ~5000 karakterden fazlasını sevmez; cümle bazında böleriz.
     * Başarısız olursa orijinal metin döner.
     */
    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text
        val t = translator()
        try {
            // Güvenli model indirme (zaten inmişse anında döner)
            val conditions = DownloadConditions.Builder().build()
            Tasks.await(t.downloadModelIfNeeded(conditions))

            val parts = TextUtil.speechChunks(text).ifEmpty { listOf(text) }
            val out = StringBuilder()
            for (p in parts) {
                val piece = if (p.length > 4500) p.take(4500) else p
                val translated = Tasks.await(t.translate(piece))
                out.append(translated)
                out.append(' ')
            }
            out.toString().trim().ifEmpty { text }
        } catch (e: Exception) {
            // Çeviri başarısız (ör. model indirilemedi) → orijinal
            text
        } finally {
            try { t.close() } catch (_: Exception) {}
        }
    }
}
