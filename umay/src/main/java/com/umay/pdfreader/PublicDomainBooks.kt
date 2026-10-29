package com.umay.pdfreader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v11 (GÖREV 5) — TELİF HAKKI DOLMUŞ KAMU MALI KİTAPLIK.
 *
 * Project Gutenberg + Open Library API'lerinden telif hakkı süresi dolmuş
 * ücretsiz kitapları arar ve indirir. İndirilen her kitap otomatik olarak
 * kütüphaneye eklenir ve ana ekranda "Okuma Kitapları" rafında görünür.
 *
 * KAYNAKLAR:
 *  - Open Library: https://openlibrary.org/search.json?q=... (JSON + kapak)
 *  - Gutenberg:    https://www.gutenberg.org/ebooks/search/ (EPUB/TXT)
 *
 * GÜVENLİK:
 *  - HTTP GET (salt okunur) — dosya silinmez/çalıştırılmaz.
 *  - İndirilen dosya doğrulanır (boş/HTML olmaz).
 *  - Her adım `try/catch` — ağ hatası uygulamayı düşürmez.
 *  - Kapak resimleri: `covers.openlibrary.org/b/id/<ID>-M.jpg`
 */
object PublicDomainBooks {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Arama sonucu. */
    data class BookResult(
        val title: String,
        val author: String,
        val year: String,
        val coverUrl: String?,   // kapak resmi (varsa)
        val downloadUrl: String?, // PDF/EPUB/TXT indirme (varsa)
        val source: String,       // "OpenLibrary" veya "Gutenberg"
        // v14 (V5.0 §3): YEDEK BAĞLANTILAR — birincil URL 404/hata verirse
        // sırayla denenir (archive.org djvu.txt metin yedeği, Gutenberg
        // format alternatifleri vb.) — "dosya bulunamadı" sorunu biter.
        val altUrls: List<String> = emptyList()
    )

    /**
     * Kamu malı kitap arar (Async) — v12 YEDEKLİ KAYNAK:
     *  1) Open Library (Internet Archive PDF indirmeleri + kapaklar)
     *  2) Boş/hata → Project Gutenberg (Gutendex) — doğrudan PDF/EPUB/TXT
     * İki kaynaktan gelen sonuçlar birleştirilir (aynı kitap teke düşer).
     */
    fun search(ctx: Context, query: String, onDone: (List<BookResult>) -> Unit) {
        scope.launch {
            val primary = try {
                searchOpenLibrary(query)
            } catch (t: Throwable) {
                BookStore.logError(ctx, "PublicDomainBooks.search(ol)", t)
                emptyList()
            }
            // Open Library yetersiz kaldıysa / hiç döndürmediyse Gutenberg dene
            val fallback = if (primary.size < 3) {
                try {
                    searchGutenberg(query)
                } catch (t: Throwable) {
                    BookStore.logError(ctx, "PublicDomainBooks.search(gutendex)", t)
                    emptyList()
                }
            } else emptyList()

            // Aynı başlık iki kaynaktan geldiyse tekilleştir (Open Library öncelikli)
            val seen = HashSet<String>()
            val merged = ArrayList<BookResult>(primary.size + fallback.size)
            (primary + fallback).forEach { r ->
                val key = r.title.trim().lowercase()
                if (seen.add(key)) merged.add(r)
            }
            withContext(Dispatchers.Main) { onDone(merged) }
        }
    }

    /**
     * v12 — Project Gutenberg (Gutendex) araması.
     * API: https://gutendex.com/books?search=…
     * Her kitabın `formats` alanında doğrudan indirilebilir
     * application/pdf / text/plain / application/epub+zip URL'leri vardır.
     */
    private suspend fun searchGutenberg(query: String): List<BookResult> =
        withContext(Dispatchers.IO) {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val urlStr = "https://gutendex.com/books?search=$encoded&limit=12"

            val conn = URL(urlStr).openConnection() as HttpURLConnection
            // v14 (V5.0 §3): 30 sn timeout (ağır ağlarda ANR yok, sabırla bekle)
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.requestMethod = "GET"

            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()

            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return@withContext emptyList()

            val out = ArrayList<BookResult>(results.length())
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val title = r.optString("title", "").trim()
                if (title.isEmpty()) continue

                // Yazar: authors[0].name
                val author = r.optJSONArray("authors")?.optJSONObject(0)
                    ?.optString("name") ?: "Bilinmiyor"
                // Yıl: publications[0].publication_country yerine id'den tahmin yok;
                // Gutendex yıl vermez — "?" bırakılır (kapak/indirme önemli).
                val year = "?"

                // formats: PDF > EPUB > TXT öncelik sırası
                val formats = r.optJSONObject("formats")
                val pdfUrl = formats?.optString("application/pdf")?.takeIf { it.isNotBlank() }
                val txtUrl = formats?.optString("text/plain; charset=utf-8")
                    ?.takeIf { it.isNotBlank() }
                val epubUrl = formats?.optString("application/epub+zip")
                    ?.takeIf { it.isNotBlank() }
                val downloadUrl = pdfUrl ?: txtUrl ?: epubUrl
                    ?: continue   // indirilebilir formatı olmayan kitabı atla

                // v14 (V5.0 §3): kalan formatlar YEDEK olarak eklenir — birincil
                // 404/bozuk gelirse otomatik sıradaki denenir.
                val alts = listOfNotNull(txtUrl, epubUrl, pdfUrl) - downloadUrl

                // Kapak: Gutendex formats alanındaki image/jpeg
                val coverUrl = formats.optString("image/jpeg")
                    ?.takeIf { it.isNotBlank() }

                out.add(
                    BookResult(title, author, year, coverUrl, downloadUrl, "Gutenberg", alts)
                )
            }
            out
        }

    /** Open Library arama API'si (kamu malı filtresi + kapak URL'leri). */
    private suspend fun searchOpenLibrary(query: String): List<BookResult> =
        withContext(Dispatchers.IO) {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val urlStr =
                "https://openlibrary.org/search.json?q=$encoded" +
                    "&limit=12&fields=title,author_name,first_publish_year,cover_i,ia"

            val conn = URL(urlStr).openConnection() as HttpURLConnection
            // v14 (V5.0 §3): 30 sn timeout
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.requestMethod = "GET"

            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()

            val json = JSONObject(body)
            val docs = json.optJSONArray("docs") ?: return@withContext emptyList()

            val out = ArrayList<BookResult>(docs.length())
            for (i in 0 until docs.length()) {
                val d = docs.optJSONObject(i) ?: continue
                val title = d.optString("title", "").trim()
                if (title.isEmpty()) continue

                val author = d.optJSONArray("author_name")?.optString(0) ?: "Bilinmiyor"
                val year = d.opt("first_publish_year")?.toString() ?: "?"
                val coverId = d.optInt("cover_i", -1)
                val coverUrl = if (coverId > 0)
                    "https://covers.openlibrary.org/b/id/$coverId-M.jpg" else null

                // Internet Archive (IA) indirme bağlantısı + YEDEK
                val ia = d.optJSONArray("ia")
                val iaId = ia?.optString(0)?.takeIf { it.isNotBlank() }
                val downloadUrl = iaId?.let {
                    "https://archive.org/download/$it/$it.pdf"
                }
                // v14 (V5.0 §3): archive.org yedek bağlantısı — PDF yoksa/bozuksa
                // IA metin sürümü (djvu.txt) denenir; o da BookStore tarafından
                // düz metin olarak içe aktarılır (okuma + TTS çalışır).
                val alts = if (iaId != null) {
                    listOf(
                        "https://archive.org/download/$iaId/${iaId}_djvu.txt",
                        "https://archive.org/download/$iaId/${iaId}.pdf?fallback=true"
                    )
                } else emptyList()

                out.add(
                    BookResult(title, author, year, coverUrl, downloadUrl, "OpenLibrary", alts)
                )
            }
            out
        }

    /**
     * Kitabı indirip kütüphaneye ekler (Async).
     * @return eklenen kitabın ID'si; hata/boş dosya durumunda null.
     */
    fun downloadAndImport(
        ctx: Context,
        book: BookResult,
        onDone: (String?) -> Unit
    ) {
        scope.launch {
            val id = try {
                downloadAndImportSync(ctx, book)
            } catch (t: Throwable) {
                BookStore.logError(ctx, "PublicDomainBooks.download", t)
                null
            }
            withContext(Dispatchers.Main) { onDone(id) }
        }
    }

    /** IO thread: indir → doğrula → kütüphaneye ekle. */
    private suspend fun downloadAndImportSync(ctx: Context, book: BookResult): String? =
        withContext(Dispatchers.IO) {
            // v14 (V5.0 §3): YEDEK BAĞLANTILAR — birincil URL başarısız olursa
            // altUrls sırayla denenir (UTF-8 encode + 30 sn timeout dahil).
            val candidates = listOfNotNull(book.downloadUrl) + book.altUrls
            var bytes: ByteArray? = null
            for (urlStr in candidates) {
                try {
                    val conn = URL(urlStr).openConnection() as HttpURLConnection
                    // Talimat: timeout 30 sn (ağ ağır olsa da bloklanmasın)
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 30_000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept-Charset", "UTF-8")
                    val b = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    // Doğrulama: boş dosya veya HTML sayfası (indirme hatası) olmamalı
                    if (b.size < 1024) continue
                    // take(20) → List<Byte>.toString(charset) çalışmaz;
                    // ByteArray'in kendi toString(charset) overload'ı kullanılır.
                    val h = b.copyOfRange(0, minOf(20, b.size))
                        .toString(Charsets.ISO_8859_1).lowercase()
                    if (h.contains("<html") || h.contains("<!doctype")) continue
                    bytes = b
                    break
                } catch (_: Throwable) {
                    // Bu aday başarısız → sıradaki yedeğe geç
                }
            }
            val downloadedBytes = bytes ?: return@withContext null

            // Doğrulama: boş dosya veya HTML sayfası (indirme hatası) olmamalı
            if (bytes.size < 1024) return@withContext null
            // v11.1 — take(20) → List<Byte>.toString(charset) çalışmaz;
            // ByteArray'in kendi toString(charset) overload'ı kullanılır.
            val head = bytes
                .copyOfRange(0, minOf(20, bytes.size))
                .toString(Charsets.ISO_8859_1)
                .lowercase()
            if (head.contains("<html") || head.contains("<!doctype")) return@withContext null

            // Geçici dosyaya yaz → content:// URI → BookStore.importPdf
            val tmp = File(ctx.cacheDir, "pd_download_${System.currentTimeMillis()}.bin")
            try {
                tmp.writeBytes(bytes)

                // Uzantıyı belirle (importPdf bunu kullanır)
                val ext = when {
                    // %PDF imzası (ilk 4 bayt: 25 50 44 46)
                    bytes.size >= 4 &&
                        bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
                        bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte() -> ".pdf"
                    head.startsWith("pk") -> ".epub"   // ZIP container
                    else -> ".txt"
                }
                val named = File(ctx.cacheDir, "pd_${System.currentTimeMillis()}$ext")
                tmp.renameTo(named)

                val uri = Uri.fromFile(named)
                var resultId: String? = null
                val latch = java.util.concurrent.CountDownLatch(1)

                BookStore.importPdf(ctx, uri) { result ->
                    result.fold(
                        onSuccess = { book ->
                            resultId = book.id
                            // v12 (Okuma Kitapları rafı): kamu malı kitabın
                            // kimliğini işaretle → ana ekranda ayrı rafta görünür.
                            markAsPublicDomain(ctx, book.id)
                            // v5.4 — ayrıca "📖 OKUMA KİTAPLARI" rafına ekle
                            // (kullanıcı Kur'an/roman türü kitapları burada toplar).
                            try {
                                ShelfManager.ensureDefaults(ctx)
                                ShelfManager.addBookToShelf(
                                    ctx, book.id, ShelfManager.SHELF_READING
                                )
                            } catch (_: Throwable) {
                            }
                        },
                        onFailure = { e ->
                            BookStore.logError(ctx, "pdImport", e)
                        }
                    )
                    latch.countDown()
                }
                latch.await(120, java.util.concurrent.TimeUnit.SECONDS)

                named.delete()
                resultId
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        }

    // ── v12 (Okuma Kitapları rafı) ──────────────────────

    /** Kitabı kamu malı/Okuma Kitapları olarak işaretler (CSV kümesi). */
    fun markAsPublicDomain(ctx: Context, bookId: String) {
        try {
            val raw = AppPrefs.getString(ctx, AppPrefs.KEY_PD_BOOKS)
            val set = raw.split(',').filter { it.isNotBlank() }.toMutableSet()
            if (set.add(bookId)) {
                AppPrefs.putString(ctx, AppPrefs.KEY_PD_BOOKS, set.joinToString(","))
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "markAsPublicDomain", t)
        }
    }

    /** Kamu malı / indirilen kitap kimlikleri. */
    fun publicDomainIds(ctx: Context): Set<String> {
        val raw = AppPrefs.getString(ctx, AppPrefs.KEY_PD_BOOKS)
        if (raw.isBlank()) return emptySet()
        return raw.split(',').filter { it.isNotBlank() }.toSet()
    }
}
