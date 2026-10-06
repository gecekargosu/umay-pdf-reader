package com.umay.pdfreader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Opsiyonel online sohbet istemcisi.
 *
 * Ücretsiz katman örnekleri:
 *  - Google Gemini: anahtar "AIza..." ile başlar (aistudio.google.com, ücretsiz)
 *  - OpenAI-uyumlu herhangi bir servis (anahtar + model/adres)
 *
 * API anahtarı girilmemişse ChatActivity tamamen offline çalışır.
 *
 * Dayanıklılık (1.3.1):
 *  - Model adayı sunucudan keşfedilir (`/models`); bulunamazsa sırayla denenir,
 *    böylece Google bir modeli emekliye ayırınca sohbet çalışmaya devam eder.
 *  - Hatalar Türkçeleştirilir (geçersiz anahtar, kota, bağlantı yok…).
 *  - 2.5+ modellerde "thinking" kapalı tutulur ki yanıt token'ı düşünmeye gitmesin.
 */
object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/"
    private const val DEFAULT_OPENAI_URL = "https://api.openai.com/v1/chat/completions"

    /** Anahtar/model gibi geri dönülemez hata: diğer modelleri denemenin anlamı yok. */
    private class ApiFatal(message: String) : Exception(message)

    /** Verilen model sunucuda yok (404/NOT_FOUND): sıradaki adaya geç. */
    private class ModelMissing(model: String) : Exception(model)

    /** Manuel girilmemişse denenecek modeller (yeni → eski). */
    private val FALLBACK_MODELS = listOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-flash-latest",
        "gemini-2.5-flash-lite",
        "gemini-1.5-flash"
    )

    /** Sunucudan bir kez keşfedilen kullanılabilir modeller (boşsa keşfedilemedi). */
    @Volatile private var discoveredModels: List<String>? = null

    /**
     * @param contextPages PDF'den getirilen alaka parçaları ("Sayfa N: ..." biçiminde)
     * @return modelin yanıtı
     */
    fun ask(
        question: String,
        contextPages: List<String>,
        /** Ayarlar'dan "Test" için: kaydı okumadan doğrudan denenir. */
        apiKeyOverride: String? = null,
        modelOverride: String? = null
    ): String {
        val ctxText = if (contextPages.isEmpty()) "(bağlam yok)"
        else contextPages.joinToString("\n\n")

        val systemPrompt = (
            "Sen 'Umay' adlı, Türkçe konuşan bir ders asistanısın. " +
                "Aşağıdaki PDF kitaptan bağlam veriliyor. " +
                "Yalnızca bağlamdaymış gibi yanıtla; emin değilsen 'Bu kitapta geçmiyor' de. " +
                "Kısa, net ve Türkçe cevap ver.\n\nBAĞLAM:\n$ctxText"
            )

        val apiKey = (apiKeyOverride ?: AppPrefs.lastApiKey ?: "").trim()
        if (apiKey.isEmpty()) {
            throw ApiFatal("API anahtarı yok. Ayarlar'dan ücretsiz bir anahtar girebilirsin.")
        }
        val modelSetting = (modelOverride ?: AppPrefs.lastApiModel ?: "").trim()

        return if (apiKey.startsWith("AIza") || modelSetting.contains("gemini", ignoreCase = true)) {
            askGemini(apiKey, modelSetting, systemPrompt, question)
        } else {
            askOpenAi(apiKey, modelSetting, systemPrompt, question)
        }
    }

    // ── Gemini ───────────────────────────────────────────────

    private fun askGemini(
        key: String,
        preferred: String,
        system: String,
        question: String
    ): String {
        // Kullanıcı tercihi → sunucudaki liste → yerleşik yedekler
        val candidates = ArrayList<String>()
        if (preferred.isNotBlank() && !preferred.startsWith("http")) candidates += preferred
        candidates += discoverModels(key)
        candidates += FALLBACK_MODELS

        var lastError: Exception? = null
        for (model in candidates.distinct()) {
            try {
                return geminiCall(key, model, system, question)
            } catch (e: ApiFatal) {
                throw e                      // anahtar sorunlu: tekrar denemenin anlamı yok
            } catch (e: ModelMissing) {
                lastError = e                // bu model emekli olmuş olabilir
            } catch (e: IOException) {
                throw ApiFatal("İnternet bağlantısı yok veya sunucuya ulaşılamıyor.")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: ApiFatal("Yanıt alınamadı. Tekrar dene.")
    }

    /** `/models` uçundan generateContent destekleyen modelleri öğrenir (bir kez). */
    private fun discoverModels(key: String): List<String> {
        discoveredModels?.let { return it }
        val names = try {
            val req = Request.Builder()
                .url(GEMINI_BASE + "models?key=" + URLEncoder.encode(key, "UTF-8"))
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<String>()
                val json = JSONObject(resp.body?.string() ?: "")
                val arr = json.optJSONArray("models") ?: return@use emptyList<String>()
                (0 until arr.length()).mapNotNull { i ->
                    val m = arr.optJSONObject(i) ?: return@mapNotNull null
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    val ok = methods != null && (0 until methods.length())
                        .any { methods.optString(it) == "generateContent" }
                    if (!ok) return@mapNotNull null
                    m.optString("name").removePrefix("models/").takeIf { it.isNotBlank() }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        // Hızlı/ucuz flash modeller önce, "lite" en sonda
        val ordered = names.filter { it.contains("flash") && !it.contains("lite") } +
            names.filter { it.contains("flash") && it.contains("lite") } +
            names.filter { !it.contains("flash") }
        if (ordered.isNotEmpty()) discoveredModels = ordered
        return ordered
    }

    private fun geminiCall(key: String, model: String, system: String, question: String): String {
        val url = GEMINI_BASE + "models/" + model + ":generateContent?key=" +
            URLEncoder.encode(key, "UTF-8")

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
            val gen = JSONObject().apply {
                put("temperature", 0.6)
                put("maxOutputTokens", 2048)
            }
            // 2.5+ modellerde "thinking" token'ları yanıtı yiyebilir → kapat
            if (thinkingAllowed(model)) {
                gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
            put("generationConfig", gen)
        }

        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw classifyGeminiError(resp.code, text, model)

            val json = JSONObject(text)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val reason = json.optJSONObject("promptFeedback")
                    ?.optString("blockReason").orEmpty()
                throw if (reason.isNotEmpty()) ApiFatal("İstek engellendi: $reason")
                else IllegalStateException("Model boş yanıt döndü.")
            }
            val c0 = candidates.getJSONObject(0)
            val parts = c0.optJSONObject("content")?.optJSONArray("parts")
                ?: JSONArray()
            val out = StringBuilder()
            for (i in 0 until parts.length()) {
                out.append(parts.getJSONObject(i).optString("text", ""))
            }
            val result = out.toString().trim()
            if (result.isEmpty()) {
                throw IllegalStateException(
                    "Model boş yanıt döndü (sebep: ${c0.optString("finishReason", "bilinmiyor")})."
                )
            }
            return result
        }
    }

    /** Düşünme (thinking) desteklemeyen eski modellerde alan gönderilmez. */
    private fun thinkingAllowed(model: String): Boolean =
        !(model.startsWith("gemini-1.") || model.startsWith("gemini-2.0"))

    private fun classifyGeminiError(code: Int, body: String, model: String): Exception {
        val apiMsg = try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: body
        } catch (_: Exception) {
            body
        }
        val low = (apiMsg + " " + body).lowercase()
        return when {
            code == 404 || low.contains("not found") || low.contains("is not found") ->
                ModelMissing(model)

            low.contains("api_key_invalid") || low.contains("api key not valid") ->
                ApiFatal("API anahtarı geçersiz. Ayarlar'dan kontrol et.")

            low.contains("permission_denied") || low.contains("not allowed") ||
                low.contains("has not been used in project") ->
                ApiFatal("Anahtar bu servis için yetkili değil. Ücretsiz anahtar: aistudio.google.com")

            code == 429 || low.contains("quota") ->
                IllegalStateException("Günlük/kota aşıldı (429). Biraz sonra tekrar dene.")

            else -> IllegalStateException("Sunucu hatası ($code): ${apiMsg.take(200)}")
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

        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val apiMsg = try {
                        JSONObject(text).optJSONObject("error")?.optString("message") ?: text
                    } catch (_: Exception) {
                        text
                    }
                    throw when {
                        resp.code == 401 || resp.code == 403 ->
                            ApiFatal("API anahtarı reddedildi (401/403). Anahtarı kontrol et.")
                        resp.code == 429 ->
                            IllegalStateException("Kota aşıldı (429). Biraz sonra tekrar dene.")
                        else -> IllegalStateException("Sunucu hatası (${resp.code}): ${apiMsg.take(200)}")
                    }
                }
                val json = JSONObject(text)
                return json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            }
        } catch (e: IOException) {
            throw ApiFatal("İnternet bağlantısı yok veya sunucuya ulaşılamıyor.")
        }
    }
}
