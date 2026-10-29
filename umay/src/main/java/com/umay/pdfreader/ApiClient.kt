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
    // v13 (AGENT 22 — A4): OpenRouter, OpenAI-uyumlu sohbet API'sidir (ücretsiz
    // modeller: openrouter.ai/models — "free" uzantılı modeller anahtarsız kota
    // ile çalışır). Özel sunucu (Ollama/LM Studio/vLLM) da aynı formattadır.
    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

    /** Anahtar/model gibi geri dönülemez hata: diğer modelleri denemenin anlamı yok. */
    private class ApiFatal(message: String) : Exception(message)

    /** Verilen model sunucuda yok (404/NOT_FOUND): sıradaki adaya geç. */
    private class ModelMissing(model: String) : Exception(model)

    /** Manuel girilmemişse denenecek modeller (yeni → eski).
     *  v11: 2.0/1.5 serisi yeni anahtarlarla 404 veriyor (API'den kaldırıldı);
     *  gemini-3.8-flash birinci sırada — canlı test edildi ✓. */
    private val FALLBACK_MODELS = listOf(
        // v5.4 (ONARIM 2) — 28 Eki 2026 canlı ölçüm (gömülü anahtarla):
        //   gemini-3.5-flash          → 200, ~2.5 sn   ✓ EN HIZLI
        //   gemini-flash-lite-latest  → 200, ~15 sn    ✓
        //   gemini-3.8-flash / 3.7 / gemini-flash-latest → 503 (aşırı yük)
        //   gemini-2.5-flash, 2.0-flash → 404 (yeni kullanıcılara kapalı)
        // Sıralama "çalıştığı ölçülmüş" modellerden "son çare"ye doğrudur.
        "gemini-3.5-flash",
        "gemini-flash-lite-latest",
        "gemini-3.8-flash",
        "gemini-flash-latest",
        "gemini-2.5-flash-lite"
    )

    /** Sunucudan bir kez keşfedilen kullanılabilir modeller (boşsa keşfedilemedi). */
    @Volatile private var discoveredModels: List<String>? = null
    /** Keşfin hangi API anahtarıyla yapıldığı (anahtar değişince yeniden keşfedilir). */
    @Volatile private var discoveredKey: String? = null

    /**
     * v5.11 — Konuşma turu (çok turlu sohbet için).
     * "Peki bu neden böyle?" gibi devam sorularının çalışmasını sağlar.
     */
    data class Turn(val fromUser: Boolean, val text: String)

    /**
     * @param contextPages PDF'den getirilen alaka parçaları ("Sayfa N: ..." biçiminde)
     * @param history bu konuşmanın son turları (eskiden yeniye)
     * @return modelin yanıtı
     */
    fun ask(
        question: String,
        contextPages: List<String>,
        /** v5.11: önceki sorular/cevaplar — devam sorularında bağlam. */
        history: List<Turn> = emptyList(),
        /** Ayarlar'dan "Test" için: kaydı okumadan doğrudan denenir. */
        apiKeyOverride: String? = null,
        modelOverride: String? = null
    ): String {
        val ctxText = if (contextPages.isEmpty()) "(bağlam yok)"
        else contextPages.joinToString("\n\n")

        val apiKey = (apiKeyOverride ?: AppPrefs.lastApiKey ?: "").trim()
        if (apiKey.isEmpty()) {
            throw ApiFatal(
                "API anahtarı yok. Ayarlar → AI Ayarları'dan ücretsiz bir sağlayıcı " +
                    "(Gemini/OpenRouter) seçip anahtar girebilirsin."
            )
        }
        val modelSetting = (modelOverride ?: AppPrefs.lastApiModel ?: "").trim()
        // v13 (A4): Ayarlar'da seçilen sağlayıcıya göre yönlendirme.
        // ("Test" için elle verilen anahtar/model varsa eski sezgisel yol korunur.)
        val provider = if (apiKeyOverride != null) "" else
            (AppPrefs.lastApiProvider ?: "").lowercase()
        // v34 — İNTERNET KOPMASINA DAYANIKLILIK: geçici ağ hatasında (Wi-Fi
        // düşüşü, DNS, timeout) istek BİR kez daha denenir. Kalıcı hatalar
        // (anahtar yok, model bulunamadı) aynen iletilir.
        return try {
            askForProvider(
                provider, question, ctxText, apiKey, modelSetting,
                AppPrefs.lastApiBase ?: "", history
            )
        } catch (f: ApiFatal) {
            throw f
        } catch (m: ModelMissing) {
            throw m
        } catch (e: Exception) {
            try { Thread.sleep(650) } catch (_: InterruptedException) { }
            askForProvider(
                provider, question, ctxText, apiKey, modelSetting,
                AppPrefs.lastApiBase ?: "", history
            )
        }
    }

    /**
     * v5.11 — SİSTEM İSTEMİ ("beyin").
     * Eski istem "yalnızca bağlamdaymış gibi yanıtla" diyordu; bu, öğrencinin
     * "bunu bana açıkla" sorusunda modeli kilitleyip "Bu kitapta geçmiyor"
     * dedirtiyordu. Yeni istem: kaynaklı + dürüst + öğretici.
     */
    private fun buildSystemPrompt(contextText: String): String = (
        "Sen 'Umay' adlı, Türkçe konuşan uzman bir ders asistanısın. " +
            "Öğrencinin yüklediği kitaptan seçilmiş sayfa parçaları aşağıdadır.\n\n" +
            "KURALLAR:\n" +
            "1) Soruyu doğrudan yanıtla. Önce 1-3 cümlelik net cevap, sonra gerekirse " +
            "maddeler, adımlar veya örnek.\n" +
            "2) KİTAP PARÇALARI ana kaynağındır. Kullandığın her bilginin sayfasını " +
            "(s. 42) biçiminde belirt. Birden çok sayfa kullandıysan hepsini yaz.\n" +
            "3) Parçalar soruyu yanıtlamaya yetmiyorsa şu cümleyle başla: " +
            "\"Kitapta bunun için yeterli bilgi yok.\" Ardından genel bilginle kısa bir " +
            "açıklama ekle ve bunu \"Genel bilgi:\" diye ayır. Kitapta olmayan bir sözü " +
            "kitaptan alıntıymış gibi ASLA gösterme.\n" +
            "4) Matematik/fizik: adım adım çöz, sembolleri sade yaz (x², √, ∫). " +
            "Sonucu kalın biçimde belirt.\n" +
            "5) Kod sorusunda çalışan, kısa bir örnek ver; kod bloğu kullan.\n" +
            "6) Önceki konuşma turları varsa onları bağlam say (\"peki bu neden böyle?\" " +
            "gibi devam sorularını anla).\n" +
            "7) Türkçe yaz. \"Harika soru!\", \"Tabii ki!\" gibi boş giriş cümleleri " +
            "kullanma. Gereksiz uzatma; öğrenciye gerçekten yardım edecek bilgiyi ver.\n\n" +
            "KİTAP PARÇALARI:\n" + contextText
        )

    /**
     * v13 (AGENT 22 — A4): Çoklu sağlayıcı yönlendirmesi TEK kapıdan.
     * Ayarlar → "Test" de bu fonksiyonu çağırır (sağlayıcı seçili hâliyle).
     * Boş sağlayıcı = eski sezgisel davranış (AIza… → Gemini, diğer → OpenAI).
     */
    fun askForProvider(
        provider: String,
        question: String,
        contextText: String,
        apiKey: String,
        modelSetting: String,
        baseUrl: String = "",
        history: List<Turn> = emptyList()
    ): String {
        val systemPrompt = buildSystemPrompt(contextText)
        val key = apiKey.trim()
        val model = modelSetting.trim()
        if (key.isEmpty()) {
            throw ApiFatal(
                "API anahtarı yok. Ayarlar → AI Ayarları'dan ücretsiz bir sağlayıcı " +
                    "(Gemini/OpenRouter) seçip anahtar girebilirsin."
            )
        }
        return when (provider.lowercase()) {
            "openrouter" ->
                askOpenAiCompat(key, model, systemPrompt, question, history, OPENROUTER_URL, true)
            "openai" ->
                askOpenAiCompat(key, model, systemPrompt, question, history, DEFAULT_OPENAI_URL, false)
            "custom" -> {
                val base = baseUrl.trim()
                if (base.isEmpty()) throw ApiFatal(
                    "Özel sunucu adresi boş. Ayarlar → AI Ayarları'dan OpenAI-uyumlu " +
                        "adres gir (örn. http://192.168.1.4:11434/v1/chat/completions)."
                )
                askOpenAiCompat(key, model, systemPrompt, question, history, base, false)
            }
            else ->
                if (key.startsWith("AIza") || model.contains("gemini", ignoreCase = true)) {
                    askGemini(key, model, systemPrompt, question, history)
                } else {
                    askOpenAiCompat(key, model, systemPrompt, question, history, DEFAULT_OPENAI_URL, false)
                }
        }
    }

    // ── Gemini ───────────────────────────────────────────────

    private fun askGemini(
        key: String,
        preferred: String,
        system: String,
        question: String,
        history: List<Turn> = emptyList()
    ): String {
        // Kullanıcı tercihi → sunucudaki liste → yerleşik yedekler
        val candidates = ArrayList<String>()
        if (preferred.isNotBlank() && !preferred.startsWith("http")) candidates += preferred
        candidates += discoverModels(key)
        candidates += FALLBACK_MODELS

        var lastError: Exception? = null
        // v5.4 (ONARIM 2): Tek bir soket hatasında TÜM zinciri iptal etmek
        // yanlıştı (model yoğunken de "internet yok" deniyordu). Artık sıradaki
        // model denenir; iki ardışık ağ hatası olursa ağ hatası bildirilir.
        var ioFails = 0
        for (model in candidates.distinct()) {
            try {
                return geminiCall(key, model, system, question, history)
            } catch (e: ApiFatal) {
                throw e                      // anahtar sorunlu: tekrar denemenin anlamı yok
            } catch (e: ModelMissing) {
                lastError = e                // bu model emekli olmuş olabilir
            } catch (e: java.io.IOException) {
                ioFails++
                lastError = ApiFatal(
                    "İnternet bağlantısı yok veya sunucuya ulaşılamıyor. " +
                        "(Wi-Fi/mobil veri ve VPN'i kontrol et)"
                )
                if (ioFails >= 2) throw ApiFatal(lastError!!.message ?: "Ağ hatası")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: ApiFatal("Yanıt alınamadı. Tekrar dene.")
    }

    /** `/models` uçundan generateContent destekleyen modelleri öğrenir (bir kez). */
    private fun discoverModels(key: String): List<String> {
        // Aynı anahtar için önbellek geçerli; anahtar değiştiyse yeniden keşfet
        if (discoveredKey == key) discoveredModels?.let { return it }
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
        if (ordered.isNotEmpty()) {
            discoveredModels = ordered
            discoveredKey = key
        }
        return ordered
    }

    private fun geminiCall(
        key: String,
        model: String,
        system: String,
        question: String,
        history: List<Turn> = emptyList()
    ): String {
        val url = GEMINI_BASE + "models/" + model + ":generateContent?key=" +
            URLEncoder.encode(key, "UTF-8")

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", system))
                })
            })
            put("contents", JSONArray().apply {
                // v5.11 — ÇOK TURLU: önceki sorular/cevaplar bağlam olarak gider.
                // (Eski kod yalnız son soruyu gönderiyordu → "peki bu neden
                // böyle?" sorusunda model neyden bahsedildiğini bilmiyordu.)
                var lastWasUser = false
                for (t in history.takeLast(6)) {
                    if (t.text.isBlank()) continue
                    // Gemini contents USER ile başlamalı: baştaki model turunu atla
                    if (length() == 0 && !t.fromUser) continue
                    put(JSONObject().apply {
                        put("role", if (t.fromUser) "user" else "model")
                        put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", t.text.take(1500)))
                        )
                    })
                    lastWasUser = t.fromUser
                }
                // İki user turu üst üste gelirse Gemini hata verebilir: araya
                // kısa bir model onayı ekle.
                if (lastWasUser) {
                    put(JSONObject().apply {
                        put("role", "model")
                        put("parts", JSONArray().put(JSONObject().put("text", "Anladım.")))
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", question)))
                })
            })
            val gen = JSONObject().apply {
                // v5.11: 0.6 → 0.35 — kitap sorusunda uydurmayı azaltır
                put("temperature", 0.35)
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

        // v5.4 — 503 (model aşırı yüklü/geçici) için aynı modelde BİR kez
        // kısa bekleyip tekrar dene; olmazsa sıradaki modele geçilir.
        var attempt = 0
        while (true) {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.isSuccessful) return geminiTextFrom(text, model)
                if (resp.code == 503 && attempt == 0) {
                    attempt++
                    try { Thread.sleep(900) } catch (_: InterruptedException) {}
                } else {
                    throw classifyGeminiError(resp.code, text, model)
                }
            }
        }
    }

    /** generateContent yanıtından düz metni ayıklar (metin + vision ortak). */
    private fun geminiTextFrom(text: String, model: String): String {
        // v9 (M6 — ÇÖKME KORUMASI): Model HTML/CAPTCHA/geçersiz gövde
        // döndürebilir (proxy, captive portal, servis değişikliği).
        // JSONObject bunu JSONException ile fırlatır → anlaşılır Türkçe
        // hataya çevir; askGemini sıradaki model adayını deneyebilsin.
        if (text.isBlank()) {
            throw IllegalStateException("$model boş yanıt döndü.")
        }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalStateException(
                "$model geçersiz yanıt döndü (JSON değil): ${text.trim().take(120)}…"
            )
        }
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val reason = json.optJSONObject("promptFeedback")
                ?.optString("blockReason").orEmpty()
            throw if (reason.isNotEmpty()) ApiFatal("İstek engellendi: $reason")
            else IllegalStateException("Model boş yanıt döndü.")
        }
        val c0 = candidates.getJSONObject(0)
        val parts = c0.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            out.append(parts.getJSONObject(i).optString("text", ""))
        }
        val result = out.toString().trim()
        if (result.isEmpty()) {
            throw IllegalStateException(
                "$model boş yanıt döndü (sebep: ${c0.optString("finishReason", "bilinmiyor")})."
            )
        }
        return result
    }

    // ── v7: Görsel → Gemini Vision (ders programı structured JSON) ──

    /**
     * v7 — Ders programı görseli için çok modlu (multimodal) istem.
     * Tablo görselleri metin OCR'ıyla düzgün okunmadığı için (WhatsApp'tan
     * gelen fotoğraf/screenshot) pikseller doğrudan Gemini'ye gider.
     */
    private val SCHEDULE_PROMPT = (
        "Bu bir haftalık ders programı tablosudur (telefon fotoğrafı veya ekran " +
            "görüntüsü, bulanık/eğik olabilir). Görseldeki TÜM ders hücrelerini " +
            "aşağıdaki şemada JSON olarak çıkar:\n" +
            "{\"classes\":[{\"day\":\"Pazartesi\",\"start\":\"09:00\"," +
            "\"end\":\"11:00\",\"name\":\"Matematik\",\"room\":\"B-204\"}]}\n" +
            "KURALLAR:\n" +
            "1) Yalnızca JSON döndür; açıklama, markdown (```) veya ek metin yazma.\n" +
            "2) day: tam Türkçe gün adı (Pazartesi…Pazar). Tabloda Pzt/Sal/Çar/Per/" +
            "Cum/Cmt/Paz kısaltmaları varsa tam güne çevir.\n" +
            "3) start/end: 24 saat biçiminde HH:mm (09:00, 13:30). Bitiş yoksa start ile aynı değeri yaz.\n" +
            "4) name: ders adı. Kısaltma/kod bile olsa olduğu gibi aktar (örn. \"MAT 101\").\n" +
            "5) room: derslik/oda; yoksa boş string.\n" +
            "6) Günler SÜTUN halindeyse (her sütun bir gün, altında saatler): hücreleri " +
            "sütun başlığındaki güne eşle ve her ders için AYRI satır üret.\n" +
            "7) Birleştirilmiş hücre / tekrarlayan başlık varsa her ders için ayrı satır yaz.\n" +
            "8) Emin olmadığın satırı ATLA; tabloda olmayan bilgiyi ASLA uydurma.\n" +
            "9) Sonuç en fazla 200 satır."
        )

    /**
     * Ders programı GÖRSELİNİ Gemini Vision modeline gönderir, ham JSON metni
     * döner (ayııklama/normalleştirme ScheduleActivity'de yapılır).
     *
     * Hatalar asla çökertmez: anahtar yoksa/geçersizse `ApiFatal`, bağlantı
     * yoksa `ApiFatal`, sunucu/serial hatası `IllegalStateException` fırlatır;
     * çağıran taraf boş listeyle manuel düzenleme ekranına düşer.
     */
    fun scheduleFromImage(bytes: ByteArray, mime: String): String {
        val apiKey = (AppPrefs.lastApiKey ?: "").trim()
        if (apiKey.isEmpty()) {
            throw ApiFatal("API anahtarı yok. Ayarlar'dan ücretsiz bir Gemini anahtarı girebilirsin.")
        }
        if (!apiKey.startsWith("AIza")) {
            throw ApiFatal("Görsel tanıma için Gemini anahtarı (AIza…) gerekir.")
        }

        // v9 (M6 — OOM SAFE): Ham fotoğraf (48 MP vb.) base64'e girmeden ÖNCE
        // boyut okunur ve örneklenerek 1600px JPEG'e indirgenir. Çağırían
        // (ScheduleActivity) zaten ölçeklese de buradaki SON SAVUNMA, büyük
        // görselde OutOfMemoryError'ın Vision isteğini düşürmesini engeller.
        val safeBytes = if (mime.startsWith("image/") && bytes.size > 1_500_000) {
            try {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    var sample = 1
                    val maxSide = maxOf(opts.outWidth, opts.outHeight)
                    while (maxSide / sample > 1600) sample *= 2
                    var bmp: android.graphics.Bitmap? = null
                    for (attempt in 0 until 3) {
                        try {
                            bmp = android.graphics.BitmapFactory.decodeByteArray(
                                bytes, 0, bytes.size,
                                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                            )
                        } catch (oom: OutOfMemoryError) {
                            bmp = null
                        }
                        if (bmp != null) break
                        sample *= 2
                    }
                    bmp?.let { b ->
                        val out = java.io.ByteArrayOutputStream()
                        b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                        b.recycle()
                        out.toByteArray()
                    } ?: bytes
                } else bytes
            } catch (t: Throwable) {
                // Ölçekleme başarısız → ham byte'larla devam (ağ hatası olur,
                // çökme olmaz)
                bytes
            }
        } else bytes

        val preferred = (AppPrefs.lastApiModel ?: "").trim()
        val candidates = ArrayList<String>()
        if (preferred.contains("gemini", ignoreCase = true) && !preferred.startsWith("http")) {
            candidates += preferred
        }
        candidates += discoverModels(apiKey)
        candidates += FALLBACK_MODELS

        var lastError: Exception? = null
        for (model in candidates.distinct()) {
            try {
                return geminiVisionCall(apiKey, model, safeBytes, mime)
            } catch (e: ApiFatal) {
                throw e
            } catch (e: ModelMissing) {
                lastError = e
            } catch (e: IOException) {
                throw ApiFatal("İnternet bağlantısı yok veya sunucuya ulaşılamıyor.")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: ApiFatal("Görsel analiz edilemedi. Tekrar dene.")
    }

    /**
     * v5.12 — ARAPÇA SAYFA TRANSKRİPSİYONU (OCR).
     *
     * Neden: meal/tefsir PDF'lerinde Arapça satırlar görsel olarak var ama
     * metin katmanında YOK (font→Unicode eşlemesi bozuk). ML Kit'in Arapça
     * modeli bulunmuyor; bu yüzden programda zaten kullanılan ücretsiz
     * Gemini görsel okuma yolu kullanılıyor. Sonuç sayfa başına önbelleğe
     * yazılır → aynı sayfa ikinci kez API'ye gitmez.
     */
    private val OCR_PROMPT = (
        "Bu bir kitap sayfasının görüntüsüdür (Kur'an meali/tefsir olabilir). " +
            "Sayfadaki TÜM metni, görseldeki okuma sırasıyla düz metin olarak yaz.\n" +
            "KURALLAR:\n" +
            "1) Arapça satırları Arapça harflerle, harekeleriyle yaz (çevirme!).\n" +
            "2) Türkçe satırları olduğu gibi yaz (düzeltme/özetleme yapma).\n" +
            "3) Sayfa numarası, cüz/sûre başlığı gibi başlıkları da koru.\n" +
            "4) Açıklama, yorum, markdown veya ``` ekleme; yalnız sayfanın metni.\n" +
            "5) Okuyamadığın kelimeyi atla; uydurma.\n" +
            "6) Satır sonlarını koru (her görsel satır yeni satır)."
        )

    /** Sayfa görüntüsünü metne çevirir (Arapça + Türkçe). Hata → istisna. */
    fun ocrPage(bytes: ByteArray, mime: String = "image/jpeg"): String {
        val apiKey = (AppPrefs.lastApiKey ?: "").trim()
        if (apiKey.isEmpty()) {
            throw ApiFatal(
                "Arapça sayfa okuma için ücretsiz AI anahtarı gerekir " +
                    "(Ayarlar → AI Ayarları)."
            )
        }
        val preferred = (AppPrefs.lastApiModel ?: "").trim()
        val candidates = ArrayList<String>()
        if (preferred.contains("gemini", ignoreCase = true) && !preferred.startsWith("http")) {
            candidates += preferred
        }
        candidates += discoverModels(apiKey)
        candidates += FALLBACK_MODELS

        var lastError: Exception? = null
        for (model in candidates.distinct()) {
            try {
                return geminiVisionCall(apiKey, model, bytes, mime, OCR_PROMPT)
            } catch (e: ApiFatal) {
                throw e
            } catch (e: ModelMissing) {
                lastError = e
            } catch (e: IOException) {
                throw ApiFatal("İnternet bağlantısı yok — Arapça sayfa okunamadı.")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: ApiFatal("Sayfa okunamadı. Tekrar dene.")
    }

    private fun geminiVisionCall(
        key: String,
        model: String,
        bytes: ByteArray,
        mime: String,
        prompt: String = SCHEDULE_PROMPT
    ): String {
        val url = GEMINI_BASE + "models/" + model + ":generateContent?key=" +
            URLEncoder.encode(key, "UTF-8")
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        // Çok modlu: görsel bytes + yapılandırılmış çıktı istemi
                        put(
                            JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", mime)
                                    put("data", b64)
                                })
                            }
                        )
                        put(JSONObject().put("text", prompt))
                    })
                })
            })
            val gen = JSONObject().apply {
                put("temperature", 0.1)          // tablo → deterministik çıktı
                put("maxOutputTokens", 4096)
                if (thinkingAllowed(model)) {
                    put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                }
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
            return geminiTextFrom(text, model)
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
                // Aşama 2.4 — kota limiti asla çökertmez, kullanıcıya yol gösterir
                IllegalStateException(
                    "Günlük AI kullanım limitinize ulaşıldı. " +
                        "Ödüllü reklam izleyerek veya Puan harcayarak hak kazanabilirsiniz!"
                )

            // v5.4 (ONARIM 2) — 503 "model şu an yoğun": ölümcül değil,
            // sıradaki model denenir; hiçbiri tutmazsa anlaşılır mesaj verilir.
            code == 503 || low.contains("high demand") || low.contains("unavailable") ->
                IllegalStateException(
                    "Model şu an yoğun ($model). Sıradaki model deneniyor…"
                )

            else -> IllegalStateException("Sunucu hatası ($code): ${apiMsg.take(200)}")
        }
    }

    // ── OpenAI uyumlu ────────────────────────────────────────

    /**
     * v13 (A4): OpenAI-uyumlu herhangi bir uç nokta (OpenAI, OpenRouter,
     * Ollama/LM Studio gibi yerel sunucular).
     * @param isOpenRouter OpenRouter'a özel başlıklar eklenir (istatistik/sıralama)
     */
    private fun askOpenAiCompat(
        key: String,
        modelSetting: String,
        system: String,
        question: String,
        history: List<Turn> = emptyList(),
        baseUrl: String,
        isOpenRouter: Boolean
    ): String {
        val isUrl = modelSetting.startsWith("http://") || modelSetting.startsWith("https://")
        val url = if (isUrl) modelSetting else baseUrl
        val model = if (isUrl) "gpt-4o-mini" else modelSetting.ifBlank { "gpt-4o-mini" }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                // v5.11 — çok turlu sohbet: son 6 tur
                for (t in history.takeLast(6)) {
                    if (t.text.isBlank()) continue
                    put(
                        JSONObject()
                            .put("role", if (t.fromUser) "user" else "assistant")
                            .put("content", t.text.take(1500))
                    )
                }
                put(JSONObject().put("role", "user").put("content", question))
            })
            put("max_tokens", 1200)
            put("temperature", 0.35)
        }

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .apply {
                if (isOpenRouter) {
                    // OpenRouter istek başlıkları (dokümantasyon zorunlu kılıyor)
                    header("HTTP-Referer", "https://umay.pdfreader.app")
                    header("X-Title", "Umay PDF Okuyucu")
                }
            }
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
                            IllegalStateException(
                                "Günlük AI kullanım limitinize ulaşıldı. " +
                                    "Ödüllü reklam izleyerek veya Puan harcayarak hak kazanabilirsiniz!"
                            )
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
