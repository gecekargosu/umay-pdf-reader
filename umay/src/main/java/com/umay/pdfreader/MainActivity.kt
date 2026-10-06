package com.umay.pdfreader

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private lateinit var adapter: BooksAdapter
    private var books: List<Book> = emptyList()

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

        adapter = BooksAdapter(
            onClick = { book -> askResume(book) },
            onLongClick = { book -> confirmDelete(book) },
            loadCover = { id, cb -> loadCover(id, cb) }
        )
        // Arşiv rafı YATAY kaydırılır (ADIM 2 — dikey liste kaldırıldı)
        binding.bookList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.bookList.adapter = adapter

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

        // Kaldığın yerden devam kartı → doğrudan kaldığın sayfaya
        binding.btnContinue.setOnClickListener {
            val last = AppPrefs.getString(this, AppPrefs.KEY_LAST_BOOK)
            val book = books.firstOrNull { it.id == last } ?: return@setOnClickListener
            val (page, sentence) = AppPrefs.loadProgress(this, book.id) ?: Pair(0, 0)
            openReader(book.id, page, sentence)
        }

        // Sıralama çipleri
        binding.sortRecent.setOnClickListener { setSort("recent") }
        binding.sortAlpha.setOnClickListener { setSort("alpha") }
        binding.sortAdded.setOnClickListener { setSort("added") }

        // Kitaplarda arama
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = applyFilter()
        })

        requestNotifPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        reload()
        ExtractionBus.listeners.add(this)
    }

    override fun onPause() {
        super.onPause()
        ExtractionBus.listeners.remove(this)
    }

    private fun reload() {
        books = BookStore.loadBooks(this)
        val empty = books.isEmpty()
        applyFilter()
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        binding.bookList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.searchInput.visibility = if (empty) View.GONE else View.VISIBLE
        binding.sortRow.visibility = if (empty) View.GONE else View.VISIBLE
        binding.libraryLabel.visibility = if (empty) View.GONE else View.VISIBLE
        applySortUi()
        refreshContinueCard()
        // Devam eden çıkarmaları sürdür
        books.forEach { BookStore.ensureExtracting(this, it.id) }
    }

    /** Arama filtresi + sıralama uygular (Türkçe karakter duyarsız). */
    private fun applyFilter() {
        val q = binding.searchInput.text.toString().trim()
        val filtered = if (q.isEmpty()) books else books.filter { book ->
            TextUtil.normalize(book.title).contains(TextUtil.normalize(q))
        }
        adapter.submit(sortBooks(filtered))
    }

    /** Kütüphane sıralaması: son okunan / alfabetik / eklenme. */
    private fun sortBooks(list: List<Book>): List<Book> =
        when (AppPrefs.getString(this, AppPrefs.KEY_SORT, "recent")) {
            "alpha" -> {
                val coll = java.text.Collator.getInstance(java.util.Locale("tr", "TR"))
                list.sortedWith(Comparator { a, b -> coll.compare(a.title, b.title) })
            }
            "added" -> list.sortedByDescending { it.createdAt }
            else -> list.sortedByDescending {
                maxOf(AppPrefs.lastRead(this, it.id), it.createdAt)
            }
        }

    private fun setSort(mode: String) {
        AppPrefs.putString(this, AppPrefs.KEY_SORT, mode)
        applySortUi()
        applyFilter()
    }

    /** Seçili sıralama çipini vurgular. */
    private fun applySortUi() {
        val mode = AppPrefs.getString(this, AppPrefs.KEY_SORT, "recent")
        val chips = listOf(
            binding.sortRecent to "recent",
            binding.sortAlpha to "alpha",
            binding.sortAdded to "added"
        )
        chips.forEach { (view, m) ->
            val selected = m == mode
            view.setBackgroundResource(
                if (selected) R.drawable.bg_button_secondary else R.drawable.bg_input
            )
            view.setTextColor(
                getColor(if (selected) R.color.umay_accent else R.color.umay_text_dim)
            )
        }
    }

    /**
     * HERO RAF (Son Okunan — ADIM 2): büyük kapak + başlık + sayfa bilgisi
     * + yüzde + ilerleme çubuğu. Kapağa dokununca kaldığı sayfadan açılır
     * (askResume akışı değişmedi). Kitap yoksa raf gizlenir.
     */
    private fun refreshContinueCard() {
        val last = AppPrefs.getString(this, AppPrefs.KEY_LAST_BOOK)
        val book = books.firstOrNull { it.id == last }
        if (book == null) {
            binding.heroWrap.visibility = View.GONE
            return
        }
        binding.continueTitle.text = book.title
        val progress = AppPrefs.loadProgress(this, book.id)
        binding.continueMeta.text = if (progress != null) {
            getString(R.string.sayfa_ilerleme, progress.first + 1, book.pageCount)
        } else {
            getString(R.string.henuz_okunmadi, book.pageCount)
        }

        // Okuma yüzdesi + çubuk (TextUtil.readPercent, saf ve test edilebilir)
        val pct = progress?.let { TextUtil.readPercent(it.first, book.pageCount) } ?: 0
        binding.heroPercent.text = getString(R.string.okundu_yuzde, pct)
        binding.heroProgress.progress = pct
        binding.heroWrap.visibility = View.VISIBLE

        binding.continueCover.setImageResource(R.drawable.ic_pdf)
        binding.continueCover.tag = book.id
        loadCover(book.id) { bmp ->
            if (binding.continueCover.tag == book.id && bmp != null) {
                binding.continueCover.setImageBitmap(bmp)
            }
        }
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
        pickPdf.launch(
            arrayOf(
                "application/pdf",
                "application/octet-stream",
                "text/plain",                    // TXT / MD / CSV
                "text/markdown",
                "text/csv",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" // DOCX
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
            .setMessage("Bu PDF kütüphaneden silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                BookStore.deleteBook(this, book.id)
                reload()
            }
            .setNegativeButton("Vazgeç", null)
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

    /**
     * Arşiv rafı hücresi (ADIM 2): kapak + dairesel yüzde rozeti + başlık.
     * Tıklama → kaldığın yerden devam; uzun basma → sil.
     */
    class BooksAdapter(
        val onClick: (Book) -> Unit,
        val onLongClick: (Book) -> Unit,
        val loadCover: (String, (android.graphics.Bitmap?) -> Unit) -> Unit
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

            // Kapak önizlemesi (yoksa PDF simgesi kalır)
            holder.cover.setImageResource(R.drawable.ic_pdf)
            holder.cover.tag = book.id
            loadCover(book.id) { bmp ->
                if (holder.cover.tag == book.id && bmp != null) {
                    holder.cover.setImageBitmap(bmp)
                }
            }

            val progress = AppPrefs.loadProgress(ctx, book.id)
            // Dairesel yüzde rozeti: %100 → turuncu, diğerleri turkuaz
            val pct = progress?.let { TextUtil.readPercent(it.first, book.pageCount) } ?: 0
            holder.ring.setPercent(pct)
            holder.meta.text = if (progress != null) {
                ctx.getString(R.string.sayfa_ilerleme, progress.first + 1, book.pageCount)
            } else {
                ctx.getString(R.string.henuz_okunmadi, book.pageCount)
            }

            holder.row.setOnClickListener { onClick(book) }
            holder.row.setOnLongClickListener {
                onLongClick(book)
                true
            }
        }
    }
}
