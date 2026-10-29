package com.umay.pdfreader

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.umay.pdfreader.databinding.ActivityCourseDetailBinding
import java.util.Calendar

/**
 * Ders detayı (ADIM 2): 3 sekme —
 *  1) Ders Kaynakları (kütüphaneden kitap bağla / oku)
 *  2) Ödevler ve Projeler (teslim tarihi + durum)
 *  3) Sınavlar (tarih + geri sayım)
 */
class CourseDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COURSE_ID = "course_id"
        /** Yalnız DEBUG testi: video rafına dosya ekleme. */
        private const val EXTRA_SEED_VIDEO = "seed_video"
    }

    private lateinit var binding: ActivityCourseDetailBinding
    private lateinit var courseId: String
    private var courseName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCourseDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)  // SDK 36 edge-to-edge

        courseId = intent.getStringExtra(EXTRA_COURSE_ID) ?: run { finish(); return }
        if (CourseStore.course(this, courseId) == null) { finish(); return }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnEdit.setOnClickListener { editCourseName() }
        binding.btnDelete.setOnClickListener { confirmDeleteCourse() }

        binding.segResources.setOnClickListener { showSection(0) }
        binding.segExams.setOnClickListener { showSection(1) }

        // v5.7 — raf tabanlı kaynak yönetimi
        binding.btnAddResourceSet.setOnClickListener { pickResources(null, null) }
        binding.btnAddShelf.setOnClickListener { addCustomShelf() }
        binding.btnAddExam.setOnClickListener { addExamDialog() }

        // ── v14 (V5.0 §5): interaktif ay takvimi ──
        try {
            binding.examCalendar.onDayClick = { millis, count ->
                if (count > 0) showExamsOfDay(millis)
                else {
                    // Boş güne tıkla → o güne sınav ekle
                    pendingExamMillis = millis
                    addExamDialog(millis)
                }
            }
            binding.examCalendar.onMonthChange = { refreshCalendarMarks() }
        } catch (t: Throwable) {
            BookStore.logError(this, "examCalendar", t)
        }

        showSection(0)
        refresh()

        // YALNIZ DEBUG — cihaz testinde video rafını gerçek videoyla doldurma
        if (BuildConfig.DEBUG) {
            val seed = intent?.getStringExtra(EXTRA_SEED_VIDEO)
            if (!seed.isNullOrBlank()) {
                java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                    val v = CourseVideoStore.importLocalFile(this, courseId, java.io.File(seed))
                    runOnUiThread {
                        if (v != null) refreshShelves()
                    }
                }
            }
        }
    }

    // ── v14 (V5.0 §4) — KAYNAK SINIFLANDIRMASI ────────────────────────
    //
    // Ders materyalleri raf gibi sınıflara ayrılır: Ders Kitapları,
    // Slide Dosyaları, Yardımcı Dosyalar, Belgeler. Çip → yalnız o
    // kategorideki kaynaklar listelenir; satıra dokununca kategori
    // değiştirilebilir ("raflar arası taşıma" gibi).
    private var pendingExamMillis: Long = 0L

    /** v5.7 — Kapak çözümü tek iş parçacığında (UI donmaz). */
    private val coverExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "umay-course-cover").apply { isDaemon = true }
    }

    // v16 (GÖREV 1) — 3 ANA RAF: Ders Kitapları / Slide / Dokümanlar-Yardımcı.
    // `get()` ile çalışma anında okunur (dil TR/EN + context hazır) — eski
    // "helper" kategorisi "doc" rafında birleştirildi (geriye dönük veri korunur).
    private val categories: List<Pair<String, String>>
        get() = listOf(
            "book" to getString(R.string.shelf_books),
            "slide" to getString(R.string.shelf_slides),
            "doc" to getString(R.string.shelf_docs)
        )

    /** Raf filtresi: "doc" rafı hem `doc` hem eski `helper` satırlarını gösterir. */
    private var shelfTintIndex = 0

    /** v24 — Raf zemini + neon dış çerçeve (ana sayfayla aynı palet). */
    /** v25 — rafın neon çerçeve rengi: ders raflarında yüzde rozeti bu renkte. */
    private fun shelfNeonColor(index: Int): Int = try {
        val neon = intArrayOf(
            R.color.umay_shelf_neon_0, R.color.umay_shelf_neon_1, R.color.umay_shelf_neon_2,
            R.color.umay_shelf_neon_3, R.color.umay_shelf_neon_4
        )
        getColor(neon[kotlin.math.abs(index) % neon.size])
    } catch (_: Throwable) {
        0xFF00E5FF.toInt()
    }

    private fun tintShelf(view: android.view.View, index: Int) {
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
            view.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18f * d
                setColor(getColor(palette[i]))
                setStroke((2f * d).toInt().coerceAtLeast(2), getColor(neon[i]))
            }
            val pad = (10f * d).toInt()
            if (view.paddingLeft < pad) view.setPadding(pad, pad, pad, pad)
        } catch (_: Throwable) {
        }
    }

    private fun inShelf(category: String, shelf: String): Boolean =
        if (shelf == "doc") category == "doc" || category == "helper"
        else category == shelf

    // ── v5.7 — DERS İÇİ RAF SİSTEMİ ────────────────────────────────

    /**
     * 4 yerleşik raf (Kitap / Slayt / Doküman / Ödev) + kullanıcı rafları.
     * Her raf: başlık bandı + sayı + "＋" + yatay kitaplar + raf çizgisi.
     * Kaynağa uzun bas → rafa taşı / raftan çıkar; raf adına uzun bas →
     * yeniden adlandır / sil / yukarı taşı.
     */
    private fun refreshShelves() {
        val container = binding.courseShelfContainer
        container.removeAllViews()
        shelfTintIndex = 0
        val docs = CourseStore.docsOf(this, courseId)
        val byDocId = docs.associateBy { it.doc.id }

        // Özel raflardaki kaynaklar yerleşik raflarda TEKRAR gösterilmez
        val inCustom = HashSet<String>()
        CourseShelfStore.shelves(this, courseId).forEach { inCustom.addAll(it.docIds) }

        categories.forEach { (cat, label) ->
            val books = docs
                .filter { inShelf(it.doc.category, cat) && it.doc.id !in inCustom }
                .mapNotNull { it.book }
            addShelfBlock(label, books, null) { pickResources(cat, null) }
        }

        CourseShelfStore.shelves(this, courseId).forEach { shelf ->
            val books = shelf.docIds.mapNotNull { byDocId[it]?.book }
            addShelfBlock(shelf.name, books, shelf) { pickResources(null, shelf.id) }
        }

        // v24 — 🎬 VİDEOLAR rafı (yerel video + YouTube bağlantısı)
        addVideoShelf()
        // v33 — 🎧 SES KAYITLARI rafı (ders kayıtları; orijinal dosya korunur)
        addAudioShelf()

        addHomeworkShelf()

        val empty = docs.isEmpty() && CourseStore.homeworkOf(this, courseId).isEmpty()
        binding.resourceEmpty.visibility = if (empty) View.VISIBLE else View.GONE
    }

    /** Tek bir raf bloğu çizer (item_dynamic_shelf: başlık + yatay liste + çizgi). */
    private fun addShelfBlock(
        title: String,
        books: List<Book>,
        custom: CourseShelfStore.Shelf?,
        onAdd: () -> Unit
    ) {
        val block = layoutInflater.inflate(R.layout.item_dynamic_shelf, binding.courseShelfContainer, false)
        // v21 — her ders rafı farklı zemin rengi (kullanıcı isteği)
        // v25 — yüzde rozeti de AYNI neon renkte yazılır (beyaz kapakta okunur)
        val neon = shelfNeonColor(shelfTintIndex)
        tintShelf(block, shelfTintIndex++)
        val header = block.findViewById<LinearLayout>(R.id.dynShelfHeader)
        header.findViewById<TextView>(R.id.shelfTitle).text = title
        header.findViewById<TextView>(R.id.shelfEmptyHint)?.visibility =
            if (books.isEmpty()) View.VISIBLE else View.GONE
        header.findViewById<TextView>(R.id.shelfCount)?.apply {
            text = books.size.toString()
            visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
        }
        header.findViewById<TextView>(R.id.shelfAdd)?.apply {
            visibility = View.VISIBLE
            setOnClickListener { onAdd() }
        }
        if (custom != null) {
            header.setOnLongClickListener { customShelfMenu(custom); true }
        }

        val rv = block.findViewById<RecyclerView>(R.id.dynShelfList)
        rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rv.adapter = MainActivity.BooksAdapter(
            onClick = { b -> openBook(b) },
            onLongClick = { b -> bookShelfMenu(b, custom) },
            loadCover = { id, cb -> loadCourseCover(id, cb) },
            accent = neon
        ).also { it.submit(ShelfManager.sortForShelf(this, books)) }
        // ‹ › okları yalnız liste taşıyorsa görünür
        val step = (resources.displayMetrics.density * 200).toInt()
        header.findViewById<ImageButton>(R.id.shelfScrollPrev)?.apply {
            visibility = if (books.isEmpty()) View.INVISIBLE else View.VISIBLE
            setOnClickListener { rv.smoothScrollBy(-step, 0) }
        }
        header.findViewById<ImageButton>(R.id.shelfScrollNext)?.apply {
            visibility = if (books.isEmpty()) View.INVISIBLE else View.VISIBLE
            setOnClickListener { rv.smoothScrollBy(step, 0) }
        }
        binding.courseShelfContainer.addView(block)
    }

    // ── v33 — SES KAYITLARI RAFI ────────────────────────────────

    private val audioPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            importAudio(uri)
        }

    private fun importAudio(uri: android.net.Uri) {
        val name = try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        } catch (_: Throwable) { null }
        Toast.makeText(this, "Ses kaydı ekleniyor…", Toast.LENGTH_SHORT).show()
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            val clip = CourseAudioStore.importLocal(this, courseId, uri, name)
            runOnUiThread {
                if (clip != null) {
                    Toast.makeText(this, "Ses kaydı rafa eklendi ✓", Toast.LENGTH_SHORT).show()
                    refreshShelves()
                } else {
                    Toast.makeText(this, "Ses kaydı eklenemedi", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Ses kayıtları rafı: başlık + yatay kartlar + ekleme düğmesi. */
    private fun addAudioShelf() {
        val clips = CourseAudioStore.clips(this, courseId)
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tintShelf(block, shelfTintIndex++)
        val header = layoutInflater.inflate(R.layout.item_shelf_header, block, false)
        header.findViewById<TextView>(R.id.shelfTitle).text = getString(R.string.shelf_audio)
        header.findViewById<TextView>(R.id.shelfEmptyHint)?.apply {
            text = getString(R.string.shelf_audio_empty)
            visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        }
        header.findViewById<TextView>(R.id.shelfCount)?.apply {
            text = clips.size.toString()
            visibility = if (clips.isEmpty()) View.GONE else View.VISIBLE
        }
        header.findViewById<TextView>(R.id.shelfAdd)?.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                try {
                    audioPicker.launch(arrayOf("audio/*"))
                } catch (t: Throwable) {
                    BookStore.logError(this@CourseDetailActivity, "audioPicker", t)
                    Toast.makeText(
                        this@CourseDetailActivity,
                        "Ses dosyası seçici açılamadı",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        block.addView(header)

        if (clips.isNotEmpty()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            clips.sortedByDescending { it.addedAt }.forEach { c ->
                val card = layoutInflater.inflate(R.layout.item_video_card, row, false)
                card.findViewById<TextView>(R.id.videoTitle).text = c.title
                card.findViewById<TextView>(R.id.videoBadge).text =
                    CourseAudioStore.formatDuration(c.durationMs)
                card.findViewById<TextView>(R.id.videoThumb).text = "🎧"
                card.setOnClickListener { openAudio(c) }
                card.setOnLongClickListener {
                    AlertDialog.Builder(this)
                        .setTitle(c.title)
                        .setMessage(getString(R.string.audio_remove_note))
                        .setItems(arrayOf("Raftan kaldır")) { _, _ ->
                            CourseAudioStore.remove(this, courseId, c.id)
                            Toast.makeText(
                                this,
                                "Raftan kaldırıldı — cihazdaki orijinal kayıt silinmedi",
                                Toast.LENGTH_LONG
                            ).show()
                            refreshShelves()
                        }
                        .setNegativeButton("Kapat", null)
                        .show()
                    true
                }
                row.addView(card)
            }
            val scroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
            block.addView(scroll)
            val step = (resources.displayMetrics.density * 240).toInt()
            header.findViewById<ImageButton>(R.id.shelfScrollPrev)?.apply {
                visibility = View.VISIBLE
                setOnClickListener { scroll.smoothScrollBy(-step, 0) }
            }
            header.findViewById<ImageButton>(R.id.shelfScrollNext)?.apply {
                visibility = View.VISIBLE
                setOnClickListener { scroll.smoothScrollBy(step, 0) }
            }
        } else {
            header.findViewById<ImageButton>(R.id.shelfScrollPrev)?.visibility = View.INVISIBLE
            header.findViewById<ImageButton>(R.id.shelfScrollNext)?.visibility = View.INVISIBLE
        }
        binding.courseShelfContainer.addView(block)
    }

    /** Ses kaydını cihazın oynatıcısında açar (çevrimdışı da çalışır). */
    private fun openAudio(c: CourseAudioStore.Clip) {
        try {
            val f = CourseAudioStore.existingFile(this, c) ?: run {
                Toast.makeText(this, "Ses dosyası bulunamadı", Toast.LENGTH_LONG).show()
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "audio/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (t: Throwable) {
            BookStore.logError(this, "openAudio", t)
            Toast.makeText(this, "Ses kaydı açılamadı", Toast.LENGTH_LONG).show()
        }
    }

    // ── v24 — VİDEO RAFI ────────────────────────────────────────

    private val videoPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            importVideo(uri)
        }

    /** Verilen içerik URI'sini dersin video rafına kopyalar. */
    private fun importVideo(uri: android.net.Uri) {
        val name = try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        } catch (_: Throwable) { null }
        Toast.makeText(this, "Video ekleniyor…", Toast.LENGTH_SHORT).show()
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            val video = CourseVideoStore.importLocal(this, courseId, uri, name)
            runOnUiThread {
                if (video != null) {
                    Toast.makeText(this, "Video rafa eklendi ✓", Toast.LENGTH_SHORT).show()
                    refreshShelves()
                } else {
                    Toast.makeText(this, "Video eklenemedi", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Video rafı: başlık + yatay kartlar + ekleme düğmesi. */
    private fun addVideoShelf() {
        val videos = CourseVideoStore.videos(this, courseId)
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tintShelf(block, shelfTintIndex++)
        val header = layoutInflater.inflate(R.layout.item_shelf_header, block, false)
        header.findViewById<TextView>(R.id.shelfTitle).text = getString(R.string.shelf_videos)
        header.findViewById<TextView>(R.id.shelfEmptyHint)?.apply {
            text = getString(R.string.shelf_videos_empty)
            visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
        }
        header.findViewById<TextView>(R.id.shelfCount)?.apply {
            text = videos.size.toString()
            visibility = if (videos.isEmpty()) View.GONE else View.VISIBLE
        }
        header.findViewById<TextView>(R.id.shelfAdd)?.apply {
            visibility = View.VISIBLE
            setOnClickListener { askAddVideo() }
        }
        block.addView(header)

        if (videos.isNotEmpty()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            videos.sortedByDescending { it.addedAt }.forEach { v ->
                val card = layoutInflater.inflate(R.layout.item_video_card, row, false)
                card.findViewById<TextView>(R.id.videoTitle).text = v.title
                card.findViewById<TextView>(R.id.videoBadge).text = if (v.isLocal) {
                    getString(R.string.video_local)
                } else {
                    getString(R.string.video_link_hint)
                }
                card.findViewById<TextView>(R.id.videoThumb).text = if (v.isLocal) "🎬" else "▶"
                card.setOnClickListener { openVideo(v) }
                card.setOnLongClickListener {
                    AlertDialog.Builder(this)
                        .setTitle(v.title)
                        .setItems(arrayOf("Kaldır")) { _, _ ->
                            CourseVideoStore.remove(this, courseId, v.id)
                            Toast.makeText(this, "Video raftan kaldırıldı", Toast.LENGTH_SHORT).show()
                            refreshShelves()
                        }
                        .setNegativeButton("Kapat", null)
                        .show()
                    true
                }
                row.addView(card)
            }
            val scroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
            block.addView(scroll)
            val step = (resources.displayMetrics.density * 240).toInt()
            header.findViewById<ImageButton>(R.id.shelfScrollPrev)?.apply {
                visibility = View.VISIBLE
                setOnClickListener { scroll.smoothScrollBy(-step, 0) }
            }
            header.findViewById<ImageButton>(R.id.shelfScrollNext)?.apply {
                visibility = View.VISIBLE
                setOnClickListener { scroll.smoothScrollBy(step, 0) }
            }
        } else {
            header.findViewById<ImageButton>(R.id.shelfScrollPrev)?.visibility = View.INVISIBLE
            header.findViewById<ImageButton>(R.id.shelfScrollNext)?.visibility = View.INVISIBLE
        }
        binding.courseShelfContainer.addView(block)
    }

    /** “＋” → cihazdan video veya YouTube/bağlantı. */
    private fun askAddVideo() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.shelf_videos))
            .setItems(
                arrayOf(getString(R.string.video_pick_local), getString(R.string.video_add_link))
            ) { _, which ->
                if (which == 0) {
                    try {
                        videoPicker.launch(arrayOf("video/*"))
                    } catch (t: Throwable) {
                        BookStore.logError(this, "videoPicker", t)
                        Toast.makeText(this, "Video seçici açılamadı", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    addVideoLinkDialog()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun addVideoLinkDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val url = EditText(this).apply {
            hint = "https://www.youtube.com/watch?v=…"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val title = EditText(this).apply { hint = "Başlık (boş bırakılabilir)" }
        wrap.addView(url)
        wrap.addView(title)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.video_add_link))
            .setView(wrap)
            .setPositiveButton("Ekle") { _, _ ->
                val v = CourseVideoStore.addLink(this, courseId, url.text.toString(), title.text.toString())
                if (v != null) {
                    Toast.makeText(this, "Bağlantı rafa eklendi", Toast.LENGTH_SHORT).show()
                    refreshShelves()
                } else {
                    Toast.makeText(this, "Bağlantı boş olamaz", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    /** Yerel videoyu uygulama içi oynatıcıda, bağlantıyı dış uygulamada açar. */
    private fun openVideo(v: CourseVideoStore.Video) {
        try {
            if (v.isLocal) {
                val f = CourseVideoStore.localFile(this, v.source)
                if (!f.exists() || f.length() == 0L) {
                    Toast.makeText(this, "Video dosyası bulunamadı", Toast.LENGTH_LONG).show()
                    return
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(v.source)))
            }
        } catch (t: Throwable) {
            BookStore.logError(this, "openVideo", t)
            Toast.makeText(this, "Video açılamadı", Toast.LENGTH_LONG).show()
        }
    }

    /** Ödev & Projeler rafı — ödevler de bir raf gibi yatay listelenir. */
    private fun addHomeworkShelf() {
        val items = CourseStore.homeworkOf(this, courseId)
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // v24 — Ödev rafı da diğer raflar gibi kendi zemin + neon çerçevesini alır
        tintShelf(block, shelfTintIndex++)
        val header = layoutInflater.inflate(R.layout.item_shelf_header, block, false)
        header.findViewById<TextView>(R.id.shelfTitle).text =
            getString(R.string.shelf_homework)
        header.findViewById<TextView>(R.id.shelfEmptyHint)?.visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE
        header.findViewById<TextView>(R.id.shelfCount)?.apply {
            text = items.size.toString()
            visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
        header.findViewById<TextView>(R.id.shelfAdd)?.apply {
            visibility = View.VISIBLE
            setOnClickListener { addHomeworkDialog() }
        }
        header.findViewById<ImageButton>(R.id.shelfScrollPrev)?.visibility = View.INVISIBLE
        header.findViewById<ImageButton>(R.id.shelfScrollNext)?.visibility = View.INVISIBLE
        block.addView(header)

        if (items.isNotEmpty()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            val now = System.currentTimeMillis()
            items.forEach { hw ->
                val card = layoutInflater.inflate(R.layout.item_hw_card, row, false)
                val days = Academy.daysLeft(hw.dueMillis, now)
                val en = LangHelper.isEnglish(this)
                card.findViewById<TextView>(R.id.hwState).text = when {
                    hw.done -> getString(R.string.teslim_edildi)
                    days < 0 -> getString(R.string.hw_overdue_badge)
                    days in 0..3 -> "⏳ $days gün"
                    else -> getString(R.string.hw_open_badge)
                }
                card.findViewById<TextView>(R.id.hwTitle).text = hw.title
                card.findViewById<TextView>(R.id.hwDue).text =
                    Academy.formatDateTime(hw.dueMillis, en) + " • " +
                        Academy.countdownPhrase(days, en)
                card.setOnClickListener { homeworkMenu(hw) }
                row.addView(card)
            }
            val scroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
            block.addView(scroll)
        }
        block.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (2 * resources.displayMetrics.density).toInt()
                )
                setBackgroundColor(getColor(R.color.umay_shelf_edge))
            }
        )
        binding.courseShelfContainer.addView(block)
    }

    /** Kitap kapağını arka planda çözer (yoksa bir kez üretir). */
    private fun loadCourseCover(bookId: String, onLoaded: (android.graphics.Bitmap?) -> Unit) {
        coverExecutor.execute {
            val bmp = try {
                val existing = BookStore.coverFile(this, bookId)
                val file = if (existing.exists() && existing.length() > 0L) existing
                else BookStore.ensureCover(this, bookId)
                file?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
            } catch (t: Throwable) {
                BookStore.logError(this, "loadCourseCover", t)
                null
            }
            runOnUiThread { onLoaded(bmp) }
        }
    }

    private fun openBook(book: Book) {
        AppPrefs.putString(this, AppPrefs.KEY_LAST_BOOK, book.id)
        startActivity(
            Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                putExtra(
                    ReaderActivity.EXTRA_PAGE,
                    AppPrefs.loadProgress(this@CourseDetailActivity, book.id)?.first ?: 0
                )
            }
        )
    }

    /** Kitaplıkta bu kitaba karşılık gelen ders kaydı (doc). */
    private fun docOf(bookId: String): com.umay.pdfreader.CourseStore.DocWithBook? =
        CourseStore.docsOf(this, courseId).firstOrNull { it.doc.bookId == bookId }

    /** Kitaba uzun basma: aç / rafa taşı / dersten çıkar. */
    private fun bookShelfMenu(book: Book, shelf: CourseShelfStore.Shelf?) {
        val doc = docOf(book.id)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels.add(getString(R.string.kitabi_ac))
        actions.add { openBook(book) }

        labels.add(getString(R.string.raf_tasi))
        actions.add { moveDocMenu(doc) }

        if (shelf != null) {
            labels.add(getString(R.string.bu_raftan_cikar))
            actions.add {
                doc?.let { CourseShelfStore.assign(this, courseId, it.doc.id, null) }
                refresh()
            }
        }
        labels.add(getString(R.string.dersten_cikar))
        actions.add {
            doc?.let {
                CourseShelfStore.assign(this, courseId, it.doc.id, null)
                CourseStore.removeDoc(this, it.doc.id)
            }
            refresh()
        }

        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(labels.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    /** "Rafa taşı": yerleşik 3 raf + kullanıcı rafları. */
    private fun moveDocMenu(doc: com.umay.pdfreader.CourseStore.DocWithBook?) {
        if (doc == null) return
        val customs = CourseShelfStore.shelves(this, courseId)
        val labels = categories.map { it.second } + customs.map { it.name }
        AlertDialog.Builder(this)
            .setTitle(R.string.raf_tasi)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < categories.size) {
                    CourseShelfStore.assign(this, courseId, doc.doc.id, null)
                    CourseStore.setDocCategory(this, doc.doc.id, categories[which].first)
                } else {
                    CourseShelfStore.assign(this, courseId, doc.doc.id, customs[which - categories.size].id)
                }
                Toast.makeText(this, R.string.raf_tasindi, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    /** Ödev kartı menüsü: durum değiştir / sil. */
    private fun homeworkMenu(hw: Homework) {
        val doneLabel = if (hw.done) getString(R.string.hw_mark_open)
        else getString(R.string.hw_mark_done)
        AlertDialog.Builder(this)
            .setTitle(hw.title)
            .setItems(arrayOf(doneLabel, getString(R.string.sil))) { _, which ->
                when (which) {
                    0 -> CourseStore.setHomeworkDone(this, hw.id, !hw.done)
                    1 -> CourseStore.deleteHomework(this, hw.id)
                }
                refresh()
            }
            .setNegativeButton(R.string.kapat, null)
            .show()
    }

    /** Kullanıcı rafı menüsü (başa al / adlandır / sil). */
    private fun customShelfMenu(shelf: CourseShelfStore.Shelf) {
        val actions = arrayOf(
            getString(R.string.raf_yukari),
            getString(R.string.raf_adlandir),
            getString(R.string.raf_sil)
        )
        AlertDialog.Builder(this)
            .setTitle(shelf.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        CourseShelfStore.moveUp(this, courseId, shelf.id)
                        refresh()
                    }
                    1 -> renameShelfDialog(shelf)
                    2 -> AlertDialog.Builder(this)
                        .setTitle(R.string.raf_sil)
                        .setMessage(R.string.raf_sil_soru)
                        .setPositiveButton(R.string.sil) { _, _ ->
                            CourseShelfStore.delete(this, courseId, shelf.id)
                            refresh()
                        }
                        .setNegativeButton(R.string.iptal, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.kapat, null)
            .show()
    }

    private fun renameShelfDialog(shelf: CourseShelfStore.Shelf) {
        val input = EditText(this).apply {
            setText(shelf.name)
            hint = getString(R.string.raf_adi)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.raf_adlandir)
            .setView(input)
            .setPositiveButton(R.string.kaydet) { _, _ ->
                CourseShelfStore.rename(this, courseId, shelf.id, input.text.toString())
                refresh()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    private fun addCustomShelf() {
        val input = EditText(this).apply { hint = getString(R.string.raf_adi) }
        AlertDialog.Builder(this)
            .setTitle(R.string.raf_ekle)
            .setView(input)
            .setPositiveButton(R.string.kaydet) { _, _ ->
                CourseShelfStore.add(this, courseId, input.text.toString())
                refresh()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    /** Takvimdeki sınav işaretlerini tazele (tüm dersler — ders bazlı değil). */
    private fun refreshCalendarMarks() {
        try {
            val all = CourseStore.examsOf(this, courseId).map { it.atMillis }
            binding.examCalendar.setExams(all)
        } catch (_: Throwable) {
        }
    }

    /** Takvime tıklanan gündeki sınavları listele + not ekle/düzenle. */
    private fun showExamsOfDay(dayMillis: Long) {
        val dayStart = java.util.Calendar.getInstance().apply {
            timeInMillis = dayMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24L * 60 * 60 * 1000
        val exams = CourseStore.examsOf(this, courseId).filter {
            it.atMillis in dayStart until dayEnd
        }
        val names = exams.map {
            it.title + if (it.note.isNotBlank()) "\n   📌 ${it.note}" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(Academy.formatDateTime(dayStart, LangHelper.isEnglish(this)))
            .setItems(names) { _, which -> editExamNote(exams[which]) }
            .setPositiveButton(R.string.kapat, null)
            .show()
    }

    /** Sınav notunu düzenle (hatırlatıcı notu — silinebilir). */
    private fun editExamNote(exam: Exam) {
        val input = EditText(this).apply {
            setText(exam.note)
            hint = "Not (örn. Konular: 1-5. ünite)"
        }
        AlertDialog.Builder(this)
            .setTitle(exam.title)
            .setView(input)
            .setPositiveButton(R.string.kaydet) { _, _ ->
                CourseStore.setExamNote(this, exam.id, input.text.toString().trim())
                refresh()
            }
            .setNegativeButton(R.string.sil) { _, _ ->
                CourseStore.setExamNote(this, exam.id, "")
                refresh()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ── Sekmeler ────────────────────────────────────────────

    private fun showSection(idx: Int) {
        binding.sectionResources.visibility = if (idx == 0) View.VISIBLE else View.GONE
        binding.sectionExams.visibility = if (idx == 1) View.VISIBLE else View.GONE

        fun chip(v: TextView, on: Boolean) {
            v.setBackgroundResource(if (on) R.drawable.bg_button_secondary else R.drawable.bg_input)
            // v5: aktif sekme = ANA vurgu (sıcak turuncu)
            v.setTextColor(getColor(if (on) R.color.umay_orange else R.color.umay_text_dim))
            v.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else null
        }
        chip(binding.segResources, idx == 0)
        chip(binding.segExams, idx == 1)
    }

    // ── Yenileme ────────────────────────────────────────────

    private fun refresh() {
        val c = CourseStore.course(this, courseId) ?: run { finish(); return }
        courseName = c.name
        binding.courseTitle.text = c.name
        refreshShelves()
        refreshExams()
    }

    private fun addRow(
        container: LinearLayout,
        title: String,
        sub: String,
        action: String = "✕",
        onAction: () -> Unit,
        onClick: (() -> Unit)? = null,
        /** v5: vurgulu satır (sınav geri sayımı / yaklaşan ödev) → sıcak turuncu */
        highlight: Boolean = false
    ): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_academy_row, container, false)
        v.findViewById<TextView>(R.id.rowTitle).text = title
        val subView = v.findViewById<TextView>(R.id.rowSub)
        if (sub.isEmpty()) {
            subView.visibility = View.GONE
        } else {
            subView.text = sub
            if (highlight) subView.setTextColor(getColor(R.color.umay_orange))
        }
        v.findViewById<TextView>(R.id.rowAction).also { a ->
            a.text = action
            a.setOnClickListener { onAction() }
        }
        if (onClick != null) v.setOnClickListener { onClick() }
        container.addView(v)
        return v
    }


    private fun refreshExams() {
        binding.examList.removeAllViews()
        val now = System.currentTimeMillis()
        val items = CourseStore.examsOf(this, courseId)
        binding.examEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { exam ->
            val days = Academy.daysLeft(exam.atMillis, now)
            val en = LangHelper.isEnglish(this)
            // v16 (YENİ GÖREV 2) — tür + not etki oranı (%) + alarm rozeti
            val meta = buildString {
                append(typeLabel(exam.type))
                if (exam.weight > 0) append(" • %${exam.weight}")
                if (!exam.alarm) append(" • 🔕")
            }
            val sub = Academy.formatDateTime(exam.atMillis, en) + " • " +
                Academy.countdownPhrase(days, en) + " • " + meta
            addRow(
                binding.examList, exam.title, sub,
                onAction = {
                    CourseStore.deleteExam(this, exam.id)
                    refresh()
                },
                // v5: sınav geri sayımı = ANA vurgu (sıcak turuncu)
                highlight = true
            )
        }
    }

    // ── Ders işlemleri ──────────────────────────────────────

    private fun editCourseName() {
        val input = EditText(this).apply {
            setText(courseName)
            hint = getString(R.string.ders_adi)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ders_duzenle)
            .setView(input)
            .setPositiveButton(R.string.kaydet) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.ders_bos_olamaz, Toast.LENGTH_SHORT).show()
                } else {
                    CourseStore.renameCourse(this, courseId, name)
                    refresh()
                }
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    private fun confirmDeleteCourse() {
        AlertDialog.Builder(this)
            .setTitle(R.string.ders_sil)
            .setMessage(R.string.ders_sil_soru)
            .setPositiveButton(R.string.ders_sil) { _, _ ->
                // Bu derse ait program satırları da alarmdan düşsün
                CourseStore.slotsOf(this).filter { it.courseId == courseId }.forEach {
                    Reminder.cancelSlot(this, it.id)
                }
                CourseStore.deleteCourse(this, courseId)
                finish()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    // ── Kaynak seçimi (çoklu) ───────────────────────────────

    /**
     * v5.7 — Kaynak seçimi. `category` yerleşik raf (book/slide/doc),
     * `shelfId` kullanıcı rafı. Seçim SADECE EKLER; kaldırma işlemi
     * kaynağa uzun basıp "Dersten çıkar" ile yapılır (yanlışlıkla silme yok).
     */
    private fun pickResources(category: String?, shelfId: String?) {
        val books = BookStore.loadBooks(this)
        if (books.isEmpty()) {
            Toast.makeText(this, R.string.kutuphane_bos_ekle, Toast.LENGTH_SHORT).show()
            return
        }
        val docs = CourseStore.docsOf(this, courseId)
        val inShelfDocIds: Set<String> = when {
            shelfId != null -> CourseShelfStore.shelves(this, courseId)
                .firstOrNull { it.id == shelfId }?.docIds?.toSet() ?: emptySet()
            category != null -> docs.filter { inShelf(it.doc.category, category) }
                .map { it.doc.id }.toSet()
            else -> docs.map { it.doc.id }.toSet()
        }
        val names = books.map { it.title }.toTypedArray()
        val checked = BooleanArray(books.size) { i ->
            docs.any { it.doc.bookId == books[i].id && it.doc.id in inShelfDocIds }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.kaynak_sec_baslik)
            .setMultiChoiceItems(names, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton(R.string.kaydet) { _, _ ->
                val chosen = books.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (chosen.isNotEmpty()) {
                    CourseStore.addDocs(this, courseId, chosen, category ?: "book")
                    val docByBook = CourseStore.docsOf(this, courseId)
                        .associate { it.doc.bookId to it.doc.id }
                    chosen.forEach { bookId ->
                        docByBook[bookId]?.let { docId ->
                            CourseShelfStore.assign(this, courseId, docId, shelfId)
                        }
                    }
                    Toast.makeText(this, R.string.kaynak_eklendi, Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    // ── Ödev ekleme ─────────────────────────────────────────

    private fun addHomeworkDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_homework_edit, null)
        val titleInput = v.findViewById<EditText>(R.id.inputTitle)
        val descInput = v.findViewById<EditText>(R.id.inputDesc)
        val dateBtn = v.findViewById<TextView>(R.id.btnPickDate)
        val alarmBtn = v.findViewById<TextView>(R.id.btnHwAlarm)

        // v16 (YENİ GÖREV 2) — teslim hatırlatıcısı aç/kapa (varsayılan açık)
        var alarmOn = true
        fun paintAlarm() {
            alarmBtn.setText(if (alarmOn) R.string.hw_alarm_on else R.string.hw_alarm_off)
        }
        paintAlarm()
        alarmBtn.setOnClickListener { alarmOn = !alarmOn; paintAlarm() }

        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 7) }
        fun paintDate() {
            dateBtn.text = getString(
                R.string.tarih_sec,
                Academy.formatDateTime(cal.timeInMillis, LangHelper.isEnglish(this))
            )
        }
        paintDate()
        dateBtn.setOnClickListener {
            DatePickerDialog(
                this,
                { _, y, m, d -> cal.set(y, m, d); paintDate() },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        val dlg = AlertDialog.Builder(this).setView(v).create()
        v.findViewById<TextView>(R.id.btnHwSave).setOnClickListener {
            val t = titleInput.text.toString().trim()
            if (t.isEmpty()) {
                Toast.makeText(this, R.string.ders_bos_olamaz, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CourseStore.addHomework(
                this, courseId, t, descInput.text.toString(), cal.timeInMillis,
                alarm = alarmOn
            )
            if (alarmOn) Toast.makeText(this, R.string.exam_alarm_set, Toast.LENGTH_SHORT).show()
            dlg.dismiss()
            refresh()
        }
        dlg.show()
    }

    // ── Sınav ekleme ────────────────────────────────────────

    /** v16 (YENİ GÖREV 2) — kanonik sınav türleri (eşik alanı + yerel etiket). */
    private val examTypes = listOf("vize", "quiz", "final", "makeup")

    /** Tür etiketi aktif dile göre (eski "Vize" satırları da eşleşir). */
    private fun typeLabel(type: String): String {
        val en = LangHelper.isEnglish(this)
        return when (type.lowercase()) {
            "vize" -> if (en) "Midterm" else "Vize"
            "quiz" -> "Quiz"
            "final" -> "Final"
            "makeup", "bütünleme" -> if (en) "Makeup" else "Bütünleme"
            else -> type
        }
    }

    private fun addExamDialog(millis: Long = 0L) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_exam_edit, null)
        val titleInput = v.findViewById<EditText>(R.id.inputTitle)
        val dateBtn = v.findViewById<TextView>(R.id.btnPickDate)
        val hourBtn = v.findViewById<TextView>(R.id.btnPickHour)
        val weightInput = v.findViewById<EditText>(R.id.inputWeight)
        val alarmBtn = v.findViewById<TextView>(R.id.btnExamAlarm)
        val chipsRow = v.findViewById<LinearLayout>(R.id.examTypeChips)

        // v16 (YENİ GÖREV 2) — tür çipleri + alarm aç/kapa
        var selectedType = 0
        var alarmOn = true
        fun paintChips() {
            chipsRow.removeAllViews()
            examTypes.forEachIndexed { i, _ ->
                val chip = TextView(this).apply {
                    text = typeLabel(examTypes[i])
                    setPadding(28, 16, 28, 16)
                    textSize = 13f
                    setOnClickListener { selectedType = i; paintChips() }
                }
                val on = i == selectedType
                chip.setBackgroundResource(
                    if (on) R.drawable.bg_button_primary else R.drawable.bg_input
                )
                chip.setTextColor(getColor(if (on) R.color.umay_on_accent else R.color.umay_text))
                chipsRow.addView(
                    chip,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 12 }
                )
            }
        }
        paintChips()
        fun paintAlarm() {
            alarmBtn.setText(if (alarmOn) R.string.exam_alarm_on else R.string.exam_alarm_off)
        }
        paintAlarm()
        alarmBtn.setOnClickListener { alarmOn = !alarmOn; paintAlarm() }

        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 14) }
        cal.set(Calendar.HOUR_OF_DAY, 9)
        cal.set(Calendar.MINUTE, 0)
        fun paint() {
            dateBtn.text = getString(
                R.string.tarih_sec,
                Academy.formatDate(cal.timeInMillis, LangHelper.isEnglish(this))
            )
            hourBtn.text = getString(
                R.string.saat_sec,
                Academy.formatClock(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
            )
        }
        paint()

        dateBtn.setOnClickListener {
            DatePickerDialog(
                this,
                { _, y, m, d -> cal.set(y, m, d); paint() },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        hourBtn.setOnClickListener {
            TimePickerDialog(
                this,
                { _, h, m -> cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); paint() },
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true
            ).show()
        }

        val dlg = AlertDialog.Builder(this).setView(v).create()
        v.findViewById<TextView>(R.id.btnExamSave).setOnClickListener {
            val t = titleInput.text.toString().trim()
            if (t.isEmpty()) {
                Toast.makeText(this, R.string.ders_bos_olamaz, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val weight = weightInput.text.toString().toIntOrNull() ?: 0
            CourseStore.addExam(
                this, courseId, t, cal.timeInMillis,
                type = examTypes[selectedType], weight = weight, alarm = alarmOn
            )
            if (alarmOn) Toast.makeText(this, R.string.exam_alarm_set, Toast.LENGTH_SHORT).show()
            dlg.dismiss()
            refresh()
        }
        dlg.show()
    }
}
