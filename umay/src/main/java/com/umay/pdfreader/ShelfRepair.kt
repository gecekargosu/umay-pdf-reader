package com.umay.pdfreader

import android.content.Context

/**
 * v39 — RAF / KATEGORİ ONARIMI (kalıcı).
 *
 * Yaşanan sorun: "belgeleri sıfırdan yükle" işlemi tüm kitapları silerken
 * `ShelfManager.onBookDeleted` her kitabı raflardan düşürdü. Yeniden yüklenen
 * kitaplar YENİ kimlik aldığı için raflar boş kaldı (ana ekranda raf başlıkları
 * duruyor, içleri boş görünüyordu).
 *
 * Onarım üç adımda çalışır ve **veri silmez**:
 *   1. Raflardaki silinmiş kitap kimliklerini (asılı kayıtları) temizler.
 *   2. Ders bağlarını kitap adındaki ders koduyla yeniden kurar
 *      (`CourseStore.autoLinkByTitle` idempotent'tir, çoğaltmaz).
 *   3. YALNIZCA **boş kalmış** rafları doldurur:
 *      • Ders Kitapları      → docx/doc/txt/pdf vb. (slayt ve tarama dışı)
 *      • Ders Slaytları      → pptx/ppt veya "slayt/sunum" içeren dosyalar
 *      • Cihaz Taraması      → "taran/scan/foto" içeren dosyalar
 *      • Okuma Kitapları     → Kur'an/meal
 *      • Ders rafları (BLG203 …) → o dersin bağlı belgeleri
 *
 * Kullanıcı bir kitabı raftan elle çıkardıysa raf BOŞ değildir; bu yüzden
 * onarım kullanıcı düzenine dokunmaz. Idempotent: her açılışta aynı sonucu verir.
 */
object ShelfRepair {

    /** Eski çağrı adı (DocReload vb.) — aynı işi yapar. */
    fun run(ctx: Context): Boolean = reconcile(ctx)

    /** Uygulama açılışında arka planda çalışır (ucuz: ~100 kitap / 10 raf). */
    fun runOnStart(ctx: Context) {
        try {
            Thread {
                try {
                    reconcile(ctx)
                } catch (_: Throwable) {
                }
            }.apply {
                name = "umay-shelf-repair"
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        } catch (_: Throwable) {
        }
    }

    fun reconcile(ctx: Context): Boolean {
        return try {
            val books = BookStore.loadBooks(ctx)
            if (books.isEmpty()) return false
            val alive = books.map { it.id }.toSet()

            if (ShelfManager.shelvesOf(ctx).isEmpty()) ShelfManager.ensureBuiltIns(ctx)

            // 1) Asılı kimlikleri temizle
            val shelves = ShelfManager.shelvesOf(ctx)
            var changed = false
            shelves.forEach { sh ->
                val before = sh.bookIds.size
                sh.bookIds.retainAll { it in alive }
                if (sh.bookIds.size != before) changed = true
            }

            // 2) Ders bağlarını yeniden kur (yeniden yükleme sonrası kimlikler değişti)
            books.forEach { b ->
                try {
                    CourseStore.autoLinkByTitle(ctx, b)
                } catch (_: Throwable) {
                }
            }

            // 3) Ders belgelerinin kategorisi uzantıya göre düzeltilir
            val courses = CourseStore.courses(ctx)
            val docsByCourse = HashMap<String, List<String>>()   // courseId → bookId
            courses.forEach { c ->
                val docs = try { CourseStore.docsOf(ctx, c.id) } catch (_: Throwable) { emptyList() }
                val ids = ArrayList<String>(docs.size)
                docs.forEach { d ->
                    val title = d.book?.title ?: ""
                    val cat = categoryOf(title)
                    if (d.doc.category != cat) {
                        try { CourseStore.setDocCategory(ctx, d.doc.id, cat) } catch (_: Throwable) {}
                    }
                    d.book?.let { ids.add(it.id) }
                }
                docsByCourse[c.id] = ids
            }

            // 4) Boş rafları doldur
            val shelfByCourse = HashMap<String, String>()  // courseId → shelfId
            courses.forEach { c ->
                val code = ShelfManager.courseCodeIn(c.name) ?: return@forEach
                val id = try { ShelfManager.ensureCourseShelf(ctx, code, c.name) } catch (_: Throwable) { null }
                if (id != null) shelfByCourse[c.id] = id
            }

            val fresh = ShelfManager.shelvesOf(ctx)
            fresh.forEach { sh ->
                // Ders rafı = o dersin belgeleri. Eksik olanlar EKLENİR
                // (kullanıcının çıkardıkları silinmez — yalnız ekleme yapılır).
                val courseId = courseForShelf(sh, courses)
                // v5.21 — KULLANICI ŞİKAYETİ: "sildiğim kitaplar geri geliyor".
                // Eski kod, ders rafında eksik her belgeyi ve boş kalan her
                // yerleşik rafı HER AÇILIŞTA yeniden dolduruyordu. Artık onarım
                // yalnızca asılı (silinmiş kitap) kimlikleri temizler; kullanıcı
                // bir kitabı raftan çıkardıysa orada kalmaz.
                if (courseId != null) return@forEach
                if (sh.bookIds.isNotEmpty()) return@forEach
            }

            if (changed) ShelfManager.save(ctx, fresh)
            true
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ShelfRepair.reconcile", t)
            false
        }
    }

    /** Raf adı/kimliği bir derse karşılık geliyor mu? */
    private fun courseForShelf(sh: ShelfManager.Shelf, courses: List<Course>): String? {
        val code = ShelfManager.courseCodeIn(sh.name)
            ?: sh.id.removePrefix("shelf_").uppercase().takeIf { it.length in 3..6 }
        if (code == null) return null
        return courses.firstOrNull {
            it.name.replace(" ", "").startsWith(code, ignoreCase = true) ||
                ShelfManager.courseCodeIn(it.name)?.equals(code, true) == true
        }?.id
    }

    /**
     * Dosya adından kategori.
     *   slide → ders slaytları · doc → ödev/proje/yönerge · scan → tarama
     *   book  → ders kitabı
     */
    fun categoryOf(title: String): String {
        val t = TextUtil.normalize(title)
        val ext = DocumentConverter.extension(title)
        // Uzantı en güvenilir sinyaldir (orijinal dosya adı korunuyor).
        if (ext == "pptx" || ext == "ppt" || ext == "odp") return "slide"
        return when {
            t.contains("taran") || t.contains("scan") || t.contains("fotogra") -> "scan"
            t.contains("slayt") || t.contains("slide") || t.contains("sunum") -> "slide"
            ext == "docx" || ext == "doc" || ext == "odt" -> "doc"
            t.contains("deney") || t.contains("foy") || t.contains("yonerge") ||
                t.contains("odev") || t.contains("proje") || t.contains("rapor") ||
                t.contains("sinav") || t.contains("vize") || t.contains("final") -> "doc"
            else -> "book"
        }
    }

    private fun isReadingBook(title: String): Boolean {
        val t = TextUtil.normalize(title)
        return t.contains("kuran") || t.contains("meal") || t.contains("kerim")
    }
}
