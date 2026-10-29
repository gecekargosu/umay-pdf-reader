package com.umay.pdfreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * v20 (ADIM 2) — Dinamik raf mimarisi + shelf_bg_color + taşıma.
 *
 * ONARIM NOTU (v20):
 *  • Açık yönetim API'si: addShelf / renameShelf / deleteShelf /
 *    addBookToShelf / removeBookFromShelf / moveBookToShelf — hepsi
 *    SharedPreferences ("umay_shelves") üzerine KALICI yazar.
 *  • Sessizce yutulan (silent-fail) hatalar artık BookStore.logError
 *    üzerinden doğrudan Documents/UmayPDF/umay_error.log'a düşer.
 *  • ensureDefaults(): ilk açılışta 3 yerleşik rafı kalıcı olarak kaydeder.
 */
object ShelfManager {

    data class Shelf(
        val id: String,
        val name: String,
        val bookIds: MutableList<String>,
        val bgColor: String = "#3B4A63"
    )

    private const val PREFS = "umay_shelves"
    private const val KEY_AUTOSLOT = "shelf_auto_slot"

    /** Uygulamanın 3 yerleşik rafı — ana ekranda sabit blok olarak durur,
     *  dinamik (kullanıcı tanımlı) raf listesine KATILMAZ. */
    val AUTO_SHELF_IDS = setOf(
        "shelf_ders_kitaplari",
        "shelf_ders_slayt_notlar",
        "shelf_cihang_taramalari"
    )

    /** v21 (DENETİM BUG-004): Cihaz Tarama ekranından kütüphaneye eklenen
     *  dosyaların VARSAYILAN hedef rafı — "Ders Kitapları" ASLA otomatik
     *  kullanılmaz; kullanıcı isterse uzun basma → "Raflara taşı" ile
     *  başka rafa alır. */
    const val SHELF_DEVICE_DOCS = "shelf_cihang_taramalari"

    /**
     * v24 — RAF SIRALAMASI. Ana sayfadaki sıralama çipi (Alfabetik /
     * En son eklenen) **her rafa** uygulanır: favoriler, özel raflar ve
     * ders içi raflar da aynı sırayı kullanır (kullanıcı isteği).
     */
    fun sortForShelf(ctx: Context, list: List<Book>): List<Book> =
        when (AppPrefs.getString(ctx, AppPrefs.KEY_SORT, "added")) {
            "alpha" -> {
                val coll = java.text.Collator.getInstance(java.util.Locale("tr", "TR"))
                list.sortedWith(Comparator { a, b -> coll.compare(a.title, b.title) })
            }
            else -> list.sortedByDescending { it.createdAt }
        }

    // ── Teşhis köprüsü (ADIM 1) ──────────────────────────────
    // Eski kodda her fonksiyon catch (_: Throwable) {} ile hatayı
    // sessizce yutuyordu → "loglar yeşil, UI bozuk" çelişkisi.
    // Artık her hata umay_error.log'a yazılır.
    private fun log(ctx: Context?, where: String, t: Throwable) {
        try {
            if (ctx != null) BookStore.logError(ctx, "ShelfManager.$where", t)
        } catch (_: Throwable) {
        }
    }

    // ── Depolama ─────────────────────────────────────────────

    fun shelvesOf(context: android.content.Context?): MutableList<Shelf> {
        val out = mutableListOf<Shelf>()
        if (context == null) return out   // v17: Context yoksa boş liste (varsayılan raf)
        try {
            val raw = context.getSharedPreferences(PREFS, 0).getString("shelves", null)
            if (raw.isNullOrBlank()) return out
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ids = mutableListOf<String>()
                val ba = o.optJSONArray("bookIds") ?: JSONArray()
                for (j in 0 until ba.length()) ids.add(ba.getString(j))
                val bg = o.optString("bgColor", "#3B4A63")
                out.add(Shelf(o.getString("id"), o.getString("name"), ids, bg))
            }
        } catch (t: Throwable) {
            // Bozuk veri → raflar sıfırlanır (kitaplık etkilenmez); ama HATA LOGlanır
            log(context, "shelvesOf", t)
        }
        return out
    }

    /** Rafları kalıcı olarak kaydeder (SharedPreferences "umay_shelves"). */
    fun save(context: android.content.Context, shelves: List<Shelf>) {
        try {
            val arr = JSONArray()
            shelves.forEach { s ->
                val o = JSONObject()
                o.put("id", s.id)
                o.put("name", s.name)
                o.put("bgColor", s.bgColor)
                val ba = JSONArray()
                s.bookIds.forEach { ba.put(it) }
                o.put("bookIds", ba)
                arr.put(o)
            }
            context.getSharedPreferences(PREFS, 0).edit()
                .putString("shelves", arr.toString()).apply()
        } catch (t: Throwable) {
            log(context, "save", t)
        }
    }

    /**
     * v20 (ADIM 2): İlk açılışta 3 yerleşik rafı KALICI olarak kaydeder.
     * Eski kodda varsayılan raflar yalnız bellekteydi (shelvesOf(null) →
     * hep boş) → kitap rafta kaldığı halde raf verisi hiç saklanmıyordu.
     */
    fun ensureDefaults(context: android.content.Context) {
        ensureBuiltIns(context)
    }

    /**
     * v5.5 (ONARIM 8) — Yerleşik rafları KALICI listeye eksikse ekler.
     *
     * Kök neden (canlı cihaz verisiyle doğrulandı): SharedPreferences
     * "umay_shelves" içinde yalnız kullanıcı rafları vardı; yerleşik üç raf
     * (Ders Kitapları / Slaytlar / Cihaz Taramaları) yalnız ana ekranda
     * sabit blok olarak çizildiği için "fark edilmiyordu". Sonuç: uzun basma
     * → "Raflara taşı" listesinde yerleşik raflar HİÇ çıkmıyor, kitap geri
     * taşınamıyordu. ensureDefaults ise liste BOŞ değilse hiçbir şey
     * yapmıyordu (eski kod) → eksik kalıyordu.
     */
    fun ensureBuiltIns(context: android.content.Context) {
        try {
            val shelves = shelvesOf(context)
            val have = shelves.map { it.id }.toSet()
            val missing = defaultShelfLayout().filter {
                it.id in AUTO_SHELF_IDS && it.id !in have
            }
            if (missing.isEmpty()) return
            // Yerleşik raflar başta, kullanıcı rafları sonda (sıra korunur).
            val merged = ArrayList<Shelf>(missing.size + shelves.size)
            merged.addAll(missing)
            merged.addAll(shelves)
            save(context, merged)
        } catch (t: Throwable) {
            log(context, "ensureBuiltIns", t)
        }
    }

    // ── RAF YÖNETİMİ — açık API (ADIM 2) ─────────────────────

    /** Yeni raf ekler; raf kimliğini döndürür (kalıcı). */
    fun addShelf(
        context: android.content.Context,
        name: String,
        bgColor: String = "#3B4A63"
    ): String {
        val id = "shelf_${System.currentTimeMillis()}"
        val shelves = shelvesOf(context)
        shelves.add(Shelf(id, name, mutableListOf(), bgColor))
        save(context, shelves)
        return id
    }

    /** Rafı yeniden adlandırır (kalıcı). */
    fun renameShelf(context: android.content.Context, shelfId: String, newName: String): Boolean {
        return try {
            val shelves = shelvesOf(context)
            val idx = shelves.indexOfFirst { it.id == shelfId }
            if (idx < 0) return false
            shelves[idx] = shelves[idx].copy(name = newName)
            save(context, shelves)
            true
        } catch (t: Throwable) {
            log(context, "renameShelf", t)
            false
        }
    }

    /** Rafı siler — raftaki kitaplar SILINMEZ, yalnız raf kalkar (kalıcı). */
    fun deleteShelf(context: android.content.Context, shelfId: String): Boolean {
        return try {
            if (shelfId in AUTO_SHELF_IDS) return false   // yerleşik raflar silinemez
            val shelves = shelvesOf(context)
            val before = shelves.size
            shelves.removeAll { it.id == shelfId }
            if (shelves.size == before) return false
            save(context, shelves)
            true
        } catch (t: Throwable) {
            log(context, "deleteShelf", t)
            false
        }
    }

    /** Raf adını güncüler (kullanıcı tarafından yeniden adlandır). */
    fun editShelfName(context: android.content.Context, shelfId: String, newName: String) {
        try {
            val shelves = shelvesOf(context)
            val idx = shelves.indexOfFirst { it.id == shelfId }
            if (idx >= 0) {
                shelves[idx] = shelves[idx].copy(name = newName)
                save(context, shelves)
            }
        } catch (t: Throwable) {
            log(context, "editShelfName", t)
        }
    }

    /** Raf arka plan rengini günceller (shelf_bg_color) — kalıcı. */
    fun editShelfBgColor(context: android.content.Context, shelfId: String, color: String) {
        try {
            val shelves = shelvesOf(context)
            val idx = shelves.indexOfFirst { it.id == shelfId }
            if (idx >= 0) {
                shelves[idx] = shelves[idx].copy(bgColor = color)
                save(context, shelves)
            }
        } catch (t: Throwable) {
            log(context, "editShelfBgColor", t)
        }
    }

    /**
     * v5.4 — Ders kodu adına kalıcı raf ("BLG205 …") oluşturur, varsa döndürür.
     * Ders tohumlaması bu rafları kurar; kullanıcı sonradan yeniden adlandırıp
     * renk verebilir. Aynı raf iki kez açılmaz (idempotent).
     */
    fun ensureCourseShelf(
        context: android.content.Context,
        code: String,
        name: String
    ): String {
        val shelves = shelvesOf(context)
        shelves.firstOrNull {
            it.name.equals(name, true) || it.name.startsWith("$code ", true)
        }?.let { return it.id }
        val id = "shelf_${code.lowercase()}"
        shelves.add(Shelf(id, name, mutableListOf(), "#3B4A63"))
        save(context, shelves)
        return id
    }

    /** Kitabın içinde bulunduğu raf adlarını döndürür. */
    fun shelfNamesOf(context: android.content.Context, bookId: String): List<String> =
        shelvesOf(context).filter { bookId in it.bookIds }.map { it.name }

    // ── T8: Otomatik ders kodu etiketleme ────────────────────

    /** "BLG205_DersNotu.pdf" → "BLG205" (Türkçe büyük harf dahil). */
    fun courseCodeIn(title: String): String? {
        val m = Regex("[A-Za-zÇĞİÖŞÜ]{2,5}\\s?-?\\d{3}", RegexOption.IGNORE_CASE)
            .find(title) ?: return null
        return m.value.uppercase().replace(" ", "").replace("--", "-")
    }

    /**
     * Kitap adında ders kodu varsa o kod adlı rafa otomatik ekle
     * (raf yoksa oluştur).
     */
    fun autoTagCourseCode(context: android.content.Context, bookId: String, title: String) {
        try {
            val code = courseCodeIn(title) ?: return
            val shelves = shelvesOf(context)
            var shelf = shelves.firstOrNull {
                it.name.equals(code, true) || it.name.startsWith("$code ")
            }
            if (shelf == null) {
                shelf = Shelf("shelf_${code.lowercase()}", code, mutableListOf())
                shelves.add(shelf)
            }
            if (bookId !in shelf.bookIds) {
                shelf.bookIds.add(bookId)
                save(context, shelves)
            }
        } catch (t: Throwable) {
            log(context, "autoTagCourseCode", t)
        }
    }

    // ── 3 RAFS (ADIM 2) ───────────────────────────────────────

    /** 3 ana rafın varsayılan tanımları (saf liste — kalıcı kayıt için
     *  ensureDefaults(context) çağrılır). */
    /** v5.4 — Kur'an-ı Kerim, roman, kamu malı kitaplar için ayrı raf. */
    const val SHELF_READING = "shelf_okuma_kitaplari"

    fun defaultShelfLayout(): List<Shelf> = listOf(
        Shelf("shelf_ders_kitaplari", "📚 DERS KİTAPLARI", mutableListOf(), "#3B4A63"),
        Shelf("shelf_ders_slayt_notlar", "📑 DERS SLAYT LARI & NOTLAR", mutableListOf(), "#44556B"),
        Shelf(SHELF_READING, "📖 OKUMA KİTAPLARI", mutableListOf(), "#5B4636"),
        Shelf("shelf_cihang_taramalari", "🖥 CİHAZ TARAMA & DOKÜMANLAR", mutableListOf(), "#6B7A5A")
    )

    /** Rafı bul, yoksa varsayılan 3 raf listesini döndür. */
    fun getShelf(id: String?): List<Shelf> {
        if (id.isNullOrBlank()) return defaultShelfLayout()
        return shelvesOf(null).firstOrNull { it.id == id }?.let { listOf(it) }
            ?: defaultShelfLayout()
    }

    // ── KİTAP ↔ RAF (ADIM 2: kalıcı + loglu) ────────────────

    /** Kitap belirli bir rafa ekle (raf yoksa oluşturur; kalıcı). */
    fun addBookToShelf(context: android.content.Context, bookId: String, shelfId: String) {
        try {
            val shelves = shelvesOf(context)
            var target = shelves.firstOrNull { it.id == shelfId }
            if (target == null) {
                target = Shelf(shelfId, shelfId.replace("_", " ").uppercase(), mutableListOf(), "#3B4A63")
                shelves.add(target)
            }
            if (bookId !in target.bookIds) {
                target.bookIds.add(bookId)
                save(context, shelves)
            }
        } catch (t: Throwable) {
            log(context, "addBookToShelf", t)
        }
    }

    /** Kitap belirli raftan çıkar (kalıcı). */
    fun removeBookFromShelf(context: android.content.Context, bookId: String, shelfId: String) {
        try {
            val shelves = shelvesOf(context)
            val target = shelves.firstOrNull { it.id == shelfId }
            if (target != null) {
                target.bookIds.removeAll { it == bookId }
                save(context, shelves)
            }
        } catch (t: Throwable) {
            log(context, "removeBookFromShelf", t)
        }
    }

    /** Kitabı TÜM özel raflardan çıkar (tek işlem; kalıcı). */
    fun removeBookFromAllShelves(context: android.content.Context, bookId: String) {
        try {
            val shelves = shelvesOf(context)
            var changed = false
            shelves.forEach { shelf ->
                if (shelf.bookIds.removeAll { it == bookId }) changed = true
            }
            if (changed) save(context, shelves)
        } catch (t: Throwable) {
            log(context, "removeBookFromAllShelves", t)
        }
    }

    /** Kitap silindiğinde raf listelerini temizle (asılı kalmasın). */
    fun onBookDeleted(context: android.content.Context, bookId: String) {
        try {
            val shelves = shelvesOf(context)
            for (shelf in shelves) {
                shelf.bookIds.removeAll { it == bookId }
            }
            save(context, shelves)
        } catch (t: Throwable) {
            log(context, "onBookDeleted", t)
        }
    }

    /** Kitabın hangi rafta olduğunu döndürür. */
    fun shelfOf(context: android.content.Context, bookId: String): String =
        shelvesOf(context).firstOrNull { bookId in it.bookIds }?.name ?: "▪ KALİTE KİTAPI"

    // ── OTOMATİK KONUMLANDIRMA ────────────────────────────────

    /** Varsayılan otomatik slot: en alt "Cihaz Taramaları" rafı. */
    fun autoSlot(context: android.content.Context): String {
        return try {
            context.getSharedPreferences(PREFS, 0).getString(KEY_AUTOSLOT, "shelf_cihang_taramalari")
                ?: "shelf_cihang_taramalari"
        } catch (t: Throwable) {
            log(context, "autoSlot", t)
            "shelf_cihang_taramalari"
        }
    }

    fun setAutoSlot(context: android.content.Context, shelfId: String) {
        try {
            context.getSharedPreferences(PREFS, 0).edit()
                .putString(KEY_AUTOSLOT, shelfId).apply()
        } catch (t: Throwable) {
            log(context, "setAutoSlot", t)
        }
    }

    /** Kitabın otomatik slot rafı. */
    fun autoSlotForBook(context: android.content.Context, bookId: String): String {
        return try {
            val shelves = shelvesOf(context)
            val target = shelves.firstOrNull { bookId in it.bookIds }
            target?.id ?: autoSlot(context)
        } catch (t: Throwable) {
            log(context, "autoSlotForBook", t)
            autoSlot(context)
        }
    }

    // ── KİTAP TAŞIMA ───────────────────────────────────────────

    /** Kitabı hedefe taşı: hedefe EKLE, kaynak raftan ÇIKAR (tek yazma; kalıcı). */
    fun moveBookToShelf(
        context: android.content.Context,
        bookId: String,
        toShelfId: String
    ) {
        try {
            val shelves = shelvesOf(context)
            var changed = false
            shelves.forEach { shelf ->
                if (shelf.id != toShelfId && shelf.bookIds.removeAll { it == bookId }) changed = true
            }
            var to = shelves.firstOrNull { it.id == toShelfId }
            if (to == null) {
                to = Shelf(toShelfId, toShelfId.replace("_", " ").uppercase(), mutableListOf(), "#3B4A63")
                shelves.add(to)
            }
            if (bookId !in to.bookIds) {
                to.bookIds.add(bookId)
                changed = true
            }
            if (changed) save(context, shelves)
        } catch (t: Throwable) {
            log(context, "moveBookToShelf", t)
        }
    }

    /** Rakip rafa taşı (eski imza — korundu). */
    fun moveBookToShelf(
        context: android.content.Context,
        bookId: String,
        fromShelfId: String,
        toShelfId: String
    ) {
        try {
            val shelves = shelvesOf(context)
            val from = shelves.firstOrNull { it.id == fromShelfId }
            if (from != null) from.bookIds.removeAll { it == bookId }
            var to = shelves.firstOrNull { it.id == toShelfId }
            if (to == null) {
                to = Shelf(toShelfId, toShelfId.replace("_", " ").uppercase(), mutableListOf(), "#3B4A63")
                shelves.add(to)
            }
            if (bookId !in to.bookIds) to.bookIds.add(bookId)
            save(context, shelves)
        } catch (t: Throwable) {
            log(context, "moveBookToShelf4", t)
        }
    }

    // ── RAF YÖNETİM DİYALOGU (BASINA) ─────────────────────────

    /** Raf yönetimi: ekle / yönet / adlandır / sil + renk ayarı. */
    fun showManageDialog(activity: Activity, onDone: () -> Unit) {
        fun refresh() {
            onDone()
            showManageDialog(activity, onDone)
        }
        // v20: varsayılan raflar KALICI olarak garanti altına alınır
        ensureDefaults(activity)
        val shelves = shelvesOf(activity)
        if (shelves.isEmpty()) shelves.addAll(defaultShelfLayout())
        val labels = mutableListOf("＋ Yeni Raf Ekle")
        shelves.forEach { labels.add("${it.name}  (${it.bookIds.size} kitap)  [${it.bgColor}] ") }

        AlertDialog.Builder(activity)
            .setTitle("Raflarım")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> promptName(activity, "Yeni Raf", "Raf adı (örn. Ders Hazırlık)") { name ->
                        if (name.isBlank()) return@promptName
                        // v20 (ADIM 2): açık API + kalıcı kayıt
                        addShelf(activity, name)
                        Toast.makeText(activity, "\"$name\" rafı eklendi", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                    else -> showShelfActions(activity, shelves[which - 1], onDone)
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showShelfActions(activity: Activity, shelf: Shelf, onDone: () -> Unit) {
        val acts = arrayOf(
            "Kitapları yönet",
            "📚 Bu rafa taşı…",       // v20.1: tek dokunuşla kitap getir
            "Yeniden adlandır",
            "Renk değiştir",
            "Raftan sil"
        )
        AlertDialog.Builder(activity)
            .setTitle(shelf.name)
            .setItems(acts) { _, i ->
                when (i) {
                    0 -> showBookPicker(activity, shelf, onDone)
                    1 -> showMovePicker(activity, shelf, onDone)
                    2 -> promptName(activity, "Rafı yeniden adlandır", shelf.name) { name ->
                        if (name.isBlank()) return@promptName
                        // v20 (ADIM 2): açık API
                        renameShelf(activity, shelf.id, name)
                        onDone()
                        showManageDialog(activity, onDone)
                    }
                    3 -> promptColor(activity, shelf) { color ->
                        if (color.isBlank()) return@promptColor
                        editShelfBgColor(activity, shelf.id, color)
                        Toast.makeText(activity, "Renk değiştirildi", Toast.LENGTH_SHORT).show()
                        onDone()
                        showManageDialog(activity, onDone)
                    }
                    4 -> AlertDialog.Builder(activity)
                        .setTitle("Raftan sil")
                        .setMessage("\"${shelf.name}\" silinsin mi? (Kitaplar silinmez)")
                        .setPositiveButton("Sil") { _, _ ->
                            // v20 (ADIM 2): açık API + yerleşik raf koruması
                            if (shelf.id in AUTO_SHELF_IDS) {
                                Toast.makeText(
                                    activity,
                                    "Yerleşik raf silinemez — yalnız yeniden adlandırılabilir",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                deleteShelf(activity, shelf.id)
                                Toast.makeText(activity, "\"${shelf.name}\" silindi", Toast.LENGTH_SHORT).show()
                            }
                            onDone()
                            showManageDialog(activity, onDone)
                        }
                        .setNegativeButton("Vazgeç", null)
                        .show()
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    /**
     * v20.1: Tek kitap seç → bu rafa TAŞI (diğer tüm raflardan çıkar;
     * kalıcı; onDone → ana ekran anında tazelenir).
     */
    private fun showMovePicker(activity: Activity, shelf: Shelf, onDone: () -> Unit) {
        val books = try { BookStore.loadBooks(activity) } catch (_: Throwable) { emptyList() }
        if (books.isEmpty()) {
            Toast.makeText(activity, "Kütüphane boş — önce PDF ekle", Toast.LENGTH_SHORT).show()
            return
        }
        var chosen = -1
        AlertDialog.Builder(activity)
            .setTitle("\"${shelf.name}\" rafına taşı — hangi kitap?")
            .setSingleChoiceItems(books.map { it.title }.toTypedArray(), -1) { _, which ->
                chosen = which
            }
            .setPositiveButton("Taşı") { _, _ ->
                if (chosen in books.indices) {
                    moveBookToShelf(activity, books[chosen].id, shelf.id)
                    Toast.makeText(
                        activity,
                        "\"${books[chosen].title}\" → ${shelf.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                onDone()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun showBookPicker(activity: Activity, shelf: Shelf, onDone: () -> Unit) {
        val books = try { BookStore.loadBooks(activity) } catch (_: Throwable) { emptyList() }
        if (books.isEmpty()) {
            Toast.makeText(activity, "Kütüphane boş — önce PDF ekle", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = books.map { it.title }.toTypedArray()
        val checked = BooleanArray(books.size) { books[it].id in shelf.bookIds }
        AlertDialog.Builder(activity)
            .setTitle("\"${shelf.name}\" — hangi kitaplar?")
            .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Kaydet") { _, _ ->
                // v20 (ADIM 2): seçim KALICI olarak yazılır
                val shelves = shelvesOf(activity)
                val target = shelves.firstOrNull { it.id == shelf.id }
                if (target != null) {
                    target.bookIds.clear()
                    books.forEachIndexed { i, b -> if (checked[i]) target.bookIds.add(b.id) }
                    save(activity, shelves)
                }
                onDone()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun promptName(activity: Activity, title: String, hint: String, onOk: (String) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setHint(hint)
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Tamam") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) onOk(name)
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun promptColor(activity: Activity, shelf: Shelf, onOk: (String) -> Unit) {
        val colors = arrayOf("#3B4A63", "#44556B", "#6B7A5A", "#7A5A4A", "#5A4A7A", "#6B5A3A", "#3A6B6B")
        AlertDialog.Builder(activity)
            .setTitle("Raf arka planı rengi")
            .setItems(colors) { _, which ->
                onOk(colors[which])
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }
}
