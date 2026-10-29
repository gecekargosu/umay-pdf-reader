package com.umay.pdfreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.RingtoneManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.umay.pdfreader.databinding.ActivitySettingsBinding
import java.util.Locale

/**
 * Ayarlar: ses (kadın/erkek), hız, çeviri, opsiyonel AI API anahtarı.
 */
class SettingsActivity : AppCompatActivity() {

    private companion object {
        const val REQ_PICK_APK = 4711
    }

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

    // ── v13 (AGENT 22 — A4): Çoklu AI sağlayıcı ──
    private data class AiProvider(val label: String, val value: String)
    private val aiProviders = listOf(
        AiProvider("Google Gemini (ücretsiz — önerilen)", "gemini"),
        AiProvider("OpenRouter (ücretsiz modeller)", "openrouter"),
        AiProvider("OpenAI (gpt-4o-mini)", "openai"),
        AiProvider("Özel sunucu (OpenAI uyumlu)", "custom")
    )
    private var currentProvider: String = "gemini"

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
        EdgeToEdge.apply(this, binding.root)  // SDK 36 edge-to-edge

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
        // v5 (ADIM C): "mekanik sesi devre dışı bırak" — varsayılan AÇIK
        binding.switchNoBuiltin.isChecked =
            AppPrefs.getBool(this, AppPrefs.KEY_TTS_NO_BUILTIN, true)
        // v13 (A4): sağlayıcı bazlı anahtar/model yükleme + rehber
        initAiProviders()

        binding.btnTestVoice.setOnClickListener { testVoice() }
        binding.btnTestApi.setOnClickListener { testApi() }
        binding.btnSave.setOnClickListener { save() }

        // v12 (YENİ ÖZELLİK) — Okuma istatistikleri paneli
        binding.btnStats.setOnClickListener { showStats() }
        // v36.1 — boşta ön hazırlık: aç/kapat (şarj + cihaz boşta iken çalışır)
        binding.btnPrep.setOnClickListener {
            val now = PrepStore.enabled(this)
            PrepStore.setEnabled(this, !now)
            paintPrepRow()
            Toast.makeText(
                this,
                if (!now) getString(R.string.ayar_prep_acildi) else getString(R.string.ayar_prep_kapandi),
                Toast.LENGTH_SHORT
            ).show()
        }
        paintPrepRow()

        // v36.3 — lamba sesli uyarısı aç/kapat
        binding.btnLampSound.setOnClickListener {
            val on = AppPrefs.getBool(this, AppPrefs.KEY_LAMP_SOUND, true)
            AppPrefs.putBool(this, AppPrefs.KEY_LAMP_SOUND, !on)
            paintLampSoundRow()
            Toast.makeText(
                this,
                getString(if (!on) R.string.ayar_lamp_ses_acik else R.string.ayar_lamp_ses_kapali),
                Toast.LENGTH_SHORT
            ).show()
            // v36.4 — açarken sarı uyarı sesini bir kez dinlet (duyduğunu bilsin)
            if (!on) LampSound.play(this, critical = false)
        }
        paintLampSoundRow()

        // v17 (GÖREV 2) — Hata günlüğü paylaşımı: umay_error.log'u
        // FileProvider ile gönder (WhatsApp/E-posta/Drive) veya olmazsa
        // panoya kopyala — kullanıcı ajanı anında bilgilendirebilsin.
        binding.btnShareLog.setOnClickListener { shareErrorLog() }

        // v11 (GÖREV 3) — Canlı Tanılama: TTS + API + DB + İzinler
        binding.btnDiag.setOnClickListener {
            binding.btnDiag.isEnabled = false
            binding.btnDiag.setText(R.string.diag_running)
            DiagnosticTestManager.runAndShow(this) {
                runOnUiThread {
                    binding.btnDiag.isEnabled = true
                    binding.btnDiag.setText(R.string.diag_run)
                }
            }
        }

        // ── v13 (AGENT 22) — Profil & Görünüm ──
        binding.btnProfile.setOnClickListener {
            startActivity(android.content.Intent(this, ProfileActivity::class.java))
        }
        // v5.7 — Kasa ana sayfanın altına taşındı; burada yalnız kısa yol kaldı
        // (Ayarlar'dan da erişilebilsin diye tema satırının altında yaşar).
        binding.btnTheme.setOnClickListener { showThemeDialog() }
        // v13 (AGENT 22 — A3): dil seçimi (TR/EN) — anında uygulanır + kalıcı
        binding.btnLang.setOnClickListener { LangHelper.askLanguage(this) }
        // v14 (V5.0 §1) — Özel tema tasarlayıcı (canlı önizleme)
        binding.btnThemeStudio.setOnClickListener {
            startActivity(android.content.Intent(this, ThemeStudioActivity::class.java))
        }
        // v14 (V5.0 §6) — Transkript & AGNO hesaplayıcı
        binding.btnTranscript.setOnClickListener {
            startActivity(android.content.Intent(this, TranscriptActivity::class.java))
        }
        // v5.5 (AŞAMA 1) — Bildirim & alarm izin merkezi (HyperOS/MIUI yönlendirmesi)
        binding.btnPermissions.setOnClickListener {
            startActivity(android.content.Intent(this, PermissionCenterActivity::class.java))
        }
        // v14 (V5.0 §5) — Özel alarm sesi seçimi (sistem zil listesi)
        binding.btnAlarmSound.setOnClickListener { pickAlarmSound() }
        // v14 (V5.0 §7) — Yedekleme: yedek al / geri yükle
        binding.btnBackup.setOnClickListener { showBackupDialog() }
        binding.switchNightPdf.isChecked =
            AppPrefs.getBool(this, AppPrefs.KEY_NIGHT_READER, true)
        binding.switchNightPdf.setOnCheckedChangeListener { _, checked ->
            AppPrefs.putBool(this, AppPrefs.KEY_NIGHT_READER, checked)
        }

        // v5.7 — Uygulama güncellemesi (ana sayfadan taşındı) + ekran açık kalsın
        binding.btnInstallApk.setOnClickListener { Updater.installLatest(this) }
        // v5.8 — Download dışındaki (WhatsApp/Telegram) APK'yı elle seçip kur
        binding.btnPickApk.setOnClickListener { Updater.pickApk(this, REQ_PICK_APK) }
        // v5.7 — Kasa ayarlardan da erişilebilir (ana sayfanın altındaki kartla aynı).
        binding.btnVault.setOnClickListener {
            try {
                startActivity(Intent(this, SecureVaultActivity::class.java))
            } catch (t: Throwable) {
                BookStore.logError(this, "settingsVault", t)
            }
        }
        binding.settingsVersion.text = getString(
            R.string.ay_surum,
            try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
        )
        binding.switchKeepScreenOn.isChecked =
            AppPrefs.getBool(this, AppPrefs.KEY_KEEP_SCREEN_ON, true)
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            AppPrefs.putBool(this, AppPrefs.KEY_KEEP_SCREEN_ON, checked)
            if (checked) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        binding.streakText.text = try { StreakStore.streakText(this) }
            catch (_: Throwable) { "" }

        initEqualizer()
        try {
            bindService(
                android.content.Intent(this, ReadService::class.java),
                connection, android.content.Context.BIND_AUTO_CREATE
            )
        } catch (_: Exception) {}
    }

    // ── v13 (AGENT 22 — A4): Çoklu AI sağlayıcı + ÜCRETSİZ ANAHTAR REHBERİ ──

    private fun initAiProviders() {
        currentProvider = AppPrefs.provider(this)
        binding.providerSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, aiProviders.map { it.label }
        )
        binding.providerSpinner.setSelection(
            aiProviders.indexOfFirst { it.value == currentProvider }.let { if (it < 0) 0 else it }
        )
        loadProviderFields(currentProvider)
        binding.aiBaseInput.setText(AppPrefs.baseUrlFor(this))

        binding.providerSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: android.view.View?,
                    position: Int, id: Long
                ) {
                    val newProvider = aiProviders[position].value
                    if (newProvider == currentProvider) return
                    // Geçilen sağlayıcının girilen değerlerini KAYDET (kaybolmasın)
                    AppPrefs.putApiKeyFor(
                        this@SettingsActivity, currentProvider,
                        binding.apiKeyInput.text.toString().trim()
                    )
                    AppPrefs.putModelFor(
                        this@SettingsActivity, currentProvider,
                        binding.apiModelInput.text.toString().trim()
                    )
                    currentProvider = newProvider
                    loadProviderFields(newProvider)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

        binding.btnAiGuide.setOnClickListener { showAiGuide() }
    }

    /** Seçili sağlayıcının anahtar/modelini alanlara doldurur. */
    private fun loadProviderFields(provider: String) {
        binding.apiKeyInput.setText(AppPrefs.apiKeyFor(this, provider))
        binding.apiModelInput.setText(AppPrefs.modelFor(this, provider))
        // Sunucu adresi yalnız "Özel sunucu"da anlamlı
        binding.aiBaseInput.visibility =
            if (provider == "custom") android.view.View.VISIBLE else android.view.View.GONE
        // Gemini + boş anahtar → gömülü anahtar devrede ipucu
        binding.apiKeyInput.hint =
            if (provider == "gemini" && binding.apiKeyInput.text.isNullOrBlank()) {
                getString(R.string.ay_api_key_builtin_hint)
            } else {
                getString(R.string.ay_api_key)
            }
    }

    /**
     * v13 (A4): ADIM ADIM ÜCRETSİZ anahtar rehberi — sahte anahtar GÖMÜLMEZ;
     * kullanıcı kendi ücretsiz anahtarını alacağı adımlar + tıklanabilir link.
     */
    private fun showAiGuide() {
        val (steps, link, linkLabel) = when (currentProvider) {
            "openrouter" -> Triple(
                getString(R.string.guide_openrouter),
                "https://openrouter.ai/keys",
                getString(R.string.guide_open_link, "openrouter.ai/keys")
            )
            "openai" -> Triple(
                getString(R.string.guide_openai),
                "https://platform.openai.com/api-keys",
                getString(R.string.guide_open_link, "platform.openai.com/api-keys")
            )
            "custom" -> Triple(
                getString(R.string.guide_custom),
                "http://chat.openai.com/",
                ""
            )
            else -> Triple(
                getString(R.string.guide_gemini),
                "https://aistudio.google.com/app/apikey",
                getString(R.string.guide_open_link, "aistudio.google.com/app/apikey")
            )
        }
        val b = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.ay_api_guide)
            .setMessage(steps)
            .setPositiveButton(R.string.kapat, null)
        if (linkLabel.isNotBlank()) {
            b.setNeutralButton(linkLabel) { _, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)
                        )
                    )
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.guide_link_error, Toast.LENGTH_LONG).show()
                }
            }
        }
        b.show()
    }

    /**
     * v13 (T9a) + v16 (GÖREV 2 — TEMA ONARIMI): Tema seçimi.
     *
     * ESKİ HATA: Özel tema (ThemeStudio) bir kez seçilmişse her ekranın arka
     * planı zorla boyanıyordu → Gece/Gündüz/Sistem seçimi HİÇ görünmüyordu.
     * ÇÖZÜM: Mod seçilince özel tema KAPATILIR (renkler hafızada kalır),
     * mod uygulanır ve ekran `recreate()` ile ANINDA yeniden çizilir.
     */
    private fun showThemeDialog() {
        val modes = arrayOf(
            getString(R.string.tema_mod_sistem),
            getString(R.string.tema_mod_gece),
            getString(R.string.tema_mod_gunduz)
        )
        val cur = AppPrefs.getString(this, AppPrefs.KEY_THEME_MODE, "night")
        var sel = when (cur) { "system" -> 0; "day" -> 2; else -> 1 }
        val customActive = ThemeStudio.isCustomized(this)
        android.app.AlertDialog.Builder(this)
            .setTitle(
                if (customActive) getString(R.string.tema_mod_ozel_uyari)
                else getString(R.string.theme_mode)
            )
            .setSingleChoiceItems(modes, sel) { _, which -> sel = which }
            .setPositiveButton(R.string.uygula) { _, _ ->
                val mode = when (sel) { 0 -> "system"; 2 -> "day"; else -> "night" }
                AppPrefs.putString(this, AppPrefs.KEY_THEME_MODE, mode)
                // Özel tema zorla-boyaması kapatılır → seçim gerçekten görünür
                if (ThemeStudio.isCustomized(this)) {
                    ThemeStudio.clearCustomization(this)
                    Toast.makeText(
                        this, R.string.tema_ozel_kapatildi, Toast.LENGTH_SHORT
                    ).show()
                }
                AppPrefs.applyThemeMode(this)
                // Anında görsel geri bildirim (appcompat çoğu zaman kendi
                // recreate eder; garanti için elle de yeniliyoruz)
                recreate()
            }
            .setNegativeButton(R.string.kapat, null)
            .show()
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

        // v15 (V5.1 ADIM B.5 / #1) — Raf arka planı teması seçimi
        val shelfThemes = listOf(
            "wood" to getString(R.string.shelf_wood),
            "metal" to getString(R.string.shelf_metal),
            "dark" to getString(R.string.shelf_dark)
        )
        binding.shelfThemeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            shelfThemes.map { it.second }
        )
        val currentTheme = AppPrefs.getString(this, AppPrefs.KEY_SHELF_THEME, "wood")
        binding.shelfThemeSpinner.setSelection(
            shelfThemes.indexOfFirst { it.first == currentTheme }.coerceAtLeast(0)
        )
        binding.shelfThemeSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?,
                    position: Int, id: Long
                ) {
                    AppPrefs.putString(
                        this@SettingsActivity, AppPrefs.KEY_SHELF_THEME,
                        shelfThemes[position].first
                    )
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun currentVoiceValue(): String =
        voiceEntries.getOrElse(binding.voiceSpinner.selectedItemPosition) { voiceEntries[0] }.value

    private fun currentRate(): Float =
        speeds[binding.speedSpinner.selectedItemPosition.coerceIn(0, speeds.size - 1)]

    /** Ayarladığın sesi test eder: "Merhaba, ben Umay…" */
    // ── v12 (YENİ ÖZELLİK) — Okuma istatistikleri paneli ──────

    /** Günlük/haftalık okuma süresi + bitirilen kitap rozetleri. */
    // ── v17 (GÖREV 2) — Hata günlüğü paylaşım akışı ────────────

    /** umay_error.log var mı? Yoksa boş uyarı göster. */
    private fun shareErrorLog() {
        val file = CrashLog.publicLogFile(this)
        if (file == null || !file.exists() || file.length() == 0L) {
            Toast.makeText(this, R.string.errlog_empty, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Umay hata günlüğü (umay_error.log)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.errlog_share)))
        } catch (t: Throwable) {
            // Paylaşım başarısız → hata da loglansın, sonra panoya kopyala
            CrashLog.record(this, "shareErrorLog", t)
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "umay_error.log",
                        file.readText().take(20000)
                    )
                )
                Toast.makeText(this, R.string.errlog_copied, Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
            }
        }
    }

    /** v36.3 — lamba sesi satırı. */
    private fun paintLampSoundRow() {
        try {
            val on = AppPrefs.getBool(this, AppPrefs.KEY_LAMP_SOUND, true)
            binding.btnLampSound.text = getString(
                if (on) R.string.ayar_lamp_ses_acik else R.string.ayar_lamp_ses_kapali
            )
        } catch (_: Throwable) {
        }
    }

    /** Ön hazırlık satırının yazısı: açık/kapalı + son çalışma zamanı. */
    private fun paintPrepRow() {
        try {
            val on = PrepStore.enabled(this)
            val last = PrepStore.lastRunAt(this)
            val whenTxt = if (last <= 0L) "" else {
                val f = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("tr", "TR"))
                    .format(java.util.Date(last))
                getString(R.string.ayar_prep_son, f)
            }
            val base = getString(if (on) R.string.ayar_prep_acik else R.string.ayar_prep_kapali)
            // v36.1: string'lerde baştaki boşluk aapt tarafından kırpılıyor → burada ekle
            binding.btnPrep.text = if (whenTxt.isBlank()) base else "$base $whenTxt"
        } catch (_: Throwable) {
        }
    }

    private fun showStats() {
        try {
            val today = AnalyticsStore.lastDays(this, 1).firstOrNull()?.second ?: 0
            val week = AnalyticsStore.lastDays(this, 7).sumOf { it.second }
            val total = AnalyticsStore.totalMinutes(this)
            val finished = AnalyticsStore.finishedCount(this)

            // Son 7 günün mini bar grafiği (metin tabanlı)
            val days = AnalyticsStore.lastDays(this, 7).asReversed()
            val maxM = days.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
            val bars = StringBuilder()
            days.forEach { (day, m) ->
                val label = day.takeLast(5)   // "10-11"
                val barLen = if (m == 0) 0 else (m * 12 / maxM).coerceAtLeast(1)
                bars.append(label).append("  ")
                bars.append("█".repeat(barLen))
                if (m > 0) bars.append(" $m dk")
                bars.append('\n')
            }

            val msg = (
                getString(R.string.stats_today, today) + "\n" +
                    getString(R.string.stats_week, week) + "\n" +
                    getString(R.string.stats_total, total) + "\n" +
                    getString(R.string.stats_finished, finished) + "\n\n" +
                    bars.toString().trimEnd()
                )
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.stats_title)
                .setMessage(msg)
                .setPositiveButton(R.string.kapat, null)
                .show()
        } catch (t: Throwable) {
            BookStore.logError(this, "showStats", t)
        }
    }

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

        // v13 (A4): Test de seçili sağlayıcı üzerinden gider (URL/anahtar eşleşmesi)
        val providerAtTest = currentProvider
        val baseAtTest = binding.aiBaseInput.text.toString().trim()
        Thread {
            val msg = try {
                val answer = ApiClient.askForProvider(
                    providerAtTest, "Merhaba", "", key, model, baseAtTest
                )
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

    // ── v14 (V5.0 §5) — ÖZEL ALARM SESİ SEÇİMİ ─────────────────
    //
    // Sistem zil listesinden ALARM tipinde bir ses seçilir (kullanıcı
    // Hababam Sınıfı melodisi vb. indirdiği bir zili de seçebilir —
    // indirilen tüm ziller listede görünür). URI + etiket kaydedilir;
    // Reminder.playAlarmMelody bundan okur.
    private val pickAlarmLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
            val uri: Uri? = r.data?.getParcelableExtra(
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI
            )
            if (uri != null) {
                val ringtone = RingtoneManager.getRingtone(this, uri)
                AppPrefs.putString(this, AppPrefs.KEY_ALARM_URI, uri.toString())
                AppPrefs.putString(
                    this, AppPrefs.KEY_ALARM_LABEL,
                    ringtone?.getTitle(this) ?: getString(R.string.alarm_sound_default)
                )
            } else {
                // "Varsayılan" seçildi / iptal → sistem alarm sesine dön
                AppPrefs.putString(this, AppPrefs.KEY_ALARM_URI, "")
                AppPrefs.putString(this, AppPrefs.KEY_ALARM_LABEL, "")
            }
            Toast.makeText(this, R.string.ay_kaydedildi, Toast.LENGTH_SHORT).show()
        }

    private fun pickAlarmSound() {
        try {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_TYPE,
                    RingtoneManager.TYPE_ALARM
                )
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.alarm_sound_pick))
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    AppPrefs.getString(this@SettingsActivity, AppPrefs.KEY_ALARM_URI)
                )
            }
            pickAlarmLauncher.launch(intent)
        } catch (t: Throwable) {
            BookStore.logError(this, "pickAlarmSound", t)
            Toast.makeText(this, R.string.guide_link_error, Toast.LENGTH_SHORT).show()
        }
    }

    // ── v14 (V5.0 §7) — YEDEKLEME (Yedek al / Geri yükle) ─────
    //
    // Yedek = kitap dizini + profil + raflar + akademi verisi tek zip'te
    // (Documents/UmayPDF/umay_yedek_<tarih>.zip). Geri yükleme: kullanıcı
    // bir yedek zip'i seçer → içeriği yerine konur → kitaplık yeniden yüklenir.
    // Altyapı: CloudBackup.kt (sunucu yok; zip cihazdan cihaza taşınabilir).
    private fun showBackupDialog() {
        // v16 (YENİ GÖREV 8) — 2. satır: SAF tabanlı JSON yedek al/geri yükle
        val items = arrayOf(
            getString(R.string.backup_take),
            getString(R.string.backup_restore),
            getString(R.string.json_take),
            getString(R.string.json_restore)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.backup_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> Thread {
                        val msg = try {
                            val f = CloudBackup.create(this)
                            getString(R.string.backup_done, f?.name ?: "?")
                        } catch (t: Throwable) {
                            getString(R.string.backup_failed, t.message ?: "?")
                        }
                        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                    }.start()
                    1 -> restoreBackupLauncher.launch(arrayOf("application/zip"))
                    2 -> jsonExportLauncher.launch("umay_yedek.json")
                    3 -> jsonImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }
            .setNegativeButton(R.string.kapat, null)
            .show()
    }

    /** v16 (YENİ GÖREV 8) — SAF CreateDocument: JSON yedek DIŞA AKTAR. */
    private val jsonExportLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            Thread {
                val msg = try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        CloudBackup.exportJson(this, out)
                    } ?: throw IllegalStateException("yazılamadı")
                    getString(R.string.json_done, uri.lastPathSegment ?: "umay_yedek.json")
                } catch (t: Throwable) {
                    getString(R.string.json_failed, t.message ?: "?")
                }
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }.start()
        }

    /** v16 (YENİ GÖREV 8) — SAF OpenDocument: JSON yedek İÇE AKTAR. */
    private val jsonImportLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            Thread {
                val ok = try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        CloudBackup.importJson(this, input)
                    } ?: false
                } catch (t: Throwable) {
                    false
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (ok) getString(R.string.json_restored)
                        else getString(R.string.json_failed, "—"),
                        Toast.LENGTH_LONG
                    ).show()
                    if (ok) recreate()
                }
            }.start()
        }

    private val restoreBackupLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            Thread {
                val ok = CloudBackup.restore(this, uri)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (ok) getString(R.string.backup_restored)
                        else getString(R.string.backup_failed, "—"),
                        Toast.LENGTH_LONG
                    ).show()
                    if (ok) recreate()
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
        // v5 (ADIM C): Edge Neural mümkünken mekanik sese düşülmesin mi?
        AppPrefs.putBool(this, AppPrefs.KEY_TTS_NO_BUILTIN, binding.switchNoBuiltin.isChecked)
        // v13 (A4): değerler SAĞLAYICI BAĞIMLI kaydedilir + etkin sağlayıcı saklanır
        AppPrefs.putApiKeyFor(
            this, currentProvider, binding.apiKeyInput.text.toString().trim()
        )
        AppPrefs.putModelFor(
            this, currentProvider, binding.apiModelInput.text.toString().trim()
        )
        AppPrefs.putString(this, AppPrefs.KEY_API_PROVIDER, currentProvider)
        AppPrefs.putString(
            this, AppPrefs.KEY_API_BASE, binding.aiBaseInput.text.toString().trim()
        )
        AppPrefs.lastApiKey = null   // önbellekleri tazele
        AppPrefs.lastApiModel = null
        AppPrefs.lastApiProvider = null
        AppPrefs.lastApiBase = null

        Toast.makeText(this, R.string.ay_kaydedildi, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** v5.8 — "APK dosyası seç ve kur" sonucu (SAF content:// → önbellek → kurucu). */
    @Deprecated("startActivityForResult bilinçli olarak kullanılıyor (ek bağımlılık yok)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_APK && resultCode == RESULT_OK) {
            Updater.installFromUri(this, data?.data)
        }
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
