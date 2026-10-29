package com.umay.pdfreader

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * ÖZELLİK KÜNYESİ / CANLI TANILAMA (v21 — tam kapsam).
 *
 * Amaç: uygulamanın TÜM ana modüllerini gerçekten çalıştırıp tek tek
 * doğrulamak ve sonucu hem metin hem de renkli (✗ kırmızı) olarak sunmak.
 *
 * Tasarım kuralları:
 *  • Her kontrol kendi try/catch'inde; biri çökse diğerleri çalışmaya devam eder.
 *  • Hiçbir kontrol UI thread'ini kilitlemez (IO dispatcher + kısa zaman aşımları).
 *  • "Ağ yok" bir HATA değildir: çevrimdışı çalışma tasarım gereğidir →
 *    çevrimiçi gerektiren testler çevrimdışıyken WARN ("atlandı") döner.
 *  • Sonuç AppPrefs'e yazılır; ana sayfa rozeti buradan okur.
 */
object DiagnosticTestManager {

    const val KEY_LAST_FAIL = "diag_last_fail"
    const val KEY_LAST_WARN = "diag_last_warn"
    /** v34 — çalışan (OK) özellik sayısı: yeşil lambanın içinde yazar. */
    const val KEY_LAST_OK = "diag_last_ok"
    /** v36 — varlık kademesinde geçen (yeşil sayıya DAHİL OLMAYAN) kontrol sayısı. */
    const val KEY_LAST_ASSET = "diag_last_asset"
    /** v36.2 — varlık kademesinde kalan uyarı sayısı (lambayı yakmaz). */
    const val KEY_LAST_ASSET_WARN = "diag_last_asset_warn"
    const val KEY_LAST_REPORT = "diag_last_report"
    const val KEY_LAST_AT = "diag_last_at"

    enum class Status { OK, WARN, FAIL, INFO }

    /**
     * v36 — DÜRÜST KADEME (31-ELESTIRI.md · B4).
     *
     * Eskiden "38 yeşil = her şey çalışıyor" izlenimi doğuyordu; oysa
     * kontrollerin çoğu yalnızca "kayıt/dosya/ayar var mı" bakıyordu.
     * Artık her kontrol bir kademeye ait:
     *
     *  • [ASSET]  VARLIK   — dosya/kayıt/ayar mevcut mu (çalışmayı kanıtlamaz)
     *  • [FUNC]   İŞLEV    — kod gerçekten çalıştırılır, sonucu ölçülür
     *  • [E2E]    UÇTAN UCA— kitap aç → oku → sayfa değiştir → devam et zinciri
     *
     * Yeşil lambanın içindeki sayı **yalnız FUNC/E2E** kontrollerini sayar.
     * Böylece "38" gibi şişkin bir sayı yerine gerçekten sınanan özellik sayısı görünür.
     */
    enum class Tier(val label: String) {
        ASSET("varlık"), FUNC("işlev"), E2E("uçtan uca")
    }

    /**
     * Varlık kademesinde kalan kontroller (varsayılan: işlev).
     * Yeni bir kontrol eklerken buraya yazmayı unutma; yeni kontroller
     * varsayılan olarak **işlev** sayılır — yani yeşil sayıya katılır.
     */
    private val ASSET_TITLES = setOf(
        "Ses ayarları", "Ses önbelleği", "Ses çıkışı",
        "Okuma görünümü", "Tema / dil", "Neon ışıltı hızı", "Durum lambaları",
        "Tarama yön düzeltme", "Ağ durumu", "Çevrimdışı okuma",
        "Kasa kilidi", "Kasa medyası", "Hata günlüğü", "Bellek / disk", "Sürüm",
        "Kütüphane boyutu", "Favoriler", "Okuma konumları", "Raflar",
        "Bildirim kanalı", "Hatırlatıcı kayıtları", "Pil optimizasyonu",
        "Zorunlu izinler"
    )

    fun tierOf(title: String): Tier =
        if (title in ASSET_TITLES) Tier.ASSET else Tier.FUNC

    data class Item(
        val group: String,
        val title: String,
        val status: Status,
        val detail: String,
        val tier: Tier = Tier.FUNC
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Arka planda çalıştır, sonucu kaydet, diyaloğu ana thread'de göster. */
    fun runAndShow(ctx: Activity, onDone: (String) -> Unit = {}) {
        scope.launch {
            val items = run(ctx)
            val report = buildReport(items)
            save(ctx, items, report)
            withContext(Dispatchers.Main) {
                try {
                    showResult(ctx, items, report)
                } catch (t: Throwable) {
                    BookStore.logError(ctx, "DiagnosticTestManager.show", t)
                }
                onDone(report)
            }
        }
    }

    /**
     * Sessiz tanılama: ana ekran açılışında çağrılır. Diyalog göstermez,
     * yalnız sonucu kaydeder ve özet döndürür (rozet için).
     */
    /**
     * v34 — AYNI ANDA İKİ TANILAMA ÇALIŞMASIN. Ana sayfa artık 2 dakikada bir
     * arka planda kendiliğinden denetim başlatıyor; üst üste binerse pil ve
     * ağ boşa harcanır. Bu bayrak ikinci çağrıyı sessizce atlar.
     */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    fun runSilently(ctx: Context, onDone: (fail: Int, warn: Int) -> Unit = { _, _ -> }) {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                val items = run(ctx)
                val report = buildReport(items)
                save(ctx, items, report)
                val fail = items.count { it.status == Status.FAIL }
                val warn = items.count { it.status == Status.WARN }
                withContext(Dispatchers.Main) { onDone(fail, warn) }
            } catch (t: Throwable) {
                BookStore.logError(ctx, "DiagnosticTestManager.runSilently", t)
                withContext(Dispatchers.Main) { onDone(-1, -1) }
            } finally {
                running.set(false)
            }
        }
    }

    fun lastFailCount(ctx: Context): Int = AppPrefs.getInt(ctx, KEY_LAST_FAIL, 0)
    fun lastWarnCount(ctx: Context): Int = AppPrefs.getInt(ctx, KEY_LAST_WARN, 0)
    fun lastOkCount(ctx: Context): Int = AppPrefs.getInt(ctx, KEY_LAST_OK, 0)
    fun lastReport(ctx: Context): String = AppPrefs.getString(ctx, KEY_LAST_REPORT, "")
    fun lastRunAt(ctx: Context): Long = AppPrefs.getLong(ctx, KEY_LAST_AT, 0L)

    /** Kaydedilmiş raporu (varsa) diyalogda göster; yoksa yeni test çalıştır. */
    fun showLastOrRun(ctx: Activity) {
        val report = lastReport(ctx)
        if (report.isBlank()) {
            runAndShow(ctx)
            return
        }
        // Kayıtlı rapor + "yeniden test et" seçeneği
        try {
            val b = android.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.diag_title)
                .setMessage(colored(ctx, report))
                .setPositiveButton(R.string.diag_run) { _, _ -> runAndShow(ctx) }
                .setNeutralButton(R.string.diag_copy) { _, _ -> copy(ctx, report) }
                .setNegativeButton(android.R.string.cancel, null)
            val d = b.create()
            d.show()
            styleMessage(d, report)
        } catch (t: Throwable) {
            BookStore.logError(ctx, "DiagnosticTestManager.showLast", t)
        }
    }

    private fun copy(ctx: Context, report: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Umay Tanılama", report))
            android.widget.Toast.makeText(ctx, R.string.diag_done, android.widget.Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "diagCopy", t)
        }
    }

    // ── Tüm kontroller ─────────────────────────────────────────

    suspend fun run(ctx: Context): List<Item> = withContext(Dispatchers.IO) {
        val out = ArrayList<Item>(48)
        val online = networkOk(ctx)

        fun add(group: String, title: String, status: Status, detail: String) {
            out.add(Item(group, title, status, detail, tierOf(title)))
        }
        fun guard(group: String, title: String, block: () -> Item?) {
            try {
                // v36: kademe başlıktan atanır (her çağrıyı tek tek değiştirmeden)
                block()?.let { out.add(it.copy(tier = tierOf(it.title))) }
            } catch (t: Throwable) {
                out.add(
                    Item(
                        group, title, Status.FAIL,
                        "✗ test çöktü: ${t.message}", tierOf(title)
                    )
                )
            }
        }

        // ══ 1. SES / OKUMA MOTORU ══════════════════════════════
        guard("SES & OKUMA", "Yerleşik TTS motoru") {
            val r = testBuiltinTts(ctx)
            Item("SES & OKUMA", "Yerleşik TTS motoru", r.first, r.second)
        }
        guard("SES & OKUMA", "Edge Neural sentez") {
            val r = testEdgeSynthesis(ctx, online)
            Item("SES & OKUMA", "Edge Neural sentez", r.first, r.second)
        }
        guard("SES & OKUMA", "Ses ayarları") {
            val mode = AppPrefs.getString(ctx, AppPrefs.KEY_TTS_MODE, "edge")
            val rate = AppPrefs.getFloat(ctx, AppPrefs.KEY_RATE, 1.0f)
            val pitch = AppPrefs.getFloat(ctx, AppPrefs.KEY_PITCH, 1.0f)
            val okMode = mode in setOf("system", "edge", "builtin", "auto")
            val okRate = rate in 0.3f..3.0f
            val okPitch = pitch in 0.5f..2.0f
            Item(
                "SES & OKUMA", "Ses ayarları",
                if (okMode && okRate && okPitch) Status.OK else Status.FAIL,
                "motor=$mode · hız=${"%.2f".format(rate)}x · perde=${"%.2f".format(pitch)}"
            )
        }
        guard("SES & OKUMA", "Ses önbelleği") {
            val dir = File(ctx.cacheDir, "")
            var bytes = 0L
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith("edge_") || f.name.startsWith("diag_edge")) bytes += f.length()
            }
            val mb = bytes / (1024 * 1024)
            Item(
                "SES & OKUMA", "Ses önbelleği",
                if (mb < 100) Status.OK else Status.WARN,
                "$mb MB kullanımda (sınır 100 MB)${if (mb >= 100) " — temizlik gecikmiş" else ""}"
            )
        }
        guard("SES & OKUMA", "Ses çıkışı") {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            val vol = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
            val max = am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
            val muted = vol == 0
            Item(
                "SES & OKUMA", "Ses çıkışı",
                if (muted) Status.WARN else Status.OK,
                (if (muted) "medya sesi 0 — okuma duyulmaz" else "medya sesi $vol/$max")
            )
        }

        // ══ 2. YAPAY ZEKÂ ══════════════════════════════════════
        guard("YAPAY ZEKÂ", "Gemini API") {
            if (!online) {
                Item("YAPAY ZEKÂ", "Gemini API", Status.WARN, "çevrimdışı — test atlandı (okuma etkilenmez)")
            } else {
                val (s, d) = testGemini(ctx)
                Item("YAPAY ZEKÂ", "Gemini API", s, d)
            }
        }
        guard("YAPAY ZEKÂ", "AI anahtarı") {
            AppPrefs.warmApiCache(ctx)
            val has = !AppPrefs.lastApiKey.isNullOrBlank()
            Item(
                "YAPAY ZEKÂ", "AI anahtarı",
                if (has) Status.OK else Status.WARN,
                if (has) "kayıtlı (model: ${AppPrefs.lastApiModel})" else "anahtar yok — sohbet/özet çalışmaz"
            )
        }

        // v5.10 (F2) — konu bazlı geçmişin GERÇEKTEN yazılıp geri okunabildiğini
        // test eder (dosya varlığı değil, yaz→oku→sil turu).
        guard("YAPAY ZEKÂ", "Sohbet geçmişi") {
            val probeTitle = "__diag_probe__"
            val wrote = try {
                val t = ChatHistoryStore.startTopic(ctx, probeTitle, "__diag__")
                ChatHistoryStore.append(ctx, t.id, true, "ping")
                val back = ChatHistoryStore.load(ctx, t.id)
                val ok = back != null && back.messages.any { it.text == "ping" }
                ChatHistoryStore.delete(ctx, t.id)
                ok
            } catch (t: Throwable) {
                BookStore.logError(ctx, "diag.chatHistory", t)
                false
            }
            val (topics, msgs) = try { ChatHistoryStore.stats(ctx) } catch (_: Throwable) { 0 to 0 }
            Item(
                "YAPAY ZEKÂ", "Sohbet geçmişi",
                if (!wrote) Status.WARN else if (topics == 0) Status.INFO else Status.OK,
                if (!wrote) "yazma/okuma başarısız — geçmiş tutulmuyor"
                else "konu bazlı kalıcı · $topics konu · $msgs mesaj"
            )
        }

        // v5.11 — AKILLI ARAMA: "her sayfada geçen kelime" ile "cevabı taşıyan
        // sayfa" ayrımı gerçekten yapılıyor mu? Sabit örnekle ölçülür.
        guard("YAPAY ZEKÂ", "Kitap arama motoru") {
            val probe = listOf(
                "Veri veri veri. Veri tabanı veri yapıları veri akışı. Veri veri.",
                "İkili arama ağacında her düğümün solundaki değerler daha küçüktür.",
                "Sıralama algoritmaları arasında kabarcık sıralama en basitidir."
            )
            val res = try {
                BookRetriever.passages(probe, "ikili arama ağacı", 2000)
            } catch (t: Throwable) {
                BookStore.logError(ctx, "diag.retriever", t)
                emptyList()
            }
            val ok = res.isNotEmpty() && res.first().page == 1
            Item(
                "YAPAY ZEKÂ", "Kitap arama motoru",
                if (ok) Status.OK else Status.WARN,
                if (ok) "IDF + cümle puanlama çalışıyor · doğru sayfayı seçti" +
                    " (${res.size} parça)"
                else "beklenmeyen sıralama (sayfa ${res.firstOrNull()?.page ?: -1})"
            )
        }

        // ══ 2b. OKUMA KALİTESİ (v31) ═══════════════════════════
        // Çeviri (EN→TR) gerçekten çalışıyor mu? Sabit bir İngilizce cümle
        // çevrilir; sonuç çevrilmemişse veya Türkçe iz taşımıyorsa uyarı.
        guard("OKUMA KALİTESİ", "Çeviri (EN→TR)") {
            val src = "The binary search tree stores smaller values on the left side."
            // guard bloğu suspend değil → IO thread'inde runBlocking güvenli
            val out = try {
                kotlinx.coroutines.runBlocking {
                    if (!TrTranslator.isModelDownloaded() && !online) src
                    else TrTranslator.translate(src)
                }
            } catch (_: Throwable) {
                src
            }
            val changed = out.trim().isNotEmpty() && out.trim() != src.trim()
            val turkish = out.any { it in "çğıöşüÇĞİÖŞÜ" } ||
                out.lowercase(Locale.ROOT).contains(" ve ") ||
                out.lowercase(Locale.ROOT).contains(" bir ")
            Item(
                "OKUMA KALİTESİ", "Çeviri (EN→TR)",
                if (changed && turkish) Status.OK
                else if (!online) Status.WARN else Status.FAIL,
                if (changed && turkish) "çalışıyor · \"${out.take(60)}…\""
                else if (!online) "çevrimdışı — çeviri modeli indirilemedi (okuma etkilenmez)"
                else "çeviri uygulanmadı — model indirilememiş olabilir"
            )
        }

        // Arapça OCR: önbellek gerçekten yazılıyor mu + Arapça ses yönlendirmesi.
        guard("OKUMA KALİTESİ", "Arapça OCR + ses") {
            val books = try { BookStore.loadBooks(ctx) } catch (_: Throwable) { emptyList() }
            var cachedPages = 0
            var cachedBooks = 0
            for (b in books) {
                val dir = File(BookStore.booksDir(ctx), b.id)
                val n = dir.listFiles()?.count { it.name.startsWith("ocr_") } ?: 0
                if (n > 0) { cachedBooks++; cachedPages += n }
            }
            val voice = try {
                EdgeTts.voiceFor("auto", "بِسْمِ اللّٰهِ الرَّحْمٰنِ الرَّح۪يمِ")
            } catch (_: Throwable) {
                ""
            }
            val voiceOk = voice.startsWith("ar-")
            Item(
                "OKUMA KALİTESİ", "Arapça OCR + ses",
                if (voiceOk && cachedPages > 0) Status.OK else if (voiceOk) Status.INFO else Status.FAIL,
                if (!voiceOk) "Arapça metin Arapça sese yönlenmiyor (${voice.ifBlank { "?" }})"
                else if (cachedPages == 0) "ses yönlendirmesi hazır · henüz OCR önbelleği yok (AR modu kapalı olabilir)"
                else "ses=$voice · $cachedBooks kitapta $cachedPages sayfa OCR önbelleği"
            )
        }

        // Bozuk font süzgeci: Kur'an meali tipi çöp metin gerçekten temizleniyor mu?
        // v33 — kod satırı süzgeci (kod sayfalarında metin okunur, kod atlanır)
        guard("OKUMA KALİTESİ", "Kod satırı süzgeci") {
            val code = "public static void main(String[] args) {"
            val code2 = "System.out.println(\"x\");"
            val comment = "// Function add two polynomial numbers"
            val prose = "Veri yapıları dersinde bağlı liste konusunu işledik."
            val ok = TextUtil.looksLikeCodeLine(code) &&
                TextUtil.looksLikeCodeLine(code2) &&
                TextUtil.looksLikeCodeLine(comment) &&
                !TextUtil.looksLikeCodeLine(prose)
            Item(
                "OKUMA KALİTESİ", "Kod satırı süzgeci",
                if (ok) Status.OK else Status.FAIL,
                if (ok) "kod satırları seslendirilmez, düz metin okunur"
                else "sınıflandırma hatalı (kod/metin)"
            )
        }
        // v33 — Arapça normalizasyon (sunum biçimi → temel harf)
        guard("OKUMA KALİTESİ", "Arapça normalizasyon") {
            val presentation = "\uFEA9\uFEEE\uFEA1\uFEDF"   // sunum biçimi örnek
            val fixed = TextUtil.normalizeArabic(presentation)
            val tg = "\u0640"
            val tatweelGone = !TextUtil.normalizeArabic("\u0645\u0640\u0631").contains(tg)
            val ok = fixed.isNotEmpty() && fixed.none { it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' } && tatweelGone
            Item(
                "OKUMA KALİTESİ", "Arapça normalizasyon",
                if (ok) Status.OK else Status.FAIL,
                if (ok) "sunum biçimi ve tatweel temizlendi (NFKC)"
                else "normalizasyon uygulanmadı (Arapça heceleme bozulur)"
            )
        }
        guard("OKUMA KALİTESİ", "Bozuk metin süzgeci") {
            val broken = "recek, 8 fakat 0.,f ::; r� > Q.1. .), r� �j ts.)... 6: . :; ;Jıj"
            val cleaned = TextUtil.dropGarbageWords(broken)
            val ok = cleaned.contains("fakat") &&
                !cleaned.contains("::;") && !cleaned.contains("Q.1.")
            val enCode = "void sort(int a, int b) { if (a > b) { swap(a, b); } }"
            val codeKept = TextUtil.dropGarbageWords(enCode).contains("sort")
            Item(
                "OKUMA KALİTESİ", "Bozuk metin süzgeci",
                if (ok && codeKept) Status.OK else Status.FAIL,
                if (ok && codeKept) "çöp sözcükler atılıyor, kod/metin korunuyor"
                else "süzgeç beklenen sonucu vermedi (çöp=${!ok}, kod=${!codeKept})"
            )
        }

        // ══ 3. KÜTÜPHANE & VERİ ════════════════════════════════
        guard("KÜTÜPHANE", "Veritabanı") {
            val (s, d) = testLibraryDb(ctx)
            Item("KÜTÜPHANE", "Veritabanı", s, d)
        }
        guard("KÜTÜPHANE", "Kitap dosyaları") {
            val books = BookStore.loadBooks(ctx)
            var missing = 0
            var emptyText = 0
            for (b in books) {
                if (!BookStore.sourceFile(ctx, b.id).exists()) missing++
                if (b.pageCount > 0 && BookStore.pageFile(ctx, b.id, 0).length() == 0L) emptyText++
            }
            val status = when {
                books.isEmpty() -> Status.INFO
                missing > 0 -> Status.WARN
                else -> Status.OK
            }
            Item(
                "KÜTÜPHANE", "Kitap dosyaları", status,
                "${books.size} kitap · kaynağı eksik: $missing · metni henüz çıkarılmamış: $emptyText"
            )
        }
        guard("KÜTÜPHANE", "Kütüphane boyutu") {
            val dir = BookStore.booksDir(ctx)
            var size = 0L
            dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
            Item(
                "KÜTÜPHANE", "Kütüphane boyutu", Status.INFO,
                "${size / (1024 * 1024)} MB · ${dir.listFiles()?.count { it.isDirectory } ?: 0} kitap klasörü"
            )
        }
        guard("KÜTÜPHANE", "Raflar") {
            val shelves = ShelfManager.shelvesOf(ctx)
            val ids = BookStore.loadBooks(ctx).map { it.id }.toSet()
            val ghost = shelves.sumOf { s -> s.bookIds.count { it !in ids } }
            Item(
                "KÜTÜPHANE", "Raflar",
                if (ghost > 0) Status.WARN else Status.OK,
                "${shelves.size} raf · silinmiş kitaba bağlı kayıt: $ghost"
            )
        }
        guard("KÜTÜPHANE", "Favoriler") {
            val ids = BookStore.loadBooks(ctx).map { it.id }.toSet()
            val fav = FavoritesStore.ids(ctx)
            val ghost = fav.count { it !in ids }
            Item(
                "KÜTÜPHANE", "Favoriler",
                if (ghost > 0) Status.WARN else Status.OK,
                "${fav.size} favori${if (ghost > 0) " · $ghost geçersiz" else ""}"
            )
        }
        guard("KÜTÜPHANE", "Okuma konumları") {
            val books = BookStore.loadBooks(ctx)
            val withPos = books.count { (AppPrefs.loadProgress(ctx, it.id)?.first ?: 0) > 0 }
            Item("KÜTÜPHANE", "Okuma konumları", Status.OK, "$withPos kitapta kayıtlı konum")
        }
        // v24 — ders video rafları (yerel dosyalar gerçekten duruyor mu?)
        guard("KÜTÜPHANE", "Video rafları") {
            val courses = try { CourseStore.courses(ctx) } catch (_: Throwable) { emptyList() }
            var local = 0
            var link = 0
            var missing = 0
            courses.forEach { c ->
                CourseVideoStore.videos(ctx, c.id).forEach { v ->
                    if (v.isLocal) {
                        local++
                        val f = CourseVideoStore.localFile(ctx, v.source)
                        if (!f.exists() || f.length() == 0L) missing++
                    } else link++
                }
            }
            Item(
                "KÜTÜPHANE", "Video rafları",
                if (missing > 0) Status.WARN else Status.OK,
                "$local yerel · $link bağlantı" +
                    (if (missing > 0) " · eksik dosya: $missing" else "")
            )
        }

        // v33 — ders SES KAYITLARI rafı (dosyalar gerçekten duruyor mu?)
        guard("KÜTÜPHANE", "Ses kayıtları rafı") {
            val courses = try { CourseStore.courses(ctx) } catch (_: Throwable) { emptyList() }
            var clips = 0
            var missing = 0
            courses.forEach { c ->
                CourseAudioStore.clips(ctx, c.id).forEach { clip ->
                    clips++
                    if (CourseAudioStore.existingFile(ctx, clip) == null) missing++
                }
            }
            Item(
                "KÜTÜPHANE", "Ses kayıtları rafı",
                if (missing > 0) Status.WARN else Status.OK,
                "$clips kayıt" + (if (missing > 0) " · eksik dosya: $missing" else " · orijinaller korunuyor")
            )
        }
        // v33 — okunma yüzdesi tutarlılığı (sayfa atlayınca %99 hatası)
        guard("KÜTÜPHANE", "Okunma yüzdesi") {
            val books = BookStore.loadBooks(ctx)
            var checked = 0
            var bad = 0
            books.forEach { b ->
                if (b.pageCount <= 0) return@forEach
                val n = ReadProgressStore.readCount(ctx, b.id, b.pageCount)
                val pct = ReadProgressStore.percent(ctx, b.id, b.pageCount)
                if (n > 0) {
                    checked++
                    val expected = (n * 100) / b.pageCount
                    if (pct != expected || pct > 100) bad++
                }
            }
            Item(
                "KÜTÜPHANE", "Okunma yüzdesi",
                if (bad > 0) Status.FAIL else Status.OK,
                "$checked kitap denetlendi · okunan sayfa / toplam sayfa" +
                    (if (bad > 0) " · tutarsız: $bad" else "")
            )
        }

        // ══ 4. DERSLER & AKADEMİK ══════════════════════════════
        guard("DERSLER", "Ders kayıtları") {
            val list = CourseStore.courses(ctx)
            Item("DERSLER", "Ders kayıtları", Status.OK, "${list.size} ders")
        }
        guard("DERSLER", "Sınavlar / notlar") {
            val exams = CourseStore.allExams(ctx)
            val graded = GradeStore.overview(ctx).count { it.second != null }
            Item(
                "DERSLER", "Sınavlar / notlar", Status.INFO,
                "${exams.size} sınav kaydı · ortalaması hesaplanan ders: $graded"
            )
        }
        guard("DERSLER", "Ders programı") {
            val rows = CourseStore.slotsOf(ctx)
            Item("DERSLER", "Ders programı", Status.INFO, "${rows.size} ders saati")
        }
        guard("DERSLER", "Ödev / projeler") {
            val hw = CourseStore.allHomework(ctx)
            Item("DERSLER", "Ödev / projeler", Status.INFO, "${hw.size} kayıt")
        }
        guard("DERSLER", "Not ortalaması (ANO/AGNO)") {
            val (s, d) = testGrades(ctx)
            Item("DERSLER", "Not ortalaması (ANO/AGNO)", s, d)
        }

        // ══ 5. BİLDİRİM & HATIRLATICI ══════════════════════════
        guard("BİLDİRİM", "Bildirim kanalı") {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager
            val ch = nm?.getNotificationChannel(ReadService.CHANNEL_ID)
            if (ch == null) Item("BİLDİRİM", "Bildirim kanalı", Status.FAIL, "kanal yok (okuma bildirimi çıkmaz)")
            else Item(
                "BİLDİRİM", "Bildirim kanalı",
                if (nm.areNotificationsEnabled()) Status.OK else Status.FAIL,
                if (nm.areNotificationsEnabled()) "kanal hazır · önem=${ch.importance}"
                else "kanal var ama uygulama bildirimleri kapalı"
            )
        }
        guard("BİLDİRİM", "Hatırlatıcı kayıtları") {
            val slots = CourseStore.slotsOf(ctx)
            val withReminder = slots.count { it.remindMin > 0 }
            val exams = CourseStore.allExams(ctx)
            Item(
                "BİLDİRİM", "Hatırlatıcı kayıtları", Status.INFO,
                "ders alarmı: $withReminder/${slots.size} · sınav: ${exams.count { it.exam.alarm }}"
            )
        }
        guard("BİLDİRİM", "Pil optimizasyonu") {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val ignoring = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
            // v21.1 — HyperOS/MIUI'de "Kısıtlama yok" seçimi deviceidle
            // whitelist'ine YAZILMAZ; bu yüzden muafiyet yokluğu tek başına
            // hata/uyarı sayılmaz → bilgi satırı.
            Item(
                "BİLDİRİM", "Pil optimizasyonu",
                if (ignoring) Status.OK else Status.INFO,
                if (ignoring) "muaf"
                else "sistem listesinde yok — HyperOS'ta Pil › Kısıtlama yok seçili olmalı"
            )
        }

        // ══ 6. İZİNLER ═════════════════════════════════════════
        guard("İZİNLER", "Zorunlu izinler") {
            val problems = mutableListOf<String>()
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) problems.add("bildirim")
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                if (am?.canScheduleExactAlarms() != true) problems.add("tam alarm")
            }
            if (android.os.Build.VERSION.SDK_INT >= 30 &&
                !android.os.Environment.isExternalStorageManager()
            ) problems.add("tüm dosyalara erişim")
            Item(
                "İZİNLER", "Zorunlu izinler",
                if (problems.isEmpty()) Status.OK else Status.WARN,
                if (problems.isEmpty()) "hepsi verildi"
                else "eksik: ${problems.joinToString(", ")} (Ayarlar › İzinler)"
            )
        }

        // ══ 7. GÖRÜNÜM & TEMA ══════════════════════════════════
        guard("GÖRÜNÜM", "Tema / dil") {
            val theme = AppPrefs.getString(ctx, AppPrefs.KEY_THEME_MODE, "night")
            val lang = LangHelper.currentLang(ctx)
            val ok = theme in setOf("night", "day", "system")
            Item(
                "GÖRÜNÜM", "Tema / dil",
                if (ok) Status.OK else Status.WARN,
                "tema=$theme · dil=$lang"
            )
        }
        // v33 — neon çerçeve yanıp sönme hızı (dakikada 2-3 ışıltı olmalı)
        guard("GÖRÜNÜM", "Neon ışıltı hızı") {
            val perMinute = 60_000.0 / NeonFrameDrawable.CYCLE_MS
            Item(
                "GÖRÜNÜM", "Neon ışıltı hızı",
                if (perMinute in 1.5..4.0) Status.OK else Status.WARN,
                "dakikada %.1f ışıltı (%.0f sn döngü)".format(perMinute, NeonFrameDrawable.CYCLE_MS / 1000)
            )
        }
        // v36.4 — ANİMASYON ÖLÇEĞİ (bugün bulunan gerçek kök neden)
        // Cihazda animator_duration_scale=0 iken ObjectAnimator hiç oynamaz,
        // son değere atlar: lambalar "soluk" kalıyordu. Artık kod zamanlayıcıyla
        // çalışıyor; yine de kullanıcı bilsin diye raporda görünür.
        guard("GÖRÜNÜM", "Animasyon ölçeği") {
            val scale = try {
                android.provider.Settings.Global.getFloat(
                    ctx.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                )
            } catch (_: Throwable) {
                1f
            }
            Item(
                "GÖRÜNÜM", "Animasyon ölçeği", Status.INFO,
                if (scale <= 0f)
                    "kapalı (0x) — lamba yanıp sönmesi/ışıltı zamanlayıcıyla çalışır; istersen Geliştirici seçenekleri › Animasyon ölçeği 1x"
                else "%.1fx · hareketler sistem animasyonuyla uyumlu".format(scale)
            )
        }
        // v33 — durum lambaları rozeti
        guard("GÖRÜNÜM", "Durum lambaları") {
            val fail = lastFailCount(ctx)
            val warn = lastWarnCount(ctx)
            val last = lastRunAt(ctx)
            Item(
                "GÖRÜNÜM", "Durum lambaları",
                if (last <= 0L) Status.INFO else Status.OK,
                if (last <= 0L) "henüz tanılama yok (yeşil lamba ilk açılışta yanar)"
                else "kırmızı=$fail · sarı=$warn · profıl kartında görünür"
            )
        }
        // v33 — taranan fotoğraf yön düzeltme (gerçek döndürme testi)
        guard("GÖRÜNÜM", "Tarama yön düzeltme") {
            val src = android.graphics.Bitmap.createBitmap(40, 20, android.graphics.Bitmap.Config.ARGB_8888)
            val rot = ScanUpright.rotate(src, 90)
            val ok = rot.width == 20 && rot.height == 40
            try { src.recycle() } catch (_: Throwable) { }
            try { if (rot !== src) rot.recycle() } catch (_: Throwable) { }
            Item(
                "GÖRÜNÜM", "Tarama yön düzeltme",
                if (ok) Status.OK else Status.FAIL,
                if (ok) "90°/180°/270° döndürme + OCR yön seçimi hazır"
                else "döndürme sonucu hatalı"
            )
        }
        guard("GÖRÜNÜM", "Okuma görünümü") {
            val size = AppPrefs.getInt(ctx, "reader_font_size", 18)
            Item(
                "GÖRÜNÜM", "Okuma görünümü",
                if (size in 10..40) Status.OK else Status.WARN,
                "punto=$size"
            )
        }

        // ══ 8. ÇEVRİMDIŞI & GÜVENLİK ═══════════════════════════
        guard("ÇEVRİMDIŞI", "Ağ durumu") {
            Item(
                "ÇEVRİMDIŞI", "Ağ durumu",
                Status.INFO,
                if (online) "çevrimiçi — Edge Neural + Gemini kullanılabilir"
                else "çevrimdışı — yerleşik TTS ile okuma çalışır"
            )
        }
        guard("ÇEVRİMDIŞI", "Çevrimdışı okuma") {
            val builtinOk = AppPrefs.getBool(ctx, "offline_ok", true)
            Item(
                "ÇEVRİMDIŞI", "Çevrimdışı okuma",
                if (builtinOk) Status.OK else Status.WARN,
                "çıkarılmış sayfa metinleri cihazda; yerleşik motor çevrimdışı çalışır"
            )
        }
        guard("ÇEVRİMDIŞI", "Kasa kilidi") {
            val hasPin = SecureVault.hasPin(ctx)
            Item(
                "ÇEVRİMDIŞI", "Kasa kilidi",
                if (hasPin) Status.OK else Status.INFO,
                if (hasPin) "kasa PIN korumalı" else "kasa kurulmamış"
            )
        }
        // v24 — kasa medya rafları + şifreli dosya bütünlüğü
        guard("ÇEVRİMDIŞI", "Kasa medyası") {
            val items = try { SecureVault.items(ctx) } catch (_: Throwable) { mutableListOf() }
            val missing = items.count { !File(SecureVault.vaultDir(ctx), "${it.id}.enc").exists() }
            val images = items.count { it.kind == "image" }
            val videos = items.count { it.kind == "video" }
            val docs = items.count { it.kind == "doc" || it.kind == "book" }
            Item(
                "ÇEVRİMDIŞI", "Kasa medyası",
                if (missing > 0) Status.WARN else Status.OK,
                "$images resim · $videos video · $docs belge" +
                    (if (missing > 0) " · eksik şifreli dosya: $missing" else " · AES-256")
            )
        }
        // v25 (denetim) — yanlış PIN kilidi + parmak izi sarmalı durumu
        guard("ÇEVRİMDIŞI", "Kasa giriş denemeleri") {
            val fails = try { SecureVault.failCount(ctx) } catch (_: Throwable) { 0 }
            val wait = try { SecureVault.lockRemainingMs(ctx) } catch (_: Throwable) { 0L }
            val bioWrapped = try {
                ctx.getSharedPreferences("umay_vault", Context.MODE_PRIVATE)
                    .getString("pin_wrapped", null) != null
            } catch (_: Throwable) { false }
            Item(
                "ÇEVRİMDIŞI", "Kasa giriş denemeleri",
                if (wait > 0L) Status.WARN else Status.OK,
                if (wait > 0L) "kilitli — ${(wait + 999L) / 1000L} sn bekleme · yanlış: $fails"
                else "yanlış deneme: $fails · artan bekleme açık" +
                    (if (bioWrapped) " · parmak izi kayıtlı" else "")
            )
        }

        // v25 (C8) — "Umay ile aç / paylaş" menüsü kaydı gerçekten görünüyor mu?
        guard("ÇEVRİMDIŞI", "Paylaş menüsü (Umay ile aç)") {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse("content://x/y.pdf"))
            }
            val handlers = ctx.packageManager
                .queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
            val self = handlers.contains(ctx.packageName)
            Item(
                "ÇEVRİMDIŞI", "Paylaş menüsü (Umay ile aç)",
                if (self) Status.OK else Status.FAIL,
                if (self) "PDF/doküman paylaşımında Umay seçilebiliyor (ACTION_SEND)"
                else "paylaş menüsünde görünmüyor — manifest niyet filtresi eksik"
            )
        }

        guard("ÇEVRİMDIŞI", "Hata günlüğü") {
            val log = File(ctx.filesDir, "umay_error.log")
            val size = if (log.exists()) log.length() else 0L
            val recent = if (log.exists() && log.length() > 0) {
                log.readLines().takeLast(60).count { it.contains("E/") || it.contains("ERROR") }
            } else 0
            Item(
                "ÇEVRİMDIŞI", "Hata günlüğü",
                if (recent > 12) Status.WARN else Status.OK,
                "son 60 satırda $recent hata · dosya ${size / 1024} KB"
            )
        }

        // ══ 9. SİSTEM KAYNAKLARI ═══════════════════════════════
        guard("SİSTEM", "Bellek / disk") {
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            val stat = android.os.StatFs(ctx.filesDir.path)
            val freeMb = stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)
            Item(
                "SİSTEM", "Bellek / disk",
                if (freeMb < 200) Status.WARN else Status.OK,
                "RAM $usedMb/$maxMb MB · boş disk $freeMb MB"
            )
        }
        guard("SİSTEM", "Sürüm") {
            Item(
                "SİSTEM", "Sürüm", Status.INFO,
                "${BuildConfig.VERSION_NAME} (kod ${BuildConfig.VERSION_CODE})"
            )
        }

        out
    }

    // ── Alt testler ────────────────────────────────────────────

    private fun testBuiltinTts(ctx: Context): Pair<Status, String> {
        return try {
            var result: Pair<Status, String> = Status.WARN to "⏱ motor yanıt vermedi"
            val latch = java.util.concurrent.CountDownLatch(1)
            var tts: TextToSpeech? = null
            tts = TextToSpeech(ctx.applicationContext) { status ->
                result = when (status) {
                    TextToSpeech.SUCCESS -> {
                        val t = tts
                        if (t == null) Status.OK to "hazır"
                        else {
                            val langRes = t.setLanguage(Locale("tr", "TR"))
                            if (langRes == TextToSpeech.LANG_MISSING_DATA ||
                                langRes == TextToSpeech.LANG_NOT_SUPPORTED
                            ) Status.WARN to "Türkçe ses verisi eksik (Ayarlar'dan indirin)"
                            else Status.OK to "hazır (Türkçe destekli)"
                        }
                    }
                    TextToSpeech.ERROR -> Status.FAIL to "motor başlatılamadı"
                    else -> Status.WARN to "bilinmeyen durum ($status)"
                }
                latch.countDown()
            }
            val ok = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            try { tts?.shutdown() } catch (_: Throwable) {}
            if (ok) result else Status.WARN to "5 sn içinde yanıt yok (motor meşgul)"
        } catch (t: Throwable) {
            Status.FAIL to "istisna: ${t.message}"
        }
    }

    private fun testEdgeSynthesis(ctx: Context, online: Boolean): Pair<Status, String> {
        if (!online) return Status.WARN to "çevrimdışı — atlandı (yerleşik motor kullanılır)"
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    try {
                        val out = File(ctx.cacheDir, "diag_edge_test.mp3")
                        val voice = EdgeTts.voiceFor(
                            AppPrefs.getString(ctx, AppPrefs.KEY_VOICE, "female"), "test"
                        )
                        EdgeTts.synthesize("Umay tanılama testi.", voice, 0, 1f, out)
                        val kb = out.length() / 1024
                        out.delete()
                        if (kb > 2) Status.OK to "çalışıyor ($kb KB ses üretildi)"
                        else Status.FAIL to "ses dosyası boş (servis yanıt vermedi)"
                    } catch (e: Exception) {
                        Status.FAIL to "hata: ${e.message?.take(90) ?: "bilinmeyen"}"
                    }
                } ?: (Status.FAIL to "15 sn içinde yanıt gelmedi")
            }
        } catch (t: Throwable) {
            Status.FAIL to "istisna: ${t.message?.take(90)}"
        }
    }

    private fun testGemini(ctx: Context): Pair<Status, String> {
        AppPrefs.warmApiCache(ctx)
        val key = AppPrefs.lastApiKey
        val model = AppPrefs.lastApiModel
        if (key.isNullOrBlank()) return Status.FAIL to "API anahtarı yok (Ayarlar'dan girin)"
        val out = arrayOfNulls<Pair<Status, String>>(1)
        val latch = java.util.concurrent.CountDownLatch(1)
        Thread({
            try {
                val answer = ApiClient.ask("Merhaba, test mesajı. Tek kelime cevap ver.", emptyList())
                out[0] = Status.OK to "anahtar çalışıyor (model: $model · yanıt: ${answer.take(30)}…)"
            } catch (e: Exception) {
                out[0] = Status.FAIL to "AI hatası: ${e.message?.take(120) ?: "bilinmeyen"}"
            } finally {
                latch.countDown()
            }
        }, "umay-diag-ai").apply { isDaemon = true }.start()
        val done = latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        return if (done) out[0] ?: (Status.WARN to "bilinmeyen sonuç")
        else Status.WARN to "20 sn içinde yanıt gelmedi — ağ/anahtar"
    }

    private fun testLibraryDb(ctx: Context): Pair<Status, String> {
        return try {
            val db = LibraryDb.get(ctx).readableDatabase
            if (db == null || !db.isOpen) return Status.FAIL to "açılamadı (yedek JSON'a düşülecek)"
            fun count(table: String): Int = try {
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                    if (it.moveToFirst()) it.getInt(0) else -1
                }
            } catch (_: Throwable) { -1 }
            val books = count("books")
            val courses = count("courses")
            val docs = count("documents")
            val exams = count("exams")
            val hw = count("homework")
            val sch = count("schedule")
            val grades = count("grades")
            val bad = listOf(books, courses, docs, exams, hw, sch, grades).count { it < 0 }
            val detail = "kitap=$books · ders=$courses · belge=$docs · sınav=$exams · " +
                "ödev=$hw · program=$sch · not=$grades"
            if (bad > 0) Status.WARN to "$detail\n  ⚠ $bad tablo okunamadı"
            else Status.OK to detail
        } catch (t: Throwable) {
            Status.FAIL to "istisna: ${t.message}"
        }
    }

    private fun testGrades(ctx: Context): Pair<Status, String> {
        return try {
            val rows = GradeStore.overview(ctx)
            val credits = rows.sumOf { it.third }
            val agno = GradeStore.overallAgno(ctx)
            val avgTxt = GradeStore.format2(agno) ?: "—"
            Status.OK to "${rows.size} ders · toplam kredi=$credits · AGNO=$avgTxt"
        } catch (t: Throwable) {
            Status.FAIL to "istisna: ${t.message}"
        }
    }

    private fun networkOk(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Throwable) { false }

    // ── Rapor ──────────────────────────────────────────────────

    private fun save(ctx: Context, items: List<Item>, report: String) {
        try {
            AppPrefs.putInt(ctx, KEY_LAST_FAIL, items.count { it.status == Status.FAIL })
            // v36.2 — SARİ LAMBA YALNIZ İŞLEV UYARISI: “varlık” kontrollerinin
            // uyarısı (ör. hata günlüğü satır sayısı) lambayı yakmıyordu ama
            // yakıyordu; geçici gürültü yüzünden 2 dakika sarı yanıp sönüyordu.
            // Varlık uyarıları raporda görünmeye devam eder, lambayı yakmaz.
            AppPrefs.putInt(
                ctx, KEY_LAST_WARN,
                items.count { it.status == Status.WARN && it.tier != Tier.ASSET }
            )
            AppPrefs.putInt(
                ctx, KEY_LAST_ASSET_WARN,
                items.count { it.status == Status.WARN && it.tier == Tier.ASSET }
            )
            // v36 — yeşil lamba YALNIZ gerçekten sınanan (işlev/uçtan uca) sayısını gösterir
            AppPrefs.putInt(
                ctx, KEY_LAST_OK,
                items.count { it.status == Status.OK && it.tier != Tier.ASSET }
            )
            AppPrefs.putInt(
                ctx, KEY_LAST_ASSET,
                items.count { it.status == Status.OK && it.tier == Tier.ASSET }
            )
            AppPrefs.putString(ctx, KEY_LAST_REPORT, report)
            AppPrefs.putLong(ctx, KEY_LAST_AT, System.currentTimeMillis())
        } catch (t: Throwable) {
            BookStore.logError(ctx, "diagSave", t)
        }
    }

    fun buildReport(items: List<Item>): String {
        val sb = StringBuilder()
        val now = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", Locale("tr", "TR"))
            .format(java.util.Date())
        val fail = items.count { it.status == Status.FAIL }
        val warn = items.count { it.status == Status.WARN }
        val ok = items.count { it.status == Status.OK && it.tier != Tier.ASSET }
        val asset = items.count { it.status == Status.OK && it.tier == Tier.ASSET }
        val e2e = items.count { it.tier == Tier.E2E }
        sb.appendLine("═══ UMAY ÖZELLİK KÜNYESİ ═══")
        sb.appendLine("Tarih: $now")
        sb.appendLine("Sonuç: ✓ $ok işlev geçti · ! $warn uyarı · ✗ $fail hata")
        val assetWarn = items.count { it.status == Status.WARN && it.tier == Tier.ASSET }
        sb.appendLine("Kademe: varlık $asset (yeşil sayıya katılmaz) · uçtan uca $e2e")
        if (assetWarn > 0) {
            sb.appendLine(
                "Not: $assetWarn varlık uyarısı var (dosya/kayıt/ayar düzeyi) — sarı lambayı yakmaz."
            )
        }
        if (e2e == 0) {
            sb.appendLine(
                "Not: uçtan uca (kitap aç→oku→sayfa değiştir) testi henüz otomatik değil."
            )
        }
        sb.appendLine()
        var group = ""
        for (it in items) {
            if (it.group != group) {
                group = it.group
                sb.appendLine()
                sb.appendLine("── $group ──")
            }
            val mark = when (it.status) {
                Status.OK -> "✓"
                Status.WARN -> "!"
                Status.FAIL -> "✗"
                Status.INFO -> "•"
            }
            sb.appendLine("$mark [${it.tier.label}] ${it.title}: ${it.detail}")
        }
        sb.appendLine()
        sb.appendLine("═══ RAPOR SONU ═══")
        return sb.toString()
    }

    fun colored(ctx: Context, report: String): CharSequence = spannable(ctx, report)

    private fun spannable(ctx: Context, report: String): android.text.SpannableString {
        val s = android.text.SpannableString(report)
        val red = 0xFFFF5252.toInt()
        val orange = 0xFFFFB300.toInt()
        val green = 0xFF3DDC84.toInt()
        var idx = 0
        for (line in report.split("\n")) {
            val start = idx
            val end = idx + line.length
            when {
                line.trimStart().startsWith("✗") -> s.setSpan(
                    android.text.style.ForegroundColorSpan(red), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                line.trimStart().startsWith("!") -> s.setSpan(
                    android.text.style.ForegroundColorSpan(orange), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                line.trimStart().startsWith("✓") -> s.setSpan(
                    android.text.style.ForegroundColorSpan(green), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                line.trimStart().startsWith("──") -> {
                    s.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            idx = end + 1
        }
        return s
    }

    private fun styleMessage(dialog: android.app.AlertDialog, report: String) {
        try {
            dialog.findViewById<android.widget.TextView>(android.R.id.message)?.apply {
                text = spannable(context, report)
                setTextIsSelectable(true)
                textSize = 12f
            }
        } catch (_: Throwable) {
        }
    }

    private fun showResult(ctx: Activity, items: List<Item>, report: String) {
        try {
            val d = android.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.diag_title)
                .setMessage(colored(ctx, report))
                .setPositiveButton(R.string.diag_copy) { _, _ -> copy(ctx, report) }
                .setNeutralButton(R.string.diag_run) { _, _ -> runAndShow(ctx) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            d.show()
            styleMessage(d, report)
        } catch (t: Throwable) {
            BookStore.logError(ctx, "DiagnosticTestManager.showResult", t)
        }
    }
}
