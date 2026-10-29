package com.umay.pdfreader

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.Window

/**
 * v14 (V5.0 §1) — ÖZEL TEMA TASARLAYICI.
 *
 * Kullanıcı Ayarlar → "Tema Tasarımcısı" ekranında:
 *  • uygulama arka planını,
 *  • panel/kart rengini,
 *  • vurgu (aksan) rengini,
 *  • raf çerçeve rengini
 * seçebilir; seçim CANLI ÖNİZLEME ile anında görünür ve kalıcıdır
 * (AppPrefs KEY_THEME_* hex değerleri).
 *
 * Uygulama: UmayApp.registerActivityLifecycleCallbacks → her ekran
 * açıldığında apply(activity) çağrılır (status/nav bar + decor zemin).
 * Ekran içi sabit @color view'ları sonraki açılışlarda temayı alır.
 *
 * Varsayılan palet mat ve göz yormayandır (siber-akademik tema korunur);
 * tema seçilmediğinde (hex boş) renk dosyalarındaki değerler kullanılır.
 */
object ThemeStudio {

    /** Varsayılan mat palet (colors.xml ile aynı — referans). */
    const val DEFAULT_BG = "#0B0E14"
    const val DEFAULT_PANEL = "#161B22"
    const val DEFAULT_ACCENT = "#FF6B00"
    const val DEFAULT_SHELF_FRAME = "#1E293B"

    /** Uygulanacak tema (hex; null/boş → varsayılan). */
    @Volatile var bg: String = DEFAULT_BG
    @Volatile var panel: String = DEFAULT_PANEL
    @Volatile var accent: String = DEFAULT_ACCENT
    @Volatile var shelfFrame: String = DEFAULT_SHELF_FRAME

    /**
     * v16 (GÖREV 2) — ÖZEL TEMA ETKİN Mİ?
     * false iken `apply()` ekranlara HİÇ dokunmaz → Gece/Gündüz/Sistem
     * (values ↔ values-night) seçimi görünür şekilde çalışır.
     * true iken kullanıcı seçtiği özel palet her ekranda geçerlidir.
     */
    @Volatile var customized: Boolean = false

    /**
     * Önceden hazır mat paletler (canlı önizleme için).
     * v16 (GÖREV 3) — `labelEn`: İngilizce arayüzde gösterilecek ad
     * (Türkçe adlar `label`). Boş bırakılırsa `label` kullanılır.
     */
    data class Preset(
        val label: String,
        val bg: String,
        val panel: String,
        val accent: String,
        val shelf: String,
        val labelEn: String = ""
    ) {
        /** Aktif dile göre etiket. */
        fun label(english: Boolean): String =
            if (english && labelEn.isNotBlank()) labelEn else label
    }

    val PRESETS = listOf(
        Preset("Siber Akademik", DEFAULT_BG, DEFAULT_PANEL, DEFAULT_ACCENT, DEFAULT_SHELF_FRAME,
            "Cyber Academic"),
        Preset("Sıcak Gece", "#12100E", "#1E1A16", "#FF8A3D", "#2C2620", "Warm Night"),
        Preset("Okyanus", "#0A1220", "#122036", "#00C2A8", "#1B2E4A", "Ocean"),
        Preset("Orman", "#0C1410", "#132019", "#7BC96F", "#1D2F24", "Forest"),
        Preset("Lavanta", "#14121F", "#1D1A2E", "#A78BFA", "#282442", "Lavender"),
        Preset("Kül & Altın", "#141414", "#1F1F1F", "#E0B034", "#2B2B2B", "Ash & Gold"),
        Preset("Gündüz Mat", "#EDF1F7", "#FFFFFF", "#C2410C", "#DCE3ED", "Day Matte"),
        Preset("Gündüz Okyanus", "#F1F5F7", "#FFFFFF", "#0E7490", "#DCE6EB", "Day Ocean"),
        // v16 (GÖREV 2) — "Midnight" ve AMOLED seçenekleri (koyu tema boğuculuğu şikâyeti)
        Preset("Midnight (göz yormayan)", "#0F141C", "#19212C", "#FF7A26", "#232E3D",
            "Midnight (easy on the eyes)"),
        Preset("OLED Saf Siyah", "#000000", "#0A0A0A", "#FF6B00", "#161616", "OLED Pure Black")
    )

    /** Uygulamayı başlatırken AppPrefs'ten oku (asla çökmez). */
    fun load(ctx: android.content.Context) {
        try {
            bg = AppPrefs.getString(ctx, AppPrefs.KEY_THEME_BG).ifBlank { DEFAULT_BG }
            panel = AppPrefs.getString(ctx, AppPrefs.KEY_THEME_PANEL).ifBlank { DEFAULT_PANEL }
            accent = AppPrefs.getString(ctx, AppPrefs.KEY_THEME_ACCENT).ifBlank { DEFAULT_ACCENT }
            shelfFrame = AppPrefs.getString(ctx, AppPrefs.KEY_THEME_SHELF).ifBlank { DEFAULT_SHELF_FRAME }
            // v16 (GÖREV 2) — bayrak yoksa "özel tema yok" kabul et (eski
            // kurulumlarda özel renk kayıtlı olsa bile Gece/Gündüz çalışsın)
            customized = AppPrefs.getBool(ctx, AppPrefs.KEY_THEME_CUSTOM, false)
        } catch (_: Throwable) {
        }
    }

    /** Özel tema etkin mi? (Ayarlar/Gündüz-Gece geçişi bunu okur). */
    fun isCustomized(ctx: android.content.Context): Boolean = try {
        AppPrefs.getBool(ctx, AppPrefs.KEY_THEME_CUSTOM, false)
    } catch (_: Throwable) {
        customized
    }

    /**
     * v16 (GÖREV 2) — ÖZEL TEMAYI KAPAT: Gece/Gündüz/Sistem seçiminin
     * görünmesini ENGELLEYEN zorla-boyama kaldırılır. Renkler hafızada
     * kalır (kullanıcı tasarımcıya dönüp tekrar açabilir).
     */
    fun clearCustomization(ctx: android.content.Context) {
        try {
            AppPrefs.putBool(ctx, AppPrefs.KEY_THEME_CUSTOM, false)
        } catch (_: Throwable) {
        }
        customized = false
        bg = DEFAULT_BG; panel = DEFAULT_PANEL
        accent = DEFAULT_ACCENT; shelfFrame = DEFAULT_SHELF_FRAME
    }

    /** Özel temayı etkinleştir (kayıtlı renklerle) — tasarımcı ekranı çağırır. */
    fun enableCustomization(ctx: android.content.Context) {
        try {
            AppPrefs.putBool(ctx, AppPrefs.KEY_THEME_CUSTOM, true)
        } catch (_: Throwable) {
        }
        customized = true
    }

    /** Temayı kaydet + uygula (canlı önizleme bu fonksiyonu her değişimde çağırır). */
    fun saveAndApply(ctx: android.content.Context, t: Preset) {
        AppPrefs.putString(ctx, AppPrefs.KEY_THEME_BG, t.bg)
        AppPrefs.putString(ctx, AppPrefs.KEY_THEME_PANEL, t.panel)
        AppPrefs.putString(ctx, AppPrefs.KEY_THEME_ACCENT, t.accent)
        AppPrefs.putString(ctx, AppPrefs.KEY_THEME_SHELF, t.shelf)
        // v16 (GÖREV 2) — palet seçildiği an özel tema ETKİN olur; aksi hâlde
        // seçim kaydedilir ama ekrana hiç uygulanmaz (eski hata).
        AppPrefs.putBool(ctx, AppPrefs.KEY_THEME_CUSTOM, true)
        bg = t.bg; panel = t.panel; accent = t.accent; shelfFrame = t.shelf
        customized = true
    }

    /**
     * Kayıtlı temayı sıfırla → **OTOMATİK moda dön**: özel tema kapatılır,
     * uygulama yeniden Gece/Gündüz/Sistem seçimini izler.
     */
    fun reset(ctx: android.content.Context) {
        clearCustomization(ctx)
    }

    /** Hex (#RRGGBB) → Color; bozuk değerse fallback. */
    fun color(hex: String, fallback: Int = Color.TRANSPARENT): Int = try {
        if (hex.isBlank()) fallback else Color.parseColor(hex)
    } catch (_: Throwable) {
        fallback
    }

    /** Bir activity'ye kayıtlı temayı uygula (zemin + sistem barları). */
    fun apply(activity: Activity) {
        try {
            // v16 (GÖREV 2) — TEK KARAR NOKTASI: özel tema kapalıysa ekranlara
            // HİÇ dokunulmaz. Böylece Ayarlar'dan Gece/Gündüz/Sistem seçimi
            // gerçekten görünür olur (eski sürümde özel tema her şeyi eziyordu).
            if (!customized) return
            val w: Window = activity.window
            val bgC = color(bg)
            w.decorView.setBackgroundColor(bgC)
            @Suppress("DEPRECATION")
            w.statusBarColor = bgC
            @Suppress("DEPRECATION")
            w.navigationBarColor = color(panel)
            // Aydınlık/karanlık ikon dengesi: açık zeminlerde koyu ikon.
            // v16 (GÖREV 2): deprecated systemUiVisibility yerine MODERN
            // WindowInsetsController (API 30+'ta güvenilir, EdgeToEdge ile uyumlu).
            val light = isLight(bgC)
            try {
                val ic = androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
                ic.isAppearanceLightStatusBars = light
                ic.isAppearanceLightNavigationBars = light
            } catch (_: Throwable) {
                @Suppress("DEPRECATION")
                val vp = w.decorView as? View
                vp?.let {
                    val f = it.systemUiVisibility
                    it.systemUiVisibility = if (light) {
                        f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    } else {
                        f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Zemin açık ton mu? (status bar ikonları için). */
    fun isLight(bgColor: Int): Boolean {
        val r = Color.red(bgColor); val g = Color.green(bgColor); val b = Color.blue(bgColor)
        return (0.299 * r + 0.587 * g + 0.114 * b) > 186
    }
}

/**
 * Application.ActivityLifecycleCallbacks için BOŞ adapter — UmayApp yalnız
 * onActivityResumed'ı doldurur (diğer kancalar boş kalır, crash yok).
 */
abstract class ActivityLifecycleCallbacksAdapter : android.app.Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
    override fun onActivityStarted(activity: android.app.Activity) {}
    override fun onActivityResumed(activity: android.app.Activity) {}
    override fun onActivityPaused(activity: android.app.Activity) {}
    override fun onActivityStopped(activity: android.app.Activity) {}
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
    override fun onActivityDestroyed(activity: android.app.Activity) {}
}
