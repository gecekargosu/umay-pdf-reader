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
    const val KEY_TRANSLATE_MODEL_DOWNLOADED = "translate_model_ready"
    const val KEY_LAST_BOOK = "last_book"
    const val KEY_PLAYING = "playing"   // servis öldürülürse otomatik devam için
    const val KEY_TTS_MODE = "tts_mode"  // builtin | edge (doğal neural ses)
    const val KEY_SORT = "sort_mode"      // recent | alpha | added (kütüphane sıralama)
    const val KEY_PITCH = "pitch"         // 0.5 .. 2.0 — ses perdesi
    const val KEY_EQ_ON = "eq_on"         // ekolayzır açık mı (Edge/mp3 modu)
    const val KEY_EQ_LOW = "eq_low"       // -100 .. +100 (alçak/bas)
    const val KEY_EQ_MID = "eq_mid"       // -100 .. +100 (orta)
    const val KEY_EQ_HIGH = "eq_high"     // -100 .. +100 (yüksek/tiz)

    // İlerleme anahtarı önekleri
    private const val PROG_PAGE = "prog_page_"
    private const val PROG_SENT = "prog_sent_"
    private const val LAST_READ = "last_read_"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Ayarlar ────────────────────────────────────────────────

    fun getString(ctx: Context, key: String, def: String = ""): String =
        prefs(ctx).getString(key, def) ?: def

    fun putString(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString(key, value).apply()
    }

    fun getFloat(ctx: Context, key: String, def: Float): Float =
        prefs(ctx).getFloat(key, def)

    fun putFloat(ctx: Context, key: String, value: Float) {
        prefs(ctx).edit().putFloat(key, value).apply()
    }

    fun getBool(ctx: Context, key: String, def: Boolean = false): Boolean =
        prefs(ctx).getBoolean(key, def)

    /** ApiClient'ın hızlı erişimi için (sayfa açıldığında doldurulur). */
    @Volatile var lastApiKey: String? = null
    @Volatile var lastApiModel: String? = null

    fun warmApiCache(ctx: Context) {
        if (lastApiKey == null) lastApiKey = getString(ctx, KEY_API_KEY)
        if (lastApiModel == null) lastApiModel = getString(ctx, KEY_API_MODEL)
    }

    fun putBool(ctx: Context, key: String, value: Boolean) {
        prefs(ctx).edit().putBoolean(key, value).apply()
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
}
