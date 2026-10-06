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

    // 0.5x'ten başlayan aralık: yavaşlatma da mümkün (ders anlatımı için)
    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    private data class VoiceEntry(val label: String, val value: String)

    private val voiceEntries = mutableListOf(
        VoiceEntry("Otomatik (motor varsayılanı)", "auto"),
        VoiceEntry("Kadın (varsayılan)", "female"),
        VoiceEntry("Erkek (varsayılan)", "male")
    )

    private var tts: TextToSpeech? = null

    // Ekolayzırı açık okuma oturumuna anında uygulamak için servis bağı
    private var service: ReadService? = null
    private var bound = false
    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            service = (binder as ReadService.LocalBinder).service
            bound = true
            applyEqNow()
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            service = null
            bound = false
        }
    }

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
            speeds.indexOfFirst { it == curRate }.let { if (it < 0) 2 else it }
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
        // Doğal ses varsayılan açık (yoksa kullanıcı şikâyeti: "robot gibi okuyor")
        binding.switchEdgeVoice.isChecked =
            AppPrefs.getString(this, AppPrefs.KEY_TTS_MODE, "edge") == "edge"
        binding.apiKeyInput.setText(AppPrefs.getString(this, AppPrefs.KEY_API_KEY))
        binding.apiModelInput.setText(AppPrefs.getString(this, AppPrefs.KEY_API_MODEL))

        binding.btnTestVoice.setOnClickListener { testVoice() }
        binding.btnTestApi.setOnClickListener { testApi() }
        binding.btnSave.setOnClickListener { save() }

        initEqualizer()
        try {
            bindService(
                android.content.Intent(this, ReadService::class.java),
                connection, android.content.Context.BIND_AUTO_CREATE
            )
        } catch (_: Exception) {}
    }

    // ── Ekolayzır (Ayarlar paneli) ────────────────────────────

    private fun gainToProgress(g: Float): Int = (20 + g / 5f).toInt().coerceIn(0, 40)
    private fun progressToGain(p: Int): Float = ((p.coerceIn(0, 40) - 20) * 5f)

    private fun initEqualizer() {
        binding.settingsEqSwitch.isChecked =
            AppPrefs.getBool(this, AppPrefs.KEY_EQ_ON, false)
        binding.settingsEqLow.progress =
            gainToProgress(AppPrefs.getFloat(this, AppPrefs.KEY_EQ_LOW, 0f))
        binding.settingsEqMid.progress =
            gainToProgress(AppPrefs.getFloat(this, AppPrefs.KEY_EQ_MID, 0f))
        binding.settingsEqHigh.progress =
            gainToProgress(AppPrefs.getFloat(this, AppPrefs.KEY_EQ_HIGH, 0f))
        refreshEqLabels()

        binding.settingsEqSwitch.setOnCheckedChangeListener { _, _ -> applyEqNow() }
        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    refreshEqLabels()
                    applyEqNow()
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        }
        binding.settingsEqLow.setOnSeekBarChangeListener(listener)
        binding.settingsEqMid.setOnSeekBarChangeListener(listener)
        binding.settingsEqHigh.setOnSeekBarChangeListener(listener)
    }

    private fun refreshEqLabels() {
        binding.settingsEqLowLabel.text =
            "${getString(R.string.ay_eq_bas)} ${progressToGain(binding.settingsEqLow.progress).toInt()}"
        binding.settingsEqMidLabel.text =
            "${getString(R.string.ay_eq_orta)} ${progressToGain(binding.settingsEqMid.progress).toInt()}"
        binding.settingsEqHighLabel.text =
            "${getString(R.string.ay_eq_tiz)} ${progressToGain(binding.settingsEqHigh.progress).toInt()}"
    }

    /** Değerleri kaydeder ve açık bir okuma varsa hemen uygular. */
    private fun applyEqNow() {
        val on = binding.settingsEqSwitch.isChecked
        val low = progressToGain(binding.settingsEqLow.progress)
        val mid = progressToGain(binding.settingsEqMid.progress)
        val high = progressToGain(binding.settingsEqHigh.progress)
        AppPrefs.putBool(this, AppPrefs.KEY_EQ_ON, on)
        AppPrefs.putFloat(this, AppPrefs.KEY_EQ_LOW, low)
        AppPrefs.putFloat(this, AppPrefs.KEY_EQ_MID, mid)
        AppPrefs.putFloat(this, AppPrefs.KEY_EQ_HIGH, high)
        service?.setEqualizer(on, low, mid, high)
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

    /**
     * Girilen API anahtarıyla gerçek bir Gemini/OpenAI isteği atar.
     * Kaydetmeden önce "bu anahtar çalışıyor mu?" sorusunu yanıtlar.
     * Ağ işlemi ana thread'de yapılmaz (NetworkOnMainThread crash'i).
     */
    private fun testApi() {
        val key = binding.apiKeyInput.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.ay_api_test_bos, Toast.LENGTH_SHORT).show()
            return
        }
        val model = binding.apiModelInput.text.toString().trim()

        binding.btnTestApi.isEnabled = false
        binding.btnTestApi.setText(R.string.ay_api_test_bekle)

        Thread {
            val msg = try {
                val answer = ApiClient.ask("Merhaba", emptyList(), key, model)
                getString(R.string.ay_api_test_ok, answer.replace('\n', ' ').take(70))
            } catch (e: Exception) {
                "❌ " + (e.message ?: "Bilinmeyen hata")
            }
            runOnUiThread {
                binding.btnTestApi.isEnabled = true
                binding.btnTestApi.setText(R.string.ay_api_test)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun save() {
        AppPrefs.putString(this, AppPrefs.KEY_VOICE, currentVoiceValue())
        AppPrefs.putFloat(this, AppPrefs.KEY_RATE, currentRate())
        AppPrefs.putBool(this, AppPrefs.KEY_TRANSLATE, binding.switchTranslate.isChecked)
        AppPrefs.putString(
            this, AppPrefs.KEY_TTS_MODE,
            if (binding.switchEdgeVoice.isChecked) "edge" else "builtin"
        )
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
            if (bound) unbindService(connection)
        } catch (_: Exception) {}
        bound = false
        service = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        super.onDestroy()
    }
}
