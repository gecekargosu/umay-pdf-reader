package com.umay.pdfreader

import android.content.ContentValues
import android.content.Context

/**
 * Akademik modülün veri katmanı — `LibraryDb` (library.db) içindeki tablolar:
 *
 *   courses    (id, name, created_at)                 ← Derslerim
 *   documents  (id, course_id, book_id, added_at)     ← dersin kaynakları (books.id'ye bağ)
 *   exams      (id, course_id, title, at_millis)      ← sınav tarihleri
 *   homework   (id, course_id, title, description, due_millis, done)
 *   schedule   (id, course_id, day, minute_of_day, room, remind_min)
 *
 * Neden Room değil: mevcut `LibraryDb` zaten SQLiteOpenHelper üzerine kurulu ve
 * Room eklemek KSP/kapt + yeni bağımlılık demek (bkz. LibraryDb dokümantasyonu).
 * Şema 1 → 2 olarak genişletildi; `books` tablosuna DOKUNULMADI.
 *
 * Tüm sorgular tek tek `try/catch`'lidir: akademik modül bozukken kütüphane /
 * okuma asla etkilenmez (fallback: boş liste).
 */
object CourseStore {

    private fun db(ctx: Context): LibraryDb = LibraryDb.get(ctx)

    private inline fun <T> safe(ctx: Context, where: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "CourseStore.$where", t)
            fallback
        }

    // ── Dersler ─────────────────────────────────────────────

    /** Tüm dersler (Türkçe collator ile alfabetik). */
    fun courses(ctx: Context): List<Course> = safe(ctx, "courses", emptyList()) {
        val out = ArrayList<Course>(8)
        db(ctx).readableDatabase.rawQuery(
            // v16 (GÖREV 6.2) — COALESCE: eski DB'lerde kolon yeni eklendiğinde
            // (migration öncesi okuma) değer 0 kabul edilir, sorgu patlamaz.
            "SELECT id, name, created_at, COALESCE(ects, 0), " +
                "COALESCE(instructor, '') FROM courses " +
                "ORDER BY created_at DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Course(
                        c.getString(0), c.getString(1), c.getLong(2),
                        c.getInt(3), c.getString(4)
                    )
                )
            }
        }
        // Not: `safe { ... }` ifade gövdesi olduğu için son satır DÖNÜŞ
        // değeridir → `sortedWith` sonucu doğrudan döner (sıralama çalışır).
        val coll = java.text.Collator.getInstance(java.util.Locale("tr", "TR"))
        out.sortedWith(Comparator { a, b -> coll.compare(a.name, b.name) })
    }

    fun course(ctx: Context, id: String): Course? = safe(ctx, "course", null) {
        db(ctx).readableDatabase.rawQuery(
            "SELECT id, name, created_at, COALESCE(ects, 0), COALESCE(instructor, '') " +
                "FROM courses WHERE id = ?",
            arrayOf(id)
        ).use { c ->
            if (c.moveToFirst())
                Course(
                    c.getString(0), c.getString(1), c.getLong(2),
                    c.getInt(3), c.getString(4)
                )
            else null
        }
    }

    /** Ders ekler; aynı isimde ders varsa onu döndürür (tekilleştirme). */
    fun addCourse(ctx: Context, name: String): Course? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        courses(ctx).firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it }
        val id = Academy.newId()
        val ok = safe(ctx, "addCourse", false) {
            val cv = ContentValues().apply {
                put("id", id)
                put("name", trimmed)
                put("created_at", System.currentTimeMillis())
            }
            db(ctx).writableDatabase.insert("courses", null, cv) != -1L
        }
        return if (ok) course(ctx, id) else null
    }

    fun renameCourse(ctx: Context, id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return safe(ctx, "renameCourse", false) {
            val cv = ContentValues().apply { put("name", trimmed) }
            db(ctx).writableDatabase.update("courses", cv, "id = ?", arrayOf(id)) > 0
        }
    }

    /** Dersi ve tüm bağlı kayıtlarını siler (kaynak kitaplar SİLİNMEZ, sadece bağlantı). */
    fun deleteCourse(ctx: Context, id: String): Boolean = safe(ctx, "deleteCourse", false) {
        val d = db(ctx).writableDatabase
        d.delete("documents", "course_id = ?", arrayOf(id))
        d.delete("exams", "course_id = ?", arrayOf(id))
        d.delete("homework", "course_id = ?", arrayOf(id))
        d.delete("schedule", "course_id = ?", arrayOf(id))
        // v16 (GÖREV 6.2) — ders silinince not satırları da temizlenir
        // (yetim kayıt kalmaz; başka dersin verisi etkilenmez).
        d.delete("grades", "course_id = ?", arrayOf(id))
        d.delete("courses", "id = ?", arrayOf(id)) > 0
    }

    // ── Ders kaynakları (kitaplar) ──────────────────────────

    data class DocWithBook(val doc: CourseDoc, val book: Book?)

    /** Derse bağlı kaynaklar + kitap künyesi (kitap silinmişse null). */
    fun docsOf(ctx: Context, courseId: String): List<DocWithBook> =
        safe(ctx, "docsOf", emptyList()) {
            val books = BookStore.loadBooks(ctx).associateBy { it.id }
            val out = ArrayList<DocWithBook>(8)
            db(ctx).readableDatabase.rawQuery(
                "SELECT id, course_id, book_id, added_at, " +
                    "COALESCE(category, 'book') FROM documents WHERE course_id = ? " +
                    "ORDER BY added_at DESC", arrayOf(courseId)
            ).use { c ->
                while (c.moveToNext()) {
                    val doc = CourseDoc(
                        c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4)
                    )
                    out.add(DocWithBook(doc, books[doc.bookId]))
                }
            }
            out
        }

    /**
     * v21 — TÜM derslere bağlı kitap kimlikleri.
     * Ana sayfadaki "Ders Kitapları" rafı, derslere eklenen kaynakları da
     * gösterir (kullanıcı: "derslerdeki kitaplar ana sayfada da görünsün").
     */
    fun allLinkedBookIds(ctx: Context): Set<String> = safe(ctx, "allLinkedBookIds", emptySet()) {
        val out = HashSet<String>()
        db(ctx).readableDatabase.rawQuery("SELECT book_id FROM documents", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                if (id.isNotBlank()) out.add(id)
            }
        }
        out
    }

    fun linkedBookIds(ctx: Context, courseId: String): Set<String> =
        safe(ctx, "linkedBookIds", emptySet()) {
            val out = HashSet<String>(8)
            db(ctx).readableDatabase.rawQuery(
                "SELECT book_id FROM documents WHERE course_id = ?", arrayOf(courseId)
            ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
            out
        }

    /**
     * v14 (V5.0 §4): kaynak kategorisiyle ekle —
     * "book" Ders Kitapları · "slide" Slide · "helper" Yardımcı · "doc" Belge
     */
    fun addDocs(
        ctx: Context,
        courseId: String,
        bookIds: Collection<String>,
        category: String = "book"
    ): Int {
        var added = 0
        val already = linkedBookIds(ctx, courseId)
        safe<Unit>(ctx, "addDocs", Unit) {
            val d = db(ctx).writableDatabase
            bookIds.forEach { bookId ->
                if (bookId in already) return@forEach
                val cv = ContentValues().apply {
                    put("id", Academy.newId())
                    put("course_id", courseId)
                    put("book_id", bookId)
                    put("added_at", System.currentTimeMillis())
                    put("category", category)
                }
                if (d.insert("documents", null, cv) != -1L) added++
            }
        }
        return added
    }

    /**
     * v5.4 — KİTAP ADINDAN DERS EŞLEŞTİRME.
     *
     * Yalnız dosya adında GERÇEKTEN bir ders kodu geçiyorsa (ör. "BLG205
     * Slayt 3.pptx") o derse bağlar ve dersin rafına ekler. Kod yoksa hiçbir
     * şey yapmaz — yanlış rafa atma riski yoktur.
     *
     * @return eşleşen dersin kimliği (yoksa null)
     */
    fun autoLinkByTitle(ctx: Context, book: Book): String? = safe(ctx, "autoLinkByTitle", null) {
        val code = ShelfManager.courseCodeIn(book.title) ?: return@safe null
        val course = courses(ctx).firstOrNull {
            it.name.startsWith(code, ignoreCase = true)
        } ?: return@safe null
        val lower = book.title.lowercase()
        val category = when {
            lower.endsWith(".pptx") || lower.endsWith(".ppt") || lower.endsWith(".odp") ||
                lower.contains("slayt") || lower.contains("sunum") -> "slide"
            lower.contains("odev") || lower.contains("ödev") || lower.contains("proje") -> "doc"
            else -> "book"
        }
        addDocs(ctx, course.id, listOf(book.id), category)
        val shelfId = ShelfManager.ensureCourseShelf(ctx, code, course.name)
        ShelfManager.addBookToShelf(ctx, book.id, shelfId)
        course.id
    }

    /**
     * v5.5 (ONARIM 10 / H12.6) — MEVCUT kitapları bir kez derslere bağlar.
     *
     * Kök neden: `autoLinkByTitle()` yalnız İÇE AKTARMA anında çağrılıyordu.
     * v21'de toplu içe aktarma kaldırıldığı için eski kitaplar hiçbir derse
     * etiketlenmedi → ders kartlarında hep "0 kaynak" görünüyordu.
     * Tek seferlik bayrakla (`backfill_v1`) arka planda çalışır; eşleşme
     * yoksa hiçbir şey yazmaz, var olan bağlantıyı çoğaltmaz (addDocs zaten
     * aynı kitabı ikinci kez eklemiyor).
     */
    fun backfillAutoLink(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences("umay_prefs", 0)
            if (prefs.getBoolean("course_backfill_v1", false)) return
            val books = BookStore.loadBooks(ctx)
            if (books.isNotEmpty()) {
                var linked = 0
                for (b in books) {
                    if (autoLinkByTitle(ctx, b) != null) linked++
                }
                android.util.Log.d("CourseStore", "backfillAutoLink: $linked/${books.size} kitap derse bağlandı")
            }
            prefs.edit().putBoolean("course_backfill_v1", true).apply()
        } catch (t: Throwable) {
            android.util.Log.e("CourseStore", "backfillAutoLink başarısız", t)
        }
    }

    /** v14 (V5.0 §4): mevcut kaynağın kategorisini değiştir (rafa taşı gibi). */
    fun setDocCategory(ctx: Context, docId: String, category: String): Boolean =
        safe(ctx, "setDocCategory", false) {
            val cv = ContentValues().apply { put("category", category) }
            db(ctx).writableDatabase.update("documents", cv, "id = ?", arrayOf(docId)) > 0
        }

    fun removeDoc(ctx: Context, docId: String): Boolean = safe(ctx, "removeDoc", false) {
        db(ctx).writableDatabase.delete("documents", "id = ?", arrayOf(docId)) > 0
    }

    /**
     * v6 (Aşama 3) — Kitap kütüphaneden silinince ders kaynaklarındaki
     * asılı (dangling) bağlantılar da temizlenir; ders rafı hata vermez.
     */
    fun unlinkBook(ctx: Context, bookId: String): Int = safe(ctx, "unlinkBook", 0) {
        db(ctx).writableDatabase.delete("documents", "book_id = ?", arrayOf(bookId))
    }

    // ── Sınavlar ────────────────────────────────────────────

    fun addExam(
        ctx: Context, courseId: String, title: String, atMillis: Long, note: String = "",
        // v16 (YENİ GÖREV 2): tür / etki oranı (%) / hatırlatma
        type: String = "Vize", weight: Int = 0, alarm: Boolean = true
    ): Boolean {
        val ok = safe(ctx, "addExam", false) {
            val cv = ContentValues().apply {
                put("id", Academy.newId())
                put("course_id", courseId)
                put("title", title.trim())
                put("at_millis", atMillis)
                put("note", note.trim())
                put("type", type.trim().ifBlank { "Vize" })
                put("weight", weight.coerceIn(0, 100))
                put("alarm", if (alarm) 1 else 0)
            }
            db(ctx).writableDatabase.insert("exams", null, cv) != -1L
        }
        // v13 (T7): yeni sınav eklenince canlı geri sayım bildirimini başlat
        if (ok) try { ExamCountdownService.refresh(ctx) } catch (_: Throwable) {}
        return ok
    }

    fun examsOf(ctx: Context, courseId: String): List<Exam> = safe(ctx, "examsOf", emptyList()) {
        val out = ArrayList<Exam>(8)
        db(ctx).readableDatabase.rawQuery(
            "SELECT id, course_id, title, at_millis, COALESCE(note, ''), " +
                "COALESCE(type, 'Vize'), COALESCE(weight, 0), COALESCE(alarm, 1) " +
                "FROM exams WHERE course_id = ? ORDER BY at_millis", arrayOf(courseId)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Exam(
                        c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
                        c.getString(4), c.getString(5), c.getInt(6), c.getInt(7) != 0
                    )
                )
            }
        }
        out
    }

    /** v14 (V5.0 §5): sınav notunu güncelle (hatırlatıcı notu). */
    fun setExamNote(ctx: Context, examId: String, note: String): Boolean =
        safe(ctx, "setExamNote", false) {
            val cv = ContentValues().apply { put("note", note) }
            db(ctx).writableDatabase.update("exams", cv, "id = ?", arrayOf(examId)) > 0
        }

    /** Tüm sınavlar (günlük hatırlatma kontrolü için) — ders adıyla birlikte. */
    data class ExamWithCourse(val exam: Exam, val courseName: String)

    fun allExams(ctx: Context): List<ExamWithCourse> = safe(ctx, "allExams", emptyList()) {
        val out = ArrayList<ExamWithCourse>(16)
        db(ctx).readableDatabase.rawQuery(
            "SELECT e.id, e.course_id, e.title, e.at_millis, COALESCE(e.note, ''), " +
                "COALESCE(e.type, 'Vize'), COALESCE(e.weight, 0), COALESCE(e.alarm, 1), " +
                "COALESCE(c.name, '') " +
                "FROM exams e LEFT JOIN courses c ON c.id = e.course_id ORDER BY e.at_millis",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ExamWithCourse(
                        Exam(
                            c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
                            c.getString(4), c.getString(5), c.getInt(6), c.getInt(7) != 0
                        ),
                        c.getString(8)
                    )
                )
            }
        }
        out
    }

    /** v16 (GÖREV 6) — Öğretim üyesi adını kaydet (matris + ders detayı gösterir). */
    fun setInstructor(ctx: Context, courseId: String, instructor: String): Boolean =
        safe(ctx, "setInstructor", false) {
            val cv = ContentValues().apply { put("instructor", instructor.trim()) }
            db(ctx).writableDatabase.update("courses", cv, "id = ?", arrayOf(courseId)) > 0
        }

    /**
     * v16 (GÖREV 5) — Mevcut bir ders programı satırını DÜZENLE
     * (gün / saat / derslik / hatırlatma). Satır yoksa false.
     */
    fun updateSlot(
        ctx: Context, slotId: String, day: Int, minuteOfDay: Int,
        room: String, remindMin: Int, durationMin: Int = 120
    ): Boolean = safe(ctx, "updateSlot", false) {
        val cv = ContentValues().apply {
            put("day", day.coerceIn(0, 6))
            put("minute_of_day", minuteOfDay.coerceIn(0, 1439))
            put("room", room.trim())
            put("remind_min", remindMin.coerceIn(0, 1440))
            put("duration_min", durationMin.coerceIn(15, 480))
        }
        db(ctx).writableDatabase.update("schedule", cv, "id = ?", arrayOf(slotId)) > 0
    }

    /** v16 (YENİ GÖREV 2) — Sınavı DÜZENLE (başlık / tarih / not / tür / etki / alarm). */
    fun updateExam(
        ctx: Context, examId: String, title: String, atMillis: Long, note: String,
        type: String = "Vize", weight: Int = 0, alarm: Boolean = true
    ): Boolean = safe(ctx, "updateExam", false) {
        val cv = ContentValues().apply {
            put("title", title.trim())
            put("at_millis", atMillis)
            put("note", note.trim())
            put("type", type.trim().ifBlank { "Vize" })
            put("weight", weight.coerceIn(0, 100))
            put("alarm", if (alarm) 1 else 0)
        }
        db(ctx).writableDatabase.update("exams", cv, "id = ?", arrayOf(examId)) > 0
    }

    fun deleteExam(ctx: Context, id: String): Boolean {
        val ok = safe(ctx, "deleteExam", false) {
            db(ctx).writableDatabase.delete("exams", "id = ?", arrayOf(id)) > 0
        }
        // v13 (T7): sınav değişince canlı geri sayım bildirimini tazele
        if (ok) try { ExamCountdownService.refresh(ctx) } catch (_: Throwable) {}
        return ok
    }

    // ── Ödev / Projeler ─────────────────────────────────────

    fun addHomework(
        ctx: Context,
        courseId: String,
        title: String,
        description: String,
        dueMillis: Long,
        // v16 (YENİ GÖREV 2): teslim hatırlatıcısı
        alarm: Boolean = true
    ): Boolean = safe(ctx, "addHomework", false) {
        val cv = ContentValues().apply {
            put("id", Academy.newId())
            put("course_id", courseId)
            put("title", title.trim())
            put("description", description.trim())
            put("due_millis", dueMillis)
            put("done", 0)
            put("alarm", if (alarm) 1 else 0)
        }
        db(ctx).writableDatabase.insert("homework", null, cv) != -1L
    }

    fun homeworkOf(ctx: Context, courseId: String): List<Homework> =
        safe(ctx, "homeworkOf", emptyList()) {
            val out = ArrayList<Homework>(8)
            db(ctx).readableDatabase.rawQuery(
                "SELECT id, course_id, title, description, due_millis, done, " +
                    "COALESCE(alarm, 1) FROM homework " +
                    "WHERE course_id = ? ORDER BY done ASC, due_millis", arrayOf(courseId)
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Homework(
                            c.getString(0), c.getString(1), c.getString(2),
                            c.getString(3), c.getLong(4), c.getInt(5) != 0,
                            c.getInt(6) != 0
                        )
                    )
                }
            }
            out
        }

    data class HomeworkWithCourse(val homework: Homework, val courseName: String)

    /** Günlük hatırlatma için: tüm ödevler (teslim edilenler hariç caller'da filtrelenir). */
    fun allHomework(ctx: Context): List<HomeworkWithCourse> = safe(ctx, "allHomework", emptyList()) {
        val out = ArrayList<HomeworkWithCourse>(16)
        db(ctx).readableDatabase.rawQuery(
            "SELECT h.id, h.course_id, h.title, h.description, h.due_millis, h.done, " +
                "COALESCE(h.alarm, 1), COALESCE(c.name, '') FROM homework h " +
                "LEFT JOIN courses c ON c.id = h.course_id " +
                "ORDER BY h.due_millis", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    HomeworkWithCourse(
                        Homework(
                            c.getString(0), c.getString(1), c.getString(2),
                            c.getString(3), c.getLong(4), c.getInt(5) != 0,
                            c.getInt(6) != 0
                        ),
                        c.getString(7)
                    )
                )
            }
        }
        out
    }

    fun setHomeworkDone(ctx: Context, id: String, done: Boolean): Boolean =
        safe(ctx, "setHomeworkDone", false) {
            val cv = ContentValues().apply { put("done", if (done) 1 else 0) }
            db(ctx).writableDatabase.update("homework", cv, "id = ?", arrayOf(id)) > 0
        }

    fun deleteHomework(ctx: Context, id: String): Boolean = safe(ctx, "deleteHomework", false) {
        db(ctx).writableDatabase.delete("homework", "id = ?", arrayOf(id)) > 0
    }

    // ── Haftalık ders programı ──────────────────────────────

    fun addSlot(
        ctx: Context,
        courseId: String,
        day: Int,
        minuteOfDay: Int,
        room: String,
        remindMin: Int,
        /** v5.8 — ders süresi (dakika). Blok bu süre boyunca boyanır. */
        durationMin: Int = 120
    ): String? {
        val id = Academy.newId()
        val ok = safe(ctx, "addSlot", false) {
            val cv = ContentValues().apply {
                put("id", id)
                put("course_id", courseId)
                put("day", day)
                put("minute_of_day", minuteOfDay)
                put("room", room.trim())
                put("remind_min", remindMin)
                put("duration_min", durationMin.coerceIn(15, 480))
            }
            db(ctx).writableDatabase.insert("schedule", null, cv) != -1L
        }
        // v12: program değişince ana ekran widget'ını tazele
        if (ok) try { UmayWidgetProvider.refreshAll(ctx) } catch (_: Throwable) {}
        return if (ok) id else null
    }

    fun slotsOf(ctx: Context): List<ScheduleSlot> = safe(ctx, "slotsOf", emptyList()) {
        val out = ArrayList<ScheduleSlot>(16)
        db(ctx).readableDatabase.rawQuery(
            "SELECT s.id, s.course_id, COALESCE(c.name, ''), s.day, s.minute_of_day, s.room, " +
                "s.remind_min, COALESCE(s.duration_min, 120) " +
                "FROM schedule s LEFT JOIN courses c ON c.id = s.course_id " +
                "ORDER BY s.day, s.minute_of_day", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ScheduleSlot(
                        c.getString(0), c.getString(1), c.getString(2),
                        c.getInt(3), c.getInt(4), c.getString(5), c.getInt(6),
                        c.getInt(7)
                    )
                )
            }
        }
        out
    }

    fun slot(ctx: Context, id: String): ScheduleSlot? = safe(ctx, "slot", null) {
        db(ctx).readableDatabase.rawQuery(
            "SELECT s.id, s.course_id, COALESCE(c.name, ''), s.day, s.minute_of_day, s.room, " +
                "s.remind_min, COALESCE(s.duration_min, 120) " +
                "FROM schedule s LEFT JOIN courses c ON c.id = s.course_id WHERE s.id = ?",
            arrayOf(id)
        ).use { c ->
            if (c.moveToFirst()) {
                ScheduleSlot(
                    c.getString(0), c.getString(1), c.getString(2),
                    c.getInt(3), c.getInt(4), c.getString(5), c.getInt(6),
                    c.getInt(7)
                )
            } else null
        }
    }

    fun deleteSlot(ctx: Context, id: String): Boolean {
        val ok = safe(ctx, "deleteSlot", false) {
            db(ctx).writableDatabase.delete("schedule", "id = ?", arrayOf(id)) > 0
        }
        // v12: program değişince ana ekran widget'ını tazele
        if (ok) try { UmayWidgetProvider.refreshAll(ctx) } catch (_: Throwable) {}
        return ok
    }

    /** İstatistik: derse ait kaynak/ödev/sınav sayıları (kart rozetleri için). */
    data class CourseStats(val docs: Int, val homework: Int, val exams: Int)

    fun statsOf(ctx: Context, courseId: String): CourseStats =
        safe(ctx, "statsOf", CourseStats(0, 0, 0)) {
            fun count(sql: String): Int =
                db(ctx).readableDatabase.rawQuery(sql, arrayOf(courseId)).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            CourseStats(
                count("SELECT COUNT(*) FROM documents WHERE course_id = ?"),
                count("SELECT COUNT(*) FROM homework WHERE course_id = ?"),
                count("SELECT COUNT(*) FROM exams WHERE course_id = ?")
            )
        }
}
