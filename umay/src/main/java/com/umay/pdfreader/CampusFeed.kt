package com.umay.pdfreader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * v43 — KAMPÜS AKIŞI (okul sitesi duyuru/haber takibi).
 *
 * Kullanıcı isteği: "Okul sitesinde duyuru/haber yayınlandığında program
 * uyarsın, tıklayınca kendi içinde görebileyim; siteye gitmek zorunda kalmayayım."
 *
 * Nasıl çalışır:
 *  • `https://rumeli.edu.tr/tr/duyurular` ve `/tr/haberler` sayfaları **tarayıcı
 *    başlığıyla** çekilir (site bot koruması düz istemciyi engelliyor).
 *  • Bağlantılar (`/tr/duyurular/…`) başlık metniyle birlikte çıkarılır.
 *  • Yeni kimlikler bir önceki listeyle karşılaştırılır; yeni varsa **bildirim**.
 *  • İlk çalıştırmada bildirim atılmaz (mevcut 50 duyuru "yeni" sayılmasın).
 *  • Liste `files/campus_feed.json` içinde saklanır; çevrimdışıyken de okunur.
 *
 * Sınırlar: sayfa yapısı değişirse ayrıştırma boş dönebilir; bu durumda
 * Seyir Defteri'ne "0 öğe" yazılır ve uygulama çökmez.
 */
object CampusFeed {

    data class Item(
        val id: String,
        val title: String,
        val url: String,
        val source: String,
        val firstSeen: Long
    )

    private const val PREFS = "umay_campus"
    private const val KEY_LAST_CHECK = "campus_last_check"
    private const val KEY_SEEN = "campus_seen_ids"
    private const val KEY_INIT = "campus_initialized"
    private const val KEY_NEW = "campus_new_count"
    private const val CHANNEL = "umay_campus"

    /** Site düz istemciyi "Güvenlik Kontrolü" sayfasına yönlendiriyor. */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val PAGES = listOf(
        "https://rumeli.edu.tr/tr/duyurular" to "Duyuru",
        "https://rumeli.edu.tr/tr/haberler" to "Haber"
    )

    private val ANCHOR = Pattern.compile(
        "<a\\s+[^>]*?href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>",
        Pattern.DOTALL or Pattern.CASE_INSENSITIVE
    )

    private val SKIP_TITLES = setOf(
        "devamını oku", "devamini oku", "tüm duyurular", "tum duyurular",
        "tüm haberler", "tum haberler", "tüm bloglar", "detay", "detaylar"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun itemsFile(ctx: Context) = File(ctx.filesDir, "campus_feed.json")

    fun load(ctx: Context): List<Item> = try {
        val f = itemsFile(ctx)
        if (!f.exists() || f.length() == 0L) emptyList()
        else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Item(
                    o.optString("id"),
                    o.optString("title"),
                    o.optString("url"),
                    o.optString("source", "Duyuru"),
                    o.optLong("firstSeen")
                )
            }.filter { it.id.isNotBlank() && it.url.isNotBlank() }
        }
    } catch (t: Throwable) {
        BookStore.logError(ctx, "CampusFeed.load", t)
        emptyList()
    }

    private fun save(ctx: Context, items: List<Item>) {
        try {
            val arr = JSONArray()
            items.take(120).forEach { it ->
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("url", it.url)
                        .put("source", it.source)
                        .put("firstSeen", it.firstSeen)
                )
            }
            val f = itemsFile(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CampusFeed.save", t)
        }
    }

    private fun seenIds(ctx: Context): MutableSet<String> = try {
        val raw = prefs(ctx).getString(KEY_SEEN, null)
        if (raw.isNullOrBlank()) mutableSetOf()
        else mutableSetOf(*JSONArray(raw).let { a -> Array(a.length()) { a.getString(it) } })
    } catch (_: Throwable) {
        mutableSetOf()
    }

    private fun saveSeen(ctx: Context, ids: Set<String>) {
        try {
            prefs(ctx).edit().putString(
                KEY_SEEN, JSONArray(ids.toList()).toString()
            ).apply()
        } catch (_: Throwable) {
        }
    }

    fun lastCheckAt(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_CHECK, 0L)

    fun newCount(ctx: Context): Int = prefs(ctx).getInt(KEY_NEW, 0)

    fun markAllSeen(ctx: Context) {
        try {
            val ids = load(ctx).map { it.id }.toSet() + seenIds(ctx)
            saveSeen(ctx, ids)
            prefs(ctx).edit().putInt(KEY_NEW, 0).apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * Akışı tazeler.
     * @param notify yeni öğe varsa bildirim göster
     * @param force son kontrolden bağımsız çalış (elle "Yenile")
     * @return yeni öğe sayısı
     */
    fun check(ctx: Context?, notify: Boolean = true, force: Boolean = false): Int {
        if (ctx == null) return 0
        val now = System.currentTimeMillis()
        // elle denetim yoksa 20 dakikada bir yeter
        if (!force && now - lastCheckAt(ctx) < 20 * 60_000L) return 0
        val parsed = ArrayList<Item>()
        var fetchedAny = false
        for ((url, source) in PAGES) {
            val html = fetch(url) ?: continue
            fetchedAny = true
            parsed += parse(html, source, now)
        }
        if (!fetchedAny) {
            SeyirDefteri.yaz(
                ctx, "KAMPÜS", "siteye ulaşılamadı",
                lastError.ifBlank { "internet/koruma" }
            )
            return 0
        }
        return merge(ctx, parsed, notify)
    }

    /** Son kontrolün üzerinden 20 dk geçti mi? (ana sayfa arka plan kontrolü) */
    fun due(ctx: Context): Boolean =
        System.currentTimeMillis() - lastCheckAt(ctx) >= 20 * 60_000L

    /**
     * v43.2 — WEBVIEW İLE ÇEKME (ana sayfa arka planı).
     *
     * Site düz HTTP'yi WAF ile 403'lüyor (cihazda: `HTTP 403 · /tr/haberler`).
     * Gerçek Chromium parmak izi gerektiği için sayfalar 1x1 piksellik görünmez
     * bir WebView ile yüklenir, HTML oradan okunur; ekranda hiçbir şey görünmez.
     * Sıra: duyurular → haberler. 20 sn takılırsa o sayfa atlanır.
     */
    fun checkWithWebView(
        activity: android.app.Activity,
        notify: Boolean = true,
        force: Boolean = false,
        done: (Int) -> Unit = {}
    ) {
        if (!force && !due(activity)) {
            done(0)
            return
        }
        val out = ArrayList<Item>()
        var idx = 0

        fun next() {
            if (activity.isFinishing || idx >= PAGES.size) {
                val fresh = if (out.isEmpty()) 0 else merge(activity, out, notify)
                if (out.isEmpty()) {
                    SeyirDefteri.yaz(activity, "KAMPÜS", "siteye ulaşılamadı", "WebView boş döndü")
                }
                done(fresh)
                return
            }
            val (url, source) = PAGES[idx++]
            val holder = android.widget.FrameLayout(activity)
            val web = android.webkit.WebView(activity)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            holder.addView(web, android.view.ViewGroup.LayoutParams(1, 1))
            try {
                activity.addContentView(holder, android.view.ViewGroup.LayoutParams(1, 1))
            } catch (_: Throwable) { }
            var finished = false
            fun finishOnce(html: String?) {
                if (finished) return
                finished = true
                try {
                    if (!html.isNullOrBlank()) out.addAll(parsePage(html, source))
                } catch (_: Throwable) { }
                try {
                    holder.removeAllViews()
                    web.destroy()
                    (holder.parent as? android.view.ViewGroup)?.removeView(holder)
                } catch (_: Throwable) { }
                next()
            }
            web.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, u: String?) {
                    view?.evaluateJavascript(
                        "(function(){return document.documentElement.outerHTML;})()"
                    ) { raw ->
                        val html = try {
                            (org.json.JSONTokener(raw).nextValue() as? String)
                        } catch (_: Throwable) {
                            raw
                        }
                        finishOnce(html)
                    }
                }
            }
            web.loadUrl(url)
            android.os.Handler(activity.mainLooper).postDelayed({ finishOnce(null) }, 20_000L)
        }
        next()
    }

    /**
     * v43.1 — Ayrıştırılmış öğeleri listeye ekler.
     * WebView ile çekim (siteden geçen gerçek tarayıcı) de bu yolu kullanır.
     */
    fun merge(ctx: Context, parsed: List<CampusFeed.Item>, notify: Boolean): Int {
        if (parsed.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val initialized = prefs(ctx).getBoolean(KEY_INIT, false)
        val old = load(ctx)
        val oldIds = old.map { it.id }.toSet()
        val fresh = parsed.filter { it.id !in oldIds }
        // yeni öğeler başta, sonra eskiler (tekrarsız)
        val merged = (fresh + old).distinctBy { it.id }
        save(ctx, merged)
        prefs(ctx).edit().putLong(KEY_LAST_CHECK, now).apply()
        saveSeen(ctx, oldIds + parsed.map { it.id })

        if (!initialized) {
            prefs(ctx).edit().putBoolean(KEY_INIT, true).putInt(KEY_NEW, 0).apply()
            SeyirDefteri.yaz(ctx, "KAMPÜS", "akış kuruldu", "${merged.size} öğe (ilk tarama)")
            return 0
        }
        if (fresh.isEmpty()) {
            prefs(ctx).edit().putInt(KEY_NEW, 0).apply()
            return 0
        }
        prefs(ctx).edit().putInt(KEY_NEW, fresh.size).apply()
        SeyirDefteri.yaz(
            ctx, "KAMPÜS", "${fresh.size} yeni içerik",
            fresh.take(3).joinToString(" · ") { it.title.take(60) }
        )
        if (notify) notifyNew(ctx, fresh)
        return fresh.size
    }

    /** Son ağ hatası (Seyir Defteri'ne yazılır — teşhis için). */
    @Volatile
    var lastError: String = ""
        private set

    private fun fetch(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        // v43.1 — WAF 403'ünü aşmak için tam tarayıcı parmak izi
        c.setRequestProperty("Accept-Encoding", "identity")
        c.setRequestProperty("Connection", "keep-alive")
        c.setRequestProperty("Upgrade-Insecure-Requests", "1")
        c.setRequestProperty("Referer", "https://rumeli.edu.tr/")
        c.setRequestProperty("Sec-Fetch-Site", "same-origin")
        c.setRequestProperty("Sec-Fetch-Mode", "navigate")
        c.setRequestProperty("Sec-Fetch-User", "?1")
        c.setRequestProperty("Sec-Fetch-Dest", "document")
        val code = c.responseCode
        if (code !in 200..299) {
            lastError = "HTTP $code · $url"
            null
        } else {
            val bytes = c.inputStream.readBytes()
            val declared = c.contentType?.substringAfter("charset=", "")?.trim()
            decode(bytes, declared)
        }
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message?.take(80)} · $url"
        null
    }

    /** Türkçe karakterler için: UTF-8 çözülemezse windows-1254 denenir. */
    private fun decode(bytes: ByteArray, declared: String?): String {
        val cand = ArrayList<String>()
        if (!declared.isNullOrBlank()) cand.add(declared)
        cand.add("UTF-8")
        cand.add("windows-1254")
        var best = ""
        var bestScore = -1
        for (cs in cand) {
            val text = try {
                String(bytes, charsetOf(cs))
            } catch (_: Throwable) {
                continue
            }
            val bad = text.count { it == '\uFFFD' }
            val score = 1000 - bad * 10
            if (score > bestScore) {
                bestScore = score
                best = text
            }
        }
        return best
    }

    private fun charsetOf(name: String): java.nio.charset.Charset = try {
        java.nio.charset.Charset.forName(name.trim().trim('"'))
    } catch (_: Throwable) {
        Charsets.UTF_8
    }

    /** WebView'den gelen HTML için genel ayrıştırıcı. */
    fun parsePage(html: String, source: String): List<Item> =
        parse(html, source, System.currentTimeMillis())

    private fun parse(html: String, source: String, now: Long): List<Item> {
        val out = LinkedHashMap<String, Item>()
        val m = ANCHOR.matcher(html)
        while (m.find()) {
            val rawHref = m.group(1) ?: continue
            if (!rawHref.contains("/tr/duyurular/") && !rawHref.contains("/tr/haberler/")) continue
            val href = when {
                rawHref.startsWith("http") -> rawHref
                rawHref.startsWith("//") -> "https:$rawHref"
                else -> "https://rumeli.edu.tr" + rawHref
            }.substringBefore('#')
            val title = clean(m.group(2) ?: "")
            if (title.length < 12) continue
            if (title.lowercase() in SKIP_TITLES) continue
            val id = href.substringAfter("rumeli.edu.tr", href).trimEnd('/')
            if (id.isBlank() || out.containsKey(id)) continue
            out[id] = Item(id, title, href, source, now)
        }
        return out.values.toList()
    }

    private fun clean(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]+>"), " ")
        val unescaped = try {
            android.text.Html.fromHtml(noTags, android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString()
        } catch (_: Throwable) {
            noTags
        }
        return unescaped.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun notifyNew(ctx: Context, fresh: List<Item>) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "Kampüs duyuruları",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "Okul sitesinde yeni duyuru/haber" }
                )
            }
            val intent = Intent(ctx, CampusActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(
                ctx, 8101, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val first = fresh.first()
            val text = if (fresh.size == 1) first.title.take(90)
            else "${fresh.size} yeni içerik · ${first.title.take(70)}"
            val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("🏫 Kampüs: yeni duyuru")
                .setContentText(text)
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                        fresh.take(4).joinToString("\n") { "• " + it.title.take(80) }
                    )
                )
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(8101, n)
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CampusFeed.notify", t)
        }
    }
}
