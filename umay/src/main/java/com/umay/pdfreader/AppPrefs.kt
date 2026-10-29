package com.umay.pdfreader

import android.content.Context

/**
 * Uygulama ayarları ve kitap başına okuma ilerlemesi.
 * Tek dosyada SharedPreferences üzerinden tutulur.
 */
object AppPrefs {

    private const val PREFS = "umay_prefs"

    // Ayar anahtarları
    const val KEY_VOICE = "voice_mode"          // auto | female | male | voice:<name>
    const val KEY_RATE = "speech_rate"          // 0.75 .. 2.0
    const val KEY_TRANSLATE = "translate_en_tr" // true => İngilizce PDF'i Türkçe oku
    const val KEY_API_KEY = "api_key"
    const val KEY_API_MODEL = "api_model"
    // v13 (AGENT 22 — A4): Çoklu AI sağlayıcı (Gemini/OpenRouter/OpenAI/Özel)
    const val KEY_API_PROVIDER = "api_provider"     // gemini | openrouter | openai | custom
    const val KEY_API_BASE = "api_base"             // OpenAI-uyumlu uç nokta (custom/alternatif)
    const val KEY_API_KEY_OR = "api_key_openrouter"
    const val KEY_API_KEY_OA = "api_key_openai"
    const val KEY_API_KEY_CU = "api_key_custom"
    const val KEY_API_MODEL_OR = "api_model_openrouter"
    const val KEY_API_MODEL_OA = "api_model_openai"
    const val KEY_API_MODEL_CU = "api_model_custom"
    // v11 (GÖREV 1): ders programı tohum verisi bir kez yazıldı mı?
    const val KEY_SCHEDULE_SEED_V11 = "schedule_seed_v11_done"
    // v12 (Okuma Kitapları rafı): kamu malı/indirilen kitap kimlikleri (CSV)
    const val KEY_PD_BOOKS = "public_domain_books"
    const val KEY_TRANSLATE_MODEL_DOWNLOADED = "translate_model_ready"
    const val KEY_LAST_BOOK = "last_book"
    const val KEY_PLAYING = "playing"   // servis öldürülürse otomatik devam için
    const val KEY_TTS_MODE = "tts_mode"  // builtin | edge (doğal neural ses)
    /** v36.3 — durum lambaları sarı/kırmızıya dönerken sesli uyarı çalsın mı? */
    const val KEY_LAMP_SOUND = "lamp_sound"
    // v5 (ADIM C): Edge Neural mümkünken mekanik yerleşik ses kullanılmasın mı?
    // (İnternet YOKSA çevrimdışı okuma için yerleşik yine de kullanılır.)
    const val KEY_TTS_NO_BUILTIN = "tts_no_builtin"
    const val KEY_SORT = "sort_mode"      // recent | alpha | added (kütüphane sıralama)
    const val KEY_PITCH = "pitch"         // 0.5 .. 2.0 — ses perdesi
    const val KEY_EQ_ON = "eq_on"         // ekolayzır açık mı (Edge/mp3 modu)
    const val KEY_EQ_LOW = "eq_low"       // -100 .. +100 (alçak/bas)
    const val KEY_EQ_MID = "eq_mid"       // -100 .. +100 (orta)
    const val KEY_EQ_HIGH = "eq_high"     // -100 .. +100 (yüksek/tiz)
    // v13 (AGENT 22) — T9a/T9b/T9d
    const val KEY_THEME_MODE = "theme_mode"        // night | day | system
    const val KEY_NIGHT_READER = "night_reader_pdf" // gece modunda PDF koyulaştırma
    const val KEY_ONBOARDING_DONE = "onboarding_done"
    const val KEY_PROFILE = "profile_json"          // ad/üniversite/bölüm/sınıf/avatar
    // v13 (AGENT 22 — A3): dil + sosyal giriş (simüle; sunucu yok)
    const val KEY_LANG = "app_lang"                 // tr (varsayılan) | en
    const val KEY_LANG_SET = "app_lang_set"         // onboarding'de dil seçildi mi?
    const val KEY_SOCIAL_PROVIDER = "social_provider"     // google|apple|facebook|instagram|""
    const val KEY_SOCIAL_NAME = "social_name"
    /** v16 (YENİ GÖREV 8) — yerel e-posta hesapları: {email: sha256} JSON. */
    const val KEY_AUTH_ACCOUNTS = "auth_accounts"

    // v14 (V5.0 §1) — Özel tema tasarlayıcı (hex renkler; boş = varsayılan)
    /** v16 (GÖREV 2 / tema onarımı) — ÖZEL TEMA ETKİN Mİ?
     *  false → Gece/Gündüz/Sistem modu geçerlidir (values/values-night).
     *  true  → ThemeStudio renkleri her ekranda uygulanır.
     *  Eskiden bu bayrak YOKTU: bir kez özel tema seçilince
     *  Gece/Gündüz/Sistem seçimi görsel olarak HİÇ değişmiyordu. */
    const val KEY_THEME_CUSTOM = "theme_custom_active"

    /** v16 (GÖREV 5) — Ders programı görünümü: true = Excel tarzı TABLO
     *  (günler × saatler matrisi), false = klasik dikey liste. */
    const val KEY_SCHEDULE_GRID = "schedule_grid_view"

    /** v16 (GÖREV 6) — Rumeli Ünv. doğru programı bir kez yazıldı mı? */
    const val KEY_SCHEDULE_SEED_V16 = "schedule_seed_v16"

    // ── v16 (GÖREV 3) — SAYFA ARALIĞI MODU ("15-40 arasını okut") ──
    const val TTS_RANGE_FROM = "tts_from_"    // + bookId (1 tabanlı)
    const val TTS_RANGE_TO = "tts_to_"        // + bookId (1 tabanlı, 0 = sınırsız)

    /** Aralığın başı (1 tabanlı). */
    fun ttsRangeFrom(ctx: Context, bookId: String): Int = try {
        prefs(ctx).getInt(TTS_RANGE_FROM + bookId, 1).coerceAtLeast(1)
    } catch (_: Throwable) {
        1
    }

    /** Aralığın sonu (1 tabanlı; 0 = kitabın sonuna kadar). */
    fun ttsRangeTo(ctx: Context, bookId: String): Int = try {
        prefs(ctx).getInt(TTS_RANGE_TO + bookId, 0).coerceAtLeast(0)
    } catch (_: Throwable) {
        0
    }

    /** Aralığı kaydet (sıfırdan küçük/değersiz değerler 0'a çekilir). */
    fun setTtsRange(ctx: Context, bookId: String, from: Int, to: Int) {
        try {
            val f = from.coerceAtLeast(1)
            val t = if (to <= 0) 0 else to.coerceAtLeast(f)
            prefs(ctx).edit()
                .putInt(TTS_RANGE_FROM + bookId, f)
                .putInt(TTS_RANGE_TO + bookId, t)
                .apply()
        } catch (_: Throwable) {
        }
    }

    /** Aralık tanımlı mı? (UI rozeti için) */
    fun ttsRangeActive(ctx: Context, bookId: String): Boolean =
        ttsRangeTo(ctx, bookId) > 0
    const val KEY_THEME_BG = "theme_bg"
    const val KEY_THEME_PANEL = "theme_panel"
    const val KEY_THEME_ACCENT = "theme_accent"
    const val KEY_THEME_SHELF = "theme_shelf_frame"

    // v15 (V5.1 ADIM B.5 / #1) — 3D raf arka planı teması: wood | metal | dark
    const val KEY_SHELF_THEME = "shelf_theme"

    /** v5.4 — Okurken/gezinirken ekran kapanmasın (varsayılan açık). */
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

    /** v5.4 — Edge neural ses 403 aldığında 24 saat boyunca atlanır. */
    const val KEY_EDGE_BLOCKED_UNTIL = "edge_blocked_until"

    // v14 (V5.0 §5) — Özel alarm sesi (seçilen zilin content URI'si; boş = sistem)
    const val KEY_ALARM_URI = "alarm_sound_uri"
    // v5.2 (ADIM C): reklam anahtarı (guarded — varsayılan true) + sınıf modu
    const val KEY_ADS_ENABLED = "ads_enabled"
    const val KEY_CLASSROOM_ACTIVE = "classroom_active"
    const val KEY_CLASSROOM_SINCE = "classroom_since_ms"
    const val KEY_LAST_SYNC_SUMMARY = "library_sync_summary"
    const val KEY_ALARM_LABEL = "alarm_sound_label"

    // v30 (checkpoint / ag yoksa-cestream biterse) — checkpoint klasoru + sure
    const val KEY_CHECKPOINT_DIR = "checkpoint_dir"
    const val KEY_CHECKPOINT_MAX_AGE_MINS = "checkpoint_max_age_mins"
    const val KEY_CHECKPOINT_VERIFICATION = "checkpoint_verification"

    // v14 (V5.0 §6) — Transkript verisi: {"1":[{name,akts,grade},…], …}
    const val KEY_TRANSCRIPT_JSON = "transcript_json"

    // İlerleme anahtarı önekleri
    private const val PROG_PAGE = "prog_page_"
    private const val PROG_SENT = "prog_sent_"
    private const val LAST_READ = "last_read_"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Ayarlar ────────────────────────────────────────────────

    fun getString(ctx: Context, key: String, def: String = ""): String =
        // v8 (KATEGORİ 1 — güncelleme koruması): eski sürümde farklı tipte
        // kayıt kalmışsa ClassCastException oluşur → uygulama açılışında
        // çökmesin diye hepsi tip güvenli okunur ve bozuk değer temizlenir.
        try {
            prefs(ctx).getString(key, def) ?: def
        } catch (_: ClassCastException) {
            removeQuietly(ctx, key); def
        }

    fun putString(ctx: Context, key: String, value: String) {
        try {
            prefs(ctx).edit().putString(key, value).apply()
        } catch (_: Throwable) {
        }
    }

    fun getInt(ctx: Context, key: String, def: Int): Int =
        try { prefs(ctx).getInt(key, def) } catch (_: Throwable) { def }

    fun putInt(ctx: Context, key: String, value: Int) {
        try {
            prefs(ctx).edit().putInt(key, value).apply()
        } catch (_: Throwable) {
        }
    }

    fun getLong(ctx: Context, key: String, def: Long): Long =
        try { prefs(ctx).getLong(key, def) } catch (_: Throwable) { def }

    fun putLong(ctx: Context, key: String, value: Long) {
        try {
            prefs(ctx).edit().putLong(key, value).apply()
        } catch (_: Throwable) {
        }
    }

    fun getFloat(ctx: Context, key: String, def: Float): Float =
        try {
            prefs(ctx).getFloat(key, def)
        } catch (_: ClassCastException) {
            removeQuietly(ctx, key); def
        }

    fun putFloat(ctx: Context, key: String, value: Float) {
        try {
            prefs(ctx).edit().putFloat(key, value).apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * v5.12 — Kitap başına "Arapça sayfaları OCR ile oku" modu.
     * Açıksa okuma motoru sayfa metni yerine OCR metnini kullanır
     * (meal/tefsir PDF'lerinde Arapça satırlar metin katmanında yok).
     */
    fun ocrRead(ctx: Context, bookId: String): Boolean =
        getBool(ctx, "ocr_read_$bookId", false)

    fun setOcrRead(ctx: Context, bookId: String, on: Boolean) {
        putBool(ctx, "ocr_read_$bookId", on)
    }

    fun getBool(ctx: Context, key: String, def: Boolean = false): Boolean =
        try {
            prefs(ctx).getBoolean(key, def)
        } catch (_: ClassCastException) {
            removeQuietly(ctx, key); def
        }

    /** Bozuk (yanlış tipte) anahtarı sil — sonraki açılışta varsayılır. */
    private fun removeQuietly(ctx: Context, key: String) {
        try {
            prefs(ctx).edit().remove(key).apply()
        } catch (_: Throwable) {
        }
    }

    /** ApiClient'ın hızlı erişimi için (sayfa açıldığında doldurulur). */
    @Volatile var lastApiKey: String? = null
    @Volatile var lastApiModel: String? = null
    // v13 (A4): etkin sağlayıcı + özel uç nokta (ApiClient yönlendirmesi)
    @Volatile var lastApiProvider: String? = null
    @Volatile var lastApiBase: String? = null

    // ── v13 (AGENT 22 — A4) — Çoklu sağlayıcı anahtar/model erişimi ──

    /** Etkin sağlayıcı ("gemini" varsayılan — gömülü anahtar sadece burada). */
    fun provider(ctx: Context): String =
        getString(ctx, KEY_API_PROVIDER).ifBlank { "gemini" }

    /** Sağlayıcıya göre kayıtlı anahtar (boş olabilir — kullanıcı girer). */
    fun apiKeyFor(ctx: Context, provider: String): String = when (provider) {
        "openrouter" -> getString(ctx, KEY_API_KEY_OR)
        "openai" -> getString(ctx, KEY_API_KEY_OA)
        "custom" -> getString(ctx, KEY_API_KEY_CU)
        else -> getString(ctx, KEY_API_KEY)
    }

    /** Sağlayıcıya göre kayıtlı model (boşsa önerilen varsayılan). */
    fun modelFor(ctx: Context, provider: String): String = when (provider) {
        "openrouter" -> getString(ctx, KEY_API_MODEL_OR).ifBlank { "openai/gpt-4o-mini" }
        "openai" -> getString(ctx, KEY_API_MODEL_OA).ifBlank { "gpt-4o-mini" }
        "custom" -> getString(ctx, KEY_API_MODEL_CU)
        else -> getString(ctx, KEY_API_MODEL)
    }

    fun putApiKeyFor(ctx: Context, provider: String, value: String) {
        when (provider) {
            "openrouter" -> putString(ctx, KEY_API_KEY_OR, value)
            "openai" -> putString(ctx, KEY_API_KEY_OA, value)
            "custom" -> putString(ctx, KEY_API_KEY_CU, value)
            else -> putString(ctx, KEY_API_KEY, value)
        }
    }

    fun putModelFor(ctx: Context, provider: String, value: String) {
        when (provider) {
            "openrouter" -> putString(ctx, KEY_API_MODEL_OR, value)
            "openai" -> putString(ctx, KEY_API_MODEL_OA, value)
            "custom" -> putString(ctx, KEY_API_MODEL_CU, value)
            else -> putString(ctx, KEY_API_MODEL, value)
        }
    }

    /** Özel/alternatif uç nokta (OpenAI uyumlu chat/completions adresi). */
    fun baseUrlFor(ctx: Context): String = getString(ctx, KEY_API_BASE)

    /**
     * v11 (GÖREV 3) — VARSAYILAN GEMINI API ANAHTARI:
     * Kullanıcı Ayarlar'dan kendi anahtarını girmezse bu gömülü anahtar
     * kullanılır; böylece AI özellikler (özet/sohbet/ders programı OCR)
     * kurulum anında hazır çalışır. Anahtar girilirse girilen ÖNCELIKLI olur.
     */
    /**
     * v36.6 — anahtar artık KAYNAK KODA YAZILMIYOR (herkese açık depo güvenliği).
     * `build.gradle.kts` ortam değişkeni (`UMAY_GEMINI_KEY`) veya
     * `local.properties` içindeki `umay.geminiKey` değerini BuildConfig'e yazar.
     * Boşsa uygulama çevrimdışı sohbete düşer; kullanıcı kendi anahtarını
     * Ayarlar'dan girebilir.
     */
    val BUILTIN_API_KEY: String = BuildConfig.BUILTIN_API_KEY
    /**
     * Gömülü anahtarla birlikte önerilen model.
     * v5.4 (ONARIM 2): "gemini-3.8-flash" 28 Eki 2026'da sürekli 503 (aşırı yük)
     * dönüyordu → AI ilk denemede ölüyordu. Canlı ölçümde 200/2.5 sn veren
     * "gemini-3.5-flash" varsayılan yapıldı; FALLBACK_MODELS yedeği duruyor.
     */
    const val BUILTIN_API_MODEL = "gemini-3.5-flash"

    fun warmApiCache(ctx: Context) {
        // v13 (A4): önce etkin sağlayıcı belirlenir; gömülü anahtar YALNIZ
        // Gemini sağlayıcısında devreye girer (diğerleri için anahtar girilir).
        if (lastApiProvider == null) lastApiProvider = provider(ctx)
        val p = lastApiProvider ?: "gemini"
        if (lastApiKey == null) {
            val userKey = apiKeyFor(ctx, p)
            lastApiKey = if (userKey.isNullOrBlank()) {
                if (p == "gemini") BUILTIN_API_KEY else ""
            } else userKey
        }
        if (lastApiModel == null) {
            val userModel = modelFor(ctx, p)
            lastApiModel = if (userModel.isNullOrBlank()) {
                if (p == "gemini") BUILTIN_API_MODEL else ""
            } else userModel
        }
        if (lastApiBase == null) lastApiBase = baseUrlFor(ctx)
    }

    /** v13 (T9a): kayıtlı tema modunu uygula (UmayApp.onCreate'de bir kez çağrılır). */
    fun applyThemeMode(ctx: Context) {
        try {
            val mode = getString(ctx, KEY_THEME_MODE, "night")
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "system" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    "day" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                }
            )
        } catch (_: Throwable) {
        }
    }

    fun putBool(ctx: Context, key: String, value: Boolean) {
        try {
            prefs(ctx).edit().putBoolean(key, value).apply()
        } catch (_: Throwable) {
        }
    }

    // ── Okuma ilerlemesi (kitap başına sayfa + cümle) ─────────

    fun saveProgress(ctx: Context, bookId: String, page: Int, sentence: Int) {
        prefs(ctx).edit()
            .putInt(PROG_PAGE + bookId, page)
            .putInt(PROG_SENT + bookId, sentence)
            .apply()
    }

    /** null dönerse kitap hiç okunmamıştır. */
    fun loadProgress(ctx: Context, bookId: String): Pair<Int, Int>? {
        val p = prefs(ctx)
        if (!p.contains(PROG_PAGE + bookId)) return null
        return Pair(
            p.getInt(PROG_PAGE + bookId, 0),
            p.getInt(PROG_SENT + bookId, 0)
        )
    }

    fun clearProgress(ctx: Context, bookId: String) {
        prefs(ctx).edit()
            .remove(PROG_PAGE + bookId)
            .remove(PROG_SENT + bookId)
            .apply()
    }

    // ── Son okuma zamanı (kütüphane sıralaması için) ────────

    fun touchLastRead(ctx: Context, bookId: String) {
        prefs(ctx).edit()
            .putLong(LAST_READ + bookId, System.currentTimeMillis())
            .apply()
    }

    fun lastRead(ctx: Context, bookId: String): Long =
        prefs(ctx).getLong(LAST_READ + bookId, 0L)

    /** Kitapla ilgili TÜM kayıtları temizler (ilerleme + son okuma). */
    fun clearBookMeta(ctx: Context, bookId: String) {
        prefs(ctx).edit()
            .remove(PROG_PAGE + bookId)
            .remove(PROG_SENT + bookId)
            .remove(LAST_READ + bookId)
            .apply()
    }

    // ── v6 (Aşama 4) — AI günlük kota + ödüllü reklam hakkı ───────

    /** Her gün ücretsiz AI isteği (özet/sohbet). */
    const val AI_DAILY_FREE = 10
    /** Ödüllü reklam izlenince verilen ekstra hak. */
    const val AI_REWARDED_BONUS = 5

    private const val KEY_AI_DAY = "ai_quota_day"
    private const val KEY_AI_USED = "ai_quota_used"
    private const val KEY_AI_BONUS = "ai_quota_bonus"
    private const val KEY_SESSIONS = "app_sessions"
    private const val KEY_REVIEW_SHOWN = "review_shown_version"
    // v9 (M8): toplam başarılı AI özeti sayacı — 3'ün katlarında ödüllü
    // reklam gate + In-App Review tetikleyicisi
    private const val KEY_AI_SUMMARIES = "ai_summary_total"

    /** Başarılı AI özet sayısını 1 artırır ve yeni toplamı döner. */
    fun bumpAiSummaryTotal(ctx: Context): Int {
        val n = prefs(ctx).getInt(KEY_AI_SUMMARIES, 0) + 1
        prefs(ctx).edit().putInt(KEY_AI_SUMMARIES, n).apply()
        return n
    }

    fun aiSummaryTotal(ctx: Context): Int = prefs(ctx).getInt(KEY_AI_SUMMARIES, 0)

    private fun todayStamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())

    /** Gün değiştiyse sayacı sıfırlar (tek yerde). */
    private fun syncAiDay(p: android.content.SharedPreferences) {
        val t = todayStamp()
        if (p.getString(KEY_AI_DAY, "") != t) {
            p.edit().putString(KEY_AI_DAY, t).putInt(KEY_AI_USED, 0).apply()
        }
    }

    /** Kota kaldı mı? (bedava günlük hak + ödüllü reklam bonusu) */
    fun aiQuotaLeft(ctx: Context): Int {
        val p = prefs(ctx)
        syncAiDay(p)
        return AI_DAILY_FREE + p.getInt(KEY_AI_BONUS, 0) - p.getInt(KEY_AI_USED, 0)
    }

    /** Bir AI isteği harcar; hak yoksa false döner (asla çökmez). */
    fun aiQuotaConsume(ctx: Context): Boolean {
        val p = prefs(ctx)
        syncAiDay(p)
        val left = AI_DAILY_FREE + p.getInt(KEY_AI_BONUS, 0) - p.getInt(KEY_AI_USED, 0)
        if (left <= 0) return false
        p.edit().putInt(KEY_AI_USED, p.getInt(KEY_AI_USED, 0) + 1).apply()
        return true
    }

    /** Ödüllü reklam ödülü: ekstra AI hakkı. */
    fun aiQuotaAddBonus(ctx: Context, n: Int) {
        val p = prefs(ctx)
        syncAiDay(p)
        p.edit().putInt(KEY_AI_BONUS, p.getInt(KEY_AI_BONUS, 0) + n).apply()
    }

    // ── v6 (Aşama 6) — oturum sayacı + uygulama içi değerlendirme ──

    /** Uygulama açılış sayısını artırır, yeni oturum numarasını döner. */
    fun bumpSession(ctx: Context): Int {
        val n = prefs(ctx).getInt(KEY_SESSIONS, 0) + 1
        prefs(ctx).edit().putInt(KEY_SESSIONS, n).apply()
        return n
    }

    fun sessionCount(ctx: Context): Int = prefs(ctx).getInt(KEY_SESSIONS, 0)

    /** Bu sürüm için değerlendirme istendi mi? */
    fun reviewShownFor(ctx: Context, version: String): Boolean =
        prefs(ctx).getString(KEY_REVIEW_SHOWN, "") == version

    fun markReviewShown(ctx: Context, version: String) {
        prefs(ctx).edit().putString(KEY_REVIEW_SHOWN, version).apply()
    }
}
