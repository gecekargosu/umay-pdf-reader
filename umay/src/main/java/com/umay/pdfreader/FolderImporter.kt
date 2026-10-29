package com.umay.pdfreader

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v21.3 — AKILLI KLASÖR AKTARICI / HAVUZ DÜZENLEYİCİ.
 *
 * Amaç yalnız "ders" değil: kullanıcı iş hayatında da aynı sistemi kullanır
 * (Tapu, Kira Sözleşmesi, kişi adları...). Kural:
 *
 *   1. Dosya adı/klasör yolu mevcut bir gruba (ders) uyuyorsa oraya gider.
 *   2. Uymuyorsa üst klasör adı yeni bir GRUP olarak açılır (ör. "Tapu"),
 *      dosya o grubun rafına eklenir. Yeni ders/raf eklendiğinde kod
 *      kendini günceller: her çalıştırmada gruplar DB'den yeniden okunur.
 *   3. Dosya adından anlaşılamıyorsa İÇERİĞE bakılır (çıkarılan ilk
 *      sayfalar) ve grup adı/anahtar kelimeleri aranır.
 *   4. Hiçbiri olmazsa kitap kütüphanede "havuz"da kalır; yanlış rafa
 *      ATILMAZ ve raporda listelenir.
 *
 * Ayrıca `organizePool`: daha önce içe aktarılmış ama hiçbir gruba bağlı
 * olmayan kitapları (Cihaz Tarama havuzu) isim → içerik sırasıyla düzenler.
 */
object FolderImporter {

    const val DEFAULT_DIR = "/sdcard/Download/UmayKaynak"

    private val EXTS = setOf("pdf", "pptx", "ppt", "docx", "doc")

    /** Grup OLARAK açılmaması gereken genel klasör adları. */
    private val GENERIC = setOf(
        "ders icerikleri", "ders icerikleri ", "indirilenler", "downloads",
        "download", "belgeler", "documents", "dosyalar", "yeni klasor",
        "temp", "tmp", "genel", "karisik", "files", "kaynaklar"
    )

    data class Report(
        val imported: Int,
        val existing: Int,
        val failed: Int,
        val unmatched: MutableList<String>,
        val perCourse: MutableMap<String, Int>,
        /**
         * v24 — AKTARMA SONRASI gerçek raf sayıları: grup adı → (önce, sonra).
         * Yalnız sayısı DEĞİŞEN gruplar yer alır; böylece "11 → 22  +11"
         * gösterimi dersin gerçek kaynak sayısıyla birebir tutarlıdır.
         */
        val perCourseDelta: MutableMap<String, Pair<Int, Int>> = mutableMapOf(),
        val createdGroups: MutableList<String> = mutableListOf(),
        /** Kütüphanede ZATEN olan dosyalar (göreli yol) — kullanıcıya sorulur. */
        val duplicates: MutableList<String> = mutableListOf()
    )

    fun supported(file: File): Boolean =
        file.isFile && file.extension.lowercase() in EXTS && !file.name.startsWith(".")

    fun collect(root: File): List<File> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        return root.walkTopDown().filter { supported(it) }
            .sortedBy { it.absolutePath.lowercase() }.toList()
    }

    /**
     * Klasörü aktarır.
     * @param onProgress (dosya adı, tamamlanan, toplam)
     */
    fun run(
        ctx: Context,
        root: File,
        onProgress: (String, Int, Int) -> Unit,
        /** Kullanıcı "yine de ekle" dediği dosyaların MUTLAK yolları. */
        forcePaths: Set<String> = emptySet(),
        onDone: (Report) -> Unit
    ) {
        Thread({
            val files = collect(root)
            var imported = 0
            var existing = 0
            var failed = 0
            val unmatched = mutableListOf<String>()
            val perCourse = linkedMapOf<String, Int>()
            val created = mutableListOf<String>()
            // v24 — "önce → sonra" farkı için aktarma ÖNCESİ gerçek raf sayıları
            val beforeCounts = try {
                CourseStore.courses(ctx).associate { c ->
                    c.name to (try { CourseStore.docsOf(ctx, c.id).size } catch (_: Throwable) { 0 })
                }
            } catch (_: Throwable) { emptyMap() }
            val duplicates = mutableListOf<String>()
            val alreadyBefore = try { CourseStore.allLinkedBookIds(ctx) } catch (_: Throwable) { emptySet() }
            // Her dosyada DB'den taze okunur → yeni ders/raf eklendiyse uyum sağlar
            val pending = mutableListOf<Pair<Book, String>>()   // (kitap, göreli yol)

            files.forEachIndexed { index, file ->
                try {
                    onProgress(file.name, index, files.size)
                    val courses = try { CourseStore.courses(ctx) } catch (_: Throwable) { emptyList() }
                    var course = matchByName(file, root, courses)
                    if (course == null) {
                        val groupName = groupNameFor(file, root)
                        if (groupName != null) {
                            course = findOrCreateGroup(ctx, groupName)
                            val c = course
                            if (c != null && courses.none { it.id == c.id } &&
                                created.none { TextUtil.normalize(it) == TextUtil.normalize(c.name) }
                            ) created.add(c.name)
                        }
                    }
                    val rel = file.absolutePath.removePrefix(root.absolutePath + "/")
                    val forced = file.absolutePath in forcePaths
                    // MÜKERRER KONTROLÜ: kütüphanede aynı kaynak varsa
                    // kopya OLUŞTURMA; mevcut kitabı derse bağla ve
                    // kullanıcıya "yine de ekle?" diye sormak üzere bildir.
                    val existingBook = try {
                        val uri0 = Uri.fromFile(file)
                        BookStore.sourceKeyOf(ctx, uri0)?.let { BookStore.findBySourceKey(ctx, it) }
                    } catch (_: Throwable) { null }
                    if (existingBook != null && !forced) {
                        duplicates.add(rel)
                        existing++
                        val target = course
                        if (target != null) {
                            CourseStore.addDocs(ctx, target.id, listOf(existingBook.id), categoryOf(file.name))
                            perCourse[target.name] = (perCourse[target.name] ?: 0) + 1
                        }
                        return@forEachIndexed
                    }
                    var book: Book? = null
                    val latch = CountDownLatch(1)
                    BookStore.importPdf(ctx, Uri.fromFile(file), allowDuplicate = forced) { res ->
                        book = res.getOrNull()
                        latch.countDown()
                    }
                    latch.await(180, TimeUnit.SECONDS)
                    val b = book
                    if (b == null) {
                        failed++
                        return@forEachIndexed
                    }
                    if (b.id in alreadyBefore) existing++ else imported++
                    if (course == null) {
                        // İçerikten anlamayı dene (çıkarma bitince ilk sayfalar)
                        course = matchByContent(ctx, b, CourseStore.courses(ctx))
                        if (course == null) {
                            pending.add(b to rel)
                            return@forEachIndexed
                        }
                    }
                    CourseStore.addDocs(ctx, course.id, listOf(b.id), categoryOf(file.name))
                    perCourse[course.name] = (perCourse[course.name] ?: 0) + 1
                } catch (t: Throwable) {
                    failed++
                    BookStore.logError(ctx, "FolderImporter/${file.name}", t)
                }
            }

            // İçerik eşleşmesi yine olmadıysa: havuzda bırak + rapora yaz
            pending.forEach { (b, rel) ->
                val again = matchByContent(ctx, b, CourseStore.courses(ctx))
                if (again != null) {
                    CourseStore.addDocs(ctx, again.id, listOf(b.id), categoryOf(rel))
                    perCourse[again.name] = (perCourse[again.name] ?: 0) + 1
                } else {
                    unmatched.add(rel)
                }
            }

            // v24 — "önce → sonra" farkı: yalnız GERÇEKTEN değişen gruplar
            val delta = linkedMapOf<String, Pair<Int, Int>>()
            try {
                CourseStore.courses(ctx).forEach { c ->
                    val after = try { CourseStore.docsOf(ctx, c.id).size } catch (_: Throwable) { 0 }
                    val before = beforeCounts[c.name] ?: 0
                    if (after != before) delta[c.name] = before to after
                }
            } catch (_: Throwable) {}

            onProgress("", files.size, files.size)
            onDone(
                Report(
                    imported, existing, failed, unmatched, perCourse,
                    delta, created, duplicates
                )
            )
        }, "umay-folder-import").apply { isDaemon = true }.start()
    }

    /**
     * HAVUZ DÜZENLEYİCİ: hiçbir gruba bağlı olmayan kitapları isim → içerik
     * ile gruplara yerleştirir. Dosya taşımaz/silmez; yalnız bağ kurar.
     */
    fun organizePool(
        ctx: Context,
        onProgress: (String, Int, Int) -> Unit,
        onDone: (placed: Int, remaining: Int) -> Unit
    ) {
        Thread({
            var placed = 0
            val linked = try { CourseStore.allLinkedBookIds(ctx) } catch (_: Throwable) { emptySet() }
            val pool = try { BookStore.loadBooks(ctx).filter { it.id !in linked } } catch (_: Throwable) { emptyList() }
            pool.forEachIndexed { i, book ->
                try {
                    onProgress(book.title, i, pool.size)
                    val courses = CourseStore.courses(ctx)
                    val byName = matchTitle(book.title, courses)
                    val target = byName ?: matchByContent(ctx, book, courses)
                    if (target != null) {
                        CourseStore.addDocs(ctx, target.id, listOf(book.id), categoryOf(book.title))
                        placed++
                    }
                } catch (t: Throwable) {
                    BookStore.logError(ctx, "organizePool/${book.id}", t)
                }
            }
            onDone(placed, pool.size - placed)
        }, "umay-pool-organize").apply { isDaemon = true }.start()
    }

    // ── İsim eşleştirme ────────────────────────────────────────

    private fun matchByName(file: File, root: File, courses: List<Course>): Course? {
        val rel = file.absolutePath.removePrefix(root.absolutePath)
        val parts = rel.split(File.separatorChar).filter { it.isNotBlank() }
        // TÜM klasör bileşenleri aday (derin klasörlerde son ad "kitap" olabilir)
        val candidates = (parts.dropLast(1) + file.nameWithoutExtension)
            .filter { it.length >= 3 }
        return bestMatch(candidates, courses, threshold = 4)
    }

    private fun matchTitle(title: String, courses: List<Course>): Course? =
        bestMatch(listOf(title.substringBeforeLast('.')), courses, threshold = 4)

    private fun bestMatch(candidates: List<String>, courses: List<Course>, threshold: Int): Course? {
        var best: Course? = null
        var bestScore = 0
        for (c in courses) {
            val target = TextUtil.normalize(c.name)
            val tokens = target.split(" ").filter { it.length >= 4 && it !in STOP }
            for (cand in candidates) {
                val src = TextUtil.normalize(cand)
                if (src.isBlank()) continue
                var score = if (src.contains(target) || target.contains(src)) 12 else {
                    val words = src.split(" ")
                    val hits = tokens.count { t ->
                        words.any { w ->
                            w == t || (w.length >= 5 && t.length >= 5 &&
                                (w.startsWith(t.take(5)) || t.startsWith(w.take(5))))
                        }
                    }
                    hits * 3 + if (hits >= 2) 2 else 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
        }
        return if (bestScore >= threshold) best else null
    }

    /** Üst klasör adı grup olmaya uygun mu? ("Tapu", "Ayşe", "Kira"…) */
    private fun groupNameFor(file: File, root: File): String? {
        val rel = file.absolutePath.removePrefix(root.absolutePath)
        val parts = rel.split(File.separatorChar).filter { it.isNotBlank() }
        if (parts.size < 2) return null                    // kök dosya → grup uydurma
        val top = parts.first().trim()
        if (top.length < 3) return null
        if (TextUtil.normalize(top) in GENERIC) return null
        return top
    }

    private fun findOrCreateGroup(ctx: Context, name: String): Course? {
        val courses = try { CourseStore.courses(ctx) } catch (_: Throwable) { emptyList() }
        courses.firstOrNull { TextUtil.normalize(it.name) == TextUtil.normalize(name) }?.let { return it }
        return try { CourseStore.addCourse(ctx, name) } catch (_: Throwable) { null }
    }

    // ── İçerik eşleştirme ──────────────────────────────────────

    /**
     * Kitabın ilk sayfalarındaki metne bakar. Çıkarma henüz bitmediyse
     * en fazla ~24 sn bekler (toplam). Grup adı geçmiyorsa anahtar kelime
     * örtüşmesine bakar. Bulamazsa null → dosya havuzda kalır.
     */
    private fun matchByContent(ctx: Context, book: Book, courses: List<Course>): Course? {
        if (courses.isEmpty()) return null
        try { BookStore.ensureExtracting(ctx, book) } catch (_: Throwable) {}
        val sb = StringBuilder()
        var waited = 0
        var pageIdx = 0
        while (pageIdx < 3 && waited < 24_000) {
            val f = BookStore.pageFile(ctx, book.id, pageIdx)
            var spins = 0
            while ((!f.exists() || f.length() == 0L) && spins < 40 && waited < 24_000) {
                Thread.sleep(200)
                waited += 200
                spins++
            }
            val txt = try { BookStore.pageText(ctx, book.id, pageIdx) } catch (_: Throwable) { null }
            if (!txt.isNullOrBlank()) sb.append(txt).append(' ')
            if (sb.length > 6000) break
            pageIdx++
        }
        if (sb.length < 200) return null
        val text = TextUtil.normalize(sb.toString())
        var best: Course? = null
        var bestScore = 0
        for (c in courses) {
            val target = TextUtil.normalize(c.name)
            val tokens = target.split(" ").filter { it.length >= 5 && it !in STOP }
            if (tokens.isEmpty()) continue
            // Tam ad geçiyorsa güçlü sinyal
            var score = if (text.contains(target)) 15 else 0
            val hits = tokens.count { text.contains(it) }
            score += hits * 3
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return if (bestScore >= 6) best else null
    }

    // ── Yardımcılar ────────────────────────────────────────────

    private fun categoryOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pptx", "ppt" -> "slide"
        "docx", "doc" -> "doc"
        "pdf" -> "book"
        else -> "doc"
    }

    /** Eşleştirmede gürültü yaratan genel kelimeler. */
    private val STOP = setOf(
        "ders", "dersi", "giris", "dosyalari", "kaynak", "kitap", "kitabi",
        "notlar", "notu", "hafta", "final", "vize", "proje", "odev",
        "uygulama", "calisma", "bolum", "deney", "foyu"
    )
}
