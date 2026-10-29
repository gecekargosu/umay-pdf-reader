package com.umay.pdfreader

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity

/**
 * v13 (T9b) — Ara tanıtım ekranı.
 *
 * 3 kart otomatik akar (~5 sn toplam); "Geç" ya da süre bitince kapanır
 * ve bir daha gösterilmez (AppPrefs.KEY_ONBOARDING_DONE).
 * Alt imza: UmaySoft · Puzzle logo (ic_app).
 */
class OnboardingActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    private val advance = object : Runnable {
        override fun run() {
            val f = findViewById<ViewFlipper>(R.id.onbFlipper)
            if (f != null && f.displayedChild < f.childCount - 1) {
                f.showNext()
                handler.postDelayed(this, 1700L)
            } else {
                finishFlow()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        EdgeToEdge.apply(this, findViewById(android.R.id.content))

        handler.postDelayed(advance, 1700L)

        findViewById<TextView>(R.id.onbSkip).setOnClickListener { finishFlow() }
        findViewById<TextView>(R.id.onbNext).setOnClickListener {
            val f = findViewById<ViewFlipper>(R.id.onbFlipper)
            if (f.displayedChild < f.childCount - 1) f.showNext()
            else finishFlow()
        }

        // ── v13 (AGENT 22 — A3) — İLK KURULUM: DİL SEÇİMİ ──
        // Uygulama ilk açıldığında (veya dil seçilmemişken) kullanıcı
        // Türkçe/İngilizce seçer; tercih kalıcıdır (AppPrefs.KEY_LANG)
        // ve tüm arayüze anında yansır.
        if (!AppPrefs.getBool(this, AppPrefs.KEY_LANG_SET, false)) {
            LangHelper.askLanguage(this)
        }

        // ── v13 (AGENT 22 — A3) — SOSYAL GİRİŞ (simüle/demo) ──
        // Gerçek OAuth için Play Console + uygulama kimliği gerekir;
        // bu sürümde butonlar hızlı giriş akışını SIMÜLE eder ve seçimi
        // profilde gösterir (sunucusuz, cihazda saklanır).
        socialButton(R.id.socGoogle, "google", "Google")
        socialButton(R.id.socApple, "apple", "Apple")
        socialButton(R.id.socFacebook, "facebook", "Facebook")
        socialButton(R.id.socInstagram, "instagram", "Instagram")
    }

    /** Sosyal giriş butonu: onay diyaloğu → sağlayıcı kaydedilir → akış biter. */
    private fun socialButton(id: Int, provider: String, label: String) {
        findViewById<TextView>(id)?.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.social_login_title, label))
                .setMessage(getString(R.string.social_login_msg, label))
                .setPositiveButton(R.string.social_login_ok) { _, _ ->
                    AppPrefs.putString(this, AppPrefs.KEY_SOCIAL_PROVIDER, provider)
                    AppPrefs.putString(this, AppPrefs.KEY_SOCIAL_NAME, label)
                    Toast.makeText(
                        this, getString(R.string.social_login_done, label),
                        Toast.LENGTH_SHORT
                    ).show()
                    finishFlow()
                }
                .setNegativeButton(R.string.kapat, null)
                .show()
        }
    }

    private fun finishFlow() {
        if (done) return
        done = true
        handler.removeCallbacks(advance)
        AppPrefs.putBool(this, AppPrefs.KEY_ONBOARDING_DONE, true)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(advance)
        super.onDestroy()
    }
}
