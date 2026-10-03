package com.umay.pdfreader

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Opsiyonel online sohbet istemcisi.
 *
 * Ücretsiz katman örnekleri:
 *  - Google Gemini: anahtar "AIza..." ile başlar (aistudio.google.com, ücretsiz)
 *  - OpenAI-uyumlu herhangi bir servis (anahtar + model/adres)
 *
 * API anahtarı girilmemişse ChatActivity tamamen offline çalışır.
 */
object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val DEFAULT_GEMINI_MODEL = "gemini-2.0-flash"
    private const val DEFAULT_OPENAI_URL = "https://api.openai.com/v1/chat/completions"

    /**
     * @param contextPages PDF'den getirilen alaka parçaları ("Sayfa N: ..." biçiminde)
     * @return modelin yanıtı
     */
    fun ask(question: String, contextPages: List<String>): String {
        val ctxText = if (contextPages.isEmpty()) "(bağlam yok)"
        else contextPages.joinToString("\n\n")

        val systemPrompt = (
            "Sen 'Umay' adlı, Türkçe konuşan bir ders asistanısın. " +
                "Aşağıdaki PDF kitaptan bağlam veriliyor. " +
                "Yalnızca bağlamdaymış gibi yanıtla; emin değilsen 'Bu kitapta geçmiyor' de. " +
                "Kısa, net ve Türkçe cevap ver.\n\nBAĞLAM:\n$ctxText"
            )

        val apiKey = AppPrefs.lastApiKey ?: ""
        val modelSetting = AppPrefs.lastApiModel ?: ""

        return if (apiKey.startsWith("AIza")) {
            askGemini(apiKey, modelSetting.ifBlank { DEFAULT_GEMINI_MODEL }, systemPrompt, question)
        } else {
            askOpenAi(apiKey, modelSetting, systemPrompt, question)
        }
    }

    // ── Gemini ───────────────────────────────────────────────

    private fun askGemini(key: String, model: String, system: String, question: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "$model:generateContent?key=$key"

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", system))
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", question)))
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.6)
                put("maxOutputTokens", 700)
            })
        }

        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IllegalStateException("Sunucu hatası (${resp.code}): ${text.take(200)}")
            }
            val json = JSONObject(text)
            val parts = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            val out = StringBuilder()
            for (i in 0 until parts.length()) {
                out.append(parts.getJSONObject(i).optString("text", ""))
            }
            return out.toString().trim().ifEmpty { "Boş yanıt geldi." }
        }
    }

    // ── OpenAI uyumlu ────────────────────────────────────────

    private fun askOpenAi(key: String, modelSetting: String, system: String, question: String): String {
        val isUrl = modelSetting.startsWith("http://") || modelSetting.startsWith("https://")
        val url = if (isUrl) modelSetting else DEFAULT_OPENAI_URL
        val model = if (isUrl) "gpt-4o-mini" else modelSetting.ifBlank { "gpt-4o-mini" }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", question))
            })
            put("max_tokens", 700)
            put("temperature", 0.6)
        }

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IllegalStateException("Sunucu hatası (${resp.code}): ${text.take(200)}")
            }
            val json = JSONObject(text)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }
}
