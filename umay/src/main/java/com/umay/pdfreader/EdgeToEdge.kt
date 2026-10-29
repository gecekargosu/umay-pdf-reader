package com.umay.pdfreader

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * SDK 36 (Android 15+) edge-to-edge uyumluluğu.
 *
 * targetSdk 35+ ile sistem, pencereyi zorunlu olarak edge-to-edge yapar ve
 * `statusBarColor` / `navigationBarBarColor` yok sayılır. Bu yardımcı:
 *  - pencereyi bilinçli olarak edge-to-edge'ye alır,
 *  - durum/çubuk alanlarını şeffaf yapar (zemin temanın `windowBackground`ı),
 *  - **kök görünüme** sistem çubukları + çentik kadar padding vererek
 *    eski (SDK 34) görünümü korur: içerik çubukların altına girmez.
 *
 * Tüm ekranlar onCreate'de `EdgeToEdge.apply(this, binding.root)` çağırır.
 * Dialog'lar kendi penceresinde olduğu için etkilenmez.
 */
object EdgeToEdge {

    fun apply(activity: Activity, root: View) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Çubukları şeffaf yap (API 35+'ta zaten yok sayılır; altta tutarlılık)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.navigationBarColor = Color.TRANSPARENT
        }

        // v16 (GÖREV 2 — AÇIK TEMA ONARIMI): Çubuk ikonları artık AKTİF TEMAYA
        // göre seçilir. Eskiden sabit `false` idi → Gündüz temasında beyaz
        // zeminde BEYAZ saat/pil/geri ikonları görünmez oluyordu.
        val insetsController = WindowCompat.getInsetsController(window, root)
        val nightMode = (root.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !nightMode
        insetsController.isAppearanceLightNavigationBars = !nightMode

        // Layout'un kendi padding'ini koru, üzerine sistem çubuklarını ekle
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
        root.requestApplyInsets()
    }
}
