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
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
        /** v11: "Umay'da Sesli Dinle" — servis bağlanınca otomatik okuma başlar. */
        const val EXTRA_AUTO_PLAY = "auto_play"
        /** Okundu sayılmak için sayfanın ekranda kalması gereken süre. */
        const val DWELL_MS = 4_500L
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

            // v11 (GÖREV 1): "Umay'da Sesli Dinle" → servis hazır, okuma başlasın
            if (autoPlayRequested) {
                autoPlayRequested = false
                try {
                    if (!svc.playing) svc.play()
                } catch (t: Throwable) {
                    BookStore.logError(this@ReaderActivity, "autoPlay", t)
                }
            }
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
        EdgeToEdge.apply(this, binding.root)  // SDK 36 edge-to-edge

        // v5.4 — Okurken ekran kapanmasın (uzun sesli okuma oturumları).
        if (AppPrefs.getBool(this, AppPrefs.KEY_KEEP_SCREEN_ON, true)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        // v9 (M10/ANR): Kitap künyesi (SQLite okuma) ana thread'den DEĞİL
        // Dispatchers.IO'dan okunur; başlık hazır olunca bağlanır.
        binding.bookTitle.text = "PDF"
        aiScope.launch {
            val title = withContext(Dispatchers.IO) {
                try {
                    BookStore.loadBooks(this@ReaderActivity)
                        .firstOrNull { it.id == bookId }?.title ?: "PDF"
                } catch (t: Throwable) {
                    BookStore.logError(this@ReaderActivity, "loadBooks:title", t)
                    "PDF"
                }
            }
            bookTitle = title
            binding.bookTitle.text = title
        }
        AppPrefs.touchLastRead(this, bookId)   // kütüphane "Son okunan" sıralaması için

        openRenderer()
        initControls()
        setupSheet()
        initZoom()
        initFloatPill()   // v13 (T9e): yüzen Dinle / AI Özet rozeti

        // v11 (GÖREV 1): dışarıdan "Sesli Dinle" ile açıldıysa bağlanır bağlanmaz
        autoPlayRequested = intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)

        // Servisi başlat ve bağlan
        startService(Intent(this, ReadService::class.java))
        bindService(
            Intent(this, ReadService::class.java),
            connection, Context.BIND_AUTO_CREATE
        )
    }

    /** v11: "Umay'da Sesli Dinle" akışında servis hazır olunca okuma başlatılır. */
    private var autoPlayRequested = false

    override fun onResume() {
        super.onResume()
        ExtractionBus.listeners.add(this)
        // v21 — GERÇEK EKOLAYZIR: servis çalan mp3'ün genliğini yayınlar,
        // biz de oynatma konumuyla senkron çubukları süreriz.
        binding.waveform.removeCallbacks(eqPoller)
        binding.waveform.post(eqPoller)
    }

    override fun onPause() {
        super.onPause()
        ExtractionBus.listeners.remove(this)
        binding.waveform.removeCallbacks(eqPoller)
        stopKaraoke()   // v13 (A5): arka planda animasyon sürmesin
    }

    /**
     * v21 — EKOLAYZIR SÜRÜCÜSÜ.
     *
     * Edge/mp3 modunda: gerçek PCM zarfından gelen seviye (0..1).
     * Yerleşik TTS modunda ses verisine erişilemez → -1 döner ve görsel,
     * kelime ilerlemesine bağlı `pulse()` ile (gerçek okuma anında) hareket eder.
     * Hiçbir durumda "okuma yokken oynayan" sahte animasyon yoktur.
     */
    private val eqPoller = object : Runnable {
        override fun run() {
            try {
                val svc = service
                if (svc != null && (svc.playing || svc.extraSpeaking)) {
                    val lvl = svc.audioLevel()
                    if (lvl >= 0f) binding.waveform.pushLevel(lvl)
                }
            } catch (_: Throwable) {
            }
            binding.waveform.postDelayed(this, 60L)
        }
    }

    override fun onDestroy() {
        // v34 — ekran kapanırken kayıt açık kalmasın (dosya bozulmasın)
        try { if (recorder != null) stopRecording(false) } catch (_: Throwable) { }
        aiScope.cancel()
        stopKaraoke()
        if (bound) {
            service?.listeners?.remove(this)
            unbindService(connection)
            bound = false
        }
        // v5.4 (ÇÖKME ÖNLEMİ): Önce render işçisi durdurulur, SONRA renderer
        // kapatılır. Ters sırada, arka planda süren bir render kapatılmış
        // PdfRenderer'a erişip "Renderer is closed" / native crash üretiyordu.
        renderExecutor.shutdownNow()
        try { renderer?.close() } catch (_: Exception) {}
        try { rendererPfd?.close() } catch (_: Exception) {}
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
        if (index == renderedPage && baseBitmap != null) return
        renderedPage = index
        // v33 — GERÇEK OKUNMA YÜZDESİ (düzeltme): eskiden sayfa EKRANDA
        // göründüğü anda "okundu" sayılıyordu; 100 sayfalık kitapta 99'a
        // atlayınca yüzde %99 oluyordu. Artık sayfa en az DWELL_MS ekranda
        // kalırsa okundu sayılır; hızlıca geçilen sayfa sayılmaz.
        scheduleProgressMark(index, r.pageCount)
        renderExecutor.execute {
            var page: PdfRenderer.Page? = null
            try {
                if (index >= r.pageCount) return@execute
                page = r.openPage(index)
                val p = page
                val maxW = resources.displayMetrics.widthPixels - 48.dp
                // v34 — sayfa artık panel katlanınca büyüyebilsin: üst sınır
                // %52 → %72 (bellek: genişlik sınırlı olduğu için ~7-8 MB).
                val maxH = (resources.displayMetrics.heightPixels * 0.72f).toInt()
                val scale = minOf(
                    maxW.toFloat() / p.width,
                    maxH.toFloat() / p.height
                ).coerceAtLeast(0.1f)
                val w = (p.width * scale).toInt().coerceAtLeast(1)
                val h = (p.height * scale).toInt().coerceAtLeast(1)
                // v5.1 (ADIM B.3 / #12) — Bellek denetimi: ekranın ~%52'sine
                // ölçeklenmiş tek sayfa bitmap (in-sample mantığı) → 500+ sayfalık
                // PDF'te bile bellek sınırlı kalır.
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                // v13 (T9a): Gece okuma — koyu temada beyaz sayfa ters çevrilir
                val finalBmp = if (nightReading()) {
                    invertBitmap(bmp).also { bmp.recycle() }   // kaynak hemen serbest
                } else bmp
                // Karaoke için satır kutularını yükle (yoksa boş)
                val loaded = BookStore.readBoxes(this, bookId, index)
                runOnUiThread {
                    if (renderedPage != index) {
                        // sayfa çoktan değişmiş → bu bitmap kullanılmayacak, serbest bırak
                        finalBmp.recycle()
                        return@runOnUiThread
                    }
                    resetZoom()   // yeni sayfada yakınlaştırma sıfırlanır
                    val oldBase = baseBitmap
                    val oldDisp = displayBmp
                    baseBitmap = finalBmp
                    displayBmp = null
                    boxes = loaded
                    boxesPage = index
                    recomputeHighlight()
                    showPageWithHighlight()
                    // v5.1 (ADIM B.3 / #12) — Önceki sayfanın bitmap'lerini serbest
                    // bırak: ardışık sayfa geçişlerinde bellek şişmesin (OOM güvenli).
                    if (oldBase != null && oldBase !== finalBmp && !oldBase.isRecycled) {
                        oldBase.recycle()
                    }
                    if (oldDisp != null && oldDisp !== finalBmp && !oldDisp.isRecycled) {
                        oldDisp.recycle()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // v5.4: render başarısız olduysa sayfa "çizildi" sayılmasın —
                // aksi hâlde aynı sayfaya dönüldüğünde hiçbir şey çizilmiyordu.
                if (renderedPage == index) renderedPage = -1
            } finally {
                try { page?.close() } catch (_: Exception) {}
            }
        }
    }

    /** Okunan cümleye göre hangi satırların vurgulanacağını hesaplar. */
    /**
     * v33 — OKUNMA İŞARETİ GECİKMESİ. Sayfa [DWELL_MS] boyunca ekranda
     * kalırsa okundu sayılır (gerçekten bakılan sayfa). Sayfa değişirse
     * önceki bekleyen işaretleme iptal edilir.
     */
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var progressMarkPage = -1

    private fun scheduleProgressMark(page: Int, pageCount: Int) {
        progressMarkPage = page
        progressHandler.removeCallbacksAndMessages(null)
        progressHandler.postDelayed({
            try {
                if (progressMarkPage == page && renderedPage == page) {
                    ReadProgressStore.markRead(this, bookId, page, pageCount)
                }
            } catch (_: Throwable) { }
        }, DWELL_MS)
    }

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
            // v33 — okunan satır: yarı saydam neon şerit + altında kalın
            // renkli çizgi (tek başına soluk dolgu beyaz sayfada kayboluyordu).
            val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = getColor(R.color.umay_accent)
                alpha = 78
            }
            val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = getColor(R.color.umay_orange)
                strokeWidth = (2.2f * resources.displayMetrics.density).coerceAtLeast(2f)
                style = android.graphics.Paint.Style.STROKE
            }
            highlightLines.forEach { i ->
                val b = boxes.getOrNull(i) ?: return@forEach
                val rect = android.graphics.RectF(
                    b.x0 * display.width,
                    b.y0 * display.height,
                    b.x1 * display.width,
                    b.y1 * display.height
                )
                canvas.drawRoundRect(rect, 8f, 8f, fill)
                canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, line)
            }
        }
        binding.pageImage.setImageBitmap(display)
        // Sayfa boyutu değişti → merkezleme yeniden hesaplanır
        applyImageMatrix()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    /** v13 (T9a): Gece okuma etkin mi? (Ayarlar anahtarı + aktif gece modu) */
    private fun nightReading(): Boolean {
        if (!AppPrefs.getBool(this, AppPrefs.KEY_NIGHT_READER, true)) return false
        return (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** v13 (T9a): Beyaz↔siyah ters çevirme — gece okuma dostu koyu sayfa. */
    private fun invertBitmap(src: Bitmap): Bitmap = try {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        val pf = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        cv.drawBitmap(src, 0f, 0f, pf)
        src.recycle()
        out
    } catch (_: Throwable) {
        src
    }

    /** v13 (T9e): Görüntüleme modunda yüzen "Dinle / AI Özet" rozeti. */
    private fun initFloatPill() {
        binding.floatListen.setOnClickListener { binding.btnPlayPause.performClick() }
        binding.floatSummary.setOnClickListener { binding.btnSummary.performClick() }
    }

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

    /**
     * v5.4 (ONARIM): Sayfa bitmapi SOL-ÜST köşeye yapışıyordu (ekranın yarısı
     * boş kalıyordu). Artık fitCenter gibi davranır: bitmap görünümün
     * ortasına yerleşir, yakınlaştırma da bu merkeze göre uygulanır.
     */
    private fun applyImageMatrix() {
        val m = Matrix()
        val vw = binding.pageImage.width.toFloat()
        val vh = binding.pageImage.height.toFloat()
        val bmp = baseBitmap ?: displayBmp
        val dx: Float
        val dy: Float
        if (bmp != null && !bmp.isRecycled && vw > 0f && vh > 0f) {
            dx = (vw - bmp.width) / 2f
            dy = (vh - bmp.height) / 2f
        } else {
            dx = 0f
            dy = 0f
        }
        m.postScale(imageScale, imageScale, vw / 2f, vh / 2f)
        m.postTranslate(dx + imageOffsetX, dy + imageOffsetY)
        binding.pageImage.imageMatrix = m
    }

    // ── Kontroller ───────────────────────────────────────────

    // ── v34 — KATLANIR KONTROL PANELİ ────────────────────────
    // Kullanıcı isteği: okunan sayfa çok küçük kalıyordu. Panel katlanınca
    // sayfa alanı (weight=1) büyür; şerit her zaman görünür kalır.
    private var sheetCollapsed = false

    private fun setupSheet() {
        binding.sheetHandle.setOnClickListener { setSheetCollapsed(!sheetCollapsed) }
        binding.btnMiniPlay.setOnClickListener { binding.btnPlayPause.performClick() }
        binding.btnRecord.setOnClickListener { toggleRecording() }
        updateRecordLabel()
        // Okuyucu ilk açılışta sayfayı büyük göstersin
        setSheetCollapsed(true)
    }

    // ── v34 — AR: DERS SES KAYDI ─────────────────────────────
    // Kullanıcı isteği: okuma ekranında "AR" kayıt düğmesi. Mikrofon izni
    // yalnız düğmeye basıldığında istenir; kayıt filesDir/audio altına yazılır
    // ve istenirse dersin 🎧 SES KAYITLARI rafına eklenir.
    private var recorder: android.media.MediaRecorder? = null
    private var recordFile: java.io.File? = null
    private var recordStartedAt = 0L
    private val recordTick = object : Runnable {
        override fun run() {
            if (recorder == null) return
            updateRecordLabel()
            recordHandler.postDelayed(this, 1000L)
        }
    }
    private val recordHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording() else Toast.makeText(
                this,
                "Kayıt için mikrofon izni gerekli (Ayarlar → İzinler)",
                Toast.LENGTH_LONG
            ).show()
        }

    private fun toggleRecording() {
        if (recorder != null) stopRecording(true) else ensureMicAndRecord()
    }

    private fun ensureMicAndRecord() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        try {
            val dir = CourseAudioStore.audioDir(this)
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val f = java.io.File(dir, "kayit_$stamp.m4a")
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.media.MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordFile = f
            recordStartedAt = android.os.SystemClock.uptimeMillis()
            recordHandler.post(recordTick)
            updateRecordLabel()
            Toast.makeText(this, "Kayıt başladı — bitirmek için tekrar dokun", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            BookStore.logError(this, "startRecording", t)
            try { recorder?.release() } catch (_: Throwable) { }
            recorder = null
            recordFile = null
            updateRecordLabel()
            Toast.makeText(this, "Kayıt başlatılamadı", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording(askWhere: Boolean) {
        val r = recorder ?: return
        recorder = null
        recordHandler.removeCallbacks(recordTick)
        try { r.stop() } catch (_: Throwable) { }
        try { r.release() } catch (_: Throwable) { }
        val f = recordFile
        recordFile = null
        updateRecordLabel()
        if (f == null || !f.exists() || f.length() < 1024L) {
            try { f?.delete() } catch (_: Throwable) { }
            Toast.makeText(this, "Kayıt çok kısa/boş — kaydedilmedi", Toast.LENGTH_SHORT).show()
            return
        }
        val secs = ((android.os.SystemClock.uptimeMillis() - recordStartedAt) / 1000L)
        Toast.makeText(this, "Kayıt kaydedildi (${secs}s)", Toast.LENGTH_SHORT).show()
        if (askWhere) askAddRecordingToCourse(f)
    }

    /** Kaydı ders rafına eklemek ister misin? (dosya zaten uygulama deposunda) */
    private fun askAddRecordingToCourse(f: java.io.File) {
        val courses = try { CourseStore.courses(this) } catch (_: Throwable) { emptyList() }
        if (courses.isEmpty()) return
        val names = courses.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Kaydı ders rafına ekle")
            .setItems(names) { _, which ->
                val c = courses.getOrNull(which) ?: return@setItems
                val clip = CourseAudioStore.addExisting(this, c.id, f, f.name)
                Toast.makeText(
                    this,
                    if (clip != null) "🎧 ${c.name} ses kayıtları rafına eklendi"
                    else "Rafa eklenemedi",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNeutralButton("Sadece kaydet", null)
            .show()
    }

    private fun updateRecordLabel() {
        val r = recorder
        binding.btnRecord.text = if (r == null) {
            getString(R.string.ses_kaydi_baslat)
        } else {
            val s = ((android.os.SystemClock.uptimeMillis() - recordStartedAt) / 1000L)
            "%s  %02d:%02d".format(getString(R.string.ses_kaydi_durdur), s / 60, s % 60)
        }
    }

    private fun setSheetCollapsed(collapsed: Boolean) {
        sheetCollapsed = collapsed
        val body = binding.controlBody
        val d = resources.displayMetrics.density
        if (collapsed) {
            body.animate()
                .alpha(0f)
                .translationY(12f * d)
                .setDuration(130L)
                .withEndAction {
                    body.visibility = View.GONE
                    body.alpha = 1f
                    body.translationY = 0f
                }
                .start()
            binding.sheetToggle.text = "▾  Kontroller"
        } else {
            body.visibility = View.VISIBLE
            body.alpha = 0f
            body.animate().alpha(1f).setDuration(180L).start()
            binding.sheetToggle.text = "▴  Kontroller"
        }
        // Sayfa alanının yüksekliği değişti → görüntüyü yeniden ortala
        binding.pageImage.postDelayed({ try { applyImageMatrix() } catch (_: Throwable) { } }, 190L)
        binding.pageImage.postDelayed({ try { applyImageMatrix() } catch (_: Throwable) { } }, 450L)
    }

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
            // v25 (denetim) — sayfa alanı: baştaki/sondaki boşluk temizlenir,
            // girilen sayı kitap sınırına KIRPILIR (140 sayfalık kitaba 1402
            // yazılınca 140'a gider) ve atlama sonrası alan gerçek sayfayı
            // gösterip klavye kapanır. Eskiden alan "1402"de kalıyordu.
            val n = binding.pageInput.text.toString().trim().toIntOrNull()
            if (n != null && n > 0) {
                val total = (binding.pageSeek.max + 1).coerceAtLeast(1)
                val target = n.coerceAtMost(total)
                jumpToPage(target)
                binding.pageInput.setText(target.toString())
                binding.pageInput.clearFocus()
                hideKeyboard()
            } else {
                Toast.makeText(this, "Geçerli bir sayfa yaz", Toast.LENGTH_SHORT).show()
            }
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

        // v6 (Aşama 1.4) — Ekolayzır kısayolu: profesyonel ses panelini açar
        // (Hız + Perde + Bas/Orta/Tiz kaydırıcıları tek yerde)
        binding.btnEq.setOnClickListener { showSoundDialog() }

        // v6 (Aşama 3) — Okuma ekranından kitap silme
        binding.btnDelete.setOnClickListener { confirmDeleteBook() }

        // Kitap içi arama → sonuca tıklayınca o sayfaya git
        binding.btnSearch.setOnClickListener { showSearchDialog() }

        // Uyku zamanlayıcı
        binding.btnSleep.setOnClickListener { showSleepDialog() }

        // Çeviri aç/kapa
        binding.btnTranslate.setOnClickListener {
            val enabled = binding.btnTranslate.tag != "on"
            service?.setTranslate(enabled)
            applyTranslateUi(enabled)
            // v10 (M4): çeviri açıldıysa offline ML Kit modelini ARKA PLANDA
            // hazırla — ilk kullanımda indirme bitene kadar çeviri suskun kalmasın
            if (enabled) warmTranslateModel()
        }

        // v5.12 — Arapça sayfa OCR modu (meal / tefsir). Kitap başına saklanır.
        applyOcrUi(AppPrefs.ocrRead(this, bookId))
        binding.btnOcr.setOnClickListener { toggleArabicOcr() }

        binding.btnChat.setOnClickListener {
            openChatWithPage()
        }

        // ── ADIM 3: Yapay zekâ sayfa özeti paneli ──────────
        binding.btnSummary.setOnClickListener { toggleSummaryPanel() }
        binding.btnSummaryClose.setOnClickListener { closeSummary() }
        binding.btnSummaryListen.setOnClickListener { listenSummary() }
        binding.btnSummaryChat.setOnClickListener { openChatWithPage() }
        // v5 (ADIM D): özet sesi Duraklat / Devam / Durdur
        binding.btnSummaryPause.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (svc.summaryPaused) {
                svc.resumeSummary()
                binding.btnSummaryPause.setText(R.string.ozet_duraklat)
            } else {
                svc.pauseSummary()
                binding.btnSummaryPause.setText(R.string.ozet_devam)
            }
        }
        binding.btnSummaryStop.setOnClickListener { service?.stopSummary() }

        // ── v12 (YENİ ÖZELLİKLER) ─────────────────────────────
        // 🃏 AI çalışma kartları: açık sayfanın metninden test üret
        binding.btnCards.setOnClickListener {
            val pageText = try {
                BookStore.pageText(this, bookId, displayedPage) ?: ""
            } catch (_: Throwable) { "" }
            if (pageText.isBlank()) {
                Toast.makeText(this, R.string.flashcard_no_text, Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(this, FlashcardActivity::class.java).apply {
                        putExtra(FlashcardActivity.EXTRA_PAGE_TEXT, pageText)
                        putExtra(FlashcardActivity.EXTRA_PAGE_NO, displayedPage + 1)
                    }
                )
            }
        }
        // 📌 Yer imi paneli: sayfa işaretleyici + not
        binding.btnBookmark.setOnClickListener { showBookmarkPanel() }

        // 🍅 v15 (V5.1 ADIM B.1 / #19) — Pomodoro Odak Modu:
        // 25 dk geri sayım → nazik bildirim + AnalyticsStore + streak tik.
        binding.floatPomodoro.setOnClickListener {
            if (PomodoroManager.isRunning()) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.pomodoro_cancel_title)
                    .setMessage(R.string.pomodoro_cancel_text)
                    .setPositiveButton(R.string.pomodoro_yes) { _, _ ->
                        PomodoroManager.cancel { completed ->
                            runOnUiThread {
                                binding.floatPomodoro.setText(R.string.pomodoro_start)
                                if (!completed) {
                                    Toast.makeText(
                                        this, R.string.pomodoro_cancel_title,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton(R.string.pomodoro_no, null)
                    .show()
            } else {
                binding.floatPomodoro.text =
                    PomodoroManager.formatRemaining(
                        PomodoroManager.DEFAULT_MINUTES * 60_000L
                    )
                PomodoroManager.start(
                    this,
                    PomodoroManager.DEFAULT_MINUTES,
                    onTick = { ms ->
                        runOnUiThread {
                            binding.floatPomodoro.text = PomodoroManager.formatRemaining(ms)
                        }
                    },
                    onEnd = { _ ->
                        runOnUiThread {
                            binding.floatPomodoro.setText(R.string.pomodoro_start)
                        }
                    }
                )
            }
        }
        // 📄 Özeti dışa aktar (.md + paylaşım menüsü)
        binding.btnSummaryExport.setOnClickListener {
            val s = summaryText
            if (s.isNullOrBlank()) {
                Toast.makeText(this, R.string.ozet_aliniyor, Toast.LENGTH_SHORT).show()
            } else {
                val f = ExportManager.exportSummary(this, bookTitle, s)
                if (f != null) {
                    Toast.makeText(this, getString(R.string.export_done, f.name),
                        Toast.LENGTH_LONG).show()
                    ExportManager.shareFile(this, f)
                } else {
                    Toast.makeText(this, R.string.flashcard_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
        // v14 (V5.0 §3) — KİTABI PAYLAŞ (PDF → WhatsApp/Telegram/mesajlaşma)
        // Kitap başlığına veya alıntı butonuna UZUN basınca kaynak PDF
        // paylaşım menüsüne konur (FileProvider, güvenli kök: books/).
        fun shareBookPdf() {
            try {
                val src = BookStore.sourceFile(this, bookId)
                if (!src.exists()) {
                    Toast.makeText(this, R.string.flashcard_no_text, Toast.LENGTH_SHORT).show()
                    return
                }
                ExportManager.shareFile(this, src, "application/pdf")
                Toast.makeText(this, R.string.book_shared, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                BookStore.logError(this, "shareBookPdf", t)
            }
        }
        binding.bookTitle.setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.book_share)
                .setMessage(bookTitle)
                .setPositiveButton(R.string.kaydet) { _, _ -> shareBookPdf() }
                .setNegativeButton(R.string.kapat, null)
                .show()
            true
        }
        binding.btnSummaryQuote.setOnLongClickListener {
            shareBookPdf()
            true
        }

        // 💬 Alıntı kartı: özet metninin ilk cümlesinden şık PNG kart
        binding.btnSummaryQuote.setOnClickListener {
            val s = summaryText
            if (s.isNullOrBlank()) {
                Toast.makeText(this, R.string.ozet_aliniyor, Toast.LENGTH_SHORT).show()
            } else {
                val quote = s.lines().firstOrNull { it.isNotBlank() } ?: s
                val f = ExportManager.shareQuoteCard(this, quote, bookTitle)
                if (f != null) {
                    Toast.makeText(this, R.string.quote_done, Toast.LENGTH_SHORT).show()
                }
            }
        }

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
        // Renk: aktif = sıcak turuncu (ana vurgu), pasif = gri
        val color = getColor(if (on) R.color.umay_orange else R.color.umay_text_dim)
        binding.btnTranslate.setTextColor(color)
    }

    // ── v5.12: Arapça sayfa OCR modu ───────────────────────

    /** Buton görünümü: açıkken turuncu ve "AR ●". */
    private fun applyOcrUi(on: Boolean) {
        if (!::binding.isInitialized) return
        binding.btnOcr.tag = if (on) "on" else "off"
        binding.btnOcr.setTextColor(
            getColor(if (on) R.color.umay_orange else R.color.umay_text_dim)
        )
        binding.btnOcr.text = if (on) "AR ●" else getString(R.string.arapca_ocr_kisa)
    }

    /**
     * Arapça satırlar metin katmanında olmayan kitaplar (meal/tefsir) için
     * sayfa görüntüsünden okuma. Kitap başına saklanır; okuma sürüyorsa
     * sayfa yeniden yüklenir ki mod hemen etkili olsun.
     */
    private fun toggleArabicOcr() {
        val on = binding.btnOcr.tag != "on"
        if (!on) {
            AppPrefs.setOcrRead(this, bookId, false)
            applyOcrUi(false)
            Toast.makeText(this, R.string.arapca_ocr_kapali, Toast.LENGTH_SHORT).show()
            service?.let { if (it.playing) it.gotoPage(displayedPage + 1) }
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.arapca_ocr_baslik)
            .setMessage(R.string.arapca_ocr_aciklama)
            .setPositiveButton(R.string.panel_tamam) { _, _ ->
                AppPrefs.setOcrRead(this, bookId, true)
                applyOcrUi(true)
                Toast.makeText(this, R.string.arapca_ocr_acik, Toast.LENGTH_LONG).show()
                service?.let { if (it.playing) it.gotoPage(displayedPage + 1) }
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    /**
     * v10 (M4 — ÇEVRİMDIŞI ÇEVİRİ GARANTİSİ): Çeviri açıldığında ML Kit
     * en→tr modeli arka planda hazırlanır (ilk indirme internet ister,
     * sonrası tamamen çevrimdışı). Hazır değilse kullanıcı bilgilendirilir;
     * ReadService çeviriye rağmen boş/İngilizce kalırsa metni Türkçe
     * ses tonuyla değil orijinal dille okumaya devam eder (boşluk olmaz).
     */
    private fun warmTranslateModel() {
        aiScope.launch {
            val ok = try {
                TrTranslator.ensureModel()
            } catch (t: Throwable) {
                BookStore.logError(this@ReaderActivity, "warmTranslateModel", t)
                false
            }
            if (!ok && !isFinishing && !isDestroyed) {
                Toast.makeText(
                    this@ReaderActivity,
                    R.string.ceviri_model_hazirlanamadi,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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

    /**
     * v6 (Aşama 3) — Okuma ekranından kitap silme: onay → servisi durdur →
     * fiziksel dosya + kapak önbelleği + okuma geçmişi temizlenir → ekran kapanır.
     * Liste, MainActivity.onResume → reload() ile anında tazelenir.
     */
    private fun confirmDeleteBook() {
        AlertDialog.Builder(this)
            .setTitle(bookTitle)
            .setMessage(R.string.kitap_sil_soru)
            .setPositiveButton(R.string.kitap_sil) { _, _ ->
                try {
                    val svc = service
                    if (svc != null && svc.bookId == bookId) {
                        svc.pause()
                    }
                } catch (t: Throwable) {
                    BookStore.logError(this, "deleteBook/stopService", t)
                }
                try {
                    BookStore.deleteBook(this, bookId)
                } catch (t: Throwable) {
                    BookStore.logError(this, "deleteBook", t)
                }
                Toast.makeText(
                    this, getString(R.string.kitap_silindi, bookTitle), Toast.LENGTH_LONG
                ).show()
                finish()
            }
            .setNegativeButton(R.string.iptal, null)
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
    /** v25 — "Git" sonrası sayısal klavyeyi kapatır (ekranı kaplamasın). */
    private fun hideKeyboard() {
        try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(binding.pageInput.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

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

        // ── v6 Hız (Speed): 0.50x – 2.00x canlı kaydırıcı ──
        val speedSeek = view.findViewById<SeekBar>(R.id.speedSeek)
        val speedValue = view.findViewById<TextView>(R.id.speedValue)
        fun speedOf(p: Int): Float = (0.5f + p / 100f)
        val curRate = AppPrefs.getFloat(this, AppPrefs.KEY_RATE, 1f)
        speedSeek.progress = ((curRate - 0.5f) * 100f).toInt().coerceIn(0, 150)
        speedValue.text = "%.2f".format(java.util.Locale.US, speedOf(speedSeek.progress))
        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val r = speedOf(progress)
                    speedValue.text = "%.2f".format(java.util.Locale.US, r)
                    applySpeed(r)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

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

        // ── v16 (GÖREV 3) — Sayfa aralığı seçici (kaydedilir + sayfa atlar) ──
        val rangeBtn = view.findViewById<TextView>(R.id.btnTtsRange)
        fun rangeLabel(): String {
            val to = AppPrefs.ttsRangeTo(this, bookId)
            val from = AppPrefs.ttsRangeFrom(this, bookId)
            return if (to <= 0) getString(R.string.tts_range_all)
            else getString(R.string.tts_range_value, from, to)
        }
        rangeBtn.text = rangeLabel()
        rangeBtn.setOnClickListener {
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(64, 24, 64, 8)
            }
            val fromInput = android.widget.EditText(this).apply {
                hint = getString(R.string.tts_range_from)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(AppPrefs.ttsRangeFrom(this@ReaderActivity, bookId).toString())
            }
            val toInput = android.widget.EditText(this).apply {
                hint = getString(R.string.tts_range_to)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(AppPrefs.ttsRangeTo(this@ReaderActivity, bookId).toString())
            }
            box.addView(fromInput)
            box.addView(toInput)
            AlertDialog.Builder(this)
                .setTitle(R.string.tts_range_label)
                .setView(box)
                .setPositiveButton(R.string.kaydet) { _, _ ->
                    var f = (fromInput.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1)
                    val t = (toInput.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                    // Tutarlılık: bitiş açıksa başlangıç > bitiş OLAMAZ
                    if (t in 1 until f) f = t
                    AppPrefs.setTtsRange(this, bookId, f, t)
                    rangeBtn.text = rangeLabel()
                    // Başlangıç sayfasına ATLA ("15–40 arası" → 15'ten başla)
                    if (t <= 0 || f <= t) jumpToPage(f)
                    Toast.makeText(this, R.string.ay_kaydedildi, Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton(R.string.iptal, null)
                .show()
        }

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
        // v13 (AGENT 22 — A5): KARAOKE/BÜYÜTEÇ paneli — aktif cümle belirgin,
        // okunan kısım renk süpürmesiyle vurgulanır (eski silik kayma YOK)
        updateKaraoke(sentence, playing)
        if (sentence.isNullOrBlank() && playing) {
            binding.pageStatus.visibility = View.VISIBLE
            binding.pageStatus.text = getString(R.string.metin_hazirlaniyor)
        } else {
            binding.pageStatus.visibility = View.GONE
        }
    }

    // ── v13 (AGENT 22 — A5) — KARAOKE / BÜYÜTEÇ TAKİBİ ──────────
    //
    // v14 (V5.0 §2): EFEKT YÜKSELTİLDİ — artık CÜMLE DEĞİL AKTİF KELİME
    // büyütülür: okunan kelime puntoları ~%35 büyür + bold + turuncu olur;
    // okunmamış kelimeler silik (dim) kalır. Silik kayan ticker YOKTUR.
    // Kelime geçişi cümle süresine / kelime sayısına bölünerek tahmin edilir
    // (kelime bazlı TTS timing'i motor bağımsız çalışmaz — bilinçli yaklaşım).
    private var karaokeAnim: android.animation.ValueAnimator? = null

    private fun updateKaraoke(sentence: String?, playing: Boolean) {
        val v = binding.sentencePreview
        try {
            karaokeAnim?.cancel()
        } catch (_: Throwable) {
        }
        karaokeAnim = null
        if (sentence.isNullOrBlank()) {
            v.text = ""
            v.visibility = View.GONE
            return
        }
        v.visibility = View.VISIBLE
        val base = getColor(R.color.umay_text)
        val dim = getColor(R.color.umay_text_dim)
        val hot = getColor(R.color.umay_orange)
        val rate = (service?.rate ?: 1f).coerceIn(0.5f, 2f)

        // Kelime sınırlarını bul (Regex ile offset'ler: [start, end))
        val wordRe = Regex("\\S+")
        val bounds = wordRe.findAll(sentence).map { it.range.first to (it.range.last + 1) }.toList()
        val wordCount = bounds.size
        if (wordCount == 0) {
            v.text = sentence
            return
        }
        // v5.8 (EKOLAYZIR ONARIMI) — DURAKLATILDIĞINDA HAREKET YOK.
        // Eskiden bu fonksiyon `playing` parametresini hiç kullanmıyordu:
        // duraklatınca aynı cümle için animasyon yeniden başlıyor, animasyon
        // da her karede `waveform.pulse()` çağırdığı için çubuklar okuma
        // durmuş olsa bile oynamaya devam ediyordu (kullanıcı raporu).
        // Artık animasyon YALNIZ gerçekten ses çalarken kurulur; duraklatma /
        // bitiş durumunda cümle düz metin olarak gösterilir.
        // Kelime başına ~400ms (hız ile ölçeklenir)
        val dur = (((wordCount * 400f) / rate).coerceIn(1200f, 25_000f)).toLong()

        fun render(activeIdx: Int) {
            try {
                val span = android.text.SpannableString(sentence)
                val big = android.text.style.RelativeSizeSpan(1.5f)
                val bold = android.text.style.StyleSpan(android.graphics.Typeface.BOLD)
                val highlightBg = android.text.style.BackgroundColorSpan(getColor(R.color.umay_panel2))
                bounds.forEachIndexed { i, (s, e) ->
                    if (e > s) {
                        when {
                            i == activeIdx -> {
                                span.setSpan(
                                    android.text.style.ForegroundColorSpan(hot), s, e,
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                                span.setSpan(big, s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                span.setSpan(bold, s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                span.setSpan(highlightBg, s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            i < activeIdx -> span.setSpan(
                                android.text.style.ForegroundColorSpan(base), s, e,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            else -> span.setSpan(
                                android.text.style.ForegroundColorSpan(dim), s, e,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                }
                v.text = span
            } catch (_: Throwable) {
            }
        }

        if (!playing) {
            v.text = sentence
            return
        }

        var self: android.animation.ValueAnimator? = null
        val anim = android.animation.ValueAnimator.ofInt(0, wordCount - 1).apply {
            duration = dur
            var lastWord = -1
            addUpdateListener { a ->
                try {
                    val idx = a.animatedValue as Int
                    render(idx)
                    // v21 — Darbe YALNIZ kelime değişince (gerçek okuma ilerlemesi).
                    // Eskiden her karede çağrılıyordu → sahte sürekli oynama.
                    if (idx != lastWord) {
                        lastWord = idx
                        binding.waveform.pulse(0.95f)
                    }
                } catch (_: Throwable) {}
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    // Süpürme bitti → cümle düz renkte görünür kalsın
                    if (karaokeAnim === self) v.text = sentence
                }
            })
        }
        self = anim
        karaokeAnim = anim
        render(0)
        anim.start()
    }

    private fun stopKaraoke() {
        try {
            karaokeAnim?.cancel()
        } catch (_: Throwable) {
        }
        karaokeAnim = null
    }

    private fun applyCurrentPage(page: Int, pageCount: Int) {
        displayedPage = page
        try {
            binding.sheetPage.text =
                if (pageCount > 0) "${page + 1} / $pageCount" else ""
        } catch (_: Throwable) { }
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
        // v34 — katlanmış şeritteki mini oynat/duraklat da aynı durumu gösterir
        try {
            binding.btnMiniPlay.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
            binding.btnMiniPlay.setColorFilter(getColor(R.color.umay_on_accent))
        } catch (_: Throwable) { }
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
            // v6 (Aşama 6.1) — Biten kitap sonrası 5 yıldız iste (sürüm başına 1 kez)
            try {
                PlayCoreManager.maybeAskReview(this, "book_finished")
            } catch (t: Throwable) {
                BookStore.logError(this, "maybeAskReview", t)
            }
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

    // ── v12 (YENİ ÖZELLİK) — YER İMİ & SAYFA NOTLARI ────

    /**
     * Yer imi diyaloğu: bu sayfa işaretliyse kaldır / değilse ekle.
     * Yer imli sayfalarda not girişi görünür; kaydedince panel listeler.
     */
    private fun showBookmarkPanel() {
        try {
            val marked = BookmarkStore.has(this, bookId, displayedPage)
            val items = BookmarkStore.all(this, bookId)

            val msg = StringBuilder()
            if (items.isEmpty()) {
                msg.append(getString(R.string.bookmark_empty))
            } else {
                items.forEach { b ->
                    val noteTag = if (b.note.isNotBlank()) " — ${b.note}" else ""
                    msg.append("Sayfa ${b.page + 1}$noteTag\n")
                }
            }

            val input = EditText(this).apply {
                hint = getString(R.string.bookmark_note_hint)
                setSingleLine()
                if (marked) {
                    setText(items.firstOrNull { it.page == displayedPage }?.note ?: "")
                }
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.bookmark_open)
                .setMessage(msg.toString().trimEnd())
                .setView(input)
                .setPositiveButton(if (marked) R.string.bookmark_remove else R.string.bookmark_add) { _, _ ->
                    if (marked) {
                        BookmarkStore.remove(this, bookId, displayedPage)
                        Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
                    } else {
                        BookmarkStore.add(
                            this, bookId, displayedPage,
                            input.text.toString().trim()
                        )
                        Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.kapat, null)
                .show()
        } catch (t: Throwable) {
            BookStore.logError(this, "showBookmarkPanel", t)
        }
    }

    private fun openSummary() {
        summaryOpen = true
        binding.summaryCard.visibility = View.VISIBLE
        binding.btnSummaryEdit.setOnClickListener { editSummary() }
        // v13 (A2): kullanıcı tarafından DÜZENLENMİŞ özet varsa o gösterilir
        val edited = editedSummary(displayedPage)
        if (edited != null) {
            summaryPage = displayedPage
            summaryText = edited
            binding.summaryText.text = renderMarkdown(edited)
            return
        }
        if (summaryPage == displayedPage && summaryText != null) {
            binding.summaryText.text = renderMarkdown(summaryText!!)
            return
        }
        summarizePage(displayedPage)
    }

    // ── v13 (AGENT 22 — A2) — MARKDOWN/LATEX RENDER + DÜZENLEME ────────

    /** AI çıktısını Markdown/LaTeX olarak renklendirir (kod blokları monospace). */
    private fun renderMarkdown(text: String): CharSequence = try {
        MarkdownLite.render(
            text,
            getColor(R.color.umay_orange),
            getColor(R.color.umay_panel2),
            getColor(R.color.umay_accent)
        )
    } catch (_: Throwable) {
        text
    }

    private fun editedSummaryKey(page: Int) = "summary_edit_${bookId}_$page"

    /** Kullanıcının kaydettiği düzenlenmiş özet (yoksa null). */
    private fun editedSummary(page: Int): String? {
        val v = AppPrefs.getString(this, editedSummaryKey(page))
        return if (v.isNullOrBlank()) null else v
    }

    /**
     * v13 (A2): AI özeti KULLANICI TARAFINDAN düzenlenebilir.
     * Düzenleme panelde görünür ve sayfa başına saklanır; sonraki
     * açılışta üretilmiş özeten ÖNCE kayıtlı düzenleme gösterilir.
     */
    private fun editSummary() {
        val current = summaryText ?: editedSummary(displayedPage) ?: return
        val input = EditText(this).apply {
            setText(current)
            setSelection(current.length)
            minLines = 5
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.summary_edit_title)
            .setView(input)
            .setPositiveButton(R.string.kaydet) { _, _ ->
                val edited = input.text.toString()
                AppPrefs.putString(this, editedSummaryKey(displayedPage), edited)
                summaryText = edited
                summaryPage = displayedPage
                binding.summaryText.text = renderMarkdown(edited)
                Toast.makeText(this, R.string.summary_saved, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.summary_revert) { _, _ ->
                AppPrefs.putString(this, editedSummaryKey(displayedPage), "")
                summaryText = null
                summaryPage = -1
                summarizePage(displayedPage)
            }
            .setNegativeButton(R.string.kapat, null)
            .show()
    }

    /** Sayfanın metnini alır ve özetletir (ağ IO'da, UI kilitlenmeden). */
    private fun summarizePage(page: Int) {
        summaryPage = page
        summaryText = null
        binding.summaryText.text = getString(R.string.ozet_aliniyor)
        val bid = bookId
        aiScope.launch {
            try {
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
                    // v13 (A2): Markdown/LaTeX render (kod blokları renkli)
                    binding.summaryText.text = renderMarkdown(result)
                }
            } catch (t: Throwable) {
                // v5.1: Özet coroutine'inde istisna paneli ve uygulamayı düşürmesin
                BookStore.logError(this@ReaderActivity, "summarizePage", t)
                if (summaryOpen && summaryPage == page) {
                    binding.summaryText.text = getString(
                        R.string.ozet_hata, t.message ?: "bilinmeyen hata"
                    )
                }

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

        // v6 (Aşama 4) — AI günlük kota: doluysa ödüllü reklam teklif et,
        // çevrimdışı özete düş (Aşama 2.4 — asla çökme, asla boş ekran)
        if (!AppPrefs.aiQuotaConsume(this)) {
            runOnUiThread { AdMobManager.offerAiBonus(this) }
            return getString(R.string.ai_limit) + "\n\n" + offline
        }

        return try {
            val answer = ApiClient.ask(
                "Aşağıdaki ders sayfasını öğrenci için özetle: en fazla 5 madde, " +
                    "kısa, yalnızca verilen metne dayalı Türkçe. Kitapta olmayan " +
                    "bilgiyi ekleme.",
                listOf("Sayfa ${page + 1}: " + pageText.take(6000))
            )
            // v9 (M8+M9 — ASO): 3'ün katı başarılı özette ÖDÜLLÜ VİDEO gate
            // + Google Play In-App Review tetikleyicisi (sürüm başına 1 kez).
            try {
                val total = AppPrefs.bumpAiSummaryTotal(this)
                if (total % 3 == 0) {
                    runOnUiThread {
                        AdMobManager.showRewardedGate(
                            this,
                            "3 özeti tamamladın! Uygulamanın ücretsiz kalmasına destek ol."
                        ) { }
                        PlayCoreManager.maybeAskReview(this, "summary_$total")
                    }
                }
            } catch (t: Throwable) {
                BookStore.logError(this, "summaryMilestone", t)
            }
            answer
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
        showSummaryControls(true)
        Toast.makeText(this, R.string.ozet_okunuyor, Toast.LENGTH_SHORT).show()
    }

    /** v5 (ADIM D): Duraklat/Durdur butonlarını göster/gizle. */
    private fun showSummaryControls(show: Boolean) {
        binding.summaryControls.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSummaryPause.setText(R.string.ozet_duraklat)
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
            // v5 (ADIM D): özet sesi bitti → duraklat/durdur butonları gizlenir
            showSummaryControls(active)
            binding.waveform.setActive(active || service?.playing == true)
        }
    }

    // ── Çıkarım ilerlemesi ───────────────────────────────────

    override fun onProgress(bookId: String, done: Int, total: Int, stage: String) {
        if (bookId != this.bookId) return
        // v21 (DENETİM BUG-005): çıkarma/OCR sayfa başına tetikleniyor; her
        // olayda UI güncellemek gereksiz yeniden çizim yapıyordu. Şimdi ~%5'de
        // bir (en az 3 sayfada bir) güncelleme yapılır — bant sabit konumda
        // olduğu için boyut değişimi de ekranı zıplatmıyor.
        val step = if (total > 20) total / 20 else 1
        if (done != total && done % step != 0) return
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
