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
    private var captureIndex = 0
    private val thumbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "umay-scan-thumb").apply { isDaemon = true }
    }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = pendingUri
            pendingUri = null
            if (ok && uri != null && File(uri.path ?: "").exists()) {
                pages.add(uri)
                adapter.notifyItemInserted(pages.size - 1)
                binding.scanHint.text = getString(R.string.tara_hazir, pages.size)
                binding.recyclerPages.scrollToPosition(pages.size - 1)
            } else if (!ok) {
                // Kullanıcı iptal etti → bir sonraki çekim temiz başlar
                uri?.let { try { File(it.path ?: "").delete() } catch (_: Exception) {} }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        captureIndex = File(filesDir, "capture").listFiles()?.size ?: 0

        binding.btnBack.setOnClickListener { finish() }
        adapter = PagesAdapter()
        binding.recyclerPages.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerPages.adapter = adapter

        binding.btnCapture.setOnClickListener { capturePage() }
        binding.btnPdf.setOnClickListener { buildPdf() }
    }

    override fun onDestroy() {
        thumbExecutor.shutdownNow()
        super.onDestroy()
    }

    /** Sıradaki sayfayı kamerada açar. */
    private fun capturePage() {
        try {
            val dir = File(filesDir, "capture").apply { mkdirs() }
            captureIndex++
            val file = File(dir, "sayfa_%04d.jpg".format(captureIndex))
            if (file.exists()) file.delete()
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            pendingUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            pendingUri = null
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

        BookStore.importImages(
            this, pages.toList(),
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
                            Toast.makeText(
                                this, "${book.title} eklendi", Toast.LENGTH_SHORT
                            ).show()
                            // Doğrudan okuma ekranına geç → kuyruğa alınıp okunsun
                            startActivity(
                                Intent(this, ReaderActivity::class.java).apply {
                                    putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                                    putExtra(ReaderActivity.EXTRA_PAGE, 0)
                                }
                            )
                            finish()
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
    private fun thumbOf(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > 512) sample *= 2
        val dec = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, dec)
        }
    } catch (e: Exception) {
        null
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
