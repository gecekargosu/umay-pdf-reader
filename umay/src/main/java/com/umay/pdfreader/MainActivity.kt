package com.umay.pdfreader

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.umay.pdfreader.databinding.ActivityMainBinding

/**
 * Kütüphane ekranı: PDF içeri aktarma, kitap listesi,
 * "Kaldığın yerden devam edelim mi?" akışı.
 */
class MainActivity : AppCompatActivity(), ExtractionBus.Listener {

    private lateinit var binding: ActivityMainBinding
    /** v6 — deep link tek seferlik işlensin. */
    private var deepLinkHandled = false
    private var books: List<Book> = emptyList()

    /** v5.6 — "EN SON OKUNAN" kartında gezilen kitaplar (en yeni → eski). */
    private var heroBooks: List<Book> = emptyList()
    /** v5.6 — Kahraman kartta seçili kitabın `heroBooks` içindeki indeksi. */
    private var heroIndex = 0

    /** v5.6 — Raf adları (çizimdeki "RAF ADI" bandı; emoji yok, tutarlı tipografi). */
    private val shelfTitles = listOf(
        "DERS KİTAPLARI",
        "DERS SLAYLARI & NOTLAR",
        "CİHAZ TARAMA & DOKÜMANLAR"
    )
    // v17 (ONARIM): binding onCreate'de initialize edilir; bu listeler
    // property initializer'da erişilseydi açılışta UninitializedProperty-
    // Exception ile çökertirdi. by lazy + ViewBinding camelCase adlar.
    private val shelfLists: List<androidx.recyclerview.widget.RecyclerView> by lazy {
        listOf(
            binding.shelfDersKitaplariList,
            binding.shelfDersSlaytNotlarList,
            binding.shelfCihangTaramalariList
        )
    }
    private val shelfViews: List<android.widget.LinearLayout> by lazy {
        listOf(
            binding.shelfDersKitaplari,
            binding.shelfDersSlaytNotlar,
            binding.shelfCihangTaramalari
        )
    }

    /** v5.4 — Ağır kütüphane/çıkarım taramaları için arka plan işçisi. */
    private val sweepExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "umay-sweep").apply { isDaemon = true }
    }

    /** Kapak önizlemeleri arka planda (tek iş parçacığında) çözülür. */
    private val coverExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "umay-cover").apply { isDaemon = true }
    }
    /** Başarısız/çalıştırılmış kapak üretimleri (tekrar tekrar denenmesin). */
    private val coverTried = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPdf(uri)
        }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris != null && uris.isNotEmpty()) importImages(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)  // SDK 36 edge-to-edge

        // v13 (T9b) — İlk açılış tanıtımı (5 sn, "Geç" ile atlanır)
        if (!AppPrefs.getBool(this, AppPrefs.KEY_ONBOARDING_DONE, false)) {
            try { startActivity(Intent(this, OnboardingActivity::class.java)) }
            catch (t: Throwable) { BookStore.logError(this, "onboarding", t) }
        }

        // v6 (Aşama 4) — alt sabit banner (G sınıfı güvenli reklamlar)
        try {
            AdMobManager.attachBanner(this, binding.adContainer)
        } catch (t: Throwable) {
            BookStore.logError(this, "attachBanner", t)
        }

        // v6 (Aşama 6) — oturum sayacı: 3. oturumda in-app review + esnek güncelleme
        try {
            val session = AppPrefs.bumpSession(this)
            CrashLog.log(this, "MainActivity.onCreate session=$session")

        // v15 (V5.1 ADIM B.5 / #1) — Raf arka planı teması (ahşap/metal/koyu);
        // seçim kalıcı (AppPrefs.KEY_SHELF_THEME), her açılışta uygulanır.
        try { applyShelfTheme() } catch (_: Throwable) {}
            if (session >= 3) PlayCoreManager.maybeAskReview(this, "session_$session")
            PlayCoreManager.checkFlexibleUpdate(this)
        } catch (t: Throwable) {
            BookStore.logError(this, "playCore", t)
        }

        // 3 raf (ADIM 2) — her raf için yatay liste + başlık metni
        shelfLists.forEachIndexed { index, rv ->
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = BooksAdapter(
                onClick = { book -> askResume(book) },
                // v20 (ADIM 3): uzun basma artık TAŞI / RAFTAN ÇIKAR / SİL menüsü
                onLongClick = { book -> showBookActions(book) },
                loadCover = { id, cb -> loadCover(id, cb) },
                // v24 — yüzde rozeti rafın neon rengini kullanır
                accent = shelfNeonColor(index)
            )
            // Raf başlığı: layout'a <include> edilen header (shelf'in ilk
            // çocuğu) — eski kodda AYRI bir header inflate ediliyor ama hiç
            // eklenmiyordu, bu yüzden başlıklar görünmüyordu.
            (shelfViews[index].getChildAt(0) as? android.view.View)
                ?.findViewById<TextView>(R.id.shelfTitle)?.text = shelfTitles[index]
            // v5.6 — Raf başlığındaki ‹ › okları: aynı rafı yatay kaydırır.
            shelfViews[index].getChildAt(0)?.let { wireShelfArrows(it, rv) }
        }

        // v5.6 — Ders şeridi okları (yatay kaydırma)
        binding.coursePrev.setOnClickListener { scrollCourses(-1) }
        binding.courseNext.setOnClickListener { scrollCourses(+1) }
        // v5.6 — "EN SON OKUNAN" kartında kitap gezinme
        binding.heroPrev.setOnClickListener { shiftHero(-1) }
        binding.heroNext.setOnClickListener { shiftHero(+1) }

        binding.btnPickPdf.setOnClickListener { choosePdf() }
        binding.btnPickImages.setOnClickListener {
            pickImages.launch(arrayOf("image/*"))
        }
        // Kamerayla kitap tarama (sayfa sayfa çek → PDF → kütüphane → oku)
        binding.btnScan.setOnClickListener {
            startActivity(Intent(this, ScannerActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnChat.setOnClickListener { openChatForLastBook() }

        // v21 — TANILAMA: açılışta otomatik çalışır, sonuç rozet olarak durur.
        // Rozet YALNIZ gerçek hata (✗) varsa görünür ve kırmızı yanıp söner.
        binding.btnDiagBadge.setOnClickListener {
            DiagnosticTestManager.showLastOrRun(this)
        }
        refreshDiagBadge()
        DiagnosticTestManager.runSilently(this) { _, _ -> refreshDiagBadge() }
        // v36.4 — yalnız hata ayıklama derlemesinde: lamba senaryolarını
        // elle doğrulamak için `am start ... --es lamp_test warn|fail`
        if (BuildConfig.DEBUG) maybeRunLampDemo(intent)

        // v14 (V5.0 §7) — Öğrenci profil kartına tıklayınca profil ekranı
        binding.greetingCard.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // ── Derslerim rafı (ADIM 2) ──
        binding.courseList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.courseList.adapter = CoursesAdapter { course ->
            startActivity(
                Intent(this, CourseDetailActivity::class.java).apply {
                    putExtra(CourseDetailActivity.EXTRA_COURSE_ID, course.id)
                }
            )
        }
        binding.btnAddCourse.setOnClickListener { showAddCourseDialog() }

        // v16 (GÖREV 4) — "Kamu Malı Kitaplık" butonu KALDIRILDI (activity_main.xml):
        // PublicDomainDialog.show() çağrısı ve listener silindi.

        // v5.7 — GİZLİ KASA (ana sayfanın en altı; APK kurulumu Ayarlar'a taşındı)
        binding.vaultCard.setOnClickListener {
            try {
                startActivity(Intent(this, SecureVaultActivity::class.java))
            } catch (t: Throwable) {
                BookStore.logError(this, "vaultCard", t)
            }
        }

        // v13 (T8) — Tüm cihazı tara (SD kart dahil)
        binding.btnDeepScan.setOnClickListener {
            try {
                startActivity(Intent(this, DeepDocumentPickerActivity::class.java))
            } catch (t: Throwable) {
                BookStore.logError(this, "btnDeepScan", t)
            }
        }
        // v13 (T8) — Raf yönetimi (ekle/taşı/sil)
        binding.btnShelves.setOnClickListener {
            ShelfManager.showManageDialog(this) { reload() }
        }
        binding.btnSchedule.setOnClickListener {
            startActivity(Intent(this, ScheduleActivity::class.java))
        }
        // v16 (GÖREV 6) — İnteraktif sınav takvimi + not/yüzde/AGNO ekranı
        binding.btnExams.setOnClickListener {
            try {
                startActivity(Intent(this, ExamsActivity::class.java))
            } catch (t: Throwable) {
                BookStore.logError(this, "btnExams", t)
            }
        }

        // Hatırlatıcıları her açılışta tazele (alarmlar uygulama güncellenince
        // veya cihaz yeniden başlayınca kaybolabilir — bkz. Reminder)
        try { Reminder.rescheduleAll(this) } catch (_: Exception) {}

        // Kaldığın yerden devam kartı → kartta SEÇİLİ kitabın kaldığı sayfası
        binding.btnContinue.setOnClickListener {
            val book = heroBook() ?: return@setOnClickListener
            val (page, sentence) = AppPrefs.loadProgress(this, book.id) ?: Pair(0, 0)
            openReader(book.id, page, sentence)
        }
        // v5.6 — "Baştan başla": ilerleme sıfırlanır, kitap 1. sayfadan açılır.
        binding.btnRestart.setOnClickListener {
            val book = heroBook() ?: return@setOnClickListener
            AppPrefs.clearProgress(this, book.id)
            openReader(book.id, 0, 0)
        }
        // ADIM 2: hero rafında kapağa / kartın kendisine dokunmak da
        // aynı şekilde kaldığı sayfadan açar ("Kapağa tıkla → devam et")
        binding.continueCover.setOnClickListener { binding.btnContinue.performClick() }
        binding.continueCard.setOnClickListener { binding.btnContinue.performClick() }
        applyNeonFrames()

        // v24 — sıralama çipleri: yalnız Alfabetik / En son eklenen
        binding.sortAlpha.setOnClickListener { setSort("alpha") }
        binding.sortAdded.setOnClickListener { setSort("added") }

        // Kitaplarda arama
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = applyFilter()
        })

        requestNotifPermissionIfNeeded()

        // v5.4 — Ekran kapanmasın (kullanıcı laptop başında olmayabilir).
        // Ayarlar → "Ekran açık kalsın" anahtarıyla kapatılabilir.
        if (AppPrefs.getBool(this, AppPrefs.KEY_KEEP_SCREEN_ON, true)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // v5.4 — Dönem verisi (dersler / program / vize + proje tarihleri) bir
        // kez tohumlanır; DB işi olduğu için arka plan işçisinde çalışır.
        sweepExecutor.execute { SemesterSeed.seedIfNeeded(applicationContext) }
    }

    /**
     * v21 — Özellik Künyesi rozeti. Hata sayısı 0 ise tamamen gizlenir;
     * aksi hâlde kırmızı daire içinde sayı + yanıp sönme animasyonu.
     */
    /**
     * v21.3 — 3 NEON DURUM LAMBASI (kullanıcı isteği).
     *  • Kırmızı: hata (✗) varsa yanar + yanıp söner.
     *  • Sarı: hata yok, uyarı (!) varsa yanar + yanıp söner.
     *  • Yeşil: hiç sorun yoksa SABİT neon yanar.
     * Üç lamba da her zaman görünür; sönük olan "kapalı" durumdur.
     */

    /** v36.3 — son lamba durumu: sesli uyarı yalnız DURUM DEĞİŞİNCE çalar. */
    private var lastLampState = ""

    /** v36.4 — oturum başlangıcı (açılış gürültüsünde sesli uyarıyı susturmak için). */
    private val sessionStartedAt = android.os.SystemClock.elapsedRealtime()

    /**
     * v36.3 — LAMBA SESLİ UYARISI.
     *  • sarı (uyarı) → tek kısa bip
     *  • kırmızı (hata) → iki kısa "hata" tonu
     * Ses yalnız durum değişince ve kullanıcı ayarı açıksa çalar; 2 dakikalık
     * otomatik denetim aynı durumu tekrar bulduğunda sessiz kalır.
     */
    private fun playLampAlert(critical: Boolean) {
        // v36.4 — ses tek yerden (Ayarlar'daki önizleme de aynı sesi çalar)
        LampSound.play(this, critical)
    }

    /**
     * v36.3 — YANIP SÖNME.
     * Kullanıcı geri bildirimi: sarı lamba "1" yazıyordu ama yanıp sönmüyordu
     * (650 ms'lik yumuşak geçiş göze "sabit soluk" görünüyordu).
     * Artık belirgin aç/kapa + kritik durumda hafif büyüme.
     */
    /**
     * v36.4 — KÖK NEDEN DÜZELTMESİ.
     * Kullanıcının telefonunda **Geliştirici seçenekleri → Animasyon ölçeği = 0**
     * (animator_duration_scale=0). Bu durumda ObjectAnimator animasyonu hiç
     * oynatmaz, doğrudan SON değere atlar: sarı lamba 0.12 alfasında "soluk"
     * kalıyor, yeşil lamba da parla-sön sonrası 0.30'da sönük kalabiliyordu.
     * Bu yüzden lamba yanıp sönmesi artık **Handler** ile yapılıyor: animasyon
     * ölçeğinden bağımsız, her cihazda net çalışır.
     */
    private var lampBlinkView: android.view.View? = null
    private var lampBlinkCritical = false
    private var lampBlinkLit = false

    private val lampBlinkStep = object : Runnable {
        override fun run() {
            val v = lampBlinkView ?: return
            lampBlinkLit = !lampBlinkLit
            v.alpha = if (lampBlinkLit) 1f else 0.42f
            val s = if (lampBlinkLit) 1f else if (lampBlinkCritical) 1.16f else 1.10f
            v.scaleX = s
            v.scaleY = s
            diagHandler.postDelayed(this, if (lampBlinkCritical) 380L else 460L)
        }
    }

    /** Yanıp sönmeyi durdurur ve üç lambayı da normal boyut/alfaya döndürür. */
    private fun stopLampBlink() {
        lampBlinkView = null
        try { diagHandler.removeCallbacks(lampBlinkStep) } catch (_: Throwable) { }
        try { diagHandler.removeCallbacks(greenPulse) } catch (_: Throwable) { }
        try {
            listOf(binding.lampRed, binding.lampAmber, binding.lampGreen).forEach {
                it.alpha = 1f
                it.scaleX = 1f
                it.scaleY = 1f
            }
        } catch (_: Throwable) { }
    }

    private fun startLampBlink(view: android.view.View, critical: Boolean) {
        stopLampBlink()
        lampBlinkView = view
        lampBlinkCritical = critical
        lampBlinkLit = true
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        diagHandler.postDelayed(lampBlinkStep, if (critical) 380L else 460L)
    }

    /** v36.4 — yeşil lambanın "parla-sön" nabzı (animasyon ölçeğinden bağımsız). */
    private var greenPulseCount = 0
    private val greenPulse = object : Runnable {
        override fun run() {
            val g = binding.lampGreen
            greenPulseCount++
            if (greenPulseCount > 6) {
                g.alpha = 1f
                g.scaleX = 1f
                g.scaleY = 1f
                return
            }
            val lit = greenPulseCount % 2 == 1
            g.alpha = if (lit) 1f else 0.45f
            g.scaleX = if (lit) 1.22f else 1f
            g.scaleY = g.scaleX
            diagHandler.postDelayed(this, 400L)
        }
    }

    private fun startGreenPulse() {
        try { diagHandler.removeCallbacks(greenPulse) } catch (_: Throwable) { }
        greenPulseCount = 0
        val g = binding.lampGreen
        g.alpha = 1f
        g.scaleX = 1f
        g.scaleY = 1f
        diagHandler.postDelayed(greenPulse, 300L)
    }

    /** v36.4 — demo sürerken gerçek denetim sonucu lambaları ezmesin. */
    private var lampDemoActive = false

    /**
     * v36.4 — LAMBA SENARYO TESTİ (yalnız debug derleme).
     * `adb shell am start -n com.umay.pdfreader/.MainActivity --es lamp_test warn`
     *  • warn → sarı yanar + yanıp söner + tek bip
     *  • fail → kırmızı yanar + yanıp söner + çift "hata" tonu
     *  • cycle → kırmızı → sarı → yeşil sırayla
     * Test bitince gerçek sayaçlara döner.
     */
    private fun maybeRunLampDemo(intent: android.content.Intent?) {
        val mode = intent?.getStringExtra("lamp_test") ?: return
        lampDemoActive = true
        val red = binding.lampRed
        val amber = binding.lampAmber
        val green = binding.lampGreen
        val steps = when (mode) {
            "fail" -> listOf("fail")
            "warn" -> listOf("warn")
            else -> listOf("fail", "warn", "ok")
        }
        var i = 0
        val next = object : Runnable {
            override fun run() {
                if (i >= steps.size) {
                    lampDemoActive = false
                    refreshDiagBadge()
                    return
                }
                val s = steps[i++]
                stopLampBlink()
                red.setBackgroundResource(R.drawable.bg_lamp_off)
                amber.setBackgroundResource(R.drawable.bg_lamp_off)
                green.setBackgroundResource(R.drawable.bg_lamp_off)
                when (s) {
                    "fail" -> {
                        red.setBackgroundResource(R.drawable.bg_lamp_red)
                        red.text = "1"; amber.text = "0"; green.text = "19"
                        startLampBlink(red, critical = true)
                        playLampAlert(critical = true)
                    }
                    "warn" -> {
                        amber.setBackgroundResource(R.drawable.bg_lamp_amber)
                        red.text = "0"; amber.text = "1"; green.text = "19"
                        startLampBlink(amber, critical = false)
                        playLampAlert(critical = false)
                    }
                    else -> {
                        green.setBackgroundResource(R.drawable.bg_lamp_green)
                        red.text = "0"; amber.text = "0"; green.text = "20"
                    }
                }
                // v36.4 — animasyonun gerçekten koştuğunu ölçmek için alfa örnekle
                // (yalnız debug; logcat -s UmayLamp)
                var tick = 0
                val sampler = object : Runnable {
                    override fun run() {
                        if (tick >= 16) return
                        tick++
                        android.util.Log.d(
                            "UmayLamp",
                            "$s a=${if (s == "fail") red.alpha else if (s == "warn") amber.alpha else green.alpha}"
                        )
                        diagHandler.postDelayed(this, 200L)
                    }
                }
                diagHandler.postDelayed(sampler, 100L)
                diagHandler.postDelayed(this, 8_000L)
            }
        }
        diagHandler.postDelayed(next, 1_800L)
    }

    private fun refreshDiagBadge(flashHealthy: Boolean = false) {
        if (lampDemoActive) return
        try {
            val fail = DiagnosticTestManager.lastFailCount(this)
            val warn = DiagnosticTestManager.lastWarnCount(this)
            val ok = DiagnosticTestManager.lastOkCount(this)
            val red = binding.lampRed
            val amber = binding.lampAmber
            val green = binding.lampGreen

            red.setBackgroundResource(if (fail > 0) R.drawable.bg_lamp_red else R.drawable.bg_lamp_off)
            amber.setBackgroundResource(if (warn > 0) R.drawable.bg_lamp_amber else R.drawable.bg_lamp_off)
            green.setBackgroundResource(
                if (fail == 0 && warn == 0) R.drawable.bg_lamp_green else R.drawable.bg_lamp_off
            )
            // v34 — lambaların İÇİNDE sayı (sönükken de görünür)
            red.text = fail.toString()
            amber.text = warn.toString()
            green.text = ok.toString()

            stopLampBlink()

            val active = when {
                fail > 0 -> red
                warn > 0 -> amber
                else -> null   // yeşil sağlıklı
            }
            if (active != null) {
                startLampBlink(active, critical = fail > 0)
            } else if (flashHealthy) {
                // v36.4 — NEON PARLA-SÖN: 3 kez parlayıp söner, sonra SABİT yanık
                // kalır. Handler ile (animasyon ölçeği 0 olan cihazlarda da çalışır).
                startGreenPulse()
            }
            // v36.3 — sesli uyarı yalnız durum DEĞİŞİNCE (açılışta ilk ölçüm sessiz)
            val state = when {
                fail > 0 -> "fail"
                warn > 0 -> "warn"
                else -> "ok"
            }
            if (state != lastLampState) {
                val prev = lastLampState
                lastLampState = state
                when {
                    // Açılışta kırmızı varsa hemen duyur (uyarı için sessiz kal:
                    // her açılışta sarı bip çalmak gürültü olur).
                    prev.isEmpty() -> if (state == "fail") playLampAlert(critical = true)
                    state != "ok" -> {
                        // v36.4 — açılışın ilk 30 sn'sinde geçici uyarı (TTS motoru
                        // ısınıyor, dosya kilidi vb.) biplemesin; kırmızı her zaman çalar.
                        val warmedUp = android.os.SystemClock.elapsedRealtime() - sessionStartedAt > 30_000L
                        if (state == "fail" || warmedUp) playLampAlert(critical = state == "fail")
                    }
                }
            }

            binding.btnDiagBadge.contentDescription = when {
                fail > 0 -> "$fail hata, $warn uyarı, $ok çalışan — Özellik Künyesi"
                warn > 0 -> "$warn uyarı, $ok çalışan — Özellik Künyesi"
                else -> "Sorun yok · $ok özellik çalışıyor — Özellik Künyesi"
            }
        } catch (t: Throwable) {
            BookStore.logError(this, "refreshDiagBadge", t)
        }
    }

    // v34 — OTOMATİK SAĞLIK DENETİMİ: 2 dakikada bir, arka planda (IO),
    // okumayı/UI'yı kilitlemeden. Sonuç lambalara yazılır.
    private val diagHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val diagTick = object : Runnable {
        override fun run() {
            try {
                DiagnosticTestManager.runSilently(this@MainActivity) { f, w ->
                    if (f >= 0) refreshDiagBadge(true)
                }
            } catch (_: Throwable) { }
            diagHandler.postDelayed(this, 120_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        // v5.1: Yenileme hatası uygulamayı çökertmesin (açılışta TextUtil
        // hatası gibi beklenmeyen istisnalar loglanır, ekran yaşamaya devam eder)
        try {
            reload()
        } catch (t: Throwable) {
            BookStore.logError(this, "MainActivity.onResume/reload", t)
        }
        // v12: ana ekran widget'ını tazele (sıradaki ders bilgisi güncellensin)
        try { UmayWidgetProvider.refreshAll(this) } catch (_: Throwable) {}
        // v13 (T7): yaklaşan sınav varsa canlı geri sayım bildirimini sürdür
        try {
            if (ExamCountdownService.hasUpcomingExam(this)) {
                ExamCountdownService.refresh(this)
            }
        } catch (_: Throwable) {}
        ExtractionBus.listeners.add(this)
        // v34 — 2 dakikada bir otomatik denetim (ilk tur 2 dk sonra)
        diagHandler.removeCallbacks(diagTick)
        diagHandler.postDelayed(diagTick, 120_000L)
        // v24 — hero çerçevesi: okuma durumuna göre sabit/hareketli
        heroHandler.removeCallbacks(heroPulse)
        heroHandler.post(heroPulse)
        // v6 (Aşama 6.3) — App Link / özel şema ile gelen derin bağlantı
        // (yalnız ilk geçerli bağlantıda bir kez açılır)
        if (!deepLinkHandled) {
            deepLinkHandled = true
            try {
                handleDeepLink(intent)
            } catch (t: Throwable) {
                BookStore.logError(this, "handleDeepLink", t)
            }
        }
        // v21 (DENETİM BUG-001) — OTOMATİK TOPLU İÇE AKTARMA TAMAMEN
        // KALDIRILDI. Eski kod her onResume'da MediaStore'dan 50'ye kadar
        // PDF'i onaysız kütüphaneye ekliyordu (dosya çoğalması + yanlış
        // raf dağılımı + her açılışta yeniden aktarım). Artık keşif yalnızca
        // "Cihaz Tarama & Dokümanlar" ekranında ve YALNIZCA kullanıcının
        // seçtiği dosyalarda yapılır (DeepDocumentPicker → importSelected).
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkHandled = false
    }

    /**
     * v6 (Aşama 6.3) — Deep link:
     *   umaypdfreader.app/b/<kitapId>  veya  umay://book/<kitapId>
     * Bilinmeyen kitap id'si kullanıcıya bildirilir, ekran bozulmaz.
     */
    private fun handleDeepLink(i: Intent?) {
        // v11 (GÖREV 1): dışarıdan gelen belge (VIEW + content:// veya file://)
        if (i?.action == Intent.ACTION_VIEW && i.data != null) {
            handleIncomingDocument(i)
            return
        }

        // v25 (C8) — PAYLAŞ menüsü: WhatsApp/Telegram/Dosyalar/Gmail eki
        // dosyayı EXTRA_STREAM ile gönderir (ACTION_VIEW değil).
        if (i?.action == Intent.ACTION_SEND) {
            val stream = sharedStreamUri(i)
            if (stream != null) {
                openIncoming(stream, i.getStringExtra(Intent.EXTRA_TITLE))
                return
            }
        }

        val data = i?.data ?: return
        if (data.lastPathSegment.isNullOrBlank()) return
        val id = data.lastPathSegment!!
        val book = books.firstOrNull { it.id == id }
            ?: BookStore.loadBooks(this).firstOrNull { it.id == id }
        if (book == null) {
            Toast.makeText(this, "Bağlantıdaki kitap kütüphanede bulunamadı", Toast.LENGTH_LONG).show()
            return
        }
        CrashLog.log(this, "deep_link open=${book.id}")
        openReader(book.id, 0, 0)
    }

    /**
     * v11 (GÖREV 1) — Dışarıdan gelen belgeyi karşıla.
     *   Dosya Yöneticisi / WhatsApp / Chrome → VIEW intent + content:// URI
     *   → Bottom Sheet: "Umay'da Görüntüle" / "Umay'da Sesli Dinle"
     */
    private fun handleIncomingDocument(i: Intent) {
        try {
            val uri = i.data ?: return
            openIncoming(uri, i.getStringExtra(Intent.EXTRA_TITLE))
        } catch (t: Throwable) {
            BookStore.logError(this, "handleIncomingDocument", t)
        }
    }

    /** v25 (C8) — ACTION_SEND ile gelen dosya URI'si (API 33+ tipli okuma). */
    private fun sharedStreamUri(i: Intent): android.net.Uri? = try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            val legacy: android.net.Uri? = i.getParcelableExtra(Intent.EXTRA_STREAM)
            legacy
        }
    } catch (_: Throwable) {
        null
    }

    /** Gelen belgeyi "Umay'da Görüntüle / Sesli Dinle" seçeneğiyle açar. */
    private fun openIncoming(uri: android.net.Uri, title: String?) {
        try {
            val fileName = title ?: uri.lastPathSegment?.substringAfterLast('/')
            OpenWithDialog.show(this, uri, fileName, autoSpeak = false) { bookId, speak ->
                CrashLog.log(this, "open_with book=$bookId speak=$speak")
                val intent = Intent(this, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                    putExtra(ReaderActivity.EXTRA_PAGE, 0)
                    if (speak) putExtra(ReaderActivity.EXTRA_AUTO_PLAY, true)
                }
                startActivity(intent)
            }
        } catch (t: Throwable) {
            BookStore.logError(this, "openIncoming", t)
        }
    }

    override fun onPause() {
        try { diagHandler.removeCallbacks(diagTick) } catch (_: Throwable) { }
        super.onPause()
        ExtractionBus.listeners.remove(this)
        // v24 — "En son okunan" çerçeve animasyonu arka planda durur (pil)
        heroHandler.removeCallbacks(heroPulse)
        heroNeon?.setAnimated(false)
    }

    // ── v24 — NEON ÇERÇEVELER ────────────────────────────────
    private var heroNeon: NeonFrameDrawable? = null
    private val heroHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Sesli okuma sürüyor mu? (çerçeve yanar-döner moduna geçer) */
    private val heroPulse = object : Runnable {
        override fun run() {
            val playing = try {
                AppPrefs.getBool(this@MainActivity, AppPrefs.KEY_PLAYING, false)
            } catch (_: Throwable) { false }
            heroNeon?.setAnimated(playing)
            heroHandler.postDelayed(this, 900L)
        }
    }

    /**
     * v24 — Araçlar paneline sabit neon çerçeve; "En son okunan" kartına
     * okuma DURURKEN sabit neon, sesli okuma SÜRERKEN hareketli neon çerçeve.
     */
    private fun applyNeonFrames() {
        try {
            val toolsColors = intArrayOf(
                getColor(R.color.umay_shelf_neon_2),
                getColor(R.color.umay_shelf_neon_0),
                getColor(R.color.umay_shelf_neon_3),
                getColor(R.color.umay_shelf_neon_2)
            )
            binding.toolsPanel.background = NeonFrameDrawable(18f, 2f, toolsColors, false)
        } catch (t: Throwable) {
            BookStore.logError(this, "applyNeonFrames/tools", t)
        }
        try {
            val hero = NeonFrameDrawable(16f, 2f, NeonFrameDrawable.DEFAULT_PALETTE, false)
            binding.continueCard.background = hero
            heroNeon = hero
        } catch (t: Throwable) {
            BookStore.logError(this, "applyNeonFrames/hero", t)
        }
    }

    private fun reload() {
        // v14 (V5.0 §7) — Profil bilgilerini karta yansıt
        try {
            val profJson = AppPrefs.getString(this, "key_profile")
            if (profJson.isNotBlank()) {
                val obj = org.json.JSONObject(profJson)
                val name = obj.optString("name", "Cengiz Kılıç").ifBlank { "Cengiz Kılıç" }
                val uni = obj.optString("university", "İstanbul Rumeli Üniversitesi")
                    .ifBlank { "İstanbul Rumeli Üniversitesi" }
                val faculty = obj.optString("faculty", "Mühendislik Fakültesi")
                    .ifBlank { "Mühendislik Fakültesi" }
                val dept = obj.optString("department", "Yapay Zekâ ve Makine Öğrenimi")
                    .ifBlank { "Yapay Zekâ ve Makine Öğrenimi" }
                binding.greetingTitle.text = name
                // v34 — 3 satır: okul / fakülte / bölüm (kullanıcı isteği)
                binding.greetingSub.text = "$uni\n$faculty\n$dept"
            } else {
                binding.greetingTitle.text = "Cengiz Kılıç"
                binding.greetingSub.text =
                    "İstanbul Rumeli Üniversitesi\nMühendislik Fakültesi\nYapay Zekâ ve Makine Öğrenimi"
            }
            val avatarFile = java.io.File(java.io.File(filesDir, "profile"), "avatar.jpg")
            if (avatarFile.exists()) {
                val bmp = android.graphics.BitmapFactory.decodeFile(avatarFile.absolutePath)
                if (bmp != null) binding.profileAvatar.setImageBitmap(bmp)
            }
        } catch (_: Throwable) {}

        // v17 (ONARIM): Ders rafı kartları ZORUNLU DEĞİL — hatası raf yolunu
        // kesmesin (eski kodda buradaki tek bir exception applyFilter()'i hiç
        // çalıştırıyor, 3 raf XML'de "gone" kaldığı için tamamen kayboluyordu;
        // exception da sessizce loglandığı için loglar "yeşil" görünüyordu).
        try { reloadCourses() } catch (t: Throwable) {
            BookStore.logError(this, "reloadCourses", t)
        }
        books = try {
            // v13 (T9c): kasadaki kitaplar ana rafta GİZLENİR (kasa ekranından yönetilir)
            val vaulted = SecureVault.vaultedIds(this)
            BookStore.loadBooks(this).filter { it.id !in vaulted }
        } catch (t: Throwable) {
            BookStore.logError(this, "MainActivity.loadBooks", t)
            emptyList()
        }
        val empty = books.isEmpty()
        // v17 (ONARIM): 3 RAF DOLDURMA Artık AYRI KORUMALI ve EN ÖNCE —
        // hiçbir kozmetik adım rafları silemez/devre dışı bırakamaz.
        try {
            applyFilter()
        } catch (t: Throwable) {
            BookStore.logError(this, "applyFilter", t)
            try { fillShelves(emptyList()) } catch (_: Throwable) {}
        }
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        // v6: raf sargısı — görünürlük artık fillShelves() içinde raf bazında
        // ayarlanır (boş raf gizlenir, dolu raf görünür).
        // v20 (ADIM 1): bu catch artık sessiz değil — hata umay_error.log'a düşer
        try { applyShelfTheme() } catch (t: Throwable) {
            BookStore.logError(this, "applyShelfTheme", t)
        }
        binding.searchInput.visibility = if (empty) View.GONE else View.VISIBLE
        binding.sortRow.visibility = if (empty) View.GONE else View.VISIBLE
        binding.libraryLabel.visibility = if (empty) View.GONE else View.VISIBLE
        try { applySortUi() } catch (t: Throwable) {
            BookStore.logError(this, "applySortUi", t)
        }
        refreshContinueCard()
        // 3 raf kitap listeleri: applyFilter() → fillShelves() zaten güncelliyor
        // v5.6: ayrı "Son Okunanlar" şeridi KALDIRILDI — aynı bilgi artık
        // "EN SON OKUNAN" kahraman kartında (çizimdeki düzen) gösteriliyor.
        // v5.4 (ANR ONARIMI) — Çıkarım taraması ARTIK ARKA PLANDA ve
        // süreç başına bir kez. Eski kod her onResume'da 582 kitabın her biri
        // için ana thread'de DB okuması + sayfa dosyası kontrolü yapıyordu
        // (≈1.700 tablo okuması + 42.000+ dosya erişimi) → 7–17 sn donma.
        scheduleExtractionSweep()
    }

    @Volatile
    private var sweepStarted = false

    /** Kitapların metin çıkarımını arka planda, süreç başına bir kez tetikler. */
    private fun scheduleExtractionSweep() {
        if (sweepStarted) return
        sweepStarted = true
        val snapshot = books
        val appCtx = applicationContext
        sweepExecutor.execute {
            snapshot.forEach { book ->
                try {
                    BookStore.ensureExtracting(appCtx, book)
                } catch (t: Throwable) {
                    BookStore.logError(appCtx, "ensureExtracting:${book.id}", t)
                }
            }
            // Tarama bitti: raf sayaçları/ilerleme çubukları tazelensin
            runOnUiThread { try { if (!isFinishing) applyFilter() } catch (_: Throwable) {} }
        }
    }

    /** Ders rafını tazeler (kart yoksa satır gizlenir). */
    private fun reloadCourses() {
        val items = CourseStore.courses(this)
        binding.courseList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.coursesEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        (binding.courseList.adapter as? CoursesAdapter)?.submit(items)
    }


    /** "Yeni Ders Ekle" — tek satırlık isim diyaloğu. */
    private fun showAddCourseDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.ders_adi)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.yeni_ders_ekle)
            .setView(input)
            .setPositiveButton(R.string.kaydet) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.ders_bos_olamaz, Toast.LENGTH_SHORT).show()
                } else {
                    CourseStore.addCourse(this, name)
                    Toast.makeText(this, R.string.ders_kaydedildi, Toast.LENGTH_SHORT).show()
                    reloadCourses()
                }
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    /** Arama filtresi + sıralama uygular (Türkçe karakter duyarsız). */
    private fun applyFilter() {
        val q = binding.searchInput.text.toString().trim()
        var filtered = if (q.isEmpty()) books else books.filter { book ->
            TextUtil.normalize(book.title).contains(TextUtil.normalize(q))
        }
        // v24 — "Okuma Kitapları" filtresi kaldırıldı (kullanıcı isteği):
        // yalnız Alfabetik / En son eklenen sıralaması kaldı.
        // v17 (ONARIM): sıralama/filtre artık 3 rafı doldurur (eski tek
        // bookList RecyclerView'ı 3 raf düzeninde kaldırıldı).
        fillShelves(sortBooks(filtered))
    }

    /**
     * v20.1 (ONARIM): Kitaplıkları rafa böler ve görünürlükleri ayarlar.
     *
     * SINIFLANDIRMA KURALI (v20.1 — kullanıcının taşıma talebi):
     *  1. Kitap ShelfManager'da AÇIK bir rafa atanmışsa → YALNIZCA o rafta
     *     görünür (uzantı sınıflandırması ATLANIR). Böylece "Raflara taşı…"
     *     ile Ders Slaytlar & Notlar (veya herhangi bir yerleşik/özel rafa)
     *     taşınan belge GERÇEKTEN oraya geçer.
     *  2. Hiçbir rafa atanmamış kitaplar → dosya uzantısına göre otomatik
     *     olarak 3 yerleşik rafa dağıtılır (davranış değişmedi).
     *
     * Görünürlük kuralı (v17): kütüphane BOŞ DEĞİLSE üç rafın TAMAMI görünür
     * (boş rafın başlığı + "Bu raf boş" ipucu kalır). Kütüphane boşsa
     * zaten "kütüphanen boş" ekranı görünür.
     */
    private fun fillShelves(all: List<Book>) {
        val dersKitaplari = mutableListOf<Book>()
        val dersSlaytlar = mutableListOf<Book>()
        val cihangTaramalari = mutableListOf<Book>()
        // v20.1: Kitabın AÇIK olarak atandığı raf kimlikleri (ShelfManager)
        val assigned = try {
            val map = HashMap<String, MutableSet<String>>()
            ShelfManager.shelvesOf(this).forEach { shelf ->
                shelf.bookIds.forEach { id ->
                    map.getOrPut(id) { mutableSetOf() }.add(shelf.id)
                }
            }
            map
        } catch (t: Throwable) {
            BookStore.logError(this, "fillShelves/assigned", t)
            emptyMap<String, Set<String>>()
        }
        for (book in all) {
            // 1) AÇIK ATAMA VARSA → yalnız kullanıcının koyduğu raf(lar) geçerli
            val mine = assigned[book.id]
            if (mine != null) {
                if ("shelf_ders_kitaplari" in mine) dersKitaplari.add(book)
                if ("shelf_ders_slayt_notlar" in mine) dersSlaytlar.add(book)
                if ("shelf_cihang_taramalari" in mine) cihangTaramalari.add(book)
                continue   // özel raftaysa yerleşik sınıflandırmaya DÜŞMEZ
            }
            // 2) Atama yok → otomatik uzantı sınılandırması (eskisi gibi)
            val src = book.title.lowercase()
            when {
                src.endsWith(".pptx") || src.endsWith(".ppt") ||
                    src.endsWith(".odp") -> dersSlaytlar.add(book)
                src.endsWith(".pdf") || src.endsWith(".epub") -> dersKitaplari.add(book)
                src.endsWith(".doc") || src.endsWith(".docx") ||
                    src.endsWith(".txt") || src.endsWith(".md") ||
                    src.endsWith(".csv") || src.endsWith(".tex") ||
                    src.endsWith(".jpg") || src.endsWith(".jpeg") ||
                    src.endsWith(".png") -> cihangTaramalari.add(book)
                else -> cihangTaramalari.add(book)   // uzantısız/bilinmeyen: kaybolmasın
            }
        }
        // v21 — DERS KAYNAKLARI ANA SAYFADA DA GÖRÜNSÜN.
        // Derslere eklenen her kaynak, "Ders Kitapları" rafında da listelenir
        // (kullanıcı isteği). Dosya kopyalanmaz; aynı Book nesnesi gösterilir.
        try {
            val linkedIds = CourseStore.allLinkedBookIds(this)
            if (linkedIds.isNotEmpty()) {
                val shown = dersKitaplari.map { it.id }.toHashSet()
                for (book in all) {
                    if (book.id in linkedIds && shown.add(book.id)) dersKitaplari.add(book)
                }
            }
        } catch (t: Throwable) {
            BookStore.logError(this, "fillShelves/linked", t)
        }
        val lists = listOf(dersKitaplari, dersSlaytlar, cihangTaramalari)
        val libraryEmpty = all.isEmpty()
        // v5.6 — Boş yerleşik raflar ekranı kaplamaz. Raflar yalnız hiçbirinde
        // kitap yokken (yapı görünsün diye) boş hâliyle gösterilir.
        val anyContent = lists.any { it.isNotEmpty() }
        shelfLists.forEachIndexed { index, rv ->
            (rv.adapter as? BooksAdapter)?.submit(lists[index])
            tintShelf(shelfViews[index], index)
            val show = !libraryEmpty && (lists[index].isNotEmpty() || !anyContent)
            shelfViews[index].visibility = if (show) View.VISIBLE else View.GONE
            // Boş raf ipucu: başlık görünür kalsın, "Bu raf boş" yazısı açılır
            val hint = shelfViews[index].findViewById<TextView>(R.id.shelfEmptyHint)
            hint?.visibility = if (lists[index].isEmpty()) View.VISIBLE else View.GONE
            // v5.6 — Raf başlığında kitap sayısı (çizimdeki "RAF ADI" bandı)
            val count = shelfViews[index].findViewById<TextView>(R.id.shelfCount)
            if (count != null) {
                count.text = lists[index].size.toString()
                count.visibility = if (lists[index].isEmpty()) View.GONE else View.VISIBLE
            }
        }
        // v5.5 (AŞAMA 1): ⭐ Favoriler rafı (en üstte)
        renderFavorites(all)
        // v20 (ADIM 3): Kullanıcının ShelfManager'dan eklediği ÖZEL raflar da
        // her applyFilter/reload çağrısında ANINDA yeniden çizilir.
        renderDynamicShelves(all, libraryEmpty)
    }

    /**
     * v5.5 (AŞAMA 1) — ⭐ FAVORİLERİM rafı.
     *
     * Favori kimlikleri kullanıcının eklediği SIRAYLA çözülür; silinmiş
     * kitapların kimlikleri sessizce atlanır (raf "hayalet" kitap göstermez).
     * Liste boşsa raf tamamen gizlenir — ana ekranda yer kaplamaz.
     * Dosya taşınmaz/kopyalanmaz: yalnız kimlik listesi (FavoritesStore).
     */
    private fun renderFavorites(all: List<Book>) {
        try {
            val byId = all.associateBy { it.id }
            val books = FavoritesStore.ids(this).mapNotNull { byId[it] }
            if (books.isEmpty()) {
                binding.shelfFavorites.visibility = View.GONE
                return
            }
            binding.shelfFavorites.findViewById<TextView>(R.id.shelfTitle).text =
                getString(R.string.favorilerim)
            binding.shelfFavorites.findViewById<TextView>(R.id.shelfEmptyHint)
                ?.visibility = View.GONE
            binding.shelfFavorites.findViewById<TextView>(R.id.shelfCount)?.let {
                it.text = books.size.toString()
                it.visibility = View.VISIBLE
            }
            val rv = binding.shelfFavoritesList
            rv.layoutManager = LinearLayoutManager(
                this, LinearLayoutManager.HORIZONTAL, false
            )
            rv.adapter = BooksAdapter(
                onClick = { book -> askResume(book) },
                onLongClick = { book -> showBookActions(book) },
                loadCover = { id, cb -> loadCover(id, cb) },
                accent = shelfNeonColor(3)
            ).also { it.submit(ShelfManager.sortForShelf(this, books)) }
            binding.shelfFavorites.getChildAt(0)?.let { wireShelfArrows(it, rv) }
            tintShelf(binding.shelfFavorites, 3)
            binding.shelfFavorites.visibility = View.VISIBLE
        } catch (t: Throwable) {
            BookStore.logError(this, "renderFavorites", t)
            try { binding.shelfFavorites.visibility = View.GONE } catch (_: Throwable) {}
        }
    }

    /**
     * v20 (ADIM 3): ShelfManager özel raflarını ana ekrana çizer.
     *  • Her çağrıda container temizlenir → ekleme / silme / yeniden
     *    adlandırma / renk değişikliği ANINDA yansır.
     *  • Raflar SharedPreferences'ta kalıcıdır (ShelfManager.save).
     *  • Raf başlığı = shelf.name, arka plan = shelf.bgColor (Renk değiştir).
     *  • Kitap listesi = shelf.bookIds → kitaplıktaki karşılıkları.
     */
    private fun renderDynamicShelves(all: List<Book>, libraryEmpty: Boolean) {
        val container = binding.shelfDynamicWrap
        try {
            container.removeAllViews()
            if (libraryEmpty) {
                container.visibility = View.GONE
                return
            }
            ShelfManager.ensureDefaults(this)
            val custom = ShelfManager.shelvesOf(this)
                .filter { it.id !in ShelfManager.AUTO_SHELF_IDS }
            if (custom.isEmpty()) {
                container.visibility = View.GONE
                return
            }
            val byId = all.associateBy { it.id }
            custom.forEachIndexed { sIdx, shelf ->
                val booksOfShelf = shelf.bookIds.mapNotNull { byId[it] }
                // v5.6 — İçi boş özel raf ana ekranı kaplamaz ("Bu raf boş"
                // bantları üst üste biniyordu); yönetim: Rafları Düzenle.
                if (booksOfShelf.isEmpty()) return@forEachIndexed
                val block = layoutInflater
                    .inflate(R.layout.item_dynamic_shelf, container, false)
                tintShelf(block, 4 + sIdx)
                val header = block.findViewById<LinearLayout>(R.id.dynShelfHeader)
                header.findViewById<TextView>(R.id.shelfTitle).text = shelf.name
                header.findViewById<TextView>(R.id.shelfEmptyHint)?.visibility =
                    if (booksOfShelf.isEmpty()) View.VISIBLE else View.GONE
                header.findViewById<TextView>(R.id.shelfCount)?.let {
                    it.text = booksOfShelf.size.toString()
                    it.visibility = if (booksOfShelf.isEmpty()) View.GONE else View.VISIBLE
                }
                // v5.6 — Kullanıcının seçtiği raf rengi artık raf bandını
                // kaplamıyor (gri blok görünüyordu); başlıktaki renk noktası
                // olarak gösteriliyor — bant kimliği tüm raflarda aynı kalıyor.
                try {
                    header.findViewById<View>(R.id.shelfColorDot)?.apply {
                        setBackgroundColor(android.graphics.Color.parseColor(shelf.bgColor))
                        visibility = View.VISIBLE
                    }
                } catch (_: Throwable) {}
                val rv = block.findViewById<RecyclerView>(R.id.dynShelfList)
                rv.layoutManager = LinearLayoutManager(
                    this, LinearLayoutManager.HORIZONTAL, false
                )
                rv.adapter = BooksAdapter(
                    onClick = { book -> askResume(book) },
                    onLongClick = { book -> showBookActions(book) },
                    loadCover = { id, cb -> loadCover(id, cb) },
                    accent = shelfNeonColor(4 + sIdx)
                ).also { it.submit(ShelfManager.sortForShelf(this, booksOfShelf)) }
                // v5.6 — özel rafın başlığındaki ‹ › okları
                wireShelfArrows(header, rv)
                container.addView(block)
            }
            container.visibility = View.VISIBLE
        } catch (t: Throwable) {
            // ADIM 1: hatalar sessizce yutulmaz → umay_error.log
            BookStore.logError(this, "renderDynamicShelves", t)
            try { container.visibility = View.GONE } catch (_: Throwable) {}
        }
    }

    /** v24 — Raf sırasına göre neon renk (yüzde rozeti de bunu kullanır). */
    private fun shelfNeonColor(index: Int): Int {
        val res = intArrayOf(
            R.color.umay_shelf_neon_0, R.color.umay_shelf_neon_1, R.color.umay_shelf_neon_2,
            R.color.umay_shelf_neon_3, R.color.umay_shelf_neon_4
        )
        return getColor(res[kotlin.math.abs(index) % res.size])
    }

    /**
     * v24 — RAF ZEMİNİ + NEON ÇERÇEVE. Her rafın arka fonu farklı ve artık
     * belirgin bir tonda; en dış çizgi o tonun parlak (neon) eşiyle çizilir.
     * Çizgi YANIP SÖNMEZ — amaç yalnız rafları birbirinden ayırmak.
     */
    private fun tintShelf(view: View, index: Int) {
        try {
            val palette = intArrayOf(
                R.color.umay_shelf_bg_0, R.color.umay_shelf_bg_1, R.color.umay_shelf_bg_2,
                R.color.umay_shelf_bg_3, R.color.umay_shelf_bg_4
            )
            val neon = intArrayOf(
                R.color.umay_shelf_neon_0, R.color.umay_shelf_neon_1, R.color.umay_shelf_neon_2,
                R.color.umay_shelf_neon_3, R.color.umay_shelf_neon_4
            )
            val d = resources.displayMetrics.density
            val i = kotlin.math.abs(index) % palette.size
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18f * d
                setColor(getColor(palette[i]))
                setStroke((2f * d).toInt().coerceAtLeast(2), getColor(neon[i]))
            }
            val pad = (10f * d).toInt()
            view.setBackground(bg)
            if (view.paddingLeft < pad) view.setPadding(pad, pad, pad, pad)
        } catch (t: Throwable) {
            BookStore.logError(this, "tintShelf", t)
        }
    }

    /**
     * v20 (ADIM 3): Kitap hücresi uzun basma menüsü —
     *  📚 Raflara taşı… / ⬅ Raftan çıkar (kitap bir raftaysa) / Kitabı sil.
     *  Her işlem sonrası reload() → arayüz anında güncellenir.
     */
    private fun showBookActions(book: Book) {
        val onShelf = ShelfManager.shelvesOf(this)
            .any { it.id !in ShelfManager.AUTO_SHELF_IDS && book.id in it.bookIds }
        val fav = FavoritesStore.isFavorite(this, book.id)

        // v5.5: etiket ve EYLEM aynı listede tutulur — indeks kayması olmaz.
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels.add(if (fav) getString(R.string.favori_cikar) else getString(R.string.favori_ekle))
        actions.add {
            val nowFav = FavoritesStore.toggle(this, book.id)
            Toast.makeText(
                this,
                if (nowFav) R.string.favori_eklendi else R.string.favori_cikarildi,
                Toast.LENGTH_SHORT
            ).show()
            reload()
        }

        labels.add(getString(R.string.raf_tasi))
        actions.add { askMoveToShelf(book) }

        if (onShelf) {
            labels.add(getString(R.string.raftan_cikar))
            actions.add {
                // Raftan çıkar: KİTAP SİLİNMEZ, yalnız raf listesinden düşer
                ShelfManager.removeBookFromAllShelves(this, book.id)
                Toast.makeText(this, R.string.raftan_cikildi, Toast.LENGTH_SHORT).show()
                reload()   // fillShelves + renderDynamicShelves → ANINDA güncelleme
            }
        }

        labels.add(getString(R.string.kitap_sil))
        actions.add { confirmDelete(book) }

        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(labels.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    /** Kitabı seçilen rafa taşır (diğer raflardan çıkarır; kalıcı). */
    private fun askMoveToShelf(book: Book) {
        // v5.5 (ONARIM 8): yerleşik raflar (Ders Kitapları vb.) kalıcı listeye
        // eksikse eklenir — aksi hâlde taşıma listesinde hiç görünmüyorlardı.
        ShelfManager.ensureBuiltIns(this)
        val shelves = ShelfManager.shelvesOf(this)
        if (shelves.isEmpty()) {
            Toast.makeText(this, R.string.raf_yok_once_ekle, Toast.LENGTH_LONG).show()
            return
        }
        // Kitabın ŞU AN durduğu raf → listede ✓ ile işaretlenir.
        val current = shelves.firstOrNull { book.id in it.bookIds }?.id
        val labels = shelves.map { s ->
            if (s.id == current) "✓ ${s.name}" else s.name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.raf_tasi_baslik)
            .setItems(labels) { _, i ->
                val target = shelves[i]
                if (target.id == current) return@setItems   // aynı raf: gereksiz yazma yok
                ShelfManager.moveBookToShelf(this, book.id, target.id)
                Toast.makeText(
                    this, getString(R.string.tasindi_raf, target.name),
                    Toast.LENGTH_SHORT
                ).show()
                reload()   // anında güncelleme
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    /** Kütüphane sıralaması (v24): alfabetik / en son eklenen. */
    private fun sortBooks(list: List<Book>): List<Book> = ShelfManager.sortForShelf(this, list)

    private fun setSort(mode: String) {
        AppPrefs.putString(this, AppPrefs.KEY_SORT, mode)
        applySortUi()
        applyFilter()   // rafların TAMAMI aynı sıraya girer
    }

    /** Seçili sıralama çipini vurgular. */
    private fun applySortUi() {
        val mode = AppPrefs.getString(this, AppPrefs.KEY_SORT, "added")
        val chips = listOf(
            binding.sortAlpha to "alpha",
            binding.sortAdded to "added"
        )
        chips.forEach { (view, m) ->
            val selected = m == mode
            view.setBackgroundResource(
                if (selected) R.drawable.bg_button_secondary else R.drawable.bg_input
            )
            view.setTextColor(
                // v5: aktif buton = ANA vurgu (sıcak turuncu)
                getColor(if (selected) R.color.umay_orange else R.color.umay_text_dim)
            )
        }
    }

    /**
     * HERO RAF (Son Okunan — ADIM 2): büyük kapak + başlık + sayfa bilgisi
     * + yüzde + ilerleme çubuğu. Kapağa dokununca kaldığı sayfadan açılır
     * (askResume akışı değişmedi). Kitap yoksa raf gizlenir.
     */
    /**
     * v15 (V5.1 ADIM B.5 / #1) — Raf tahtası temasını uygular.
     * ahşap (varsayılan) | metal | koyu — AppPrefs.KEY_SHELF_THEME'de saklı.
     */
    private fun applyShelfTheme() {
        val theme = AppPrefs.getString(this, AppPrefs.KEY_SHELF_THEME, "wood")
        val res = when (theme) {
            "metal" -> R.drawable.bg_shelf_plank_metal
            "dark" -> R.drawable.bg_shelf_plank_dark
            else -> R.drawable.bg_shelf_plank
        }
        // v17 (ONARIM): shelfPlank görünümü 3 raf düzeninde kaldırıldı; tema
        // artık raf başlığı kartlarına uygulanır (her shelf'in ilk çocuğu =
        // <include> edilen item_shelf_header).
        shelfViews.forEach { shelf ->
            if (shelf.childCount > 0) shelf.getChildAt(0).setBackgroundResource(res)
        }
    }

    private fun refreshContinueCard() {
        // v5.1: Hero kart her koşulda güvenli — hata durumunda kart gizlenir,
        // uygulama açılışta asla çökmez (stack trace'teki 244. satır koruması)
        try {
            refreshContinueCardInner()
        } catch (t: Throwable) {
            BookStore.logError(this, "refreshContinueCard", t)
            try { binding.heroWrap.visibility = View.GONE } catch (_: Throwable) {}
        }
    }

    /** v5.6 — "EN SON OKUNAN" kartında seçili kitap (yoksa null). */
    private fun heroBook(): Book? = heroBooks.getOrNull(heroIndex)

    /** v5.6 — Kartta kitap değiştirir (‹ eskiye, › yeniye doğru, döngüsel). */
    private fun shiftHero(delta: Int) {
        val size = heroBooks.size
        if (size <= 1) return
        heroIndex = ((heroIndex + delta) % size + size) % size
        refreshContinueCard()
    }

    /**
     * v5.6 — "EN SON OKUNAN" kartı (çizimdeki kahraman kart).
     * Liste: en son okunanlar (en yeni → eski). Hiç okunmamışsa son açılan
     * kitap gösterilir; hiçbiri yoksa kart tamamen gizlenir.
     */
    private fun refreshContinueCardInner() {
        val last = AppPrefs.getString(this, AppPrefs.KEY_LAST_BOOK)
        heroBooks = books
            .filter { AppPrefs.lastRead(this, it.id) > 0L }
            .sortedByDescending { AppPrefs.lastRead(this, it.id) }
            .ifEmpty { listOfNotNull(books.firstOrNull { it.id == last }) }
        if (heroBooks.isEmpty()) {
            heroIndex = 0
            binding.heroWrap.visibility = View.GONE
            return
        }
        if (heroIndex !in heroBooks.indices) heroIndex = 0
        val book = heroBooks[heroIndex]

        binding.continueTitle.text = book.title
        val progress = AppPrefs.loadProgress(this, book.id)
        val page = (progress?.first ?: 0) + 1
        // v32 — gerçek okunmuş sayfa sayısından yüzde (atlanan sayfa sayılmaz)
        ReadProgressStore.seedIfEmpty(this, book.id, progress?.first, book.pageCount)
        val heroPct = ReadProgressStore.percent(this, book.id, book.pageCount)
        binding.continueMeta.text = if (progress != null) {
            // Okunma oranı açıkça % işaretiyle: "%18 Okundu • Sayfa 3 / 50"
            getString(R.string.yuzde_ve_sayfa, heroPct, page, book.pageCount)
        } else {
            getString(R.string.henuz_okunmadi, book.pageCount)
        }

        // Yüzde bilgisi üst satırda zaten var → burada son okuma zamanı gösterilir
        binding.heroPercent.text = lastReadLabel(book.id)
        binding.heroPercent.visibility =
            if (binding.heroPercent.text.isBlank()) View.GONE else View.VISIBLE
        binding.heroProgress.progress = heroPct
        binding.heroPageCurrent.text = page.toString()
        binding.heroPageTotal.text = book.pageCount.toString()
        // Tek kitap varsa oklar yer kaplamaz ama düzen kaymaz (INVISIBLE)
        val multi = heroBooks.size > 1
        binding.heroPrev.visibility = if (multi) View.VISIBLE else View.INVISIBLE
        binding.heroNext.visibility = if (multi) View.VISIBLE else View.INVISIBLE
        binding.heroWrap.visibility = View.VISIBLE

        // Yedek kapak — gerçek kapak çözülene kadar kitap adı görünür
        binding.coverFallbackHeroTitle.text = book.title
        binding.coverFallbackHero.visibility = View.VISIBLE
        binding.continueCover.setImageResource(R.drawable.ic_pdf)
        binding.continueCover.tag = book.id
        loadCover(book.id) { bmp ->
            if (binding.continueCover.tag == book.id && bmp != null) {
                binding.continueCover.setImageBitmap(bmp)
                binding.coverFallbackHero.visibility = View.GONE
            }
        }
    }

    /** v5.6 — "Bugün 22:48" / "27.10.2026 21:10" biçiminde son okuma etiketi. */
    private fun lastReadLabel(bookId: String): String {
        val t = AppPrefs.lastRead(this, bookId)
        if (t <= 0L) return ""
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        val clock = android.text.format.DateFormat
            .getTimeFormat(this).format(java.util.Date(t))
        return if (sameDay) {
            getString(R.string.son_okuma_bugun, clock)
        } else {
            val full = android.text.format.DateFormat
                .format("dd.MM.yyyy HH:mm", t).toString()
            getString(R.string.son_okuma_tarih, full)
        }
    }

    /** v5.6 — Raf başlığındaki ‹ › oklarını verilen yatay listeye bağlar. */
    private fun wireShelfArrows(header: View, rv: RecyclerView) {
        val step = (resources.displayMetrics.density * 220).toInt()
        header.findViewById<View>(R.id.shelfScrollPrev)?.setOnClickListener {
            rv.smoothScrollBy(-step, 0)
        }
        header.findViewById<View>(R.id.shelfScrollNext)?.setOnClickListener {
            rv.smoothScrollBy(step, 0)
        }
    }

    /** v5.6 — Ders şeridini bir kart boyu kaydırır. */
    private fun scrollCourses(direction: Int) {
        val step = (resources.displayMetrics.density * 142).toInt() * direction
        binding.courseList.smoothScrollBy(step, 0)
    }

    /** Kapak JPEG'ini arka planda çözer; boşsa eski kitaplar için bir kez üretir. */
    private fun loadCover(bookId: String, onLoaded: (android.graphics.Bitmap?) -> Unit) {
        coverExecutor.execute {
            val bmp: android.graphics.Bitmap? = try {
                val existing = BookStore.coverFile(this, bookId)
                val file = when {
                    existing.exists() && existing.length() > 0L -> existing
                    coverTried.add(bookId) -> BookStore.ensureCover(this, bookId)
                    else -> null
                }
                file?.let {
                    android.graphics.BitmapFactory.decodeFile(it.absolutePath)
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread { onLoaded(bmp) }
        }
    }

    private fun choosePdf() {
        // ADIM 1: çoklu format — PDF, DOCX, PPTX, eski DOC/PPT ve metin dosyaları
        pickPdf.launch(
            arrayOf(
                "application/pdf",
                "application/octet-stream",      // uzantısı tanınmayan dosyalar
                "text/plain",                    // TXT / LOG
                "text/markdown",                 // MD
                "text/csv",                      // CSV
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // DOCX
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", // PPTX
                "application/vnd.ms-powerpoint", // PPT (eski)
                "application/mspowerpoint",
                "application/powerpoint",
                "application/msword"             // DOC (eski)
            )
        )
    }

    private fun importPdf(uri: android.net.Uri) {
        binding.progressText.visibility = View.VISIBLE
        binding.progressText.text = getString(R.string.dosya_aliniyor)
        BookStore.importPdf(this, uri) { result ->
            runOnUiThread { handleImported(result) }
        }
    }

    private fun importImages(uris: List<android.net.Uri>) {
        binding.progressText.visibility = View.VISIBLE
        binding.progressText.text = getString(R.string.resim_cevriliyor)
        BookStore.importImages(
            this, uris,
            onProgress = { done, total ->
                runOnUiThread {
                    binding.progressText.text =
                        getString(R.string.sayfa_olusturuluyor, done, total)
                }
            },
            onDone = { result ->
                runOnUiThread { handleImported(result) }
            }
        )
    }

    /** Ortak içe aktarma sonucu işleyici. */
    private fun handleImported(result: Result<Book>) {
        result.fold(
            onSuccess = { book ->
                AppPrefs.putString(this, AppPrefs.KEY_LAST_BOOK, book.id)
                binding.progressText.visibility = View.GONE
                Toast.makeText(
                    this,
                    "${book.title} eklendi",
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            },
            onFailure = { e ->
                binding.progressText.visibility = View.GONE
                Toast.makeText(
                    this,
                    "Dosya alınamadı: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    /** Devam sorusu: Evet → kaldığın yerden, Hayır → baştan / yeni dosya. */
    private fun askResume(book: Book) {
        val progress = AppPrefs.loadProgress(this, book.id)
        if (progress == null) {
            openReader(book.id, 0, 0)
            return
        }
        val (page, sentence) = progress
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setMessage(getString(R.string.devam_sorusu, page + 1))
            .setPositiveButton(R.string.devam_et) { _, _ ->
                openReader(book.id, page, sentence)
            }
            .setNegativeButton(R.string.bastan_basla) { _, _ ->
                AppPrefs.clearProgress(this, book.id)
                openReader(book.id, 0, 0)
            }
            .setNeutralButton(R.string.yeni_dosya) { _, _ -> choosePdf() }
            .show()
    }

    private fun openReader(bookId: String, page: Int, sentence: Int) {
        AppPrefs.putString(this, AppPrefs.KEY_LAST_BOOK, bookId)
        AppPrefs.touchLastRead(this, bookId)   // "Son okunan" sıralaması için
        startActivity(
            Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                putExtra(ReaderActivity.EXTRA_PAGE, page)
                putExtra(ReaderActivity.EXTRA_SENTENCE, sentence)
            }
        )
    }

    private fun confirmDelete(book: Book) {
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setMessage(R.string.kitap_sil_soru)
            .setPositiveButton(R.string.kitap_sil) { _, _ ->
                BookStore.deleteBook(this, book.id)
                // v5.5: favori listesinde asılı kalmasın
                FavoritesStore.onBookDeleted(this, book.id)
                reload()
                // v6 — onay bildirimi (Aşama 3): liste anında güncellenir + Snackbar
                try {
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root,
                        getString(R.string.kitap_silindi, book.title),
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                    )
                        .setActionTextColor(getColor(R.color.umay_orange))
                        .show()
                } catch (t: Throwable) {
                    BookStore.logError(this, "deleteSnackbar", t)
                }
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    private fun openChatForLastBook() {
        val last = AppPrefs.getString(this, AppPrefs.KEY_LAST_BOOK)
        val target = last.takeIf { id -> books.any { it.id == id } } ?: books.firstOrNull()?.id
        if (target == null) {
            Toast.makeText(this, "Önce bir PDF seç", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_BOOK_ID, target)
            }
        )
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private val requestLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // ── ExtractionBus ────────────────────────────────────────

    override fun onProgress(bookId: String, done: Int, total: Int, stage: String) {
        // v21 (DENETİM BUG-005) — ARAYÜZ TİTREMESİNİ ÖNLE: çıkarma/OCR
        // sayfa başına onlarca kez çağrılıyor; her metin değişiminde
        // LinearLayout yeniden yerleşiyor ve rafalr aşağı-zıplıyordu.
        // Artık ~%5'de bir (en az 3 sayfada bir) güncelleme yapılır.
        val step = if (total > 20) total / 20 else 1
        if (done != total && done % step != 0) return
        runOnUiThread {
            val prefix = if (stage == "ocr") getString(R.string.ocr_yapiliyor, done, total)
            else getString(R.string.metin_cikariliyor, done, total)
            binding.progressText.visibility = View.VISIBLE
            binding.progressText.text = prefix
        }
    }

    override fun onFinished(bookId: String) {
        runOnUiThread {
            binding.progressText.visibility = View.GONE
        }
    }

    // ── Liste ────────────────────────────────────────────────

    /** Ders kartı rafı (ADIM 2): isim + istatistik + sıradaki ders bilgisi. */
    class CoursesAdapter(
        val onClick: (Course) -> Unit
    ) : RecyclerView.Adapter<CoursesAdapter.VH>() {

        private var items: List<Course> = emptyList()

        fun submit(list: List<Course>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.courseName)
            val meta: TextView = v.findViewById(R.id.courseMeta)
            val next: TextView = v.findViewById(R.id.courseNext)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_course, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val course = items[position]
            val ctx = holder.itemView.context
            holder.name.text = course.name

            // v14 (V5.0 §8) — Farklı renklerde çerçeveli kartlar (rotasyon)
            val cardBgs = intArrayOf(
                R.drawable.bg_course_card_0,
                R.drawable.bg_course_card_1,
                R.drawable.bg_course_card_2,
                R.drawable.bg_course_card_3,
                R.drawable.bg_course_card_4
            )
            holder.itemView.setBackgroundResource(cardBgs[position % cardBgs.size])

            val stats = CourseStore.statsOf(ctx, course.id)
            holder.meta.text = ctx.getString(
                R.string.ders_istatistik, stats.docs, stats.homework, stats.exams
            )

            // Bir sonraki ders hangi gün/saat?
            val now = System.currentTimeMillis()
            val next = CourseStore.slotsOf(ctx)
                .filter { it.courseId == course.id }
                .map { it to Academy.nextOccurrence(it.day, it.minuteOfDay, now) }
                .minByOrNull { it.second }
            if (next != null) {
                holder.next.visibility = View.VISIBLE
                holder.next.text = ctx.getString(
                    R.string.sonraki_ders,
                    // (v16) Adaptör iç içe sınıftır (`inner` değil) → dış Activity
                    // referansı YOK; eldeki `ctx` kullanılır.
                    Academy.dayName(next.first.day, LangHelper.isEnglish(ctx)),
                    Academy.formatClock(next.first.minuteOfDay)
                )
            } else {
                holder.next.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onClick(course) }
        }
    }

    /**
     * Arşiv rafı hücresi (ADIM 2): kapak + dairesel yüzde rozeti + başlık.
     * Tıklama → kaldığın yerden devam; uzun basma → sil.
     */
    class BooksAdapter(
        val onClick: (Book) -> Unit,
        val onLongClick: (Book) -> Unit,
        val loadCover: (String, (android.graphics.Bitmap?) -> Unit) -> Unit,
        /** v24 — rafın neon rengi (yüzde rozeti bu renkte yazılır). */
        private val accent: Int = 0
    ) : RecyclerView.Adapter<BooksAdapter.VH>() {

        private var items: List<Book> = emptyList()

        fun submit(list: List<Book>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.bookName)
            val meta: TextView = v.findViewById(R.id.bookMeta)
            val ring: ProgressRingView = v.findViewById(R.id.bookRing)
            val row: View = v.findViewById(R.id.bookRow)
            val cover: android.widget.ImageView = v.findViewById(R.id.bookCover)
            val coverFallback: View = v.findViewById(R.id.coverFallback)
            val coverFallbackTitle: TextView = v.findViewById(R.id.coverFallbackTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_book, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val book = items[position]
            val ctx = holder.itemView.context
            holder.name.text = book.title

            // Kapak önizlemesi: kapak üretilene dek Gradient Dark yedek şablon
            holder.cover.setImageResource(R.drawable.ic_pdf)
            holder.coverFallbackTitle.text = book.title
            holder.coverFallback.visibility = View.VISIBLE
            holder.cover.tag = book.id
            loadCover(book.id) { bmp ->
                if (holder.cover.tag == book.id && bmp != null) {
                    holder.cover.setImageBitmap(bmp)
                    holder.coverFallback.visibility = View.GONE
                }
            }

            val progress = AppPrefs.loadProgress(ctx, book.id)
            // Dairesel yüzde rozeti: %100 → turuncu, diğerleri turkuaz
            // v32 — okunan sayfa sayısına göre (sayfa atlamak yüzdeyi şişirmez)
            ReadProgressStore.seedIfEmpty(ctx, book.id, progress?.first, book.pageCount)
            val pct = ReadProgressStore.percent(ctx, book.id, book.pageCount)
            if (accent != 0) holder.ring.setAccent(accent)
            holder.ring.setPercent(pct)
            holder.meta.text = if (progress != null) {
                // v5: okunma oranında açıkça % işareti ("%18 Okundu • Sayfa 3 / 50")
                ctx.getString(R.string.yuzde_ve_sayfa, pct, progress.first + 1, book.pageCount)
            } else {
                ctx.getString(R.string.henuz_okunmadi, book.pageCount)
            }

            holder.row.setOnClickListener { onClick(book) }
            holder.row.setOnLongClickListener {
                onLongClick(book)
                true
            }
            // v17 (ONARIM): Hücrenin TÜMÜ (başlık/kapak boşlukları dahil)
            // tıklanabilir — eski kodda yalnız 118dp'lik `bookRow` alanı
            // dinliyordu, kart kenarına dokunmak "tepki vermiyor" görünüyordu.
            holder.itemView.setOnClickListener { onClick(book) }
            holder.itemView.setOnLongClickListener {
                onLongClick(book)
                true
            }
        }
    }
}
