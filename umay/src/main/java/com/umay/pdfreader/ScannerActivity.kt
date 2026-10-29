package com.umay.pdfreader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.umay.pdfreader.databinding.ActivityScannerBinding
import java.io.File
import java.util.concurrent.Executors

/**
 * Kitap tarama ekranı: kamerayla sayfa sayfa çekilir, sayfa numarasına göre
 * sıralanıp tek PDF'e çevrilir ve kütüphaneye kitap olarak eklenir.
 * Taranmış sayfa olduğu için metin çıkarma otomatik OCR'e girer →
 * bitince okuma kuyruğuna alınır ve okunabilir.
 *
 * Kamera izni gerekmez: harici kamera uygulaması ACTION_IMAGE_CAPTURE ile
 * çalışır ve çıktıyı FileProvider URI'mıza yazar.
 */
class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding
    private val pages = mutableListOf<Uri>()
    private lateinit var adapter: PagesAdapter
    private var pendingUri: Uri? = null
    /** v5.4 (ONARIM): kameranın yazdığı GERÇEK dosya.
     *  Eski kod `File(uri.path).exists()` ile kontrol ediyordu; content:// URI'nin
     *  path'i (/files/capture/x.jpg) cihazda YOK → çekilen her sayfa sessizce
     *  atılıyordu ("çekiyorum ama eklenmiyor"). Artık dosya referansı tutulur. */
    private var pendingFile: java.io.File? = null
    private var captureIndex = 0
    private val thumbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "umay-scan-thumb").apply { isDaemon = true }
    }
    // v5.1 (B.4/#5) — OCR için ayrı tek iş parçacığı (ANR yok, thumb akışı bölünmez)
    private val ocrExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "umay-scan-ocr").apply { isDaemon = true }
    }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = pendingUri
            val file = pendingFile
            pendingUri = null
            pendingFile = null
            val saved = file != null && file.exists() && file.length() > 0
            if (ok && uri != null && saved) {
                pages.add(uri)
                adapter.notifyItemInserted(pages.size - 1)
                binding.scanHint.text = getString(R.string.tara_hazir, pages.size)
                binding.recyclerPages.scrollToPosition(pages.size - 1)
            } else {
                // İptal VEYA boş dosya (kamera yazamadı) → kalıntıyı temizle
                try { file?.takeIf { it.exists() && it.length() == 0L }?.delete() } catch (_: Exception) {}
                if (ok && !saved) {
                    Toast.makeText(
                        this, R.string.tara_kaydedilemedi, Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    /** v16 (YENİ GÖREV 6) — Galeriden ÇOKLU sayfa fotoğrafı seçimi. */
    /** v5.4 (ONARIM): çoklu seçim sınırı 2'ydi → kullanıcı 4-5 sayfa seçemiyordu.
     *  Sistemin izin verdiği en yüksek sayıya çıkarıldı (API 33+ için
     *  MediaStore.getPickImagesMaxLimit(), altında 100). */
    private val pickGallery = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxPickImages())
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        // Sıra GARANTİSİ (GÖREV 7): seçilen görseller capture dizinine
        // sayfa_XXXX.jpg olarak kopyalanır → PDF artan sırada birleşir.
        var added = 0
        try {
            val dir = File(filesDir, "capture").apply { mkdirs() }
            uris.forEach { u ->
                try {
                    captureIndex++
                    val dst = File(dir, "sayfa_%04d.jpg".format(captureIndex))
                    contentResolver.openInputStream(u)?.use { input ->
                        dst.outputStream().use { out -> input.copyTo(out) }
                    }
                    if (dst.exists() && dst.length() > 0) {
                        pages.add(
                            androidx.core.content.FileProvider.getUriForFile(
                                this, "$packageName.fileprovider", dst
                            )
                        )
                        added++
                    } else captureIndex--
                } catch (t: Throwable) {
                    captureIndex--
                    BookStore.logError(this, "pickGallery", t)
                }
            }
        } catch (t: Throwable) {
            BookStore.logError(this, "pickGallery(outer)", t)
        }
        if (added > 0) {
            adapter.notifyDataSetChanged()
            binding.scanHint.text = getString(R.string.tara_hazir, pages.size)
            binding.recyclerPages.scrollToPosition(pages.size - 1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)  // SDK 36 edge-to-edge

        captureIndex = nextCaptureIndex() - 1

        binding.btnBack.setOnClickListener { finish() }
        adapter = PagesAdapter()
        binding.recyclerPages.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerPages.adapter = adapter

        binding.btnCapture.setOnClickListener { capturePage() }
        // v16 (YENİ GÖREV 6) — galeri çoklu seçim (EXTRA_ALLOW_MULTIPLE eşdeğeri)
        binding.btnGallery.setOnClickListener {
            try {
                pickGallery.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            } catch (t: Throwable) {
                BookStore.logError(this, "btnGallery", t)
                Toast.makeText(this, R.string.tara_kamera_yok, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnPdf.setOnClickListener { buildPdf() }
    }

    override fun onDestroy() {
        thumbExecutor.shutdownNow()
        ocrExecutor.shutdownNow()
        super.onDestroy()
    }

    /** Çoklu seçim üst sınırı — sistem sınırını aşarsa IllegalArgumentException. */
    @Suppress("NewApi")
    private fun maxPickImages(): Int = try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.provider.MediaStore.getPickImagesMaxLimit().coerceAtLeast(2)
        } else 100
    } catch (_: Throwable) {
        100
    }

    /** Dosya adındaki en büyük sayfa numarası + 1 (silinen dosyalar yüzünden
     *  aynı adı EZMEK için sayı adedi değil, MAKSİMUM indeks kullanılır). */
    private fun nextCaptureIndex(): Int {
        val files = File(filesDir, "capture").listFiles() ?: return 1
        val max = files.mapNotNull {
            Regex("sayfa_(\\d{4})\\.jpg").find(it.name)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        return max + 1
    }

    /** Sıradaki sayfayı kamerada açar. */
    private fun capturePage() {
        try {
            val dir = File(filesDir, "capture").apply { mkdirs() }
            captureIndex = nextCaptureIndex()
            val file = File(dir, "sayfa_%04d.jpg".format(captureIndex))
            if (file.exists()) file.delete()
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            pendingUri = uri
            pendingFile = file
            takePicture.launch(uri)
        } catch (e: Exception) {
            pendingUri = null
            pendingFile = null
            BookStore.logError(this, "ScannerActivity.capture", e)
            Toast.makeText(this, R.string.tara_kamera_yok, Toast.LENGTH_LONG).show()
        }
    }

    /** Çekilen sayfalardan PDF yapıp kütüphaneye ekler ve okuma ekranını açar. */
    private fun buildPdf() {
        if (pages.isEmpty()) {
            Toast.makeText(this, R.string.tara_pdf_bos, Toast.LENGTH_SHORT).show()
            return
        }
        binding.scanProgress.visibility = View.VISIBLE
        binding.scanProgress.text = getString(R.string.resim_cevriliyor)
        binding.btnPdf.isEnabled = false
        binding.btnCapture.isEnabled = false

        // v5.1 (ADIM B.4 / #5) — Önce ML Kit ile OCR (kopyalanabilir metin
        // katmanı için), sonra PDF'e çevir. OCR hatası PDF üretimini asla
        // tıkamaz: boş metin listesi geçilir.
        binding.scanProgress.text = getString(R.string.scan_ocr_running)
        ocrExecutor.execute {
            // v33 — YAN/TERS/EĞİK ÇEKİM DÜZELTMESİ: her sayfa için yön
            // denenir, en okunur yön seçilir; düzeltilmiş kopya hem PDF'e
            // girer hem OCR metni oradan alınır. Flush olmazsa yön tespiti
            // atlanır ve eski davranış (orijinal) korunur.
            val uris = ArrayList<Uri>(pages.size)
            val texts = ArrayList<String?>(pages.size)
            pages.forEachIndexed { i, uri ->
                val fixed = try {
                    ScanUpright.normalize(this, uri, "sayfa_%04d".format(i + 1))
                } catch (_: Throwable) {
                    ScanUpright.Result(null, 0, null)
                }
                if (fixed.fixedFile != null) {
                    try {
                        uris.add(
                            androidx.core.content.FileProvider.getUriForFile(
                                this, "$packageName.fileprovider", fixed.fixedFile
                            )
                        )
                    } catch (_: Throwable) {
                        uris.add(uri)
                    }
                } else {
                    uris.add(uri)
                }
                texts.add(
                    if (!fixed.text.isNullOrBlank()) fixed.text
                    else recognizeTextSafe(uri)
                )
                runOnUiThread {
                    binding.scanProgress.text =
                        getString(R.string.scan_upright_progress, i + 1, pages.size)
                }
            }
            runOnUiThread {
                importScannedPages(texts, uris)
            }
        }
    }

    /** ML Kit metin tanıma — güvenlik ağılı (null = OCR yok, akış devam). */
    private fun recognizeTextSafe(uri: Uri): String? {
        // Blok gövdesi şart: icinde `?: return null` oldugu icin ifade
        // gövdesi (expression body) derleme hatasi veriyordu (B.7 hatasi).
        return try {
            val bmp = thumbOf(uri) ?: return null
            val recognizer = com.google.mlkit.vision.text.TextRecognition
                .getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                // ML Kit TextRecognizer process() sadece InputImage kabul eder;
                // Bitmap verilince overload bulunamaz (B.7 hatasi 2).
                val input = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
                val task = recognizer.process(input)
                val latch = java.util.concurrent.CountDownLatch(1)
                var text: String? = null
                task.addOnSuccessListener { result -> text = result.text; latch.countDown() }
                task.addOnFailureListener { latch.countDown() }
                latch.await(20, java.util.concurrent.TimeUnit.SECONDS)   // ANR koruması
                text
            } finally {
                try { recognizer.close() } catch (_: Throwable) {}
                if (!bmp.isRecycled) bmp.recycle()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** OCR metinleriyle birlikte PDF üretimini başlatır. */
    private fun importScannedPages(ocrTexts: List<String?>, uris: List<Uri> = pages.toList()) {
        // v16 (YENİ GÖREV 7) — Hiç metin yoksa (tahta/el yazısı/formül) kullanıcıya
        // NET uyarı: görüntü kitap oluşur ama sesli okuma çalışmaz.
        val anyText = ocrTexts.any { !it.isNullOrBlank() }
        if (!anyText) {
            runOnUiThread {
                Toast.makeText(this, R.string.ocr_no_text, Toast.LENGTH_LONG).show()
            }
        }
        BookStore.importImages(
            this, uris, ocrTexts = ocrTexts,
            onProgress = { done, total ->
                runOnUiThread {
                    binding.scanProgress.text =
                        getString(R.string.sayfa_olusturuluyor, done, total)
                }
            },
            onDone = { result ->
                runOnUiThread {
                    binding.scanProgress.visibility = View.GONE
                    binding.btnPdf.isEnabled = true
                    binding.btnCapture.isEnabled = true
                    result.fold(
                    onSuccess = { book ->
                        AppPrefs.putString(this, AppPrefs.KEY_LAST_BOOK, book.id)
                        // ADIM 1: PDF başarıyla oluştu ve doğrulandı → geçici çekim
                        // dosyalarını temizle (aksi halde her taramada filesDir/capture
                        // büyümeye devam eder); kütüphedeki source.pdf zaten kalıcıdır.
                        try {
                            File(filesDir, "capture").listFiles()?.forEach { it.delete() }
                            // v33 — düzeltilmiş kopyalar PDF'e girdi → geçici dosyaları sil
                            File(filesDir, "scanfix").listFiles()?.forEach { it.delete() }
                        } catch (_: Exception) {}
                            Toast.makeText(
                                this,
                                getString(R.string.ocr_pages_added, pages.size, if (anyText) "✓" else "—"),
                                Toast.LENGTH_SHORT
                            ).show()
                            // v9 (M8): OCR belge dönüşümü tamamlandı → ÖDÜLLÜ VİDEO
                            // gate; gate bitince doğrudan okuma ekranına geç.
                            // Gate asla akışı tıkamaz: reklam yoksa/devam derse açılır.
                            AdMobManager.showRewardedGate(
                                this,
                                "Belgen hazır! Kısa bir reklamla ücretsiz kalmasına destek ol."
                            ) {
                                runOnUiThread {
                                    // Doğrudan okuma ekranına geç → kuyruğa alınıp okunsun
                                    startActivity(
                                        Intent(this, ReaderActivity::class.java).apply {
                                            putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                                            putExtra(ReaderActivity.EXTRA_PAGE, 0)
                                        }
                                    )
                                    finish()
                                }
                            }
                        },
                        onFailure = { e ->
                            Toast.makeText(
                                this, e.message ?: "PDF oluşturulamadı",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
        )
    }

    /** Küçük önizleme (tam çözünürlük OOM riskine karşı örneklenir). */
    private fun thumbOf(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            // v9 (M6 — OOM SAFE): Bozuk/çözülemeyen görselde boyutlar 0 kalır;
            // "0 / sample" döngüsü sonuca varmaz ve tam çözünürlük decode denenir
            // → OOM. Boyut geçersizse HEMEN null dön (ızgara bozulmadan atlanır).
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / sample > 512) sample *= 2
            val dec = BitmapFactory.Options().apply { inSampleSize = sample }
            // v9 (M6): OOM olursa örnekleme iki katına çıkarılıp en fazla 3 kez
            // denenir; hiç çözülemezse null → o thumbnail atlanır, uygulama
            // asla düşmez.
            var bmp: Bitmap? = null
            for (attempt in 0 until 3) {
                try {
                    bmp = contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, dec)
                    }
                } catch (oom: OutOfMemoryError) {
                    bmp = null
                }
                if (bmp != null) break
                dec.inSampleSize *= 2
            }
            bmp
        } catch (e: Exception) {
            null
        } catch (oom: OutOfMemoryError) {
            null
        }
    }

    // ── Sayfa ızgarası ───────────────────────────────────────

    inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: android.widget.ImageView = v.findViewById(R.id.pageThumb)
            val num: android.widget.TextView = v.findViewById(R.id.pageNum)
            val del: android.widget.TextView = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_scan_page, parent, false)
            )

        override fun getItemCount(): Int = pages.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val uri = pages[position]
            holder.num.text = (position + 1).toString()
            holder.thumb.tag = uri
            thumbExecutor.execute {
                val bmp = thumbOf(uri)
                runOnUiThread {
                    if (holder.thumb.tag != uri) return@runOnUiThread  // artık o sayfa değil
                    if (bmp != null) holder.thumb.setImageBitmap(bmp)
                }
            }
            holder.del.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                pages.removeAt(pos)
                adapter.notifyItemRemoved(pos)
                adapter.notifyItemRangeChanged(pos, pages.size)
                binding.scanHint.text =
                    if (pages.isEmpty()) getString(R.string.tara_bos)
                    else getString(R.string.tara_hazir, pages.size)
            }
        }
    }
}
