package com.umay.pdfreader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Kitaplık indeksi için SQLite veritabanı (ADIM 1 — "Room DB / SQLite" gereği).
 *
 * Neden SQLite: eski `books.json` tek bir dosyaydı; dosya bozulursa ya da
 * yazım yarım kalırsa tüm kütüphane kaybolabiliyordu. Artık kaynak doğruluk
 * satır tabanlı veritabanında; `books.json` yalnızca geriye dönük yedek
 * (ayna) olarak yazmaya devam ediyor.
 *
 * Room KULLANILMADI bilinçli olarak: KSP/kapt eklemeden, mevcut
 * `BookStore.loadBooks/saveBooks` arayüzünü hiç değiştirmeden ilerlemek için
 * (ekranlar, servis, çıkarım motoru aynı kalır → mevcut yapı bozulmaz).
 *
 * Geçiş: veritabanı boşken eski `books.json` bir kez içeri alınır.
 *
 * Tablolar yalnızca kitap künyesini tutar; okuma ilerlemesi eskisi gibi
 * SharedPreferences (`AppPrefs`) içindedir — o akışa dokunulmadı.
 */
class LibraryDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    /** v8: self-heal için application context (SQLiteHelper'da alan yok). */
    private val appContext: Context = ctx.applicationContext

    companion object {
        private const val DB_NAME = "library.db"
        /** v2: akademik modül tabloları (courses/documents/exams/homework/schedule) eklendi. */
        /** v14 (V5.0 §4/§5): documents.category + exams.note kolonları eklendi. */
        /** v16 (GÖREV 6.2): `grades` tablosu + `courses.ects` · (GÖREV 6): `courses.instructor`. */
        /** v16 (YENİ GÖREV 2): `exams.type/weight/alarm` + `homework.alarm` kolonları. */
        private const val DB_VERSION = 7
        private const val TABLE = "books"

        @Volatile
        private var instance: LibraryDb? = null

        /** Uygulama boyunca tek örnek (bağlantı maliyeti yok). */
        fun get(ctx: Context): LibraryDb =
            instance ?: synchronized(this) {
                instance ?: LibraryDb(ctx.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // v8 (KATEGORİ 1): şema oluşturma hatası uygulamayı düşürmesin;
        // BookStore JSON yedeğine düşer, kitaplık kaybolmaz.
        try {
            createBooks(db)
            createAcademy(db)
        } catch (t: Throwable) {
            android.util.Log.e("LibraryDb", "onCreate başarısız", t)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v8 (KATEGORİ 1 — migration koruması): ALTER/CREATE işlemleri güvenli
        // try-catch içinde; hata olursa uygulama ÇÖKMEZ, mevcut şema ile devam
        // edilir ve loglanır (bozuk DB'de openSafe self-heal ile yeniden açar).
        try {
            // v1 → v2: akademik tablolar eklendi (books'a dokunulmaz)
            if (oldVersion < 2) createAcademy(db)
            // v2 → v3 (V5.0 §4/§5): kaynak kategorisi + sınav notu kolonları
            if (oldVersion < 3) {
                addColumnSafe(db, "documents", "category", "TEXT NOT NULL DEFAULT 'book'")
                addColumnSafe(db, "exams", "note", "TEXT NOT NULL DEFAULT ''")
            }
            // v3 → v4 (v16 / GÖREV 6.2 + GÖREV 8.2 — VERİ KAYBI YOK):
            //  • courses.ects : AKTS kredisi (AGNO ağırlığı)
            //  • grades       : vize/quiz/final/proje not + yüzde satırları
            // Yalnız ALTER TABLE ADD COLUMN + CREATE TABLE IF NOT EXISTS;
            // mevcut satırlar/kolonlar KORUNUR (kullanıcı verisi silinmez).
            if (oldVersion < 4) {
                addColumnSafe(db, "courses", "ects", "INTEGER NOT NULL DEFAULT 0")
                createGrades(db)
            }
            // v3/v4 → v5 (v16): GÖREV 6 eklemeleri. Hepsi IDEMPOTENT
            // (`ALTER TABLE ADD COLUMN` kolon varsa sessizce geçer) → yani
            // herhangi bir eski şemada (2/3/4) VERİ KAYBISIZ tamamlanır.
            if (oldVersion < 5) {
                addColumnSafe(db, "courses", "ects", "INTEGER NOT NULL DEFAULT 0")
                // v16 (GÖREV 6) — öğretim üyesi adı (matris hücre + ders detayı)
                addColumnSafe(db, "courses", "instructor", "TEXT NOT NULL DEFAULT ''")
                createGrades(db)
            }
            // v16 (YENİ GÖREV 2) — Sınav türü/etki oranı/alarm + ödev alarmı.
            // Hepsi IDEMPOTENT ALTER → eski veri KORUNUR, varsayılanlar mevcut
            // satırları etkilemez (alarm=1 → mevcut hatırlatıcılar aynen sürer).
            if (oldVersion < 6) {
                addColumnSafe(db, "exams", "type", "TEXT NOT NULL DEFAULT 'Vize'")
                addColumnSafe(db, "exams", "weight", "INTEGER NOT NULL DEFAULT 0")
                addColumnSafe(db, "exams", "alarm", "INTEGER NOT NULL DEFAULT 1")
                addColumnSafe(db, "homework", "alarm", "INTEGER NOT NULL DEFAULT 1")
            }
            // v5.8 — Program bloğu SÜRESİ (varsayılan 2 saat). Eskiden süre
            // sabit 120 dk varsayılıyordu; 1 saatlik dersler yanlış boyanıyordu.
            if (oldVersion < 7) {
                addColumnSafe(db, "schedule", "duration_min", "INTEGER NOT NULL DEFAULT 120")
            }
        } catch (t: Throwable) {
            android.util.Log.e("LibraryDb", "onUpgrade $oldVersion→$newVersion başarısız", t)
        }
    }

    /**
     * v8 (KATEGORİ 1): DB açmayı dener; BOZUK/erişilemez DB'de dosyayı silip
     * yeniden oluşturur (self-heal). Sonuç yoksa null → çağıran taraf
     * (BookStore) `books.json` yedeğine düşer. Hiçbir durumda fırlatmaz.
     */
    private fun openSafe(writable: Boolean): SQLiteDatabase? = try {
        if (writable) writableDatabase else readableDatabase
    } catch (t: Throwable) {
        android.util.Log.e("LibraryDb", "DB açılamadı, self-heal deneniyor", t)
        try {
            close()
            appContext.deleteDatabase(DB_NAME)
            if (writable) writableDatabase else readableDatabase
        } catch (t2: Throwable) {
            android.util.Log.e("LibraryDb", "self-heal de başarısız", t2)
            null
        }
    }

    /**
     * v16 (GÖREV 6.2) — Not tablosu (vize/quiz/final/proje + yüzde ağırlık).
     * `id` = "<course_id>-<kind>" → aynı tür için tek satır (upsert).
     */
    private fun createGrades(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS grades ( " +
                    "id TEXT PRIMARY KEY, course_id TEXT NOT NULL, kind TEXT NOT NULL, " +
                    "score REAL NOT NULL DEFAULT 0, weight REAL NOT NULL DEFAULT 0 )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_grades_course ON grades (course_id)"
            )
        } catch (t: Throwable) {
            android.util.Log.e("LibraryDb", "createGrades başarısız", t)
        }
    }

    /** ALTER TABLE ADD COLUMN — kolon zaten varsa sessizce geç (idempotent). */
    private fun addColumnSafe(db: SQLiteDatabase, table: String, col: String, def: String) {
        try {
            db.execSQL("ALTER TABLE $table ADD COLUMN $col $def")
        } catch (_: Throwable) {
            // kolon zaten eklenmiş — sorun değil
        }
    }

    private fun createBooks(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE ( " +
                "id TEXT PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "pageCount INTEGER NOT NULL, " +
                "createdAt INTEGER NOT NULL DEFAULT 0 )"
        )
    }

    /**
     * Akademik asistan tabloları (ADIM 2/3/4):
     * Course, Document (ders→kitap bağı), Exam, Homework, Schedule.
     * Aynı `CREATE TABLE IF NOT EXISTS` olduğundan şema her açılışta güvenlidir.
     * v16: `courses.ects` (AGNO) + `grades` tablosu da burada oluşturulur →
     * YENİ kurulumlarda migration'a gerek kalmadan şema tam olur.
     */
    private fun createAcademy(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS courses ( " +
                "id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL DEFAULT 0, " +
                // v16 (GÖREV 6.2): AKTS kredisi · (GÖREV 6): öğretim üyesi
                "ects INTEGER NOT NULL DEFAULT 0, " +
                "instructor TEXT NOT NULL DEFAULT '' )"
        )
        createGrades(db)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS documents ( " +
                "id TEXT PRIMARY KEY, course_id TEXT NOT NULL, book_id TEXT NOT NULL, " +
                "added_at INTEGER NOT NULL DEFAULT 0, " +
                // v14 (V5.0 §4): kaynak sınıflandırması
                "category TEXT NOT NULL DEFAULT 'book' )"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS exams ( " +
                "id TEXT PRIMARY KEY, course_id TEXT NOT NULL, title TEXT NOT NULL, " +
                "at_millis INTEGER NOT NULL, " +
                // v14 (V5.0 §5): sınav hatırlatma notu
                "note TEXT NOT NULL DEFAULT '', " +
                // v16 (YENİ GÖREV 2): tür (Vize/Quiz/Final/Bütünleme),
                // not etki oranı (%) ve hatırlatma bayrağı
                "type TEXT NOT NULL DEFAULT 'Vize', " +
                "weight INTEGER NOT NULL DEFAULT 0, " +
                "alarm INTEGER NOT NULL DEFAULT 1 )"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS homework ( " +
                "id TEXT PRIMARY KEY, course_id TEXT NOT NULL, title TEXT NOT NULL, " +
                "description TEXT NOT NULL DEFAULT '', due_millis INTEGER NOT NULL, " +
                "done INTEGER NOT NULL DEFAULT 0, " +
                // v16 (YENİ GÖREV 2): teslim hatırlatıcısı bayrağı
                "alarm INTEGER NOT NULL DEFAULT 1 )"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS schedule ( " +
                "id TEXT PRIMARY KEY, course_id TEXT NOT NULL, day INTEGER NOT NULL, " +
                "minute_of_day INTEGER NOT NULL, room TEXT NOT NULL DEFAULT '', " +
                "remind_min INTEGER NOT NULL DEFAULT 15, " +
                "duration_min INTEGER NOT NULL DEFAULT 120 )"
        )
    }

    /** Tüm kitaplar (en yeni eklenen önce). Hata durumunda boş liste. */
    fun readAll(): List<Book> {
        val out = ArrayList<Book>(16)
        val db = openSafe(writable = false) ?: return out
        db.rawQuery(
            "SELECT id, title, pageCount, createdAt FROM $TABLE ORDER BY createdAt DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Book(
                        id = c.getString(0),
                        title = c.getString(1),
                        pageCount = c.getInt(2),
                        createdAt = c.getLong(3)
                    )
                )
            }
        }
        return out
    }

    /**
     * Kitap listesini atomik olarak değiştirir (transaction).
     * Mevcut kayıt sayısı 0 ise hızlı geçer.
     */
    fun replaceAll(books: List<Book>) {
        val db = openSafe(writable = true) ?: return   // v8: JSON yedeği korunur
        db.beginTransaction()
        try {
            db.delete(TABLE, null, null)
            if (books.isNotEmpty()) {
                val cv = ContentValues()
                books.forEach { b ->
                    cv.clear()
                    cv.put("id", b.id)
                    cv.put("title", b.title)
                    cv.put("pageCount", b.pageCount)
                    cv.put("createdAt", b.createdAt)
                    db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
