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

    companion object {
        private const val DB_NAME = "library.db"
        private const val DB_VERSION = 1
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
        db.execSQL(
            "CREATE TABLE $TABLE ( " +
                "id TEXT PRIMARY KEY, " +
                "title TEXT NOT NULL, " +
                "pageCount INTEGER NOT NULL, " +
                "createdAt INTEGER NOT NULL DEFAULT 0 )"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Sürüm 1 — ileride sütun eklenirse burada ALTER TABLE yapılır.
    }

    /** Tüm kitaplar (en yeni eklenen önce). Hata durumunda boş liste. */
    fun readAll(): List<Book> {
        val out = ArrayList<Book>(16)
        readableDatabase.rawQuery(
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
        val db = writableDatabase
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
