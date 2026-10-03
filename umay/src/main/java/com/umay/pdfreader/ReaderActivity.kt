package com.umay.pdfreader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.umay.pdfreader.databinding.ActivityReaderBinding
import java.util.concurrent.Executors

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

    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    private var speedIdx = 1
    private val voiceModes = arrayOf("auto", "female", "male")
    private var voiceIdx = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as ReadService.LocalBinder).service
            service = svc
            bound = true
            svc.listeners.add(this@ReaderActivity)

            if (svc.bookId != bookId) {
                val page = intent.getIntExtra(EXTRA_PAGE, 0)
                val sentence = intent.getIntExtra(EXTRA_SENTENCE, 0)
                svc.openBook(bookId, page, sentence)
            }
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
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        bookTitle = BookStore.loadBooks(this).firstOrNull { it.id == bookId }?.title ?: "PDF"
        binding.bookTitle.text = bookTitle

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
                runOnUiThread {
                    resetZoom()   // yeni sayfada yakınlaştırma sıfırlanır
                    binding.pageImage.setImageBitmap(bmp)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { page?.close() } catch (_: Exception) {}
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ── Yakınlaştırma (pinch-to-zoom + sürükle + çift dokunuş) ──

    private var imageScale = 1f
    private var imageOffsetX = 0f
    private var imageOffsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTapAt = 0L

    private val scaleDetector =
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                imageScale = (imageScale * detector.scaleFactor).coerceIn(1f, 8f)
                applyImageMatrix()
                return true
            }
        })

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

        binding.btnPlayPause.setOnClickListener { service?.toggle() }
        binding.btnPrev.setOnClickListener { service?.prevPage() }
        binding.btnNext.setOnClickListener { service?.nextPage() }

        binding.btnGoPage.setOnClickListener {
            val n = binding.pageInput.text.toString().toIntOrNull()
            if (n != null && n > 0) service?.gotoPage(n)
            else Toast.makeText(this, "Geçerli bir sayfa yaz", Toast.LENGTH_SHORT).show()
        }

        binding.pageSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                service?.gotoPage(sb.progress + 1)
            }
        })

        // Hız döngüsü
        binding.btnSpeed.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            val r = speeds[speedIdx]
            service?.applyRate(r)
            binding.btnSpeed.text = formatSpeed(r)
        }

        // Ses döngüsü: Otomatik → Kadın → Erkek
        binding.btnVoice.setOnClickListener {
            voiceIdx = (voiceIdx + 1) % voiceModes.size
            val mode = voiceModes[voiceIdx]
            service?.setVoice(mode)
            binding.btnVoice.text = voiceLabel(mode)
        }

        // Uyku zamanlayıcı
        binding.btnSleep.setOnClickListener { showSleepDialog() }

        // Çeviri aç/kapa
        binding.btnTranslate.setOnClickListener {
            val enabled = binding.btnTranslate.tag != "on"
            service?.setTranslate(enabled)
            applyTranslateUi(enabled)
        }

        binding.btnChat.setOnClickListener {
            startActivity(
                Intent(this, ChatActivity::class.java).apply {
                    putExtra(ChatActivity.EXTRA_BOOK_ID, bookId)
                }
            )
        }

        // Ayarlardaki seçimleri yansıt
        speedIdx = speeds.indexOfFirst { it == AppPrefs.getFloat(this, AppPrefs.KEY_RATE, 1f) }
            .let { if (it < 0) 1 else it }
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

    private fun voiceLabel(mode: String) = when (mode) {
        "female" -> getString(R.string.ses_kadin)
        "male" -> getString(R.string.ses_erkek)
        else -> getString(R.string.ses_otomatik)
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
        binding.sentencePreview.text = sentence ?: ""
        if (sentence.isNullOrBlank() && playing) {
            binding.pageStatus.visibility = View.VISIBLE
            binding.pageStatus.text = getString(R.string.metin_hazirlaniyor)
        } else {
            binding.pageStatus.visibility = View.GONE
        }
    }

    private fun applyCurrentPage(page: Int, pageCount: Int) {
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
        runOnUiThread { binding.pageStatus.visibility = View.GONE }
    }
}
