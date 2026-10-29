package com.umay.pdfreader

import android.content.Context
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ses motoru.
 *
 * İki mod:
 *  - "builtin": Android yerleşik TextToSpeech (ücretsiz, çevrimdışı)
 *  - "edge":    Microsoft Edge neural ses (ücretsiz, internet gerekir;
 *               ChatGPT/Edge kalitesinde doğal okuma). İnternet/hata
 *               durumunda OTOMATİK olarak yerleşik sese düşer.
 *
 * Arayüz sabittir: speak/stop/isReady/applyRate/applyVoice/shutdown;
 * ReadService'in bilgisi değişmez.
 */
class TtsManager(
    context: Context,
    private val onReady: (ok: Boolean) -> Unit,
    private val onDone: (utteranceId: String) -> Unit,
    private val onError: (utteranceId: String?) -> Unit
) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingVoiceMode: String = AppPrefs.getString(context, AppPrefs.KEY_VOICE, "auto")
    private var pendingRate: Float = AppPrefs.getFloat(context, AppPrefs.KEY_RATE, 1.0f)

    // ── Edge (doğal) ses durumu ─────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var edgeJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentUtterance: String? = null

    // ── v21 — GERÇEK EKOLAYZIR: çalan mp3'ün PCM zarfı ─────────────
    // Zarf dosya başına bir kez (IO'da) çözülür; ReaderActivity oynatma
    // konumuna göre okur. Ses yoksa -1 döner → çubuklar hareket etmez.
    @Volatile private var edgeFileRef: File? = null
    @Volatile private var edgeEnvelope: FloatArray? = null
    private var prefetched: Pair<String, File>? = null   // (metin, hazır mp3)
    private var prefetchJob: Job? = null

    // ── v10 (M3 — MOTOR KİLİTLENME / ZAMAN AŞIMI KORUMASI) ─────────
    // Yerleşik TTS motoru bazı cihazlarda speak() sonrası HİÇ olay üretmez
    // (onDone/onError gelmez) → parça takılır, "yanıt vermedi" hatası ve
    // sessiz atlama oluşur. Watchdog motorun yanıtını süreyle sınırlar:
    // süre dolarsa parça hataya düşer, motor kilitlenmiş sayılır ve (ağ
    // varsa) okuma Edge Neural yedek motoruna OTOMATİK taşınır.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var watchdog: Runnable? = null
    private var lastSpokenText: String? = null
    private var builtinStuck = false       // oturum: yerleşik motor cevapsız kaldı
    /** v5.4 — Motorun o anki dili; gereksiz `setLanguage` çağrısı yapılmasın. */
    private var currentLocale: java.util.Locale? = null

    private var edgeFailures = 0          // art arda hata sayacı
    private var edgeDisabled = false      // oturum boyunca yerleşiğe düştü mü

    // ── v13 (AGENT 22 — ADIM 1): EDGE/MP3 OYNATMA WATCHDOG (4 SN KURALI) ──
    // MediaPlayer prepareAsync()/start() ağ kopması veya YARIM INMİŞ mp3'te
    // hiçbir olay üretmeyebilir (onPrepared/onCompletion/onError sessiz) →
    // parça sonsuza kadar "okunuyor" gibi görünür ("1 sayfa okuyup takılma").
    // Kural: (1) ses 4 sn'de başlamazsa, (2) oynatma ilerlemiyorsa
    // (currentPosition 4 tur sabit kalırsa) parça kesilir ve oturum
    // KİLİTLENMEDEN sessizce yerleşik motora devredilir.
    private var edgeWatchdog: Runnable? = null
    private var edgeStartedAt = 0L
    private var edgeLastPos = -1
    private var edgeStallTicks = 0

    // ── Perde + ekolayzır ("Ses Renkleri") ─────────────
    private var pendingPitch: Float = AppPrefs.getFloat(context, AppPrefs.KEY_PITCH, 1.0f)
    private var eqEnabled = AppPrefs.getBool(context, AppPrefs.KEY_EQ_ON, false)
    private var eqLow = AppPrefs.getFloat(context, AppPrefs.KEY_EQ_LOW, 0f)
    private var eqMid = AppPrefs.getFloat(context, AppPrefs.KEY_EQ_MID, 0f)
    private var eqHigh = AppPrefs.getFloat(context, AppPrefs.KEY_EQ_HIGH, 0f)
    private var equalizer: android.media.audiofx.Equalizer? = null

    /**
     * v5.4 — Edge uç noktası Microsoft tarafından değiştirildiğinde (403)
     * sunucuya 24 saat boyunca hiç gidilmez. Aksi hâlde her cümlede 15-20 sn
     * bağlantı denemesi yapılıyor, okuma "donmuş" gibi görünüyordu.
     */
    private fun edgeBlocked(): Boolean {
        val until = AppPrefs.getLong(appContext, AppPrefs.KEY_EDGE_BLOCKED_UNTIL, 0L)
        return until > System.currentTimeMillis()
    }

    /**
     * v36 — Karar artık `TtsEnginePolicy` içinde (saf, birim testli).
     * Burada yalnız cihazdan gelen durum toplanır.
     */
    private fun policyState(): TtsEnginePolicy.State = TtsEnginePolicy.State(
        mode = AppPrefs.getString(appContext, AppPrefs.KEY_TTS_MODE, "edge"),
        edgeBlockedUntil = AppPrefs.getLong(appContext, AppPrefs.KEY_EDGE_BLOCKED_UNTIL, 0L),
        now = System.currentTimeMillis(),
        online = networkAvailable(),
        edgeDisabledSession = edgeDisabled,
        builtinStuck = builtinStuck,
        builtinMachineAllowed = !AppPrefs.getBool(appContext, AppPrefs.KEY_TTS_NO_BUILTIN, true)
    )

    /** Ayar + oturum (hata kilitleri) birlikte: Edge kullanılabilir mi? */
    private fun edgeMode(): Boolean = TtsEnginePolicy.edgeUsable(policyState())

    /** Kullanıcı Edge Neural'ı birincil motor olarak mı istiyor? */
    private fun edgePreferred(): Boolean = TtsEnginePolicy.edgePreferred(policyState())

    /**
     * v5 (ADIM C): Mekanik yerleşik ses devre dışı bırakılmış mı?
     *  - Kullanıcı "yerleşik" seçtiyse → elbette kullanılır.
     *  - Ağ YOKSA → Edge hiç mümkün değil; çevrimdışı okuma için izin verilir.
     *  - Aksi halde ayar (KEY_TTS_NO_BUILTIN, varsayılan AÇIK) geçerlidir:
     *    Edge Neural ulaşılamazsa mekanik ses ÇALMAZ, parça hataya düşer
     *    (ses asla mekanik tonda çıkmaz — spec: "yerleşik ses devre dışı").
     */
    private fun builtinAllowed(): Boolean = TtsEnginePolicy.builtinFallbackAllowed(policyState())

    /** Ağ var mı? Yoksa Edge'e hiç deneme — çevrimdışı anında yerleşik sese düş. */
    private fun networkAvailable(): Boolean = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        false
    }

    /** Otomatik seçim için varsayılan (motor) sesi. */
    var defaultVoice: Voice? = null
        private set

    var allVoices: List<Voice> = emptyList()
        private set

    init {
        scope.launch { purgeEdgeCache() }
        tts = TextToSpeech(context) { status ->
            val ok = status == TextToSpeech.SUCCESS
            ready = ok
            if (ok) {
                val t = tts ?: return@TextToSpeech
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {}

                    override fun onDone(utteranceId: String?) {
                        cancelWatchdog()   // v10 (M3): motor cevap verdi
                        utteranceId?.let(onDone)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        cancelWatchdog()
                        onError(utteranceId)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        cancelWatchdog()
                        onError(utteranceId)
                    }
                })

                // Türkçe ses varsa onu tercih et
                val tr = Locale("tr", "TR")
                val res = t.setLanguage(tr)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    t.setLanguage(Locale.getDefault())
                }

                refreshVoices()
                applyRate(pendingRate)
                applyVoice(pendingVoiceMode)
                t.setPitch(pendingPitch)
            }
            onReady(ok || AppPrefs.getString(appContext, AppPrefs.KEY_TTS_MODE, "edge") == "edge")
        }
    }

    private fun refreshVoices() {
        val t = tts ?: return
        val voices = try {
            t.voices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        allVoices = voices
        defaultVoice = t.defaultVoice
            ?: voices.firstOrNull { it.locale.language.equals("tr", true) }
            ?: voices.firstOrNull()
    }

    fun isReady(): Boolean =
        AppPrefs.getString(appContext, AppPrefs.KEY_TTS_MODE, "edge") == "edge" || ready

    // ── Ses seçimi ─────────────────────────────────────────

    fun guessGender(voice: Voice): String = Companion.guessGender(voice)

    companion object {
        /** Ses isminden cinsiyet tahmini (Google Standard/Wavenet A,C,E = kadın vb.). */
        fun guessGender(voice: Voice): String {
            val n = voice.name.lowercase(Locale.ROOT)
            if (n.contains("female") || n.contains("kadin")) return "female"
            if (n.contains("male")) return "male"
            // DİKKAT: sonu(^/$) bağlacı — "([a-f])\\$" olsaydı regex SABİT "$"
            // karakteri arar ve hiçbir sesle eşleşmezdi (cinsiyet tahmini ölü koddu)
            val m = Regex("(standard|wavenet|neural)[-_]?([a-f])\$", RegexOption.IGNORE_CASE)
                .find(n)
            if (m != null) {
                val letter = m.groupValues[2]
                if (letter in "ace") return "female"
                if (letter in "bdf") return "male"
            }
            return "unknown"
        }
    }

    private fun pickVoice(mode: String): Voice? {
        if (mode.startsWith("name:")) {
            val name = mode.removePrefix("name:")
            allVoices.firstOrNull { it.name == name }?.let { return it }
        }
        val gender = if (mode == "female") "female" else if (mode == "male") "male" else null
        if (gender == null) return defaultVoice

        // Önce Türkçe sesler arasında ara, yoksa tüm seslerde ara
        val trVoices = allVoices.filter { it.locale.language.equals("tr", true) }
        val pool = if (trVoices.isNotEmpty()) trVoices else allVoices
        pool.firstOrNull { guessGender(it) == gender }?.let { return it }
        if (mode == "female") return pool.getOrNull(0) ?: defaultVoice
        return pool.getOrNull(1) ?: defaultVoice
    }

    /**
     * Önceden indirilen (prefetch) ses artık güncel ayarlarla üretilmedi;
     * ESKİ HIZDAKİ dosya çalınıyordu — iptal edip sil.
     */
    private fun invalidatePrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        val p = prefetched
        prefetched = null
        if (p != null) {
            try { p.second.delete() } catch (_: Exception) {}
        }
    }

    fun applyVoice(mode: String) {
        pendingVoiceMode = mode
        invalidatePrefetch()
        val t = tts ?: return
        if (!ready) return
        val v = pickVoice(mode) ?: return
        try {
            t.voice = v
        } catch (e: IllegalArgumentException) {
            // Bazı motorlar seçili sesi yalnızca belirli locale için kabul eder
        }
    }

    fun applyRate(rate: Float) {
        pendingRate = rate
        invalidatePrefetch()   // eski hızdaki önbellek dosyası çalınmasın
        tts?.setSpeechRate(rate)
    }

    /**
     * Ses perdesi: 0.5 (derin) – 2.0 (tiz). Her iki motorda da çalışır
     * (yerleşik TTS: setPitch, Edge: SSML prosody pitch).
     */
    fun applyPitch(p: Float) {
        pendingPitch = p.coerceIn(0.5f, 2.0f)
        invalidatePrefetch()
        tts?.setPitch(pendingPitch)
    }

    /**
     * Ekolayzır (Alçak/Orta/Yüksek, -100..+100).
     * YALNIZ Edge/mp3 modunda çalışır: sistem TTS'in ses oturumu
     * uygulamaya açıktır, mp3'ün açıktır. Desteklenmeyen cihazda
     * sessizce yok sayılır, okuma etkilenmez.
     */
    fun setEqualizer(on: Boolean, low: Float, mid: Float, high: Float) {
        eqEnabled = on
        eqLow = low.coerceIn(-100f, 100f)
        eqMid = mid.coerceIn(-100f, 100f)
        eqHigh = high.coerceIn(-100f, 100f)
        AppPrefs.putBool(appContext, AppPrefs.KEY_EQ_ON, eqEnabled)
        AppPrefs.putFloat(appContext, AppPrefs.KEY_EQ_LOW, eqLow)
        AppPrefs.putFloat(appContext, AppPrefs.KEY_EQ_MID, eqMid)
        AppPrefs.putFloat(appContext, AppPrefs.KEY_EQ_HIGH, eqHigh)
        // Çalışan oynatıma anında uygula
        mediaPlayer?.let { attachEqualizer(it) }
    }

    /** Ekolayzırı verilen mp3 oturumuna bağlar. */
    private fun attachEqualizer(mp: MediaPlayer) {
        releaseEqualizer()
        if (!eqEnabled) return
        try {
            val eq = android.media.audiofx.Equalizer(0, mp.audioSessionId)
            val minLevel = eq.bandLevelRange[0].toInt()
            val maxLevel = eq.bandLevelRange[1].toInt()

            fun setFreq(freqHz: Int, gain: Float) {
                // Frekans → bant eşlemesi (center freq'lerden; API'de
                // getBandForFrequency yok) — birim milisaniye değil mHz
                val target = freqHz.toLong() * 1000L
                var bestBand = 0.toShort()
                var bestDelta = Long.MAX_VALUE
                for (b in 0 until eq.numberOfBands.toInt()) {
                    val cf = eq.getCenterFreq(b.toShort()).toLong()
                    val delta = Math.abs(cf - target)
                    if (delta < bestDelta) {
                        bestDelta = delta
                        bestBand = b.toShort()
                    }
                }
                // Kazanç -100..+100 → cihazın mB aralığına ölçekle
                val level = (gain / 100f * maxLevel).toInt()
                    .coerceIn(minLevel, maxLevel).toShort()
                eq.setBandLevel(bestBand, level)
            }
            setFreq(100, eqLow)      // bas
            setFreq(1000, eqMid)     // orta
            setFreq(8000, eqHigh)    // tiz
            eq.enabled = true
            equalizer = eq
        } catch (e: Exception) {
            // Bu cihazda desteklenmiyor olabilir → okuma etkilenmez
            releaseEqualizer()
        }
    }

    private fun releaseEqualizer() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
        } catch (_: Exception) {}
        equalizer = null
    }

    // ── Konuşma ────────────────────────────────────────────

    // ── v10 (M3): Yerleşik motora güvenli konuşma + ZAMAN AŞIMI watchdog ──

    /**
     * Yerleşik motora güvenle konuşur:
     *  - motor ölüyse `speak()` IllegalStateException fırlatabilir → yakalanır,
     *    parça hataya düşer ve akış durmaz (eski kodda bu İSTİSNA servisi
     *    çökertebiliyordu).
     *  - motor hiç cevap üretmezse (yanıt vermiyor / kilitlendi) watchdog
     *    süre sonunda parça hatasını üretir → "yanıt vermedi" mesajı ve
     *    otomatik yedek motor geçişi devreye girer.
     */
    private fun speakBuiltinSafely(text: String, utteranceId: String) {
        val t = tts
        if (t == null || !ready) {
            onError(utteranceId)
            return
        }
        try {
            // v5.4 — YABANCI ALFABE KORUMASI (Kur'an-ı Kerim / Arapça sayfalar):
            // Türkçe ses motoru Arapça metinde hata verince okuma DURUYORDU.
            // Artık metin motora uyarlanır; hiç okunabilir harf kalmazsa parça
            // sessizce atlanır ve okuma devam eder (asla takılı kalmaz).
            val speakText = adaptToEngine(t, text)
            if (speakText.isBlank()) {
                onDone(utteranceId)
                return
            }
            val res = t.speak(speakText, TextToSpeech.QUEUE_ADD, null, utteranceId)
            if (res == TextToSpeech.ERROR) {
                onError(utteranceId)
                return
            }
            startWatchdog(speakText, utteranceId)
        } catch (e: Throwable) {
            Log.w("TtsManager", "Yerleşik TTS speak() fırlattı: ${e.message}")
            onError(utteranceId)
        }
    }

    /**
     * v5.4 — Motorun okuyamacağı alfabeleri (Arapça, İbrani, CJK) yönetir.
     *  • Motor dil desteği varsa o dile geçer (Arapça metin doğru okunur).
     *  • Destek yoksa Arapça bloklar çıkarılır; geriye okunabilir bir şey
     *    kalmazsa boş döner → çağıran parçayı atlar (okuma durmaz).
     * Her çağrıdan sonra dil, metnin ağırlıklı yazısına göre ayarlanır.
     */
    private fun adaptToEngine(t: TextToSpeech, text: String): String {
        val arabicCount = text.count { it in '\u0600'..'\u06FF' }
        val latinCount = text.count {
            it in 'A'..'Z' || it in 'a'..'z' || it in "ÇĞİÖŞÜçğıöşü"
        }
        if (arabicCount == 0) {
            switchLanguage(t, java.util.Locale("tr", "TR"))
            return text
        }
        val arabicSupported = try {
            t.isLanguageAvailable(java.util.Locale("ar")) >= TextToSpeech.LANG_AVAILABLE
        } catch (_: Throwable) {
            false
        }
        if (arabicSupported) {
            switchLanguage(t, java.util.Locale("ar"))
            return text
        }
        if (latinCount == 0) return ""          // sadece Arapça, motor okuyamıyor
        // Latin kısımları kalsın (dipnot/başlık), Arapça bloklar çıksın
        return text.filter { it !in '\u0600'..'\u06FF' }
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    /** Dil değişimi başarısız olursa sessizce yok sayılır (okuma kesilmez). */
    private fun switchLanguage(t: TextToSpeech, locale: java.util.Locale) {
        if (currentLocale == locale) return   // zaten bu dilde → gereksiz iş yok
        try {
            if (t.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                if (t.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) {
                    currentLocale = locale
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Motor cevap süresi (ms): 30 sn + 1.5 sn/karakter, en fazla 5 dk. */
    private fun startWatchdog(text: String, utteranceId: String) {
        cancelWatchdog()
        val timeout = (30_000L + text.length * 1_500L).coerceAtMost(300_000L)
        val r = Runnable { onBuiltinTimeout(utteranceId) }
        watchdog = r
        mainHandler.postDelayed(r, timeout)
    }

    private fun cancelWatchdog() {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
    }

    /**
     * ZAMAN AŞIMI: yerleşik motor yanıt vermedi → kilitlendi sayılır.
     * Ağ varsa parça DOĞRUDAN yedek Edge Neural motoruyla okunur ve oturum
     * boyunca Edge birincil hâle gelir; yoksa parça hataya düşer (ReadService
     * tekrar deneme + duraklatma mantığıyla ilerler).
     */
    private fun onBuiltinTimeout(utteranceId: String) {
        watchdog = null
        if (currentUtterance != utteranceId) return   // bu sırada zaten ilerlenmiş
        Log.w("TtsManager", "Yerleşik TTS motoru yanıt vermedi → kilitlendi sayıldı")
        try { tts?.stop() } catch (_: Throwable) {}

        val text = lastSpokenText
        if (text != null && networkAvailable() && !edgeDisabled) {
            builtinStuck = true
            edgeFailures = 0
            edgeJob?.cancel()
            edgeJob = scope.launch {
                val file = try {
                    synthesizeEdge(text)
                } catch (e: Exception) {
                    Log.w("TtsManager", "Yedek Edge motoru da başarısız: ${e.message}")
                    withContext(Dispatchers.Main) {
                        if (currentUtterance == utteranceId) onError(utteranceId)
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    if (currentUtterance == utteranceId) {
                        Log.w("TtsManager", "Yedek ses motoruna (Edge Neural) geçildi")
                        playEdgeFile(file, utteranceId, text)
                    } else file.delete()
                }
            }
            return
        }
        onError(utteranceId)   // yedek yok → ReadService tekrarlar/duraklatır
    }

    fun speak(raw: String, utteranceId: String) {
        // Sembolleri okunacak metne çevir (matematik/yazılım işaretleri
        // aksi halde motorun ağzında bozuk ses çıkarır)
        val text = TextUtil.forSpeech(raw)
        currentUtterance = utteranceId
        lastSpokenText = text          // v10 (M3): yedek motor geçişi için sakla
        cancelWatchdog()               // önceki yerleşik parçanın watchdog'ı temizlensin

        // v9 (GÖREV 2.1 — TTS ATLAMA KÖK ÇÖZÜMÜ): BOŞ/GEÇERSİZ CHUNK KORUMASI.
        // `forSpeech` yalnızca sembol/HTML içeren bir parçayı tamamen boş
        // bırakabilir (örn. "<  > && | ||"). Android TTS `speak("")` çağrısı
        // için ne onStart ne onDone ne onError üretir → parça TAKILIR ve
        // okuma sessizce sayfa sonuna atlar (kullanıcıya "okumuyor" görünür).
        // Boş chunk'ta zaten okunacak bir şey yoktur → doğrudan "tamam"
        // bildirilir, akış sıradaki parçaya BLOKSUZ ilerler.
        // (onDone, ReadService'te mainHandler.post ile işlendiği için stack
        //  taşması olmaz; iki motor için de ortak korumadır.)
        if (text.isBlank()) {
            onDone(utteranceId)
            return
        }

        if (!edgeMode()) {
            // v5: mekanik ses devre dışı bırakıldıysa (Edge tercihli + ağ var)
            // yerleşik motor ÇALMAZ — parça hataya düşer, akış sürdürülür.
            if (builtinAllowed()) speakBuiltinSafely(text, utteranceId)
            else onError(utteranceId)
            return
        }

        // Edge modu: hazır (prefetch) dosya varsa anında çal
        val cached = prefetched
        if (cached != null && cached.first == text) {
            prefetched = null
            // Dosya bu arada silinmiş/purge edilmiş olabilir → kontrol et
            if (cached.second.exists() && cached.second.length() > 0L) {
                playEdgeFile(cached.second, utteranceId, text)
                return
            }
            try { cached.second.delete() } catch (_: Exception) {}
        }

        edgeJob?.cancel()
        edgeJob = scope.launch {
            val file = try {
                synthesizeEdge(text).also { edgeFailures = 0 }
            } catch (e: Exception) {
                // İnternet yok / servis hata → yerleşik sese düş, okuma durmasın
                // (v5: yalnızca "yerleşik ses" izinliyse; ADIM C'de aksi hâlde
                //  parça hataya düşer ve okuma ATLANARAK sürdürülür)
                Log.w("TtsManager", "Edge ses başarısız: ${e.message}")
                // v5.4 — 403/401 = protokol değişti: 24 saat Edge'i tamamen kapat
                val msg = e.message ?: ""
                if (msg.contains("403") || msg.contains("401") || msg.contains("101")) {
                    AppPrefs.putLong(
                        appContext, AppPrefs.KEY_EDGE_BLOCKED_UNTIL,
                        System.currentTimeMillis() + 24L * 60 * 60 * 1000
                    )
                    Log.w("TtsManager", "Edge 24 saat devre dışı (403) — yerleşik motor kullanılacak")
                }
                if (++edgeFailures >= 2) {
                    edgeDisabled = true   // oturum boyunca Edge atlanır (hız); KİLİT YOK
                    Log.w("TtsManager", "Edge geçici olarak atlandı — yerleşik motora düşülüyor")
                }
                // v13 (AGENT 22): "mekanik ses devre dışı" engeli BİLEREK aşılır:
                // kararlılık hatasında oturum asla kilitlenmez, okuma sessizce
                // yerleşik Android TTS motoruna devreder (kullanıcıya duyurulmaz).
                withContext(Dispatchers.Main) {
                    if (currentUtterance == utteranceId) {
                        if (ready) speakBuiltinSafely(text, utteranceId)
                        else onError(utteranceId)
                    }
                    // bu sırada duraklatılmış/atlanmış — sessiz geç
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (currentUtterance == utteranceId) playEdgeFile(file, utteranceId, text)
                else file.delete()   // bu sırada duraklatılmış/atlannmış
            }
        }
    }

    /** Sıradaki parçayı önceden indirir (chunk'lar arası beklemeyi kısaltır). */
    fun prefetch(raw: String?) {
        if (!edgeMode()) return   // ağ yoksa hiç deneme
        val text = raw?.let { TextUtil.forSpeech(it) }
        if (text.isNullOrBlank()) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            try {
                val f = synthesizeEdge(text)
                withContext(Dispatchers.Main) { prefetched = text to f }
            } catch (e: Exception) {
                // sessiz: prefetch başarısızsa speak anında indirir
            }
        }
    }

    @Throws(Exception::class)
    private suspend fun synthesizeEdge(text: String): File {
        val voice = EdgeTts.voiceFor(pendingVoiceMode, text)
        val ratePct = ((pendingRate - 1f) * 100f).toInt().coerceIn(-70, 100)
        // v34 — İNTERNET KOPMALARINA DAYANIKLILIK: 2 deneme, kısa bekleme.
        // Kesinti anında okuma hemen "hata" verip durmak yerine yeniden dener.
        var last: Throwable? = null
        repeat(2) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(450L)
            val out = File(appContext.cacheDir, "edge_${System.nanoTime()}.mp3")
            try {
                return EdgeTts.synthesize(text, voice, ratePct, pendingPitch, out)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                last = t
                try { out.delete() } catch (_: Throwable) { }
            }
        }
        throw (last ?: IllegalStateException("Edge sentez başarısız"))
    }

    private fun playEdgeFile(file: File, utteranceId: String, text: String) {
        releasePlayer()
        edgeFileRef = file
        edgeEnvelope = null
        val mp = MediaPlayer()
        mediaPlayer = mp
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                try {
                    attachEqualizer(it)   // Edge mp3'üne ekolayzırı tak
                    it.start()
                    // v21: zarfı arka planda çöz (oynatmayı BLOKLAMAZ)
                    scope.launch {
                        val env = try { AudioEnvelope.forFile(file) } catch (_: Throwable) { null }
                        withContext(Dispatchers.Main) {
                            if (edgeFileRef === file) edgeEnvelope = env
                        }
                    }
                } catch (t: Throwable) {
                    // MediaPlayer bu sırada release edilmiş / geçersiz durumda
                    // → sessizce bırakma, hataya düşür ki servis tekrar etsin
                    Log.w("TtsManager", "mp3 başlatılamadı: ${t.message}")
                    releasePlayer()
                    onError(utteranceId)
                }
            }
            mp.setOnCompletionListener {
                releasePlayer()
                try { file.delete() } catch (_: Exception) {}   // önbelleği şişirmesin
                if (currentUtterance == utteranceId) onDone(utteranceId)
            }
            mp.setOnErrorListener { _, _, _ ->
                releasePlayer()
                // Yerleşik sese düş (v10: güvenli yol + watchdog)
                if (ready) speakBuiltinSafely(text, utteranceId)
                else onError(utteranceId)
                true
            }
            mp.prepareAsync()
            startEdgeWatchdog(utteranceId, text)   // v13: 4 sn kuralı (başlamazsa/takılırsa kes)
        } catch (e: Exception) {
            releasePlayer()
            if (ready) speakBuiltinSafely(text, utteranceId)
            else onError(utteranceId)
        }
    }

    // ── v13 (AGENT 22): Edge/mp3 oynatma watchdog — kesin 4 sn kuralı ──────
    //
    //  • prepareAsync 4 sn'de tamamlanmaz / start() çalışmaz → ses BAŞLAMADI
    //    sayılır, parça kesilir.
    //  • Oynatma başladıysa currentPosition 4 tur (≈4 sn) sabit kalırsa →
    //    YARIM/BOZUK mp3 veya ağ kopması: parça kesilir.
    //  • Her iki durumda da oturum KİLİTLENMEZ: doğrudan yerleşik motora
    //    sessizce devredilir (fallback). Builtin kendi watchdog'uyla korunur.
    private fun startEdgeWatchdog(utteranceId: String, text: String) {
        cancelEdgeWatchdog()
        edgeStartedAt = android.os.SystemClock.uptimeMillis()
        edgeLastPos = -1
        edgeStallTicks = 0
        val r = object : Runnable {
            override fun run() {
                val mp = mediaPlayer
                if (mp == null || currentUtterance != utteranceId) {
                    cancelEdgeWatchdog()
                    return
                }
                val elapsed = android.os.SystemClock.uptimeMillis() - edgeStartedAt
                val pos = try { mp.currentPosition } catch (_: Throwable) { -2 }
                val started = try { mp.isPlaying } catch (_: Throwable) { false }
                val timedOut = if (started) {
                    if (pos in 0..edgeLastPos) edgeStallTicks++ else edgeStallTicks = 0
                    if (pos > edgeLastPos) edgeLastPos = pos
                    edgeStallTicks >= 4           // ≈4 sn ilerleme durması
                } else {
                    elapsed > 4_000L              // 4 sn'de ses BAŞLAMADI
                }
                if (timedOut) {
                    cancelEdgeWatchdog()
                    Log.w("TtsManager", "Edge oynatma takıldı (elapsed=${elapsed}ms pos=$pos started=$started) → yerleşik motora devrediliyor")
                    try { mp.stop() } catch (_: Throwable) {}
                    releasePlayer()
                    if (ready) speakBuiltinSafely(text, utteranceId)
                    else onError(utteranceId)
                } else {
                    mainHandler.postDelayed(this, 1_000L)
                }
            }
        }
        edgeWatchdog = r
        mainHandler.postDelayed(r, 1_000L)
    }

    private fun cancelEdgeWatchdog() {
        edgeWatchdog?.let { mainHandler.removeCallbacks(it) }
        edgeWatchdog = null
    }

    /** v21 — Gerçek ekolayzır seviyesi (0..1) veya ses yoksa -1. */
    fun audioLevel(): Float {
        val mp = mediaPlayer ?: return -1f
        val env = edgeEnvelope ?: return -1f
        return try {
            if (!mp.isPlaying) return -1f
            val dur = mp.duration
            if (dur <= 0) return -1f
            val frac = (mp.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
            val idx = (frac * (env.size - 1)).toInt().coerceIn(0, env.size - 1)
            env[idx]
        } catch (_: Throwable) {
            -1f
        }
    }

    /** Edge mp3 çalıyor mu? (zarf hazır olmasa da true) */
    fun isEdgePlaying(): Boolean = try { mediaPlayer?.isPlaying == true } catch (_: Throwable) { false }

    private fun releasePlayer() {
        cancelEdgeWatchdog()   // v13: oynatıcı ölünce watchdog da ölsün
        edgeFileRef = null
        edgeEnvelope = null
        releaseEqualizer()
        try {
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    /**
     * v13 (AGENT 22 — A1): Edge ses önbelleği 100 MB'lık GÜVENLİ tampon
     * içinde tutulur (eski: yalnız "1 saat" kuralı → şişme/OOM riski).
     * Temizlik ARKA PLANDA çalışır (init'teki IO coroutine); 0 baytlık
     * yarım inmiş dosyalar anında silinir, bütçe aşılırsa en eskiler gider.
     */
    private fun purgeEdgeCache() {
        try {
            CacheKeeper.trim(appContext.cacheDir, "edge_", CacheKeeper.MAX_CACHE_BYTES)
        } catch (_: Exception) {}
    }

    fun stop() {
        currentUtterance = null
        cancelWatchdog()
        edgeJob?.cancel()
        edgeJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        prefetched = null
        releasePlayer()
        tts?.stop()
    }

    fun shutdown() {
        stop()
        cancelWatchdog()
        try {
            scope.coroutineContext[Job]?.cancel()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        ready = false
    }
}
