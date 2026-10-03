package com.umay.pdfreader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Android yerleşik TextToSpeech motoru sarmalayıcısı.
 * Tamamen ücretsiz ve APIsiz çalışır.
 *
 * - Kadın/erkek/otomatik ses seçimi (motorun sunduğu seslerden)
 * - Hız kontrolü
 * - Cümle bazlı geri bildirim (onDone) ile sayfa akışı yönetimi
 */
class TtsManager(
    context: Context,
    private val onReady: (ok: Boolean) -> Unit,
    private val onDone: (utteranceId: String) -> Unit,
    private val onError: (utteranceId: String?) -> Unit
) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingVoiceMode: String = AppPrefs.getString(context, AppPrefs.KEY_VOICE, "auto")
    private var pendingRate: Float = AppPrefs.getFloat(context, AppPrefs.KEY_RATE, 1.0f)

    /** Otomatik seçim için varsayılan (motor) sesi. */
    var defaultVoice: Voice? = null
        private set

    var allVoices: List<Voice> = emptyList()
        private set

    init {
        tts = TextToSpeech(context) { status ->
            val ok = status == TextToSpeech.SUCCESS
            ready = ok
            if (ok) {
                val t = tts ?: return@TextToSpeech
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

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
            }
            onReady(ok)
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

    fun isReady() = ready

    // ── Ses seçimi ───────────────────────────────────────────

    /** Cinsiyet tahmini: isim ipuçlarına göre. */
    fun guessGender(voice: Voice): String = Companion.guessGender(voice)

    companion object {
        /** Ses isminden cinsiyet tahmini (Google Standard/Wavenet A,C,E = kadın vb.). */
        fun guessGender(voice: Voice): String {
            val n = voice.name.lowercase(Locale.ROOT)
            if (n.contains("female") || n.contains("kadin")) return "female"
            if (n.contains("male")) return "male"
            val m = Regex("(standard|wavenet|neural)[-_]?([a-f])$").find(n)
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
        // Sınıflandırılamadıysa sırayla birini ver (kullanıcı ayarlardan seçebilir)
        if (mode == "female") return pool.getOrNull(0) ?: defaultVoice
        return pool.getOrNull(1) ?: defaultVoice
    }

    fun applyVoice(mode: String) {
        pendingVoiceMode = mode
        val t = tts ?: return
        if (!ready) return
        val v = pickVoice(mode) ?: return
        try {
            t.voice = v
        } catch (e: IllegalArgumentException) {
            // Bazı motorlar seçili sesi yalnızca belirli locale için kabul eder;
            // bu durumda mevcut (varsayılan) ses kullanılmaya devam eder.
        }
    }

    fun applyRate(rate: Float) {
        pendingRate = rate
        tts?.setSpeechRate(rate)
    }

    // ── Konuşma ──────────────────────────────────────────────

    fun speak(text: String, utteranceId: String) {
        val t = tts ?: return
        t.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        ready = false
    }
}
