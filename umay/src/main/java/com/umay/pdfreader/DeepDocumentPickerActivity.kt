package com.umay.pdfreader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.text.format.Formatter
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v13 (T8) — DERİN DOSYA TARAYICI / ÇOKLU SEÇİMLİ BELGE SEÇİCİ.
 *
 * Cihazdaki TÜM desteklenen belgeleri (.pdf, .doc, .docx, .txt, .epub,
 * .md, .csv, .pptx) derinlemesine tarar:
 *  - Dahili depolama: Environment.DIRECTORY_DOCUMENTS + DOWNLOAD
 *  - Harici SD kart: getExternalFilesDirs / StorageVolume (API 21+)
 *
 * Kullanıcı çoklu seçim yapar → "Kütüphaneye Aktar" ile BookStore.importPdf
 * sırayla çalışır; her dosya AYRI ayrı korunur (bozuk dosya aktarımı durdurmaz).
 *
 * İZİN (v16 / T8.1 — izin kilidi tamiri):
 *  - API 30+ : MANAGE_EXTERNAL_STORAGE (\"Tüm dosyalara erişim\"). MANIFEST'TE
 *    BEYAN EDİLMEZSE ayar anahtarı açılamaz → anahtar artık beyan edilmiştir.
 *  - API ≤32: READ_EXTERNAL_STORAGE yedek akışı.
 *  - API 33+ : belge (PDF/DOCX) için çalışma-zamanı izni YOKTUR; izin
 *    verilemezse SAF (ACTION_OPEN_DOCUMENT) yedeği devreye girer.
 *  - Derin tarama 6 seviye derinlik + 800 dosya sınırı (ANR/bellek koruması).
 *  - Tarama / aktarım Dispatchers.IO — ana thread asla bloklanmaz (ANR yok).
 *
 * v22 (DENETİM AŞAMA 5.5):
 *  - Satır görünümü: kapak önizlemesi (tembel, önbellekli) + tür/boyut/tarih/
 *    sayfa sayısı metadata + durum (eklenmedi / zaten ekli / erişilemiyor).
 *  - Filtreler: dosya türü, durum; sıralama: ad A-Z/Z-A, boyut, tarih.
 *    Filtreler YALNIZCA listeyi değiştirir; dosya eklemez/emez/silmez.
 *  - Seçim dosya yoluna bağlı kalır; filtre değişimi seçimi etkilemez.
 *  - "Tümünü Seç" yalnızca Görünür (filtrelenmiş) dosyaları seçer.
 */
class DeepDocumentPickerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanning = AtomicBoolean(false)

    /** Bu oturumda "tüm dosyalara erişim" diyaloğu gösterildi mi? (modal döngü yok) */
    private var promptedAllFiles = false

    private lateinit var listView: ListView
    private lateinit var statusText: TextView
    private lateinit var btnScan: TextView
    private lateinit var btnImport: TextView
    private lateinit var btnSafPick: TextView
    private lateinit var btnTypeFilter: TextView
    private lateinit var btnStatusFilter: TextView
    private lateinit var btnSort: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnClearSel: TextView

    /** Ayarlar › Tüm dosyalara erişim ekranından dönüş. */
    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onStorageSettingsReturn() }

    /** SAF yedeği: izin verilemezse belge seç → kütüphaneye aktar. */
    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) importFromSaf(uris) }

    /**
     * Taranan dosya: kimlik + scan anındaki metadata.
     * `added` (zaten ekli) tarama sonrası IO'da tek snapshot ile hesaplanır.
     * `pages` / `thumb` / `thumbFailed` tembel (yalnız satır görünürken) dolar.
     */
    private data class Found(
        val name: String,
        val path: String,
        val ext: String,
        val size: Long,
        val modified: Long,
        var added: Boolean = false,
        var pages: Int = -1,
        var thumb: Bitmap? = null,
        var thumbFailed: Boolean = false
    )

    private val found = ArrayList<Found>()
    /** Filtre + sıralama sonrası EKRANDAKİ liste (adapter bunu gösterir). */
    private val visible = ArrayList<Found>()
    // v21 (DENETİM BUG-003): seçim artık POZİSYON yerine dosya YOLUNA
    // bağlıdır — liste yenilense/filtrelense bile hangi dosyanın seçili
    // olduğu karışmaz; checkbox yalnız kendi dosyasının seçimini değiştirir.
    private val selectedPaths = LinkedHashSet<String>()
    private lateinit var adapter: FoundAdapter

    // ── v22 — filtre/sıralama durumu (yalnız görünümü değiştirir) ──────
    private data class FilterState(
        var typeIdx: Int = 0,      // 0 = tümü, sonrası FILTER_EXTS dizini
        var statusIdx: Int = 0,    // 0 = tümü, 1 = eklenmemiş, 2 = zaten ekli
        var sortIdx: Int = 0       // 0 = A-Z … 5 = tarih eski
    )
    private val filter = FilterState()

    // ── v22 — tembel kapak önizlemesi (yalnız görünür satırlar) ────────
    private val thumbExecutor = Executors.newSingleThreadExecutor()
    private val thumbPending = java.util.Collections.synchronizedSet(HashSet<String>())
    private val thumbCache = object : LruCache<String, Bitmap>(64) {}
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private var notifyScheduled = false

    /**
     * v21.1 — KLASÖRDEN DERSLERE AKTAR.
     * Kaynak: Download/UmayKaynak (alt klasörlerle). Onay alınır, ilerleme
     * gösterilir, sonuçta ders bazında özet + eşleşmeyen dosyalar listelenir.
     */
    private fun confirmFolderImport() {
        val dir = java.io.File(FolderImporter.DEFAULT_DIR)
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.folder_import)
            .setMessage(
                getString(R.string.folder_import_info) + "\n\n" + dir.absolutePath +
                    (if (dir.exists()) "" else "\n\n⚠ Bu klasör yok. Dosyaları buraya kopyalayın.")
            )
            .setPositiveButton(android.R.string.ok) { _, _ -> runFolderImport(dir) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** v21.3 — Havuzdaki (hiçbir gruba bağlı olmayan) kitapları düzenler. */
    private fun confirmPoolOrganize() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.pool_organize)
            .setMessage(R.string.pool_organize_info)
            .setPositiveButton(android.R.string.ok) { _, _ -> runPoolOrganize() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runPoolOrganize() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.pool_organize)
            .setMessage("…")
            .setCancelable(false)
            .create()
        dialog.show()
        FolderImporter.organizePool(this, { name, done, total ->
            runOnUiThread { dialog.setMessage("$done/$total\n$name") }
        }) { placed, remaining ->
            runOnUiThread {
                try { dialog.dismiss() } catch (_: Throwable) {}
                android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.pool_organize)
                    .setMessage(
                        "Yerleştirilen: $placed\nHavuzda kalan: $remaining\n\n" +
                            "Kalanları Cihaz Tarama listesinden elle taşıyabilirsin."
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /**
     * v24 — Aktarma raporu. Renkli (+N) farkı gösterebilmek için düz
     * `setMessage` yerine kaydırılabilir, seçilebilir bir TextView kullanılır.
     */
    private fun showReport(text: CharSequence) {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 14f
            setTextColor(getColor(R.color.umay_text))
            setPadding(pad, pad, pad, pad / 2)
            setText(text)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.folder_import_done)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** v24 — Rapor gövdesi: "önce → sonra  +N" farkı TURUNCU ve kalın. */
    private fun buildReportText(rep: FolderImporter.Report): android.text.SpannableStringBuilder {
        val sb = android.text.SpannableStringBuilder()
        val orange = getColor(R.color.umay_orange)
        val bold = android.graphics.Typeface.BOLD
        fun line(s: String = "") { sb.append(s).append('\n') }
        /** Eklenen sayı → turuncu + kalın (yalnız GERÇEKTEN eklenme varsa). */
        fun orangePlus(n: Int) {
            if (n <= 0) return
            val st = sb.length
            sb.append("  +$n")
            sb.setSpan(
                android.text.style.ForegroundColorSpan(orange), st, sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(
                android.text.style.StyleSpan(bold), st, sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        line("Yeni eklenen: ${rep.imported}")
        line("Zaten kütüphanede: ${rep.existing}")
        line("Hata: ${rep.failed}")
        if (rep.duplicates.isNotEmpty()) line("Mükerrer (eklenmedi): ${rep.duplicates.size}")
        line()
        val total = try { BookStore.loadBooks(this).size } catch (_: Throwable) { 0 }
        line("Kütüphanedeki toplam kaynak: $total")

        if (rep.createdGroups.isNotEmpty()) {
            line()
            line("Yeni açılan grup(lar): ${rep.createdGroups.joinToString(", ")}")
        }
        if (rep.perCourseDelta.isNotEmpty()) {
            line()
            line("Derslere dağılım (önce → sonra):")
            rep.perCourseDelta.forEach { (name, pair) ->
                val before = pair.first
                val after = pair.second
                sb.append("• $name: $before → $after")
                orangePlus(after - before)
                sb.append('\n')
            }
        }
        if (rep.unmatched.isNotEmpty()) {
            line()
            line("Eşleşmeyen (rafa eklenmedi): ${rep.unmatched.size}")
            rep.unmatched.take(12).forEach { line("– $it") }
        }
        return sb
    }

    private fun runFolderImport(
        dir: java.io.File,
        forcePaths: Set<String> = emptySet(),
        askDuplicates: Boolean = true
    ) {
        if (!dir.exists() || !dir.isDirectory) {
            android.widget.Toast.makeText(
                this, "Klasör bulunamadı: ${dir.absolutePath}", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.folder_import)
            .setMessage(getString(R.string.folder_import_running) + "…")
            .setCancelable(false)
            .create()
        dialog.show()
        FolderImporter.run(this, dir, { name, done, total ->
            runOnUiThread {
                dialog.setMessage(
                    getString(R.string.folder_import_running) + ": $done/$total\n" + name
                )
            }
        }, forcePaths = forcePaths) { rep ->
            runOnUiThread {
                try { dialog.dismiss() } catch (_: Throwable) {}
                val sb = buildReportText(rep)
                if (askDuplicates && rep.duplicates.isNotEmpty()) {
                    val shown = rep.duplicates.take(15).joinToString("\n") { "– $it" }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Mükerrer dosyalar")
                        .setMessage(
                            "Bu ${rep.duplicates.size} dosya kütüphanede ZATEN var, " +
                                "bu yüzden eklenmedi:\n\n$shown" +
                                (if (rep.duplicates.size > 15) "\n…" else "") +
                                "\n\nYine de eklemek ister misin? (Kopya oluşur)"
                        )
                        .setPositiveButton("Yine de ekle") { _, _ ->
                            val force = rep.duplicates
                                .map { java.io.File(dir, it).absolutePath }.toSet()
                            runFolderImport(dir, force, askDuplicates = false)
                        }
                        .setNegativeButton("Hayır, ekleme") { _, _ -> showReport(sb) }
                        .setCancelable(false)
                        .show()
                } else {
                    showReport(sb)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_deep_picker)

        listView = findViewById(R.id.fileList)
        statusText = findViewById(R.id.statusText)
        btnScan = findViewById(R.id.btnScan)
        btnImport = findViewById(R.id.btnImport)

        adapter = FoundAdapter()
        listView.adapter = adapter
        // Seçim yol bazlı yönetiliyor; ListView'in pozisyon bazlı checked
        // durumuna GEREK YOK (filtre değişiminde karışmasın diye NONE).
        listView.choiceMode = ListView.CHOICE_MODE_NONE

        btnSafPick = findViewById(R.id.btnSafPick)
        // v21.1 — hazır klasörü derslere toplu aktarma
        findViewById<android.widget.TextView>(R.id.btnFolderImport)?.setOnClickListener {
            confirmFolderImport()
        }
        findViewById<android.widget.TextView>(R.id.btnPoolOrganize)?.setOnClickListener {
            confirmPoolOrganize()
        }
        btnTypeFilter = findViewById(R.id.btnTypeFilter)
        btnStatusFilter = findViewById(R.id.btnStatusFilter)
        btnSort = findViewById(R.id.btnSort)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnClearSel = findViewById(R.id.btnClearSel)

        // "Yeniden Tara": izni yeniden isteme hakkı da verilir (kilitli anahtar
        // çözülmüşse otomatik tarama, hâlâ kapalıysa SAF yönlendirmesi).
        btnScan.setOnClickListener {
            promptedAllFiles = false
            ensurePermissionThenScan()
        }
        btnSafPick.setOnClickListener { openSafPicker() }
        btnImport.setOnClickListener { importSelected() }

        btnTypeFilter.setOnClickListener { pickTypeFilter() }
        btnStatusFilter.setOnClickListener { pickStatusFilter() }
        btnSort.setOnClickListener { pickSort() }
        btnSelectAll.setOnClickListener {
            // Yalnızca GÖRÜNÜR (filtrelenmiş) dosyalar seçilir.
            visible.forEach { selectedPaths.add(it.path) }
            adapter.notifyDataSetChanged()
            updateSelectionLabel()
        }
        btnClearSel.setOnClickListener {
            selectedPaths.clear()
            adapter.notifyDataSetChanged()
            updateSelectionLabel()
        }

        // Satır tıklaması: yalnız o dosyanın seçimini değiştirir (yol bazlı).
        listView.setOnItemClickListener { _, _, pos, _ ->
            val f = visible.getOrNull(pos) ?: return@setOnItemClickListener
            if (!selectedPaths.remove(f.path)) selectedPaths.add(f.path)
            adapter.notifyDataSetChanged()
            updateSelectionLabel()
        }

        updateFilterButtons()

        // Açılır açılmaz tara (izin zaten verilmişse)
        ensurePermissionThenScan()
    }

    // v14 (V5.0 §3) + v16 (T8.1) — ANDROID 11+ (API 30) TAM DOSYA ERİŞİMİ:
    //
    // READ_MEDIA_* yalnız "medya" görür; telefondaki TÜM PDF'leri (klasör
    // içi, uygulama klasörleri, SD kart) eksiksiz taramak için
    // MANAGE_EXTERNAL_STORAGE gerekir.
    //
    // v16 KRİTİK: Bu izin AndroidManifest.xml'de BEYAN EDİLMEZSE
    // Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION ekranı açılır
    // ama "Tüm dosyalara erişim" anahtarı GRİ/KİLİTLİ kalır → kullanıcı
    // izni ASLA veremez. Manifest düzeltildi (v16 / T8.1).
    @Suppress("ObsoleteSdkInt")
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    /**
     * API ≤32: READ_EXTERNAL_STORAGE · API 33+: belge için çalışma-zamanı
     * izni yoktur (null) → SAF yedeği veya MANAGE_EXTERNAL_STORAGE kullanılır.
     */
    private fun requiredPermission(): String? = when {
        Build.VERSION.SDK_INT >= 33 -> null
        else -> Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun ensurePermissionThenScan() {
        if (Build.VERSION.SDK_INT >= 30 && !hasAllFilesAccess()) {
            if (promptedAllFiles) {
                // Ayarlardan izin VERİLMEDEN dönüldü → modal döngü YOK:
                // durum metni + SAF yedeği sunulur, tarama yine denenir.
                onStorageSettingsReturn()
            } else {
                promptedAllFiles = true
                showAllFilesDialog()
            }
            return
        }
        legacyPermissionScan()
    }

    /** Kilitli anahtar açıklaması + Ayarlar'a yönlendirme + SAF yedeği. */
    private fun showAllFilesDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.deep_all_files_title)
            .setMessage(R.string.deep_all_files_msg)
            .setPositiveButton(R.string.deep_all_files_go) { _, _ -> openAllFilesSettings() }
            .setNeutralButton(R.string.deep_saf) { _, _ -> openSafPicker() }
            .setNegativeButton(R.string.iptal) { _, _ ->
                statusText.text = getString(R.string.deep_all_files_denied)
                legacyPermissionScan()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Ayarlar › Tüm dosyalara erişim ekranını açmayı dener (3 kademeli):
     * 1) uygulamaya özel sayfa (paket URI'li)
     * 2) tüm uygulamaları listeleyen genel sayfa
     * 3) uygulama bilgisi sayfası (OEM'lerde yolu farklı olabiliyor)
     */
    private fun openAllFilesSettings() {
        val attempts = listOf(
            Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            ),
            Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
        for (intent in attempts) {
            try {
                allFilesLauncher.launch(intent)
                return
            } catch (t: Throwable) {
                BookStore.logError(this, "deep/allFilesSettings", t)
            }
        }
        // Hiçbir ayar ekranı açılamazsa klasik izin akışına düş
        legacyPermissionScan()
    }

    /** Ayarlar ekranından dönüş: izin verildiyse tara, verilmediyse yönlendir. */
    private fun onStorageSettingsReturn() {
        if (hasAllFilesAccess()) {
            Toast.makeText(this, R.string.deep_all_files_ok, Toast.LENGTH_SHORT).show()
            startScan()
            return
        }
        statusText.text = getString(R.string.deep_all_files_denied)
        Toast.makeText(this, R.string.deep_all_files_manual, Toast.LENGTH_LONG).show()
        legacyPermissionScan()
    }

    /** Klasik izin akışı (Android 10 ve altı veya "Tüm dosya" reddedildiyse). */
    private fun legacyPermissionScan() {
        val perm = requiredPermission()
        if (perm == null || ContextCompat.checkSelfPermission(this, perm) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            // API 33+ ve izin yok → File API yalnız erişilebilen yerleri görür;
            // boş sonuçta kullanıcı SAF'a yönlendirilir (startScan içinde).
            startScan()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 100)
        }
    }

    /** SAF yedeği: ACTION_OPEN_DOCUMENT ile çoklu belge seçimi. */
    private fun openSafPicker() {
        try {
            safLauncher.launch(
                arrayOf(
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "text/plain",
                    "application/epub+zip"
                )
            )
        } catch (t: Throwable) {
            BookStore.logError(this, "deep/saf", t)
            Toast.makeText(this, R.string.deep_saf_error, Toast.LENGTH_SHORT).show()
        }
    }

    /** SAF'tan seçilen URI'ler → kütüphaneye aktarım (içeri kopyalanır). */
    private fun importFromSaf(uris: List<Uri>) {
        btnImport.isEnabled = false
        statusText.text = getString(R.string.deep_importing, 0, uris.size)
        scope.launch {
            var okCount = 0
            var alreadyCount = 0
            var failCount = 0
            uris.forEachIndexed { i, uri ->
                // Kalıcı okuma izni → yeniden başlatmada SecurityException'a karşı
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {
                    // bazı sağlayıcılar kalıcı izin vermez — akış devam eder
                }
                // v21: zaten ekli mi? (importPdf bunu ayrıca korur; burada
                // sonuç raporu için ayrı sayılır)
                val already = BookStore.sourceKeyOf(this@DeepDocumentPickerActivity, uri)
                    ?.let { BookStore.findBySourceKey(this@DeepDocumentPickerActivity, it) } != null
                val result: Result<Book> =
                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        BookStore.importPdf(this@DeepDocumentPickerActivity, uri) { r ->
                            cont.resumeWith(Result.success(r))
                        }
                    }
                when {
                    result.isFailure -> failCount++
                    already -> alreadyCount++
                    else -> {
                        okCount++
                        // v21: varsayılan raf — CİHAZ TARAMA & DOKÜMANLAR
                        // (kullanıcı isterse uzun basma → "Raflara taşı" ile
                        // başka rafa alınır; otomatik ders rafı ETİKETİ YOK)
                        result.getOrNull()?.let { b ->
                            ShelfManager.addBookToShelf(
                                this@DeepDocumentPickerActivity,
                                b.id, ShelfManager.SHELF_DEVICE_DOCS
                            )
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    statusText.text = getString(R.string.deep_importing, i + 1, uris.size)
                }
            }
            finishImport(okCount, alreadyCount, failCount)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startScan()
            } else {
                statusText.text = getString(R.string.deep_no_permission)
            }
        }
    }

    private fun startScan() {
        if (!scanning.compareAndSet(false, true)) return
        statusText.text = getString(R.string.deep_scanning)
        btnScan.isEnabled = false
        scope.launch {
            // v21: tarama YALNIZCA keşiftir — hiçbir dosya otomatik
            // aktarılmaz/eklenmez; içe aktarma yalnız butona basınca olur.
            val results = scanDevice()

            // v22 (AŞAMA 5.5): tarama sonrası "zaten ekli" durumu — kaynak
            // indeksi TEK seferde okunur (800 dosya için tek JSON parse).
            val srcKeys = BookStore.sourceKeysSnapshot(this@DeepDocumentPickerActivity)
            if (srcKeys.isNotEmpty()) {
                results.forEach { f ->
                    val key = BookStore.sourceKeyOf(
                        this@DeepDocumentPickerActivity, Uri.fromFile(File(f.path))
                    )
                    f.added = key != null && srcKeys.contains(key)
                }
            }

            withContext(Dispatchers.Main) {
                scanning.set(false)
                btnScan.isEnabled = true
                found.clear()
                found.addAll(results)
                selectedPaths.clear()
                thumbPending.clear()
                thumbCache.evictAll()
                rebuildVisible()
                updateSelectionLabel()
                statusText.text = if (results.isEmpty()) {
                    if (Build.VERSION.SDK_INT >= 30 && !hasAllFilesAccess()) {
                        getString(R.string.deep_empty_no_access)
                    } else {
                        getString(R.string.deep_empty)
                    }
                } else {
                    getString(R.string.deep_found, results.size)
                }
            }
        }
    }

    /** Seçili dosya sayısı + ipucu ("15 dosya seçildi"). */
    private fun updateSelectionLabel() {
        val n = selectedPaths.size
        if (n == 0) {
            if (found.isNotEmpty()) {
                statusText.text = getString(R.string.deep_found, found.size)
            }
        } else {
            statusText.text = getString(R.string.deep_selected_count, n)
        }
    }

    // ── v22 — FİLTRE / SIRALAMA (yalnız görünüm; dosya eklemez/silmez) ──

    private val filterExts =
        listOf("pdf", "doc", "docx", "pptx", "txt", "epub", "md", "csv")

    private fun pickTypeFilter() {
        val labels = arrayOf(getString(R.string.deep_filter_all)) +
            filterExts.map { it.uppercase(Locale.ROOT) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.deep_filter_type_title)
            .setSingleChoiceItems(labels, filter.typeIdx) { d, which ->
                filter.typeIdx = which
                d.dismiss()
                updateFilterButtons()
                rebuildVisible()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    private fun pickStatusFilter() {
        val labels = arrayOf(
            getString(R.string.deep_filter_all),
            getString(R.string.deep_status_filter_new),
            getString(R.string.deep_status_filter_added)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.deep_filter_status_title)
            .setSingleChoiceItems(labels, filter.statusIdx) { d, which ->
                filter.statusIdx = which
                d.dismiss()
                updateFilterButtons()
                rebuildVisible()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    private fun pickSort() {
        val labels = arrayOf(
            getString(R.string.deep_sort_az),
            getString(R.string.deep_sort_za),
            getString(R.string.deep_sort_size_asc),
            getString(R.string.deep_sort_size_desc),
            getString(R.string.deep_sort_date_new),
            getString(R.string.deep_sort_date_old)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.deep_sort_title)
            .setSingleChoiceItems(labels, filter.sortIdx) { d, which ->
                filter.sortIdx = which
                d.dismiss()
                updateFilterButtons()
                rebuildVisible()
            }
            .setNegativeButton(R.string.iptal, null)
            .show()
    }

    private fun updateFilterButtons() {
        val typeLabel = if (filter.typeIdx == 0) getString(R.string.deep_filter_all)
        else filterExts[filter.typeIdx - 1].uppercase(Locale.ROOT)
        btnTypeFilter.text = getString(R.string.deep_filter_type, typeLabel)

        val statusLabel = when (filter.statusIdx) {
            1 -> getString(R.string.deep_status_filter_new)
            2 -> getString(R.string.deep_status_filter_added)
            else -> getString(R.string.deep_filter_all)
        }
        btnStatusFilter.text = getString(R.string.deep_filter_status, statusLabel)

        val sortLabel = when (filter.sortIdx) {
            0 -> getString(R.string.deep_sort_az)
            1 -> getString(R.string.deep_sort_za)
            2 -> getString(R.string.deep_sort_size_asc)
            3 -> getString(R.string.deep_sort_size_desc)
            4 -> getString(R.string.deep_sort_date_new)
            else -> getString(R.string.deep_sort_date_old)
        }
        btnSort.text = getString(R.string.deep_sort_label, sortLabel)
    }

    /** Filtre + sıralama → `visible`. Seçim (selectedPaths) etkilenmez. */
    private fun rebuildVisible() {
        val out = found.filter { f ->
            val typeOk = filter.typeIdx == 0 || f.ext == filterExts[filter.typeIdx - 1]
            val statusOk = when (filter.statusIdx) {
                1 -> !f.added
                2 -> f.added
                else -> true
            }
            typeOk && statusOk
        }
        val sorted = when (filter.sortIdx) {
            0 -> out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            1 -> out.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
            2 -> out.sortedBy { it.size }
            3 -> out.sortedByDescending { it.size }
            4 -> out.sortedByDescending { it.modified }
            else -> out.sortedBy { it.modified }
        }
        visible.clear()
        visible.addAll(sorted)
        adapter.notifyDataSetChanged()
    }

    // ── v22 — SATIR METADATA + TEMBEL KAPAK ÖNİZLEMESİ ─────────────────

    /** Meta satırı: "PDF · 18,4 MB · 428 sayfa · 12.09.2026" */
    private fun metaOf(f: Found): String {
        val parts = ArrayList<String>(4)
        parts.add(f.ext.uppercase(Locale.ROOT))
        parts.add(Formatter.formatFileSize(this, f.size))
        if (f.pages > 0) parts.add(getString(R.string.deep_page_count, f.pages))
        if (f.modified > 0) parts.add(dateFormat.format(Date(f.modified)))
        return parts.joinToString("  ·  ")
    }

    /**
     * PDF satırı görünür olduğunda sayfa sayısı + küçük kapak önizlemesi
     * arka planda üretilir (tek iş parçacığı; ana thread asla bloklanmaz).
     * Sonuç LruCache'te; içerik değişmedikçe tekrar üretilmez.
     */
    private fun requestThumb(f: Found) {
        if (f.ext != "pdf" || f.thumbFailed || f.thumb != null) return
        if (!thumbPending.add(f.path)) return
        thumbExecutor.execute {
            var bmp: Bitmap? = null
            var pages = -1
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            var page: PdfRenderer.Page? = null
            try {
                pfd = ParcelFileDescriptor.open(
                    File(f.path), ParcelFileDescriptor.MODE_READ_ONLY
                )
                val r = PdfRenderer(pfd)
                renderer = r
                pages = r.pageCount
                val p = r.openPage(0)
                page = p
                val targetW = 144
                val scale = targetW.toFloat() / p.width.toFloat()
                val h = (p.height * scale).toInt().coerceAtLeast(1)
                val out = Bitmap.createBitmap(targetW, h, Bitmap.Config.ARGB_8888)
                out.eraseColor(Color.WHITE)
                val m = Matrix().apply { setScale(scale, scale) }
                p.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                // "boş render" algılama: tamamen beyaz kapak = başarısız
                if (!isMostlyWhite(out)) bmp = out
            } catch (_: Throwable) {
                // bozuk/korumalı PDF → önizleme yok; satır ikon etiketiyle durur
            } finally {
                try { page?.close() } catch (_: Throwable) { }
                try { renderer?.close() } catch (_: Throwable) { }
                try { pfd?.close() } catch (_: Throwable) { }
            }
            thumbPending.remove(f.path)
            runOnUiThread {
                if (pages > 0) f.pages = pages
                val b = bmp
                if (b != null) {
                    f.thumb = b
                    thumbCache.put(f.path, b)
                } else {
                    f.thumbFailed = true
                }
                scheduleNotify()
            }
        }
    }

    /** Bitmap ~%98 beyaz mı? (kapak render edilemedi demek) */
    private fun isMostlyWhite(bmp: Bitmap): Boolean {
        val step = (bmp.width * bmp.height / 400).coerceAtLeast(1)
        var white = 0
        var total = 0
        var i = 0
        val n = bmp.width * bmp.height
        while (i < n) {
            val px = bmp.getPixel(i % bmp.width, i / bmp.width)
            total++
            if (px == Color.WHITE) white++
            i += step
        }
        return total > 0 && white.toDouble() / total > 0.98
    }

    /** Çoklu sonuçta tek notifyDataSetChanged (kaydırma akıcılığı korunur). */
    private fun scheduleNotify() {
        if (notifyScheduled || isFinishing || isDestroyed) return
        notifyScheduled = true
        listView.postDelayed({
            notifyScheduled = false
            if (!isFinishing && !isDestroyed) adapter.notifyDataSetChanged()
        }, 250)
    }

    /** Satır bağlandığında: önbellek yoksa arka planda üret. */
    private fun ensureThumb(f: Found) {
        val cached = thumbCache.get(f.path)
        if (cached != null) {
            f.thumb = cached
            return
        }
        requestThumb(f)
    }

    private inner class FoundAdapter : BaseAdapter() {
        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): Found = visible[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater
                .inflate(R.layout.item_deep_file, parent, false)
            val f = visible[position]

            val chk = v.findViewById<CheckBox>(R.id.chkFile)
            val img = v.findViewById<ImageView>(R.id.imgThumb)
            val thumbLabel = v.findViewById<TextView>(R.id.thumbLabel)
            val name = v.findViewById<TextView>(R.id.txtName)
            val meta = v.findViewById<TextView>(R.id.txtMeta)
            val st = v.findViewById<TextView>(R.id.txtStatus)

            name.text = f.name
            name.contentDescription = f.name            // tam ad erişilebilir
            chk.isChecked = selectedPaths.contains(f.path)
            meta.text = metaOf(f)

            val exists = File(f.path).exists()
            when {
                !exists -> {
                    st.text = getString(R.string.deep_status_gone)
                    st.setTextColor(ContextCompat.getColor(this@DeepDocumentPickerActivity, R.color.umay_danger))
                }
                f.added -> {
                    st.text = getString(R.string.deep_status_added)
                    st.setTextColor(ContextCompat.getColor(this@DeepDocumentPickerActivity, R.color.umay_ok))
                }
                else -> {
                    st.text = getString(R.string.deep_status_not_added)
                    st.setTextColor(ContextCompat.getColor(this@DeepDocumentPickerActivity, R.color.umay_text_dim))
                }
            }

            // Kapak: varsa göster, yoksa tür etiketi; PDF ise tembel üret.
            val t = f.thumb
            if (t != null && !t.isRecycled) {
                img.setImageBitmap(t)
                thumbLabel.visibility = View.INVISIBLE
            } else {
                img.setImageDrawable(null)
                thumbLabel.visibility = View.VISIBLE
                thumbLabel.text = f.ext.uppercase(Locale.ROOT)
                if (exists) ensureThumb(f)
            }
            return v
        }
    }

    /** IO: TÜM birimler (dahili depolama + SD kart) derin tarama, tekilleştirilmiş. */
    private suspend fun scanDevice(): List<Found> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Found>()   // yol → Found (tekilleştirme)
        for (root in storageRoots()) {
            if (out.size >= MAX_RESULTS) break
            try {
                deepCollect(root, EXTS, 0, out)
            } catch (_: Throwable) {
                // erişilemeyen birim/klasör → atla (bozuk dosya taramayı durdurmaz)
            }
        }
        out.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Taranacak kökler (v16 / T8.1 — SD kart + tüm cihaz):
     *  1) StorageManager hacim kökleri (API 30+ gerçek yol; SD kart/USB)
     *  2) /storage/<UUID> çıkarılabilir birimler (API < 30 yedeği)
     *  3) dahili depolama kökü + yaygın genel klasörler
     *  4) uygulamaya özel harici klasörler (izin yokken de okunur)
     * Aynı yol iki kez taranmaz (LinkedHashSet).
     */
    private fun storageRoots(): List<File> {
        val roots = LinkedHashSet<File>()

        // 1) StorageManager hacimleri: SD kartın GERÇEK kökü burada verilir.
        try {
            val sm = getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            sm?.storageVolumes?.forEach { v ->
                val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else null
                if (dir != null) roots.add(dir)
            }
        } catch (_: Throwable) { }

        // 2) /storage altındaki harici birimler (emulated zaten 3. adımda)
        try {
            File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "self" && f.name != "emulated") {
                    roots.add(f)
                }
            }
        } catch (_: Throwable) { }

        // 3) Dahili kök + yaygın genel klasörler (Documents/Downloads/DCIM)
        try {
            Environment.getExternalStorageDirectory()?.let { roots.add(it) }
        } catch (_: Throwable) { }
        listOf(
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DCIM
        ).forEach { dir ->
            try {
                Environment.getExternalStoragePublicDirectory(dir)?.let { roots.add(it) }
            } catch (_: Throwable) { }
        }

        // 4) Uygulama belgeleri + harici dizinler (SD kart / Adopted storage)
        try {
            getExternalFilesDirs(null)?.filterNotNull()?.forEach { roots.add(it) }
        } catch (_: Throwable) { }

        return roots.toList()
    }

    /**
     * Belgeleri toplar. Güvenlik ağları: derinlik sınırı (MAX_DEPTH), sonuç
     * sınırı (MAX_RESULTS), gizli/sistem klasörü atlama, okunamayan dizinde
     * sessiz çıkış ve yol bazlı tekilleştirme.
     */
    private fun deepCollect(
        dir: File, exts: Set<String>, depth: Int, out: MutableMap<String, Found>
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_RESULTS) return
        val children = dir.listFiles() ?: return    // izin yok → sessizce atla
        for (f in children) {
            if (out.size >= MAX_RESULTS) return
            if (f.isDirectory) {
                val n = f.name
                if (n.startsWith(".")) continue            // gizli klasörler
                if (SKIP_DIRS.contains(n)) continue        // Android/obb, LOST.DIR
                deepCollect(f, exts, depth + 1, out)
            } else if (f.isFile) {
                val name = f.name
                val dot = name.lastIndexOf('.')
                if (dot <= 0) continue
                val ext = name.substring(dot + 1).lowercase()
                if (ext !in exts) continue
                val path = f.absolutePath
                if (!out.containsKey(path)) {
                    // v22: metadata scan anında alınır (stat = bedava)
                    out[path] = Found(name, path, ext, f.length(), f.lastModified())
                }
            }
        }
    }

    /**
     * Seçilen dosyaları sırayla kütüphaneye aktarır.
     * v21 (DENETİM BUG-001/002/004):
     *  • YALNIZCA seçili dosyalar işlenir (liste asla toplu gönderilmez).
     *  • Boş seçimde içe aktarma engellenir.
     *  • Aynı dosya tekrar seçilirse YENİ KOPYA OLUŞMAZ (zaten ekli sayılır).
     *  • Varsayılan hedef raf: CİHAZ TARAMA & DOKÜMANLAR (otomatik ders
     *    rafı etiketlemesi kaldırıldı — kullanıcı isterse taşır).
     */
    private fun importSelected() {
        if (selectedPaths.isEmpty()) {
            Toast.makeText(this, R.string.deep_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val paths = selectedPaths.toList()

        btnImport.isEnabled = false
        statusText.text = getString(R.string.deep_importing, 0, paths.size)

        scope.launch {
            var okCount = 0
            var alreadyCount = 0
            var failCount = 0
            paths.forEachIndexed { i, path ->
                val f = File(path)
                if (!f.exists()) { failCount++; return@forEachIndexed }
                val uri = android.net.Uri.fromFile(f)
                // v21: aynı dosya zaten kütüphanedeyse yeni kayıt açma
                val already = BookStore.sourceKeyOf(this@DeepDocumentPickerActivity, uri)
                    ?.let { BookStore.findBySourceKey(this@DeepDocumentPickerActivity, it) } != null
                val result: Result<Book> =
                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        BookStore.importPdf(this@DeepDocumentPickerActivity, uri) { r ->
                            cont.resumeWith(Result.success(r))
                        }
                    }
                when {
                    result.isFailure -> failCount++
                    already -> alreadyCount++
                    else -> {
                        okCount++
                        // v21: varsayılan raf = CİHAZ TARAMA & DOKÜMANLAR.
                        // (v13'teki autoTagCourseCode KALDIRILDI: dosya adında
                        // ders kodu geçiyor diye otomatik ders rafına gidiyordu.)
                        result.getOrNull()?.let { b ->
                            ShelfManager.addBookToShelf(
                                this@DeepDocumentPickerActivity,
                                b.id, ShelfManager.SHELF_DEVICE_DOCS
                            )
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    statusText.text =
                        getString(R.string.deep_importing, i + 1, paths.size)
                }
            }
            finishImport(okCount, alreadyCount, failCount)
        }
    }

    /** Aktarım sonucu: durum metni + toast + yenilik varsa ekranı kapat. */
    private suspend fun finishImport(okCount: Int, alreadyCount: Int, failCount: Int) =
        withContext(Dispatchers.Main) {
            btnImport.isEnabled = true
            val msg = getString(R.string.deep_import_done3, okCount, alreadyCount, failCount)
            statusText.text = msg
            Toast.makeText(this@DeepDocumentPickerActivity, msg, Toast.LENGTH_LONG).show()
            if (okCount > 0) finish()   // ana ekran listeyi tazeleyecek
        }

    override fun onDestroy() {
        super.onDestroy()
        thumbExecutor.shutdown()
    }

    companion object {
        /** Derin tarama güvenlik ağları (ANR / bellek koruması). */
        private const val MAX_DEPTH = 6
        private const val MAX_RESULTS = 800

        /** Taranmayan sistem/hurda klasörler. */
        private val SKIP_DIRS = setOf("Android", "LOST.DIR", "obb")

        /** Desteklenen belge uzantıları (BookStore/DocumentConverter ile hizalı). */
        private val EXTS = setOf("pdf", "doc", "docx", "txt", "epub", "md", "csv", "pptx")
    }
}
