package com.umay.pdfreader

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/**
 * v24 — GİZLİ KASA (PIN / parmak izi + medya rafları).
 *
 * Kapı:
 *  • İlk açılışta varsayılan PIN **1234** atanır (kullanıcı isteği); Dilersen
 *    "PIN değiştir" ile değiştirilir.
 *  • PIN bir kez girildikten sonra PIN, Android Keystore anahtarıyla
 *    şifrelenip saklanır → sonraki açılışlarda **parmak izi tek başına yeter**.
 *  • Biyometrik yoksa/iptal edilirse PIN kapısı devreye girer.
 *
 * İçerik:
 *  • Resim / video / belge / kitap ayrı raflarda (yatay kaydırmalı).
 *  • Her dosya AES-256/CBC ile şifrelenir (vault/<id>.enc).
 *  • Ekleme sırasında "orijinal telefondan silinsin mi?" ayrıca sorulur;
 *    varsayılan HAYIR (geri alınamaz işlem).
 */
class SecureVaultActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var container: LinearLayout
    private var unlocked = false
    private var pinCached: String? = null
    private val thumbs = Executors.newSingleThreadExecutor { r ->
        Thread(r, "umay-vault-thumb").apply { isDaemon = true }
    }
    private val thumbCache = HashMap<String, Bitmap?>()

    private val mediaPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            importMedia(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault)
        EdgeToEdge.apply(this, findViewById(android.R.id.content))

        // v25 (H1) — GÜVENLİK: kasa ekranı ekran görüntüsüne, ekran kaydına ve
        // "son uygulamalar" küçük resmine YANSIMAZ. Kasadan açılan belge
        // okuyucuda açıldığında FLAG_SECURE oraya taşınmaz (PDF okumak için
        // ekran görüntüsü serbest kalır).
        try {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (_: Throwable) {
        }

        status = findViewById(R.id.vaultStatus)
        container = findViewById(R.id.vaultShelves)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnAddMedia).setOnClickListener { pickMedia() }
        findViewById<TextView>(R.id.btnAddToVault).setOnClickListener { addBooksToVault() }
        findViewById<TextView>(R.id.btnVaultLock).setOnClickListener { lockDown() }

        if (!SecureVault.hasPin(this) || SecureVault.needsDefaultPinMigration(this)) {
            // İlk açılış / v24 geçişi: kullanıcının istediği varsayılan PIN
            SecureVault.applyDefaultPin(this, DEFAULT_PIN)
            SecureVault.rememberPinForBiometric(this, DEFAULT_PIN)
            pinCached = DEFAULT_PIN
            Toast.makeText(
                this, "Kasa PIN'i $DEFAULT_PIN olarak ayarlandı", Toast.LENGTH_LONG
            ).show()
            onUnlocked("Kasa açıldı 🔓 (PIN $DEFAULT_PIN)")
        } else {
            // YALNIZ DEBUG: cihaz testlerinde tohumlama için PIN ile açma.
            val devPin = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_SEED_PIN) else null
            if (devPin != null && SecureVault.verifyPin(this, devPin)) {
                pinCached = devPin
                onUnlocked("Kasa açıldı 🔓")
            } else {
                showUnlock()
            }
        }
    }

    /**
     * v24 — GÜVENLİK: kasadan açılan dosyaların çözülmüş geçici kopyaları
     * (`cache/vault_view`) kasa kapanınca SİLİNİR. Aksi hâlde çözülmüş
     * fotoğraf/video önbellekte kalırdı.
     */
    override fun onDestroy() {
        try {
            File(cacheDir, "vault_view").deleteRecursively()
            File(cacheDir, "vault_in_").deleteRecursively()
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ── Kapı ────────────────────────────────────────────────

    private fun showUnlock() {
        unlocked = false
        pinCached = null
        container.removeAllViews()
        // v25 (H4) — yanlış PIN sonrası artan bekleme
        val wait = try { SecureVault.lockRemainingMs(this) } catch (_: Throwable) { 0L }
        if (wait > 0L) {
            val sec = ((wait + 999L) / 1000L).coerceAtLeast(1L)
            status.text = "Çok fazla yanlış PIN — $sec sn sonra tekrar dene"
            status.postDelayed({
                if (!isFinishing && !isDestroyed) showUnlock()
            }, wait + 120L)
            return
        }
        status.text = "Kilitli — parmak izi veya PIN ile aç"
        if (biometricAvailable()) showBiometric() else promptPin("Kasa kilidini aç") { verifyAndOpen(it) }
    }

    private fun biometricAvailable(): Boolean = try {
        BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    } catch (_: Throwable) {
        false
    }

    private fun showBiometric() {
        try {
            val prompt = BiometricPrompt(
                this, ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val cached = SecureVault.pinForBiometric(this@SecureVaultActivity)
                        if (cached != null) {
                            pinCached = cached
                            onUnlocked("Kasa açıldı 🔓 (parmak izi)")
                        } else {
                            // İlk kez: PIN sor, sonra hatırla (bir daha sorulmaz)
                            promptPin("Bir kez PIN gir — sonra sadece parmak izi") { pin ->
                                verifyAndOpen(pin, remember = true)
                            }
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        promptPin("Kasa kilidini aç") { verifyAndOpen(it) }
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Gizli Kasa")
                .setSubtitle("Parmak iziyle aç")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText("PIN ile")
                .build()
            prompt.authenticate(info)
        } catch (t: Throwable) {
            BookStore.logError(this, "vault.biometric", t)
            promptPin("Kasa kilidini aç") { verifyAndOpen(it) }
        }
    }

    private fun promptPin(title: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "••••"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Tamam") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Vazgeç") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun verifyAndOpen(pin: String, remember: Boolean = true) {
        val wait = try { SecureVault.lockRemainingMs(this) } catch (_: Throwable) { 0L }
        if (wait > 0L) {
            val sec = ((wait + 999L) / 1000L).coerceAtLeast(1L)
            Toast.makeText(this, "Bekleme süresi: $sec sn", Toast.LENGTH_SHORT).show()
            showUnlock()
            return
        }
        if (!SecureVault.verifyPin(this, pin)) {
            SecureVault.registerWrongPin(this)
            val left = SecureVault.lockRemainingMs(this)
            val msg = if (left > 0L) {
                "Yanlış PIN — ${((left + 999L) / 1000L)} sn bekle"
            } else {
                "Yanlış PIN"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            showUnlock()
            return
        }
        SecureVault.clearWrongPins(this)
        pinCached = pin
        if (remember) SecureVault.rememberPinForBiometric(this, pin)
        onUnlocked("Kasa açıldı 🔓")
    }

    private fun onUnlocked(msg: String) {
        unlocked = true
        status.text = msg + " · İçerik AES-256 ile şifreli"
        renderShelves()
        handleSeedIntent()
    }

    private fun lockDown() {
        unlocked = false
        pinCached = null
        showUnlock()
    }

    // ── Raflar ──────────────────────────────────────────────

    private data class Group(val kind: String, val title: String)

    private val groups = listOf(
        Group("image", "🖼 Resimler"),
        Group("video", "🎬 Videolar"),
        Group("doc", "📄 Belgeler"),
        Group("book", "📚 Kitaplar"),
        Group("other", "📎 Diğer")
    )

    private fun renderShelves() {
        container.removeAllViews()
        val items = try { SecureVault.items(this) } catch (_: Throwable) { mutableListOf() }
        if (items.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = "Kasa boş.\n\n“＋ Medya / Belge” ile telefondan resim, video, PDF veya " +
                        "belge ekleyebilir; “📚 Kitabı Taşı” ile kütüphanedeki bir kitabı " +
                        "şifreli kasaya alabilirsin."
                    setTextColor(getColor(R.color.umay_text_dim))
                    textSize = 13f
                    val p = (14 * resources.displayMetrics.density).toInt()
                    setPadding(p, p, p, p)
                }
            )
            return
        }
        groups.forEachIndexed { idx, g ->
            val list = items.filter { it.kind == g.kind }
            if (list.isEmpty()) return@forEachIndexed
            container.addView(buildShelf(g.title, list, idx))
        }
    }

    private fun buildShelf(title: String, items: List<SecureVault.Item>, index: Int): View {
        val d = resources.displayMetrics.density
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * d).toInt() }
        }
        applyShelfTint(block, index)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(this).apply {
                text = title
                setTextColor(getColor(R.color.umay_text))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        head.addView(
            TextView(this).apply {
                text = items.size.toString()
                setTextColor(getColor(R.color.umay_orange))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        )
        block.addView(head)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * d).toInt(), 0, 0)
        }
        items.forEach { item -> row.addView(buildCard(item)) }

        block.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
        )
        return block
    }

    /** Ana sayfa/ders raflarıyla aynı neon paleti (yanıp sönmez). */
    private fun applyShelfTint(view: View, index: Int) {
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
        } catch (_: Throwable) {
        }
    }

    private fun buildCard(item: SecureVault.Item): View {
        val card = layoutInflater.inflate(R.layout.item_vault_card, null, false)
        card.findViewById<TextView>(R.id.vaultCardTitle).text = item.title
        val badge = card.findViewById<TextView>(R.id.vaultCardBadge)
        badge.text = when (item.kind) {
            "image" -> "resim"
            "video" -> "video"
            "doc" -> item.ext.uppercase()
            "book" -> "kitap"
            else -> item.ext.uppercase()
        } + " · " + sizeLabel(item.size)
        val thumb = card.findViewById<ImageView>(R.id.vaultCardThumb)
        if (item.kind == "image") {
            loadThumb(item, thumb)
        } else {
            thumb.setImageResource(0)
            thumb.setBackgroundColor(getColor(R.color.umay_card_grad_start))
            thumb.visibility = View.GONE
        }
        card.setOnClickListener { itemActions(item) }
        return card
    }

    private fun loadThumb(item: SecureVault.Item, view: ImageView) {
        val cached = thumbCache[item.id]
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        thumbs.execute {
            val pin = SecureVault.pinForBiometric(this) ?: return@execute
            var bmp: Bitmap? = null
            try {
                val f = SecureVault.decryptFileTo(this, item, pin)
                if (f != null && f.exists()) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    bmp = BitmapFactory.decodeFile(f.absolutePath, opts)
                }
            } catch (t: Throwable) {
                BookStore.logError(this, "vault.thumb", t)
            }
            val result = bmp
            runOnUiThread {
                thumbCache[item.id] = result
                if (result != null) view.setImageBitmap(result)
            }
        }
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes <= 0L -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1048576.0)
    }

    // ── Ekleme ──────────────────────────────────────────────

    private fun pickMedia() {
        if (!unlocked) { showUnlock(); return }
        if (pinCached == null) pinCached = SecureVault.pinForBiometric(this)
        if (pinCached == null) { showUnlock(); return }
        try {
            mediaPicker.launch(arrayOf("*/*"))
        } catch (t: Throwable) {
            BookStore.logError(this, "vault.picker", t)
            Toast.makeText(this, "Dosya seçici açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importMedia(uris: List<Uri>) {
        val pin = pinCached ?: return showUnlock()
        val progress = AlertDialog.Builder(this)
            .setTitle("Kasaya ekleniyor")
            .setMessage("…")
            .setCancelable(false)
            .create()
        progress.show()
        thumbs.execute {
            var ok = 0
            var fail = 0
            uris.forEachIndexed { i, uri ->
                try {
                    val name = displayName(uri) ?: "vault_${System.currentTimeMillis()}"
                    val ext = name.substringAfterLast('.', "").lowercase().ifBlank { "bin" }
                    val kind = kindOf(name, uri)
                    val added = SecureVault.addUriToVault(
                        this, pin, uri, name, kind, ext
                    )
                    if (added != null) ok++ else fail++
                } catch (t: Throwable) {
                    BookStore.logError(this, "vault.import", t)
                    fail++
                }
                runOnUiThread { progress.setMessage("${i + 1}/${uris.size}") }
            }
            runOnUiThread {
                try { progress.dismiss() } catch (_: Throwable) {}
                Toast.makeText(
                    this,
                    "$ok dosya kasaya eklendi${if (fail > 0) " · $fail hata" else ""}",
                    Toast.LENGTH_LONG
                ).show()
                renderShelves()
                if (ok > 0) askDeleteOriginals(uris)
            }
        }
    }

    /** Orijinal dosyalar silinsin mi? — varsayılan HAYIR (geri alınamaz). */
    private fun askDeleteOriginals(uris: List<Uri>) {
        AlertDialog.Builder(this)
            .setTitle("Orijinal dosyalar silinsin mi?")
            .setMessage(
                "Kopya artık kasada güvenle duruyor.\n\n" +
                    "Seçtiğin dosyaların TELEFONDAKİ/GALERİDEKİ orijinalleri de silinsin mi?\n\n" +
                    "Bu işlem GERİ ALINAMAZ — dosyalar yalnız kasa açılınca görünür."
            )
            .setPositiveButton("Evet, orijinalleri sil") { _, _ -> deleteOriginals(uris) }
            .setNegativeButton("Hayır, dursun") { _, _ -> }
            .setCancelable(true)
            .show()
    }

    private fun deleteOriginals(uris: List<Uri>) {
        var deleted = 0
        uris.forEach { uri ->
            try {
                if (DocumentsContract.deleteDocument(contentResolver, uri)) deleted++
            } catch (t: Throwable) {
                // Galeri gibi salt-okunur sağlayıcılar silemez → sessiz geç
                BookStore.logError(this, "vault.deleteOriginal", t)
            }
        }
        Toast.makeText(
            this,
            if (deleted > 0) "$deleted orijinal silindi" else
                "Sağlayıcı silmeye izin vermedi — orijinaller yerinde kaldı",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun kindOf(name: String, uri: Uri): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = try { contentResolver.getType(uri) ?: "" } catch (_: Throwable) { "" }
        return when {
            ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif") -> "image"
            ext in setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v") -> "video"
            ext in setOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt", "md", "epub") -> "doc"
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("text/") || mime.contains("pdf") || mime.contains("word") -> "doc"
            else -> "other"
        }
    }

    /** Kütüphanedeki bir kitabı şifreli kasaya taşır (kitaplıktan gizlenir). */
    private fun addBooksToVault() {
        if (!unlocked) { showUnlock(); return }
        val pin = pinCached ?: return showUnlock()
        val hidden = SecureVault.vaultedIds(this)
        val books = try { BookStore.loadBooks(this) } catch (_: Throwable) { emptyList() }
            .filter { it.id !in hidden }
        if (books.isEmpty()) {
            Toast.makeText(this, "Kütüphanede kasaya taşınacak kitap yok", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(books.size)
        AlertDialog.Builder(this)
            .setTitle("Hangi kitaplar kasaya taşınsın?")
            .setMultiChoiceItems(books.map { it.title }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Kilitle") { _, _ ->
                thumbs.execute {
                    var ok = 0
                    books.forEachIndexed { i, b ->
                        if (!checked[i]) return@forEachIndexed
                        val src = BookStore.sourceFile(this, b.id)
                        if (SecureVault.encryptToVault(this, b.id, pin, src)) {
                            val list = SecureVault.items(this)
                            list.add(
                                SecureVault.Item(
                                    id = b.id, title = b.title, kind = "book", ext = "pdf",
                                    size = try {
                                        File(SecureVault.vaultDir(this), "${b.id}.enc").length()
                                    } catch (_: Throwable) { 0L },
                                    addedAt = System.currentTimeMillis(), bookId = b.id
                                )
                            )
                            SecureVault.saveItems(this, list)
                            ok++
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this@SecureVaultActivity,
                            "$ok kitap kasaya taşındı (AES-256)", Toast.LENGTH_LONG
                        ).show()
                        renderShelves()
                    }
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ── Öğe işlemleri ───────────────────────────────────────

    private fun itemActions(item: SecureVault.Item) {
        if (!unlocked) { showUnlock(); return }
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels.add("Aç / Görüntüle")
        actions.add { openItem(item) }
        labels.add("Kasadan Çıkart (dosyaya geri yaz)")
        actions.add { extractItem(item) }
        if (item.kind == "book") {
            labels.add("Kütüphaneye Geri Al")
            actions.add { restoreBook(item) }
        }
        labels.add("Kalıcı Olarak Sil")
        actions.add { confirmDelete(item) }
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(labels.toTypedArray()) { _, i -> actions.getOrNull(i)?.invoke() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    /**
     * v24 — KASADAN ÇIKART. Dosya şifresi çözülüp **orijinal klasörüne**
     * yazılır; orijinal yol yoksa/erişilemezse türüne göre uygun yere:
     * resim → Pictures/UmayKasa, video → Movies/UmayKasa, diğer → Download/UmayKasa.
     * Kopya yazıldıktan sonra kasa kaydı silinir (orijinal artık telefondadır).
     */
    private fun extractItem(item: SecureVault.Item) {
        val pin = pinCached ?: return showUnlock()
        thumbs.execute {
            val ext = item.ext.ifBlank { "bin" }
            val tmp = File(cacheDir, "extract_${item.id}.$ext")
            val ok = SecureVault.decryptTo(this, item.id, pin, tmp)
            if (!ok || !tmp.exists() || tmp.length() == 0L) {
                runOnUiThread {
                    Toast.makeText(this, "Dosya çözülemedi — PIN veya kayıt hatası", Toast.LENGTH_LONG).show()
                }
                return@execute
            }
            val dir = targetDirFor(item)
            var name = item.title.ifBlank { "kasa_${item.id}.$ext" }
            if (!name.contains('.')) name = "$name.$ext"
            var dst = File(dir, name)
            var n = 1
            while (dst.exists()) {
                val base = name.substringBeforeLast('.')
                val e = name.substringAfterLast('.')
                dst = File(dir, "${base}_($n).$e")
                n++
            }
            val written = try {
                dir.mkdirs()
                tmp.copyTo(dst, overwrite = false)
                true
            } catch (t: Throwable) {
                BookStore.logError(this, "vault.extract", t)
                false
            }
            try { tmp.delete() } catch (_: Throwable) {}
            if (!written) {
                runOnUiThread {
                    Toast.makeText(this, "Yazma başarısız: ${dir.absolutePath}", Toast.LENGTH_LONG).show()
                }
                return@execute
            }
            try {
                android.media.MediaScannerConnection.scanFile(
                    this, arrayOf(dst.absolutePath), null, null
                )
            } catch (_: Throwable) {}
            SecureVault.remove(this, item.id)
            runOnUiThread {
                Toast.makeText(
                    this, "Kasadan çıkarıldı → ${dst.absolutePath}", Toast.LENGTH_LONG
                ).show()
                renderShelves()
            }
        }
    }

    /** Orijinal klasör → yoksa türe göre standart klasör. */
    private fun targetDirFor(item: SecureVault.Item): File {
        val original = item.originalPath
        if (!original.isNullOrBlank()) {
            val parent = File(original).parentFile
            if (parent != null && parent.exists() && parent.canWrite()) return parent
        }
        val root = android.os.Environment.getExternalStorageDirectory()
        val sub = when (item.kind) {
            "image" -> android.os.Environment.DIRECTORY_PICTURES
            "video" -> android.os.Environment.DIRECTORY_MOVIES
            else -> android.os.Environment.DIRECTORY_DOWNLOADS
        }
        val preferred = File(File(root, sub), "UmayKasa")
        return if (preferred.exists() || preferred.mkdirs() || preferred.canWrite()) preferred
        else File(getExternalFilesDir(null) ?: filesDir, "UmayKasa")
    }

    private fun openItem(item: SecureVault.Item) {
        val pin = pinCached ?: return showUnlock()
        val file = SecureVault.decryptFileTo(this, item, pin)
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Açılamadı — dosya veya PIN hatası", Toast.LENGTH_LONG).show()
            return
        }
        if (item.kind == "image") {
            val bmp = try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Throwable) { null }
            if (bmp == null) {
                Toast.makeText(this, "Resim çözülemedi", Toast.LENGTH_SHORT).show()
                return
            }
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                setPadding(12, 12, 12, 12)
            }
            AlertDialog.Builder(this)
                .setTitle(item.title)
                .setView(iv)
                .setPositiveButton("Kapat", null)
                .show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = when (item.kind) {
                "video" -> "video/*"
                "doc" -> when (item.ext.lowercase()) {
                    "pdf" -> "application/pdf"
                    "doc" -> "application/msword"
                    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    "ppt" -> "application/vnd.ms-powerpoint"
                    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                    "txt", "md" -> "text/plain"
                    "epub" -> "application/epub+zip"
                    else -> "*/*"
                }
                else -> "*/*"
            }
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (t: Throwable) {
            BookStore.logError(this, "vault.open", t)
            Toast.makeText(
                this, "Bu dosyayı açabilecek uygulama bulunamadı", Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun restoreBook(item: SecureVault.Item) {
        val pin = pinCached ?: return showUnlock()
        val dst = File(cacheDir, "vault_restore_${item.id}.pdf")
        thumbs.execute {
            val ok = SecureVault.decryptTo(this, item.id, pin, dst)
            var imported = false
            if (ok) {
                val latch = java.util.concurrent.CountDownLatch(1)
                BookStore.importPdf(this, Uri.fromFile(dst)) { res ->
                    imported = res.isSuccess
                    latch.countDown()
                }
                latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
            }
            runOnUiThread {
                if (ok && imported) {
                    SecureVault.remove(this, item.id)
                    Toast.makeText(this, "Kitap kütüphaneye geri alındı ✓", Toast.LENGTH_LONG).show()
                    renderShelves()
                } else {
                    Toast.makeText(this, "Geri alınamadı — PIN veya dosya hatası", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmDelete(item: SecureVault.Item) {
        AlertDialog.Builder(this)
            .setTitle("Kalıcı sil")
            .setMessage(
                "Şifreli kopya silinecek. Bu işlem geri alınamaz. Emin misin?"
            )
            .setPositiveButton("Sil") { _, _ ->
                SecureVault.remove(this, item.id)
                if (item.kind == "book" && item.bookId != null) {
                    try { BookStore.deleteBook(this, item.bookId) } catch (_: Throwable) {}
                }
                renderShelves()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ── Cihaz testi tohumlama (yalnız açıkça verilen yollarla) ──

    /**
     * `adb shell am start ... --esal seed_paths <yol1> <yol2>` ile kasa
     * raflarının gerçek dosyalarla dolması sağlanır. Kullanıcı akışını
     * atlamaz: PIN/biyometrik kapısı yine geçilmelidir.
     */
    private fun handleSeedIntent() {
        // Yayın (release) sürümünde tohumlama yolu tamamen KAPALI.
        if (!BuildConfig.DEBUG) return
        // adb `--esa` String[] / ArrayList / tek metin (ayraç: |) olabilir.
        val paths: List<String>? =
            intent?.getStringArrayExtra(EXTRA_SEED_PATHS)?.toList()
                ?: intent?.getStringArrayListExtra(EXTRA_SEED_PATHS)
                ?: intent?.getStringExtra(EXTRA_SEED_PATHS)
                    ?.split('|')?.map { it.trim() }?.filter { it.isNotBlank() }
        android.util.Log.i("UmayVault", "seed paths=$paths")
        if (paths == null) return
        if (paths.isEmpty() || seedDone) return
        seedDone = true
        val pin = pinCached ?: return
        thumbs.execute {
            var added = 0
            paths.forEach { p ->
                try {
                    val f = File(p)
                    android.util.Log.i("UmayVault", "seed file=$p exists=${f.exists()} len=${f.length()}")
                    if (!f.exists() || f.isDirectory) return@forEach
                    val ext = f.extension.lowercase().ifBlank { "bin" }
                    val kind = kindOf(f.name, Uri.fromFile(f))
                    // Orijinali SİLMEYEN kopya: önce cache'e kopyala
                    val tmp = File(cacheDir, "seed_${System.currentTimeMillis()}_$ext")
                    f.copyTo(tmp, overwrite = true)
                    val item = SecureVault.addFileToVault(
                        this, pin, tmp, f.name, kind, ext, f.absolutePath
                    )
                    if (item != null) added++
                } catch (t: Throwable) {
                    BookStore.logError(this, "vault.seed", t)
                }
            }
            runOnUiThread {
                Toast.makeText(
                    this, "$added örnek dosya kasaya eklendi", Toast.LENGTH_LONG
                ).show()
                renderShelves()
            }
        }
    }

    private var seedDone = false

    companion object {
        const val DEFAULT_PIN = "1234"
        const val EXTRA_SEED_PATHS = "seed_paths"
        const val EXTRA_SEED_PIN = "seed_pin"
    }
}
