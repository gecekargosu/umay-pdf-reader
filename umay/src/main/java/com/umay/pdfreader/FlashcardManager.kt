package com.umay.pdfreader

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * v12 (YENİ ÖZELLİK) — AI FLASHCARD / ÇALIŞMA KARTI.
 *
 * Açık PDF sayfasının metninden Gemini ile 10 soruluk çoktan seçmeli
 * test / çalışma kartı üretir. Kartlar `FlashcardActivity`'de kart
 * çevirme (flip) ve sonuç ekranıyla gösterilir.
 *
 * GÜVENLİK:
 *  - API anahtarı yoksa → net Türkçe hata (kullanıcı Ayarlar'a yönlendirilir).
 *  - JSON bozulursa → Regex fallback ile soru/şık ayrıştırma.
 *  - Ağ IO: `Dispatchers.IO` (ana thread kilitlenmez — ANR koruması).
 */
object FlashcardManager {

    /** Tek bir çalışma kartı. */
    data class Card(
        val question: String,
        val options: List<String>,   // 4 şık (A/B/C/D)
        val answerIndex: Int,        // doğru şık (0..3)
        val explanation: String      // kısa açıklama
    )

    /**
     * Verilen sayfa metninden kart üretir (Async).
     * @param pageCount sayfa numarası (bağlam etiketi için)
     */
    fun generate(
        ctx: Context,
        pageText: String,
        pageCount: Int,
        onDone: (Result<List<Card>>) -> Unit
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = try {
                generateSync(pageText, pageCount)
            } catch (t: Throwable) {
                BookStore.logError(ctx, "FlashcardManager.generate", t)
                Result.failure(
                    Exception(t.message ?: "Kart üretilemedi — tekrar deneyin")
                )
            }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    /** IO thread: prompt üret → ApiClient.ask → ayrıştır. */
    private suspend fun generateSync(pageText: String, pageNo: Int): Result<List<Card>> =
        withContext(Dispatchers.IO) {
            val trimmed = pageText.trim().take(6000)
            if (trimmed.length < 80) {
                return@withContext Result.failure(
                    Exception("Bu sayfada yeterli metin yok (taranmış/boş sayfa olabilir).")
                )
            }
            val prompt = (
                "Aşağıdaki '$pageNo. sayfa' metninden 10 adet çoktan seçmeli " +
                    "sınav sorusu üret. Her soru 4 şık (A/B/C/D) olsun, SADECE biri " +
                    "doğru. Sorular metne dayalı olsun (ezber değil, anlama).\\n\\n" +
                    "SADECE şu şemada GEÇERLİ JSON döndür, başka hiçbir şey yazma:\\n" +
                    "{\"cards\":[{\"q\":\"soru\",\"o\":[\"şık1\",\"şık2\",\"şık3\",\"şık4\"]," +
                    "\"a\":0,\"e\":\"kısa açıklama\"}]}\\n\\n" +
                    "\"a\" alanı doğru şıkkın İNDEKSİ (0-3).\\n\\nMETİN:\\n$trimmed"
                )
            val answer = ApiClient.ask(prompt, emptyList())

            // 1) Sert JSON dene
            val direct = parseJson(answer)
            if (direct.isNotEmpty()) return@withContext Result.success(direct)

            // 2) JSON bloğunu metnin içinden çıkar (```json ... ``` sarmalı dahil)
            val extracted = Regex("\\{\\s*\"cards\"\\s*:\\s*\\[.*?\\]\\s*}", RegexOption.DOT_MATCHES_ALL)
                .find(answer)?.value
            if (extracted != null) {
                val fromBlock = parseJson(extracted)
                if (fromBlock.isNotEmpty()) return@withContext Result.success(fromBlock)
            }
            Result.failure(Exception("Model yanıtı okunamadı — tekrar deneyin."))
        }

    /** JSON metninden kart listesi ayırır (hata → boş). */
    private fun parseJson(raw: String): List<Card> {
        return try {
            val cleaned = raw
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val arr = JSONObject(cleaned).optJSONArray("cards") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val q = o.optString("q", "").trim()
                val opts = o.optJSONArray("o")?.let { a ->
                    (0 until a.length()).map { a.optString(it, "") }
                } ?: emptyList()
                val a = o.optInt("a", -1)
                if (q.isEmpty() || opts.size < 2 || a !in 0..3) return@mapNotNull null
                Card(q, opts, a, o.optString("e", ""))
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
