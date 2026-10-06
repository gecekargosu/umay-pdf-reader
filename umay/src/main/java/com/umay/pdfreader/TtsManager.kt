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
    private var prefetched: Pair<String, File>? = null   // (metin, hazır mp3)
    private var prefetchJob: Job? = null
    private var edgeFailures = 0          // art arda hata sayacı
    private var edgeDisabled = false      // oturum boyunca yerleşiğe düştü mü

    // ── Perde + ekolayzır ("Ses Renkleri") ─────────────
    private var pendingPitch: Float = AppPrefs.getFloat(context, AppPrefs.KEY_PITCH, 1.0f)
    private var eqEnabled = AppPrefs.getBool(context, AppPrefs.KEY_EQ_ON, false)
    private var eqLow = AppPrefs.getFloat(context, AppPrefs.KEY_EQ_LOW, 0f)
    private var eqMid = AppPrefs.getFloat(context, AppPrefs.KEY_EQ_MID, 0f)
    private var eqHigh = AppPrefs.getFloat(context, AppPrefs.KEY_EQ_HIGH, 0f)
    private var equalizer: android.media.audiofx.Equalizer? = null

    /** Ayar + oturum (hata kilitleri) birlikte: Edge kullanılabilir mi? */
    private fun edgeMode(): Boolean =
        AppPrefs.getString(appContext, AppPrefs.KEY_TTS_MODE, "edge") == "edge" &&
            !edgeDisabled && networkAvailable()

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
                        utteranceId?.let(onDone)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onError(utteranceId)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
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
            val m = Regex("(standard|wavenet|neural)[-_]?([a-f])\\$").find(n)
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

    fun speak(raw: String, utteranceId: String) {
        // Sembolleri okunacak metne çevir (matematik/yazılım işaretleri
        // aksi halde motorun ağzında bozuk ses çıkarır)
        val text = TextUtil.forSpeech(raw)
        currentUtterance = utteranceId

        if (!edgeMode()) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            return
        }

        // Edge modu: hazır (prefetch) dosya varsa anında çal
        val cached = prefetched
        if (cached != null && cached.first == text) {
            prefetched = null
            playEdgeFile(cached.second, utteranceId, text)
            return
        }

        edgeJob?.cancel()
        edgeJob = scope.launch {
            val file = try {
                synthesizeEdge(text).also { edgeFailures = 0 }
            } catch (e: Exception) {
                // İnternet yok / servis hata → yerleşik sese düş, okuma durmasın
                Log.w("TtsManager", "Edge ses başarısız, yerleşik sese düşülüyor: ${e.message}")
                if (++edgeFailures >= 2) {
                    edgeDisabled = true   // servis kapalıysa her parçada beklemesin
                    Log.w("TtsManager", "Edge devre dışı bırakıldı, yerleşik ses aktif")
                }
                withContext(Dispatchers.Main) {
                    if (currentUtterance == utteranceId && ready) {
                        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
                    } else if (currentUtterance == utteranceId) {
                        onError(utteranceId)
                    } else {
                        // bu sırada duraklatılmış/atlannmış — sessiz geç
                    }
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
        val out = File(appContext.cacheDir, "edge_${System.nanoTime()}.mp3")
        val voice = EdgeTts.voiceFor(pendingVoiceMode, text)
        val ratePct = ((pendingRate - 1f) * 100f).toInt().coerceIn(-70, 100)
        return EdgeTts.synthesize(text, voice, ratePct, pendingPitch, out)
    }

    private fun playEdgeFile(file: File, utteranceId: String, text: String) {
        releasePlayer()
        val mp = MediaPlayer()
        mediaPlayer = mp
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                attachEqualizer(it)   // Edge mp3'üne ekolayzırı tak
                it.start()
            }
            mp.setOnCompletionListener {
                releasePlayer()
                try { file.delete() } catch (_: Exception) {}   // önbelleği şişirmesin
                if (currentUtterance == utteranceId) onDone(utteranceId)
            }
            mp.setOnErrorListener { _, _, _ ->
                releasePlayer()
                // Yerleşik sese düş
                if (ready) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
                else onError(utteranceId)
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
            if (ready) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            else onError(utteranceId)
        }
    }

    private fun releasePlayer() {
        releaseEqualizer()
        try {
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    /** 1 saati geçen indirilmiş ses dosyalarını temizler (cacheDir şişmesin). */
    private fun purgeEdgeCache() {
        try {
            val cutoff = System.currentTimeMillis() - 3_600_000L
            appContext.cacheDir?.listFiles()?.forEach { f ->
                if (f.name.startsWith("edge_") && f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        currentUtterance = null
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
        try {
            scope.coroutineContext[Job]?.cancel()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        ready = false
    }
}
