package com.umay.pdfreader

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.umay.pdfreader.databinding.ActivityShelfDetailBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v40 — RAF DETAY SAYFASI (kullanıcı isteği).
 *
 * Ana ekranda raf başlığına dokununca açılır. Amaç: "raftaki tüm belgeleri
 * görebileceğim bir sayfa açılmalı; ben sadece görmek istiyorum, dinlemiyorum,
 * okuyup gezmek istiyorum" — rafa dokunmak artık doğrudan kitap açmıyor.
 *
 * • Her dosya kapak resmiyle listelenir.
 * • Boyut / yüklenme tarihi / bağlı ders / son okuma + kaldığı sayfa görünür.
 * • Sıra ▲▼ ile değiştirilir; kalıcıdır ([ShelfManager.setManualOrder]).
 * • Raf boş olsa bile açılır; "Kaynak ekle" ile telefondan dosya eklenebilir
 *   (dosya önce kütüphaneye alınır, sonra bu rafa bağlanır — kopya üretilmez).
 */
class ShelfDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "shelf_id"
        private const val EXTRA_NAME = "shelf_name"

        fun newIntent(ctx: Context, shelfId: String, shelfName: String): Intent =
            Intent(ctx, ShelfDetailActivity::class.java).apply {
                putExtra(EXTRA_ID, shelfId)
                putExtra(EXTRA_NAME, shelfName)
            }
    }

    private lateinit var binding: ActivityShelfDetailBinding
    private lateinit var shelfId: String
    private lateinit var shelfName: String
    private val rows = mutableListOf<Row>()
    private val adapter = RowAdapter()
    private val coverIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "umay-shelf-cover").apply { isDaemon = true }
    }
    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale("tr", "TR"))

    /** Bir satır: kitap + önceden çözülmüş bilgiler (UI thread'de hesaplanmaz). */
    private data class Row(
        val book: Book,
        val sizeText: String,
        val addedText: String,
        val courseText: String,
        val lastText: String,
        var cover: android.graphics.Bitmap? = null
    )

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) importInto(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShelfDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)   // SDK 36 edge-to-edge (durum çubuğu)

        shelfId = intent.getStringExtra(EXTRA_ID) ?: ""
        shelfName = intent.getStringExtra(EXTRA_NAME) ?: ""
        if (shelfId.isBlank()) { finish(); return }
        if (shelfName.isBlank()) {
            shelfName = ShelfManager.shelvesOf(this).firstOrNull { it.id == shelfId }?.name ?: ""
        }
        binding.shelfDetailTitle.text = shelfName

        binding.shelfBookList.layoutManager = LinearLayoutManager(this)
        binding.shelfBookList.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener { pickSources() }
        binding.btnAddEmpty.setOnClickListener { pickSources() }

        // Raylar arası kalabalık olmasın: uzun listede kapak yükleme kuyruğu
        reload()
    }

    override fun onResume() {
        super.onResume()
        // Başka ekranda eklenen/silinen dosyalar yansısın
        reload()
    }

    override fun onDestroy() {
        try { coverIo.shutdownNow() } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ── Yükleme ─────────────────────────────────────────────────────

    private fun reload() {
        val all = try {
            val vaulted = SecureVault.vaultedIds(this)
            BookStore.loadBooks(this).filter { it.id !in vaulted }
        } catch (t: Throwable) {
            BookStore.logError(this, "ShelfDetail.loadBooks", t)
            emptyList()
        }
        val list = try {
            ShelfManager.shelfBooks(this, shelfId, all)
        } catch (t: Throwable) {
            BookStore.logError(this, "ShelfDetail.shelfBooks", t)
            emptyList()
        }
        val courseOf = courseNames()

        rows.clear()
        var total = 0L
        list.forEach { book ->
            val bytes = dirSize(bookDir(book.id))
            total += bytes
            rows.add(
                Row(
                    book = book,
                    sizeText = humanSize(bytes),
                    addedText = dateFmt.format(Date(book.createdAt)),
                    courseText = courseOf[book.id] ?: "",
                    lastText = lastReadText(book)
                )
            )
        }
        adapter.notifyDataSetChanged()

        val empty = rows.isEmpty()
        binding.shelfEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.shelfBookList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.shelfSummary.text = getString(
            R.string.raf_kitap_sayisi, rows.size, humanSize(total)
        )

        loadCovers()
    }

    private fun bookDir(id: String) = File(File(filesDir, "books"), id)

    private fun dirSize(dir: File): Long = try {
        if (!dir.isDirectory) 0L
        else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Throwable) { 0L }

    private fun humanSize(bytes: Long): String = when {
        bytes <= 0L -> "—"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Kitap → ders adı (derslere bağlı kaynaklar). */
    private fun courseNames(): Map<String, String> = try {
        val map = HashMap<String, String>()
        CourseStore.courses(this).forEach { c ->
            CourseStore.linkedBookIds(this, c.id).forEach { bookId ->
                if (!map.containsKey(bookId)) map[bookId] = c.name
            }
        }
        map
    } catch (t: Throwable) {
        BookStore.logError(this, "ShelfDetail.courseNames", t)
        emptyMap()
    }

    private fun lastReadText(book: Book): String {
        val last = try { AppPrefs.lastRead(this, book.id) } catch (_: Throwable) { 0L }
        val progress = try { AppPrefs.loadProgress(this, book.id) } catch (_: Throwable) { null }
        val page = progress?.first?.plus(1) ?: 0
        return when {
            last <= 0L -> "Hiç okunmadı"
            page > 0 -> "Son okuma: ${dateFmt.format(Date(last))} · sayfa $page/${book.pageCount}"
            else -> "Son okuma: ${dateFmt.format(Date(last))}"
        }
    }

    private fun loadCovers() {
        val snapshot = rows.toList()
        coverIo.execute {
            snapshot.forEach { row ->
                val bmp = try {
                    val f = BookStore.coverFile(this, row.book.id)
                    val use = if (f.exists() && f.length() > 0L) f else BookStore.ensureCover(this, row.book.id)
                    use?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
                } catch (_: Throwable) { null }
                if (bmp != null) {
                    runOnUiThread {
                        val idx = rows.indexOfFirst { it.book.id == row.book.id }
                        if (idx >= 0) {
                            rows[idx].cover = bmp
                            adapter.notifyItemChanged(idx)
                        }
                    }
                }
            }
        }
    }

    // ── Kaynak ekleme (doğrudan telefondan) ─────────────────────────

    private fun pickSources() {
        pickFiles.launch(
            arrayOf(
                "application/pdf",
                "application/octet-stream",
                "text/plain",
                "text/markdown",
                "text/csv",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.ms-powerpoint",
                "application/mspowerpoint",
                "application/powerpoint",
                "application/msword",
                "application/epub+zip",
                "image/*",
                "audio/*",
                "video/*"
            )
        )
    }

    private fun importInto(uris: List<Uri>) {
        val images = ArrayList<Uri>()
        val docs = ArrayList<Uri>()
        uris.forEach { u ->
            val mime = try { contentResolver.getType(u) ?: "" } catch (_: Throwable) { "" }
            if (mime.startsWith("image/")) images.add(u) else docs.add(u)
        }
        Toast.makeText(this, "Ekleniyor…", Toast.LENGTH_SHORT).show()
        if (images.isNotEmpty()) {
            BookStore.importImages(this, images, onProgress = { _, _ -> }, onDone = { })
        }
        if (docs.isEmpty()) { finishImport(); return }
        // v42 — MÜKERRER KORUMASI: aynı dosya tekrar seçilirse sessizce
        // yutulmaz; kullanıcıya "bu zaten var" denir ve farklı ad verme hakkı
        // tanınır (eski davranış: importPdf mevcut kitabı döndürüp hiçbir şey
        // yazmıyordu → "ekledim ama olmadı" karmaşası).
        importNext(docs, 0)
    }

    private fun importNext(queue: List<Uri>, index: Int) {
        if (index >= queue.size) {
            runOnUiThread { finishImport() }
            return
        }
        val uri = queue[index]
        val duplicate = try {
            BookStore.sourceKeyOf(this, uri)?.let { BookStore.findBySourceKey(this, it) }
        } catch (_: Throwable) {
            null
        }
        if (duplicate == null) {
            BookStore.importPdf(this, uri) { result ->
                result.onSuccess { book -> attach(book.id) }
                result.onFailure { e ->
                    runOnUiThread {
                        Toast.makeText(
                            this, "Dosya alınamadı: ${e.message}", Toast.LENGTH_LONG
                        ).show()
                    }
                }
                runOnUiThread { importNext(queue, index + 1) }
            }
            return
        }
        // Mükerrer: kullanıcı karar verir
        AlertDialog.Builder(this)
            .setTitle("Bu dosya zaten kütüphanede")
            .setMessage(
                "\"${duplicate.title}\" (${duplicate.pageCount} sayfa)\n\n" +
                    "Aynı dosyaysa tekrar eklemeyin. Gerçekten farklı bir dosyaysa " +
                    "ona yeni bir ad verin; ikisi de kütüphanede kalır."
            )
            .setPositiveButton("Farklı adla ekle") { _, _ ->
                promptDuplicateName(duplicate.title) { newName ->
                    BookStore.importPdf(this, uri, allowDuplicate = true) { result ->
                        result.onSuccess { book ->
                            BookStore.renameBook(this, book.id, newName)
                            attach(book.id)
                            SeyirDefteri.yaz(
                                this, "KÜTÜPHANE", "mükerrer dosya farklı adla eklendi",
                                "$newName (raf: $shelfId)"
                            )
                        }
                        runOnUiThread { importNext(queue, index + 1) }
                    }
                }
            }
            .setNegativeButton("Vazgeç") { _, _ -> importNext(queue, index + 1) }
            .setCancelable(false)
            .show()
        SeyirDefteri.yaz(
            this, "KÜTÜPHANE", "mükerrer dosya uyarısı",
            "${duplicate.title} · raf: $shelfId"
        )
    }

    private fun promptDuplicateName(suggested: String, onOk: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText("$suggested (2)")
            setSelectAllOnFocus(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Yeni ad")
            .setView(input)
            .setPositiveButton("Ekle") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Ad boş olamaz", Toast.LENGTH_SHORT).show()
                } else {
                    onOk(name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun finishImport() {
        Toast.makeText(this, "Kütüphaneye eklendi", Toast.LENGTH_SHORT).show()
        SeyirDefteri.yaz(this, "RAF", "rafa kaynak eklendi", "raf=$shelfId")
        reload()
    }

    /** Dosyayı bu rafa bağlar (dosya kopyalanmaz; yalnız raf bağlantısı). */
    private fun attach(bookId: String) {
        try {
            ShelfManager.addBookToShelf(this, bookId, shelfId)
            if (shelfId == ShelfManager.SHELF_FAVORITES) {
                val ids = FavoritesStore.ids(this).toMutableList()
                if (bookId !in ids) { ids.add(bookId); FavoritesStore.reorder(this, ids) }
            }
        } catch (t: Throwable) {
            BookStore.logError(this, "ShelfDetail.attach", t)
        }
    }

    // ── Sıralama ────────────────────────────────────────────────────

    private fun move(index: Int, delta: Int) {
        val to = index + delta
        if (index !in rows.indices || to !in rows.indices) return
        val moved = rows.removeAt(index)
        rows.add(to, moved)
        adapter.notifyItemMoved(index, to)
        // v40: sıra kalıcı — raf ana ekranda da bu sırayla görünür
        try {
            ShelfManager.setManualOrder(this, shelfId, rows.map { it.book.id })
        } catch (t: Throwable) {
            BookStore.logError(this, "ShelfDetail.move", t)
        }
        adapter.notifyDataSetChanged()
    }

    // ── Dosya işlemleri ─────────────────────────────────────────────

    private fun open(row: Row) {
        val book = row.book
        val progress = try { AppPrefs.loadProgress(this, book.id) } catch (_: Throwable) { null }
        AppPrefs.putString(this, AppPrefs.KEY_LAST_BOOK, book.id)
        AppPrefs.touchLastRead(this, book.id)
        startActivity(
            Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                putExtra(ReaderActivity.EXTRA_PAGE, progress?.first ?: 0)
                putExtra(ReaderActivity.EXTRA_SENTENCE, progress?.second ?: 0)
            }
        )
    }

    private fun showActions(row: Row) {
        val items = mutableListOf<String>()
        items.add("Aç / oku")
        items.add(getString(R.string.dosya_bilgisi))
        val removable = shelfId != "shelf_ders_kitaplari" &&
            shelfId != "shelf_ders_slayt_notlar" &&
            shelfId != "shelf_cihang_taramalari"
        if (removable) items.add(getString(R.string.raftan_kaldir))
        items.add("Kitaplıktan sil")

        AlertDialog.Builder(this)
            .setTitle(row.book.title)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Aç / oku" -> open(row)
                    getString(R.string.dosya_bilgisi) -> showInfo(row)
                    getString(R.string.raftan_kaldir) -> removeFromShelf(row)
                    else -> confirmDelete(row)
                }
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    private fun showInfo(row: Row) {
        val b = row.book
        val msg = buildString {
            append("Dosya: ${b.title}\n")
            append("Boyut: ${row.sizeText}\n")
            append("Eklenme: ${row.addedText}\n")
            append("Sayfa: ${b.pageCount}\n")
            if (row.courseText.isNotBlank()) append("Ders: ${row.courseText}\n")
            append(row.lastText)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dosya_bilgisi)
            .setMessage(msg)
            .setPositiveButton(getString(R.string.kaydet), null)
            .show()
    }

    private fun removeFromShelf(row: Row) {
        try {
            if (shelfId == ShelfManager.SHELF_FAVORITES) {
                val ids = FavoritesStore.ids(this).filterNot { it == row.book.id }
                FavoritesStore.reorder(this, ids)
            } else {
                ShelfManager.removeBookFromShelf(this, row.book.id, shelfId)
                ShelfManager.setManualOrder(
                    this, shelfId, rows.map { it.book.id }.filterNot { it == row.book.id }
                )
            }
        } catch (t: Throwable) {
            BookStore.logError(this, "ShelfDetail.removeFromShelf", t)
        }
        reload()
    }

    private fun confirmDelete(row: Row) {
        AlertDialog.Builder(this)
            .setTitle(row.book.title)
            .setMessage(R.string.kitap_sil_soru)
            .setPositiveButton(R.string.kitap_sil) { _, _ ->
                try {
                    BookStore.deleteBook(this, row.book.id)
                    ShelfManager.onBookDeleted(this, row.book.id)
                    FavoritesStore.reorder(this, FavoritesStore.ids(this).filterNot { it == row.book.id })
                } catch (t: Throwable) {
                    BookStore.logError(this, "ShelfDetail.delete", t)
                }
                reload()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    // ── Adaptör ─────────────────────────────────────────────────────

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val cover: ImageView = v.findViewById(R.id.sbCover)
            val title: TextView = v.findViewById(R.id.sbTitle)
            val meta: TextView = v.findViewById(R.id.sbMeta)
            val course: TextView = v.findViewById(R.id.sbCourse)
            val last: TextView = v.findViewById(R.id.sbLast)
            val up: TextView = v.findViewById(R.id.sbUp)
            val down: TextView = v.findViewById(R.id.sbDown)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_shelf_book, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.title.text = row.book.title
            holder.meta.text = "${row.sizeText} · ${row.addedText}"
            holder.course.text = row.courseText
            holder.course.visibility = if (row.courseText.isBlank()) View.GONE else View.VISIBLE
            holder.last.text = row.lastText
            val c = row.cover
            if (c != null) holder.cover.setImageBitmap(c)
            else holder.cover.setImageDrawable(null)

            holder.v.setOnClickListener { open(row) }
            holder.v.setOnLongClickListener { showActions(row); true }
            holder.up.setOnClickListener { move(holder.bindingAdapterPosition, -1) }
            holder.down.setOnClickListener { move(holder.bindingAdapterPosition, +1) }

            // Uçlarda oklar soluklaşır (yanlış dokunuşu azaltır)
            holder.up.alpha = if (position == 0) 0.25f else 1f
            holder.down.alpha = if (position == rows.size - 1) 0.25f else 1f
        }
    }
}
