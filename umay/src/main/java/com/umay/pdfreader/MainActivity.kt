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

    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPdf(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = BooksAdapter(
            onClick = { book -> askResume(book) },
            onLongClick = { book -> confirmDelete(book) }
        )
        binding.bookList.layoutManager = LinearLayoutManager(this)
        binding.bookList.adapter = adapter

        binding.btnPickPdf.setOnClickListener { choosePdf() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnChat.setOnClickListener { openChatForLastBook() }

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
        adapter.submit(books)
        binding.emptyText.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        binding.bookList.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
        // Devam eden çıkarmaları sürdür
        books.forEach { BookStore.ensureExtracting(this, it.id) }
    }

    private fun choosePdf() {
        pickPdf.launch(arrayOf("application/pdf", "application/octet-stream"))
    }

    private fun importPdf(uri: android.net.Uri) {
        binding.progressText.visibility = View.VISIBLE
        binding.progressText.text = "PDF içeri alınıyor…"
        BookStore.importPdf(this, uri) { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = { book ->
                        AppPrefs.putString(this, AppPrefs.KEY_LAST_BOOK, book.id)
                        Toast.makeText(
                            this,
                            "${book.title} eklendi (${book.pageCount} sayfa)",
                            Toast.LENGTH_SHORT
                        ).show()
                        reload()
                    },
                    onFailure = { e ->
                        binding.progressText.visibility = View.GONE
                        Toast.makeText(
                            this,
                            "PDF alınamadı: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
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
            val prefix = if (stage == "ocr") getString(R.string.ocr_yapiliyor, total)
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

    class BooksAdapter(
        val onClick: (Book) -> Unit,
        val onLongClick: (Book) -> Unit
    ) : RecyclerView.Adapter<BooksAdapter.VH>() {

        private var items: List<Book> = emptyList()

        fun submit(list: List<Book>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.bookName)
            val meta: TextView = v.findViewById(R.id.bookMeta)
            val action: TextView = v.findViewById(R.id.bookAction)
            val row: View = v.findViewById(R.id.bookRow)
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

            val progress = AppPrefs.loadProgress(ctx, book.id)
            val pages = book.pageCount
            holder.meta.text = if (progress != null) {
                "${pages} sayfa • kaldığın yer: ${progress.first + 1}. sayfa"
            } else {
                "${pages} sayfa"
            }
            holder.action.text = ctx.getString(
                if (progress != null) R.string.devam_et else R.string.oynat
            )

            holder.row.setOnClickListener { onClick(book) }
            holder.row.setOnLongClickListener {
                onLongClick(book)
                true
            }
        }
    }
}
