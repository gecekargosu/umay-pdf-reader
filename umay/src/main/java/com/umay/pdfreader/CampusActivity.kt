package com.umay.pdfreader

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.umay.pdfreader.databinding.ActivityCampusBinding

/**
 * v43 — KAMPÜS DUYURULARI.
 *
 * • Üstte okulun duyuru/haber akışı (uygulama içinde, siteye gitmeden).
 * • Bir başlığa dokununca **uygulamanın içinde** sayfa açılır (WebView).
 * • "Yenile" internetten tazeler; yeni içerik varsa Seyir Defteri'ne yazılır.
 * • Geri tuşu: sayfa açıksa kapatır, değilse ekrandan çıkar.
 */
class CampusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCampusBinding
    private var webVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCampusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.campusRefresh.setOnClickListener { refresh(force = true) }
        binding.campusWebClose.setOnClickListener { closeWeb() }

        render(CampusFeed.load(this))
        // açılışta arka planda tazele (20 dk sınırı CampusFeed içinde)
        refresh(force = false)
        try {
            SeyirDefteri.yaz(this, "KAMPÜS", "ekran açıldı")
        } catch (_: Throwable) { }
    }

    override fun onResume() {
        super.onResume()
        CampusFeed.markAllSeen(this)
        render(CampusFeed.load(this))
    }

    /**
     * v43.1 — ÇEKME YÖNTEMİ.
     *
     * Site, düz HTTP isteklerini WAF ile 403'lüyor (cihazda test edildi:
     * `HTTP 403 · /tr/duyurular`). Bu yüzden ekran, sayfaları **görünmez bir
     * WebView** (gerçek Chromium) ile yükler ve HTML'i oradan okur; WAF'ın
     * beklediği tarayıcı parmak izi böylece sağlanır ve içerik uygulama
     * içinde kalır. Pencere açılmaz, kullanıcı hiçbir şey görmez.
     */
    private fun refresh(force: Boolean) {
        binding.campusProgress.visibility = View.VISIBLE
        binding.campusStatus.text = getString(R.string.campus_loading)
        fetchWithWebView { items ->
            if (isFinishing) return@fetchWithWebView
            if (items.isEmpty()) {
                // WebView de başaramadıysa son çare: klasik HTTP + eski liste
                Thread {
                    CampusFeed.check(applicationContext, notify = false, force = true)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        binding.campusProgress.visibility = View.GONE
                        binding.campusStatus.text = getString(R.string.campus_hint)
                        render(CampusFeed.load(this))
                    }
                }.start()
                return@fetchWithWebView
            }
            val fresh = CampusFeed.merge(this, items, notify = false)
            binding.campusProgress.visibility = View.GONE
            binding.campusStatus.text =
                if (fresh > 0) getString(R.string.campus_new, fresh) else getString(R.string.campus_hint)
            if (force && fresh == 0) {
                Toast.makeText(this, R.string.campus_uptodate, Toast.LENGTH_SHORT).show()
            }
            render(CampusFeed.load(this))
        }
    }

    private fun fetchWithWebView(done: (List<CampusFeed.Item>) -> Unit) {
        val targets = listOf(
            "https://rumeli.edu.tr/tr/duyurular" to "Duyuru",
            "https://rumeli.edu.tr/tr/haberler" to "Haber"
        )
        val out = ArrayList<CampusFeed.Item>()
        var idx = 0

        fun next() {
            if (idx >= targets.size || isFinishing) {
                done(out)
                return
            }
            val (url, source) = targets[idx++]
            val holder = android.widget.FrameLayout(this)
            val web = WebView(this)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            holder.addView(web, android.view.ViewGroup.LayoutParams(1, 1))
            try {
                addContentView(
                    holder,
                    android.view.ViewGroup.LayoutParams(1, 1)
                )
            } catch (_: Throwable) { }
            var finished = false
            fun finishOnce(html: String?) {
                if (finished) return
                finished = true
                try {
                    if (!html.isNullOrBlank()) out.addAll(CampusFeed.parsePage(html, source))
                } catch (_: Throwable) { }
                try {
                    holder.removeAllViews()
                    web.destroy()
                    (holder.parent as? android.view.ViewGroup)?.removeView(holder)
                } catch (_: Throwable) { }
                next()
            }
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    view?.evaluateJavascript(
                        "(function(){return document.documentElement.outerHTML;})()"
                    ) { raw -> finishOnce(unescapeJs(raw)) }
                }
            }
            web.loadUrl(url)
            // 20 sn takılırsa sonraki sayfaya geç (ekran kilitlenmesin)
            android.os.Handler(mainLooper).postDelayed({ finishOnce(null) }, 20_000L)
        }
        next()
    }

    /** `evaluateJavascript` sonucu JSON dizesidir; çözülür. */
    private fun unescapeJs(raw: String?): String? {
        if (raw == null || raw == "null" || raw.length < 20) return null
        return try {
            (org.json.JSONTokener(raw).nextValue() as? String) ?: raw
        } catch (_: Throwable) {
            raw
        }
    }

    private fun render(items: List<CampusFeed.Item>) {
        val box = binding.campusList
        box.removeAllViews()
        if (items.isEmpty()) {
            box.addView(
                TextView(this).apply {
                    text = getString(R.string.campus_empty)
                    setTextColor(getColor(R.color.umay_text_dim))
                    textSize = 13f
                    setPadding(28, 40, 28, 40)
                }
            )
            return
        }
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(26, 20, 26, 20)
                isClickable = true
                isFocusable = true
                background = getDrawable(R.drawable.bg_panel)
                (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = 8
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
            row.layoutParams = lp

            row.addView(
                TextView(this).apply {
                    text = item.title
                    setTextColor(getColor(R.color.umay_text))
                    textSize = 13.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
            row.addView(
                TextView(this).apply {
                    val stamp = if (item.firstSeen > 0)
                        " · " + android.text.format.DateFormat
                            .format("dd.MM.yyyy HH:mm", item.firstSeen)
                    else ""
                    text = item.source + stamp
                    setTextColor(getColor(R.color.umay_text_dim))
                    textSize = 10.5f
                    setPadding(0, 6, 0, 0)
                }
            )
            row.setOnClickListener { openWeb(item) }
            box.addView(row)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openWeb(item: CampusFeed.Item) {
        try {
            binding.campusWebTitle.text = item.title
            binding.campusWebWrap.visibility = View.VISIBLE
            webVisible = true
            val web = binding.campusWeb
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false   // tüm gezinme uygulama içinde kalsın
            }
            web.loadUrl(item.url)
            SeyirDefteri.yaz(this, "KAMPÜS", "duyuru açıldı", item.title.take(80))
        } catch (t: Throwable) {
            BookStore.logError(this, "CampusActivity.openWeb", t)
            Toast.makeText(this, "Sayfa açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeWeb() {
        try {
            binding.campusWeb.stopLoading()
            binding.campusWeb.loadUrl("about:blank")
        } catch (_: Throwable) { }
        binding.campusWebWrap.visibility = View.GONE
        webVisible = false
    }

    @Deprecated("Android 13+ OnBackInvoked kullanılıyor; uyumluluk için")
    override fun onBackPressed() {
        if (webVisible) {
            closeWeb()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
