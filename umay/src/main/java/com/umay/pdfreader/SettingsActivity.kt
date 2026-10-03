package com.umay.pdfreader

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.umay.pdfreader.databinding.ActivitySettingsBinding
import java.util.Locale

/**
 * Ayarlar: ses (kadın/erkek), hız, çeviri, opsiyonel AI API anahtarı.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    private data class VoiceEntry(val label: String, val value: String)

    private val voiceEntries = mutableListOf(
        VoiceEntry("Otomatik (motor varsayılanı)", "auto"),
        VoiceEntry("Kadın (varsayılan)", "female"),
        VoiceEntry("Erkek (varsayılan)", "male")
    )

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // ── Hız ──
        val speedLabels = speeds.map {
            if (it == it.toInt().toFloat()) "${it.toInt()}x" else "${it}x"
        }
        binding.speedSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, speedLabels
        )
        val curRate = AppPrefs.getFloat(this, AppPrefs.KEY_RATE, 1.0f)
        binding.speedSpinner.setSelection(
            speeds.indexOfFirst { it == curRate }.let { if (it < 0) 1 else it }
        )

        // ── Ses listesi (geçici TTS ile doldurulur) ──
        rebuildVoiceSpinner()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                fillVoices()
            }
        }

        val curVoice = AppPrefs.getString(this, AppPrefs.KEY_VOICE, "auto")
        binding.voiceSpinner.setSelection(
            voiceEntries.indexOfFirst { it.value == curVoice }.let { if (it < 0) 0 else it }
        )

        binding.switchTranslate.isChecked =
            AppPrefs.getBool(this, AppPrefs.KEY_TRANSLATE, false)
        binding.apiKeyInput.setText(AppPrefs.getString(this, AppPrefs.KEY_API_KEY))
        binding.apiModelInput.setText(AppPrefs.getString(this, AppPrefs.KEY_API_MODEL))

        binding.btnTestVoice.setOnClickListener { testVoice() }
        binding.btnSave.setOnClickListener { save() }
    }

    private fun fillVoices() {
        val t = tts ?: return
        t.setLanguage(Locale("tr", "TR"))
        val voices = try {
            t.voices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (voices.isEmpty()) return

        val trVoices = voices.filter { it.locale.language.equals("tr", true) }
        val pool = (trVoices + voices.filter { it !in trVoices }).distinct()

        // Sadece bir kez doldur
        if (voiceEntries.size > 3) return

        pool.take(8).forEach { v ->
            val tag = when (TtsManager.guessGender(v)) {
                "female" -> "♀"
                "male" -> "♂"
                else -> "•"
            }
            voiceEntries.add(VoiceEntry("$tag ${v.name}", "name:${v.name}"))
        }
        rebuildVoiceSpinner()
    }

    private fun rebuildVoiceSpinner() {
        val previous = binding.voiceSpinner.selectedItemPosition
        binding.voiceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            voiceEntries.map { it.label }
        )
        binding.voiceSpinner.setSelection(
            previous.coerceIn(0, voiceEntries.size - 1)
        )
    }

    private fun currentVoiceValue(): String =
        voiceEntries.getOrElse(binding.voiceSpinner.selectedItemPosition) { voiceEntries[0] }.value

    private fun currentRate(): Float =
        speeds[binding.speedSpinner.selectedItemPosition.coerceIn(0, speeds.size - 1)]

    /** Ayarladığın sesi test eder: "Merhaba, ben Umay…" */
    private fun testVoice() {
        val t = tts
        if (t == null) {
            Toast.makeText(this, R.string.tts_hazir_degil, Toast.LENGTH_SHORT).show()
            return
        }
        applyVoiceTo(t, currentVoiceValue())
        t.setSpeechRate(currentRate())
        t.speak(
            "Merhaba, ben Umay. Bu sesle dersini okuyacağım.",
            TextToSpeech.QUEUE_FLUSH, null, "test"
        )
    }

    /** Verilen ses modunu doğrudan bir TextToSpeech örneğine uygular. */
    private fun applyVoiceTo(t: TextToSpeech, mode: String) {
        if (mode == "auto") return
        val voices = try {
            t.voices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (voices.isEmpty()) return

        if (mode.startsWith("name:")) {
            val name = mode.removePrefix("name:")
            voices.firstOrNull { it.name == name }?.let { v ->
                try { t.voice = v } catch (e: IllegalArgumentException) {}
            }
            return
        }

        val trVoices = voices.filter { it.locale.language.equals("tr", true) }
        val pool = if (trVoices.isNotEmpty()) trVoices else voices
        val pick = pool.firstOrNull { TtsManager.guessGender(it) == mode }
            ?: pool.getOrNull(if (mode == "female") 0 else 1)
            ?: return
        try { t.voice = pick } catch (e: IllegalArgumentException) {}
    }

    private fun save() {
        AppPrefs.putString(this, AppPrefs.KEY_VOICE, currentVoiceValue())
        AppPrefs.putFloat(this, AppPrefs.KEY_RATE, currentRate())
        AppPrefs.putBool(this, AppPrefs.KEY_TRANSLATE, binding.switchTranslate.isChecked)
        AppPrefs.putString(
            this, AppPrefs.KEY_API_KEY,
            binding.apiKeyInput.text.toString().trim()
        )
        AppPrefs.putString(
            this, AppPrefs.KEY_API_MODEL,
            binding.apiModelInput.text.toString().trim()
        )
        AppPrefs.lastApiKey = null   // önbelleği tazele
        AppPrefs.lastApiModel = null

        Toast.makeText(this, R.string.ay_kaydedildi, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        super.onDestroy()
    }
}
