package com.umay.pdfreader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.view.LayoutInflater
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.umay.pdfreader.databinding.ActivityReaderBinding
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Okuma ekranı: sayfa görüntüleme + seslendirme kontrolleri.
 * Seslendirme ReadService'te çalışır; ekran kapalıyken devam eder.
 */
class ReaderActivity : AppCompatActivity(), ReadService.Listener, ExtractionBus.Listener {

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_PAGE = "page"
        const val EXTRA_SENTENCE = "sentence"
    }

    private lateinit var binding: ActivityReaderBinding
    private lateinit var bookId: String
    private var bookTitle: String = ""

    private var service: ReadService? = null
    private var bound = false

    private var renderer: PdfRenderer? = null
    private var rendererPfd: android.os.ParcelFileDescriptor? = null
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private var renderedPage = -1

    private val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    private var speedIdx = 2
    private val voiceModes = arrayOf("auto", "female", "male")
    private var voiceIdx = 0

    // ── Sayfa senkronu ("Oku o sayfadan başlamıyor" bug'ı) ──
    /** Bu açılış taze mi? (dönüş/korner değişikliği değil) */
    private var freshLaunch = true
    /** Ekranda görünen sayfa (0 tabanlı) — okuma bundan başlamalı. */
    private var displayedPage = 0
    /** Servis bağlanmadan atlanan sayfa (1 tabanlı); bağlanınca uygulanır. */
    private var pendingGotoPage: Int? = null

    // ── Karaoke: okunan cümlenin sayfa üzerinde vurgusu ────
    private var baseBitmap: Bitmap? = null          // temiz sayfa görüntüsü
    private var displayBmp: Bitmap? = null          // üzerine vurgu çizilen kopya
    private var boxes: List<LineBox> = emptyList()  // sayfanın satır kutuları
    private var boxesPage = -1                      // kutular hangi sayfaya ait
    private var highlightLines: List<Int> = emptyList()
    private var lastSentence: String? = null

    // ── ADIM 3: Yapay zekâ sayfa özeti + ses dalgası ──────
    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var summaryOpen = false
    private var summaryPage = -1
    private var summaryText: String? = null
    private var extraSpeaking = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as ReadService.LocalBinder).service
            service = svc
            bound = true
            svc.listeners.add(this@ReaderActivity)

            val page = intent.getIntExtra(EXTRA_PAGE, -1)
            val sentence = intent.getIntExtra(EXTRA_SENTENCE, -1)
            when {
                // Kitaptan farklı bir kitap açık → niyet sayfası ya da kayıtlı ilerleme
                svc.bookId != bookId -> {
                    if (page >= 0) {
                        svc.openBook(bookId, page, sentence.coerceAtLeast(0))
                    } else {
                        // Bildirimden geldi (sayfa extra'sı yok): kayıtlı ilerleme
                        val (p, s) = AppPrefs
                            .loadProgress(this@ReaderActivity, bookId) ?: Pair(0, 0)
                        svc.openBook(bookId, p, s)
                    }
                }
                // Aynı kitap + taze açılış + kullanıcı açıkça bir sayfa seçti
                // (Devam Et / Baştan Başla) → SEÇİMİ UYGULA.
                // Eski kod openBook'u atlıyordu: servis eski sayfada kalıyor,
                // "Oku" basınca ya baştan ya kaldığı yerden okuyordu.
                page >= 0 && freshLaunch -> svc.openBook(bookId, page, sentence.coerceAtLeast(0))
                // Aynı kitap, bildirimden dönüş vb. → servisin durumu korunur
            }

            // Servis bağlanmadan sayfa atlandıysa şimdi uygula
            pendingGotoPage?.let { svc.gotoPage(it) }
            pendingGotoPage = null
            freshLaunch = false
            syncFromService(svc)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.listeners?.remove(this@ReaderActivity)
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        freshLaunch = savedInstanceState == null
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        bookTitle = BookStore.loadBooks(this).firstOrNull { it.id == bookId }?.title ?: "PDF"
        binding.bookTitle.text = bookTitle
        AppPrefs.touchLastRead(this, bookId)   // kütüphane "Son okunan" sıralaması için

        openRenderer()
        initControls()
        initZoom()

        // Servisi başlat ve bağlan
        startService(Intent(this, ReadService::class.java))
        bindService(
            Intent(this, ReadService::class.java),
            connection, Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        ExtractionBus.listeners.add(this)
    }

    override fun onPause() {
        super.onPause()
        ExtractionBus.listeners.remove(this)
    }

    override fun onDestroy() {
        aiScope.cancel()
        if (bound) {
            service?.listeners?.remove(this)
            unbindService(connection)
            bound = false
        }
        try { renderer?.close() } catch (_: Exception) {}
        try { rendererPfd?.close() } catch (_: Exception) {}
        renderExecutor.shutdownNow()
        super.onDestroy()
    }

    // ── PDF görüntüleme ──────────────────────────────────────

    private fun openRenderer() {
        try {
            val src = BookStore.sourceFile(this, bookId)
            rendererPfd = android.os.ParcelFileDescriptor.open(
                src, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            renderer = PdfRenderer(rendererPfd!!)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.sayfa_gosterilemiyor, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderPage(index: Int) {
        val r = renderer ?: return
        if (index == renderedPage) return
        renderedPage = index
        renderExecutor.execute {
            var page: PdfRenderer.Page? = null
            try {
                if (index >= r.pageCount) return@execute
                page = r.openPage(index)
                val p = page
                val maxW = resources.displayMetrics.widthPixels - 48.dp
                val maxH = (resources.displayMetrics.heightPixels * 0.52f).toInt()
                val scale = minOf(
                    maxW.toFloat() / p.width,
                    maxH.toFloat() / p.height
                ).coerceAtLeast(0.1f)
                val w = (p.width * scale).toInt().coerceAtLeast(1)
                val h = (p.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                // Karaoke için satır kutularını yükle (yoksa boş)
                val loaded = BookStore.readBoxes(this, bookId, index)
                runOnUiThread {
                    if (renderedPage != index) return@runOnUiThread   // sayfa çoktan değişmiş
                    resetZoom()   // yeni sayfada yakınlaştırma sıfırlanır
                    baseBitmap = bmp
                    displayBmp = null
                    boxes = loaded
                    boxesPage = index
                    recomputeHighlight()
                    showPageWithHighlight()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { page?.close() } catch (_: Exception) {}
            }
        }
    }

    /** Okunan cümleye göre hangi satırların vurgulanacağını hesaplar. */
    private fun recomputeHighlight() {
        val s = lastSentence
        highlightLines = if (s.isNullOrBlank() || boxesPage != renderedPage) {
            emptyList()
        } else {
            TextUtil.matchingLines(s, boxes.map { it.text })
        }
    }

    /**
     * Sayfa görüntüsünü (varsa vurguyla birlikte) ImageView'e basar.
     * Vurgu, zoom matrisinden bağımsız bitmap'e çizilir → zoom korunur.
     */
    private fun showPageWithHighlight() {
        val base = baseBitmap ?: return
        val display = displayBmp
            ?: base.copy(Bitmap.Config.ARGB_8888, true).also { displayBmp = it }
        val canvas = android.graphics.Canvas(display)
        // Önceki vurguyu temizle (temiz kopyayı yeniden çiz)
        canvas.drawBitmap(base, 0f, 0f, null)

        if (highlightLines.isNotEmpty()) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = getColor(R.color.umay_accent)
                alpha = 65
            }
            highlightLines.forEach { i ->
                val b = boxes.getOrNull(i) ?: return@forEach
                val rect = android.graphics.RectF(
                    b.x0 * display.width,
                    b.y0 * display.height,
                    b.x1 * display.width,
                    b.y1 * display.height
                )
                canvas.drawRoundRect(rect, 10f, 10f, paint)
            }
        }
        binding.pageImage.setImageBitmap(display)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ── Yakınlaştırma (pinch-to-zoom + sürükle + çift dokunuş) ──

    private var imageScale = 1f
    private var imageOffsetX = 0f
    private var imageOffsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTapAt = 0L

    // DİKKAT: Activity henüz instantiate edilirken context hazır OLMAZ;
    // bu yüzden detector lazy oluşturulur (ilk dokunuşta, onCreate sonrası).
    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                imageScale = (imageScale * detector.scaleFactor).coerceIn(1f, 8f)
                applyImageMatrix()
                return true
            }
        })
    }

    private fun initZoom() {
        binding.pageImage.scaleType = ImageView.ScaleType.MATRIX
        binding.pageImage.setOnTouchListener { v, ev ->
            scaleDetector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = ev.x
                    lastTouchY = ev.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && imageScale > 1f) {
                        imageOffsetX += ev.x - lastTouchX
                        imageOffsetY += ev.y - lastTouchY
                        lastTouchX = ev.x
                        lastTouchY = ev.y
                        applyImageMatrix()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // Çift dokunuş: yakınlaştırmayı sıfırla
                    val now = System.currentTimeMillis()
                    if (now - lastTapAt < 300) resetZoom()
                    lastTapAt = now
                    v.performClick()
                }
            }
            true
        }
    }

    private fun resetZoom() {
        imageScale = 1f
        imageOffsetX = 0f
        imageOffsetY = 0f
        applyImageMatrix()
    }

    private fun applyImageMatrix() {
        val m = Matrix()
        val cx = binding.pageImage.width / 2f
        val cy = binding.pageImage.height / 2f
        m.postScale(imageScale, imageScale, cx, cy)
        m.postTranslate(imageOffsetX, imageOffsetY)
        binding.pageImage.imageMatrix = m
    }

    // ── Kontroller ───────────────────────────────────────────

    private fun initControls() {
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnPlayPause.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            // Okuma HER ZAMAN ekrandaki sayfadan başlasın (senkron güvenlik ağı)
            if (!svc.playing && svc.currentPage != displayedPage) {
                svc.gotoPage(displayedPage + 1)
            }
            svc.toggle()
            binding.btnReadHere.visibility = View.GONE
        }
        binding.btnPrev.setOnClickListener { service?.prevPage() }
        binding.btnNext.setOnClickListener { service?.nextPage() }

        binding.btnGoPage.setOnClickListener {
            val n = binding.pageInput.text.toString().toIntOrNull()
            if (n != null && n > 0) jumpToPage(n)
            else Toast.makeText(this, "Geçerli bir sayfa yaz", Toast.LENGTH_SHORT).show()
        }

        // Klavyeden Enter (Git) ile de atlansın
        binding.pageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                binding.btnGoPage.performClick()
                true
            } else false
        }

        binding.pageSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                jumpToPage(sb.progress + 1)
            }
        })

        // Sayfadan okumaya başla: atlama sonrası duraklatıldıysa görünür
        binding.btnReadHere.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val typed = binding.pageInput.text.toString().toIntOrNull()
            val target = if (typed != null && typed > 0) typed else displayedPage + 1
            svc.gotoPage(target)
            binding.btnReadHere.visibility = View.GONE
            svc.play()
        }

        // Hız: − / + (yavaşlatma 0.5x'e kadar)
        binding.btnSpeedDown.setOnClickListener {
            if (speedIdx > 0) {
                speedIdx--
                applySpeed(speeds[speedIdx])
            }
        }
        binding.btnSpeedUp.setOnClickListener {
            if (speedIdx < speeds.size - 1) {
                speedIdx++
                applySpeed(speeds[speedIdx])
            }
        }

        // Ses → Renkleri paneli (ses + perde + hazır ayarlar + ekolayzır)
        binding.btnVoice.setOnClickListener { showSoundDialog() }

        // Kitap içi arama → sonuca tıklayınca o sayfaya git
        binding.btnSearch.setOnClickListener { showSearchDialog() }

        // Uyku zamanlayıcı
        binding.btnSleep.setOnClickListener { showSleepDialog() }

        // Çeviri aç/kapa
        binding.btnTranslate.setOnClickListener {
            val enabled = binding.btnTranslate.tag != "on"
            service?.setTranslate(enabled)
            applyTranslateUi(enabled)
        }

        binding.btnChat.setOnClickListener {
            openChatWithPage()
        }

        // ── ADIM 3: Yapay zekâ sayfa özeti paneli ──────────
        binding.btnSummary.setOnClickListener { toggleSummaryPanel() }
        binding.btnSummaryClose.setOnClickListener { closeSummary() }
        binding.btnSummaryListen.setOnClickListener { listenSummary() }
        binding.btnSummaryChat.setOnClickListener { openChatWithPage() }

        // Ayarlardaki seçimleri yansıt
        speedIdx = speeds.indexOfFirst { it == AppPrefs.getFloat(this, AppPrefs.KEY_RATE, 1f) }
            .let { if (it < 0) 2 else it }
        binding.btnSpeed.text = formatSpeed(speeds[speedIdx])

        val vm = AppPrefs.getString(this, AppPrefs.KEY_VOICE, "auto")
        voiceIdx = voiceModes.indexOf(vm).let { if (it < 0) 0 else it }
        binding.btnVoice.text = voiceLabel(voiceModes[voiceIdx])

        applyTranslateUi(AppPrefs.getBool(this, AppPrefs.KEY_TRANSLATE, false))
    }

    private fun applyTranslateUi(on: Boolean) {
        binding.btnTranslate.tag = if (on) "on" else "off"
        // Renk: aktif = cyan, pasif = gri
        val color = getColor(if (on) R.color.umay_accent else R.color.umay_text_dim)
        binding.btnTranslate.setTextColor(color)
    }

    private fun showSleepDialog() {
        val items = arrayOf(
            getString(R.string.kapali),
            "10 dk", "15 dk", "30 dk", "45 dk", "60 dk", "90 dk",
            getString(R.string.sayfada_bitir)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.uyku_zamanlayici)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        service?.applySleepMinutes(0)
                        binding.btnSleep.text = getString(R.string.kapali)
                    }
                    in 1..6 -> {
                        val minutes = intArrayOf(0, 10, 15, 30, 45, 60, 90)[which]
                        service?.applySleepMinutes(minutes)
                        service?.applySleepAtPageEnd(false)
                        binding.btnSleep.text = "${minutes}dk"
                    }
                    else -> {
                        service?.applySleepAtPageEnd(true)
                        binding.btnSleep.text = getString(R.string.sayfada_bitir)
                    }
                }
            }
            .show()
    }

    private fun formatSpeed(r: Float): String =
        if (r == r.toInt().toFloat()) "${r.toInt()}x" else "${r}x"

    /** Hız uygular + buton etiketini tazeler. */
    private fun applySpeed(r: Float) {
        service?.applyRate(r)
        binding.btnSpeed.text = formatSpeed(r)
    }

    /**
     * Sayfaya atlar; servis henüz bağlıysa doğrudan uygulanır,
     * değilse bağlanınca uygulanır. Duraklatıldıysa "buradan oku"
     * butonu belirir — kullanıcı tam olarak o sayfadan başlayabilir.
     */
    private fun jumpToPage(page1Based: Int) {
        val svc = service
        if (svc == null) pendingGotoPage = page1Based else svc.gotoPage(page1Based)
        if (service?.playing != true) {
            binding.btnReadHere.visibility = View.VISIBLE
        }
    }

    private fun voiceLabel(mode: String) = when (mode) {
        "female" -> getString(R.string.ses_kadin)
        "male" -> getString(R.string.ses_erkek)
        else -> getString(R.string.ses_otomatik)
    }

    /**
     * Kitap içi arama: sayfa metinlerinde arar, sonuca tıklayınca
     * o sayfaya atlar. Arama arka plan iş parçacığında çalışır.
     */
    private fun showSearchDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_search, null)
        val results = view.findViewById<android.widget.LinearLayout>(R.id.searchResults)
        val status = view.findViewById<TextView>(R.id.searchStatus)
        val query = view.findViewById<android.widget.EditText>(R.id.searchQuery)
        val dlg = AlertDialog.Builder(this).setView(view).create()

        fun runSearch() {
            val q = query.text.toString().trim()
            if (q.isEmpty()) return
            results.removeAllViews()
            status.text = getString(R.string.araniyor)
            val bid = bookId
            renderExecutor.execute {
                val hits = try {
                    TextUtil.search(BookStore.allPages(this, bid), q, 8)
                } catch (e: Exception) {
                    emptyList<TextUtil.Hit>()
                }
                runOnUiThread {
                    results.removeAllViews()
                    if (hits.isEmpty()) {
                        status.text = getString(R.string.sonuc_yok)
                        return@runOnUiThread
                    }
                    status.text = getString(R.string.sonuc_sayi, hits.size)
                    hits.forEach { hit ->
                        val row = TextView(this).apply {
                            text = getString(R.string.sayfa) + " ${hit.page + 1}" +
                                "  •  " + hit.sentence.take(110)
                            textSize = 13f
                            setTextColor(getColor(R.color.umay_text))
                            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                            setBackgroundResource(R.drawable.bg_input)
                        }
                        row.layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = 8.dp }
                        row.setOnClickListener {
                            jumpToPage(hit.page + 1)
                            dlg.dismiss()
                        }
                        results.addView(row)
                    }
                }
            }
        }

        view.findViewById<TextView>(R.id.btnDoSearch).setOnClickListener { runSearch() }
        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else false
        }
        dlg.show()
    }

    /**
     * Ses Renkleri paneli: ses seçimi + perde (pitch) + hazır ayarlar
     * + ekolayzır (yalnız Edge/mp3 modunda etkili). Değişiklikler anında
     * uygulanır ve AppPrefs'e kaydedilir.
     */
    private fun showSoundDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_sound, null)

        // ── Ses ──
        val curVoice = AppPrefs.getString(this, AppPrefs.KEY_VOICE, "auto")
        val radio = when (curVoice) {
            "female" -> R.id.voiceFemale
            "male" -> R.id.voiceMale
            else -> R.id.voiceAuto
        }
        view.findViewById<android.widget.RadioButton>(radio).isChecked = true
        view.findViewById<android.widget.RadioGroup>(R.id.voiceGroup)
            .setOnCheckedChangeListener { _, checkedId ->
                val mode = when (checkedId) {
                    R.id.voiceFemale -> "female"
                    R.id.voiceMale -> "male"
                    else -> "auto"
                }
                service?.setVoice(mode)
                binding.btnVoice.text = voiceLabel(mode)
            }

        // ── Perde ──
        val pitchSeek = view.findViewById<SeekBar>(R.id.pitchSeek)
        val pitchValue = view.findViewById<TextView>(R.id.pitchValue)
        fun pitchOf(progress: Int): Float = 0.5f + progress / 100f
        fun showPitch(p: Float) {
            pitchValue.text = "%.2f".format(java.util.Locale.US, p)
        }
        fun setPitch(p: Float) {
            pitchSeek.progress = ((p - 0.5f) * 100f).toInt().coerceIn(0, 150)
            showPitch(p)
            service?.applyPitch(p)
        }
        val curPitch = AppPrefs.getFloat(this, AppPrefs.KEY_PITCH, 1f)
        pitchSeek.progress = ((curPitch - 0.5f) * 100f).toInt().coerceIn(0, 150)
        showPitch(pitchOf(pitchSeek.progress))
        pitchSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val p = pitchOf(progress)
                    showPitch(p)
                    service?.applyPitch(p)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ── Hazır ayarlar ──
        view.findViewById<TextView>(R.id.presetNatural).setOnClickListener { setPitch(1.0f) }
        view.findViewById<TextView>(R.id.presetDeep).setOnClickListener { setPitch(0.85f) }
        view.findViewById<TextView>(R.id.presetChild).setOnClickListener { setPitch(1.3f) }
        view.findViewById<TextView>(R.id.presetVurgulu).setOnClickListener { setPitch(1.15f) }

        // ── Ekolayzır (kazanç -100..+100 ↔ SeekBar 0..40) ──
        val eqSwitch = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.eqSwitch)
        val eqLow = view.findViewById<SeekBar>(R.id.eqLow)
        val eqMid = view.findViewById<SeekBar>(R.id.eqMid)
        val eqHigh = view.findViewById<SeekBar>(R.id.eqHigh)
        fun gainToProgress(g: Float) = (20 + (g / 5f)).toInt().coerceIn(0, 40)
        eqSwitch.isChecked = AppPrefs.getBool(this, AppPrefs.KEY_EQ_ON, false)
        eqLow.progress = gainToProgress(AppPrefs.getFloat(this, AppPrefs.KEY_EQ_LOW, 0f))
        eqMid.progress = gainToProgress(AppPrefs.getFloat(this, AppPrefs.KEY_EQ_MID, 0f))
        eqHigh.progress = gainToProgress(AppPrefs.getFloat(this, AppPrefs.KEY_EQ_HIGH, 0f))

        fun applyEq() {
            service?.setEqualizer(
                eqSwitch.isChecked,
                (eqLow.progress - 20) * 5f,
                (eqMid.progress - 20) * 5f,
                (eqHigh.progress - 20) * 5f
            )
        }
        eqSwitch.setOnCheckedChangeListener { _, _ -> applyEq() }
        val eqListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyEq()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        eqLow.setOnSeekBarChangeListener(eqListener)
        eqMid.setOnSeekBarChangeListener(eqListener)
        eqHigh.setOnSeekBarChangeListener(eqListener)

        val dlg = AlertDialog.Builder(this).setView(view).create()
        view.findViewById<TextView>(R.id.btnSoundClose).setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    // ── Geri tuşu: okuma sürüyor mu? ────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val svc = service
        if (svc != null && svc.playing && svc.bookId == bookId) {
            AlertDialog.Builder(this)
                .setMessage("Okuma devam ediyor. Ne yapalım?")
                .setPositiveButton("Arka planda devam et") { _, _ -> finish() }
                .setNegativeButton("Çık ve durdur") { _, _ ->
                    svc.pause()
                    finish()
                }
                .setCancelable(true)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    // ── Servis durumu ────────────────────────────────────────

    private fun syncFromService(svc: ReadService) {
        applyCurrentPage(svc.currentPage, svc.pageCount)
        updatePlayIcon(svc.playing)
        binding.waveform.setActive(svc.playing || extraSpeaking)
        if (svc.rate in speeds) {
            speedIdx = speeds.indexOf(svc.rate)
            binding.btnSpeed.text = formatSpeed(svc.rate)
        }
        binding.btnVoice.text = voiceLabel(svc.voiceMode)
        applyTranslateUi(svc.translateEnabled)
        if (svc.sleepAtPageEnd) binding.btnSleep.text = getString(R.string.sayfada_bitir)
        else if (svc.sleepMinutes > 0) binding.btnSleep.text = "${svc.sleepMinutes}dk"
        else binding.btnSleep.text = getString(R.string.kapali)
    }

    override fun onState(page: Int, pageCount: Int, playing: Boolean, sentence: String?) {
        applyCurrentPage(page, pageCount)
        updatePlayIcon(playing)
        // ADIM 3: dalga çizgisi okuma durumuna göre hareketli/düz
        binding.waveform.setActive(playing || extraSpeaking)
        // Okuma sürerken "buradan oku" gereksiz
        if (playing) binding.btnReadHere.visibility = View.GONE
        // Karaoke: okunan cümleyi sayfada vurgula
        lastSentence = sentence
        recomputeHighlight()
        showPageWithHighlight()
        binding.sentencePreview.text = sentence ?: ""
        if (sentence.isNullOrBlank() && playing) {
            binding.pageStatus.visibility = View.VISIBLE
            binding.pageStatus.text = getString(R.string.metin_hazirlaniyor)
        } else {
            binding.pageStatus.visibility = View.GONE
        }
    }

    private fun applyCurrentPage(page: Int, pageCount: Int) {
        displayedPage = page
        // ADIM 3: özet açıkken sayfa değiştiyse paneli kapat
        // (eski sayfanın özeti yeni sayfada gösterilmesin)
        if (summaryOpen && summaryPage != page) closeSummary()
        if (pageCount > 0) {
            binding.pageIndicator.text = "${page + 1} / $pageCount"
            if (binding.pageSeek.max != pageCount - 1) {
                binding.pageSeek.max = (pageCount - 1).coerceAtLeast(0)
            }
            if (!binding.pageInput.hasFocus()) {
                binding.pageInput.setText("${page + 1}")
            }
            binding.pageSeek.progress = page
        }
        renderPage(page)
    }

    private fun updatePlayIcon(playing: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnPlayPause.setColorFilter(getColor(R.color.umay_bg))
    }

    override fun onInfo(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            if (msg.contains("bitti")) {
                binding.sentencePreview.text = getString(R.string.kitap_bitti)
            }
        }
    }

    override fun onBookFinished() {
        runOnUiThread {
            binding.sentencePreview.text = getString(R.string.kitap_bitti)
            binding.waveform.setActive(false)
        }
    }

    // ── ADIM 3: Yapay zekâ sayfa özeti + ses dalgası ──────

    /** Özet panelini aç/kapat. Sayfa değişmediyse eski özet yeniden kullanılır. */
    private fun toggleSummaryPanel() {
        if (summaryOpen) closeSummary() else openSummary()
    }

    private fun closeSummary() {
        summaryOpen = false
        binding.summaryCard.visibility = View.GONE
    }

    private fun openSummary() {
        summaryOpen = true
        binding.summaryCard.visibility = View.VISIBLE
        if (summaryPage == displayedPage && summaryText != null) {
            binding.summaryText.text = summaryText
            return
        }
        summarizePage(displayedPage)
    }

    /** Sayfanın metnini alır ve özetletir (ağ IO'da, UI kilitlenmeden). */
    private fun summarizePage(page: Int) {
        summaryPage = page
        summaryText = null
        binding.summaryText.text = getString(R.string.ozet_aliniyor)
        val bid = bookId
        aiScope.launch {
            val pageText = withContext(Dispatchers.IO) {
                BookStore.pageText(this@ReaderActivity, bid, page)
            }
            if (pageText.isNullOrBlank()) {
                binding.summaryText.text = getString(R.string.ozet_yok)
                return@launch
            }
            val result = withContext(Dispatchers.IO) { buildSummary(pageText, page) }
            summaryText = result
            if (summaryOpen && summaryPage == page) {
                binding.summaryText.text = result
            }
        }
    }

    /**
     * Özet üretir:
     *  - API anahtarı VARSA → Gemini (bağlam yalnızca bu sayfa).
     *  - Anahtar YOKSA veya istek hata verirse → çevrimdışı çıkarsal özet.
     * Hata asla boş ekran bırakmaz; sebep panelde yazılır.
     */
    private fun buildSummary(pageText: String, page: Int): String {
        AppPrefs.warmApiCache(this)
        val online = !AppPrefs.lastApiKey.isNullOrBlank()
        val offline = getString(R.string.ozet_cevrimdisi) + "\n\n" +
            TextUtil.summarize(listOf(pageText), 900)

        if (!online) return offline
        return try {
            ApiClient.ask(
                "Aşağıdaki ders sayfasını öğrenci için özetle: en fazla 5 madde, " +
                    "kısa, yalnızca verilen metne dayalı Türkçe. Kitapta olmayan " +
                    "bilgiyi ekleme.",
                listOf("Sayfa ${page + 1}: " + pageText.take(6000))
            )
        } catch (e: Exception) {
            getString(R.string.ozet_hata, e.message ?: "bağlantı yok") +
                "\n\n" + offline
        }
    }

    /** "▶ Dinle": özeti seslendirir (okuma duraklatılır, dalga çizgisi açılır). */
    private fun listenSummary() {
        val text = summaryText
        val svc = service
        if (svc == null || text.isNullOrBlank()) {
            Toast.makeText(this, R.string.ozet_hazir_degil, Toast.LENGTH_SHORT).show()
            return
        }
        svc.speakSummary(text)
        Toast.makeText(this, R.string.ozet_okunuyor, Toast.LENGTH_SHORT).show()
    }

    /** Panelden/alt baradan sohbet: açık sayfa bağlam olarak iletilir. */
    private fun openChatWithPage() {
        startActivity(
            Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_BOOK_ID, bookId)
                putExtra(ChatActivity.EXTRA_PAGE, displayedPage)
            }
        )
    }

    /** ADIM 3: özet seslendirmesi sürüyor mu? → dalga çizgisi durumu. */
    override fun onExtraSpeaking(active: Boolean) {
        runOnUiThread {
            extraSpeaking = active
            binding.waveform.setActive(active || service?.playing == true)
        }
    }

    // ── Çıkarım ilerlemesi ───────────────────────────────────

    override fun onProgress(bookId: String, done: Int, total: Int, stage: String) {
        if (bookId != this.bookId) return
        runOnUiThread {
            if (service?.playing != true) {
                binding.pageStatus.visibility = View.VISIBLE
                binding.pageStatus.text = if (stage == "ocr")
                    getString(R.string.ocr_yapiliyor, done, total)
                else getString(R.string.metin_cikariliyor, done, total)
            }
        }
    }

    override fun onFinished(bookId: String) {
        if (bookId != this.bookId) return
        runOnUiThread {
            binding.pageStatus.visibility = View.GONE
            // Karaoke kutuları bu turda üretilmiş olabilir → tazele
            if (renderedPage >= 0 && boxes.isEmpty()) {
                val idx = renderedPage
                renderedPage = -1
                renderPage(idx)
            }
        }
    }
}
