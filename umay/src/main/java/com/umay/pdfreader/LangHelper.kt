package com.umay.pdfreader

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * v13 (AGENT 22 — A3) — DİL YÖNETİMİ (Türkçe / English).
 *
 *  • Seçilen dil AppPrefs.KEY_LANG'da saklanır; uygulama açılışında
 *    UmayApp.onCreate'de uygulanır (token kaybı/oturum değişse de kalıcı).
 *  • appcompat 1.6 per-app locale (AppCompatDelegate.setApplicationLocales)
 *    kullanılır → TÜM ekranlar anında seçilen dilde; API 26+ güvenli.
 *  • Dil seçim diyaloğu hem Onboarding (ilk açılış) hem Ayarlar'dan çağrılır.
 */
object LangHelper {

    /** Kayıtlı dil kodu: "tr" (varsayılan) | "en". */
    fun currentLang(ctx: Context): String =
        AppPrefs.getString(ctx, AppPrefs.KEY_LANG, "tr").ifBlank { "tr" }

    /**
     * v16 (GÖREV 3 — LOKALİZASYON) — Kısayol: aktif dil İngilizce mi?
     * Kotlin içindeki dinamik metinler (gün adı, geri sayım, tarih) bu bayrağı
     * kullanarak TR/EN üretilir; string kaynakları zaten appcompat locale ile
     * otomatik çevrilir.
     */
    fun isEnglish(ctx: Context): Boolean = currentLang(ctx) == "en"

    /** Kayıtlı dili uygulama genelinde etkinleştirir (asla çökmez). */
    fun applyToApp(ctx: Context) {
        try {
            val lang = currentLang(ctx)
            val target = LocaleListCompat.forLanguageTags(lang)
            val cur = AppCompatDelegate.getApplicationLocales()
            val curTag = if (cur.isEmpty) "" else (cur.get(0)?.toLanguageTag() ?: "")
            if (curTag.take(2) != lang) {
                AppCompatDelegate.setApplicationLocales(target)
            }
        } catch (_: Throwable) {
        }
    }

    /** Dili kaydeder, uygular ve dil seçildi bayrağını doldurur. */
    fun setLang(ctx: Context, lang: String) {
        AppPrefs.putString(ctx, AppPrefs.KEY_LANG, lang)
        AppPrefs.putBool(ctx, AppPrefs.KEY_LANG_SET, true)
        applyToApp(ctx)
    }

    /**
     * Tek seçimli dil diyaloğu (Türkçe / English).
     * Seçim sonrası dil anında uygulanır; Activity'ler appcompat sayesinde
     * kendiliğinden yenilenir (ekstra recreate gerekmez).
     */
    fun askLanguage(activity: Activity, onDone: (() -> Unit)? = null) {
        try {
            val labels = arrayOf("Türkçe 🇹🇷", "English 🇬🇧")
            val cur = if (currentLang(activity) == "en") 1 else 0
            AlertDialog.Builder(activity)
                .setTitle(R.string.lang_title)
                .setSingleChoiceItems(labels, cur) { d, which ->
                    d.dismiss()
                    setLang(activity, if (which == 1) "en" else "tr")
                    // v16 (GÖREV 3) — önce çağıranın işi (varsa) tamamlanır,
                    // SONRA ekran yenilenir (recreate'den sonra eski Activity
                    // örneğine dokunulmasın diye sıra önemlidir).
                    try {
                        onDone?.invoke()
                    } catch (_: Throwable) {
                    }
                    try {
                        activity.recreate()
                    } catch (_: Throwable) {
                    }
                }
                .setNegativeButton(R.string.kapat, null)
                .show()
        } catch (_: Throwable) {
        }
    }
}
