package com.umay.pdfreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v5.10 (F2) — KONU BAZLI KALICI SOHBET GEÇMİŞİ.
 *
 * Sorun: sohbet ekranı her açılışta sıfırdan başlıyordu; uygulama kapanınca
 * ne soru ne cevap kalıyordu. Kullanıcı "lineer cebir" hakkında konuştuğu
 * geçmişi tekrar bulamıyordu.
 *
 * Çözüm:
 *  - Her soru bir KONUYA yönlendirilir (ders adı → konu sözlüğü → kitap adı → "Genel Sohbet").
 *  - Konu ilk sorudan otomatik tespit edilir; "konu: X" veya "/konu X" ile elle de verilebilir.
 *  - Mesajlar dosyada (filesDir/umay_chat_history.json) tutulur; uygulama kapansa da kalır.
 *  - Aynı konuya dönüldüğünde eski mesajlar yüklenir ve sohbet kaldığı yerden sürer.
 *  - Yazma atomiktir (tmp → rename) ve her adım try/catch içindedir: bozuk dosya
 *    uygulamayı ASLA çökertmez, yalnızca boş liste döner.
 */
object ChatHistoryStore {

    private const val FILE_NAME = "umay_chat_history.json"
    /** Disk şişmesin: en yeni 40 konu, konu başına son 120 mesaj saklanır. */
    private const val MAX_TOPICS = 40
    private const val MAX_MESSAGES = 120
    private const val MAX_TEXT = 4000

    /** Konu eşleşmesi tutmayan soruların gittiği varsayılan konu. */
    const val GENERIC = "Genel Sohbet"

    data class Message(val fromUser: Boolean, val text: String, val at: Long)

    data class Topic(
        val id: String,
        val title: String,
        val bookId: String,
        val updatedAt: Long,
        val messages: List<Message>
    ) {
        val messageCount: Int get() = messages.size

        /** Geçmiş listesinde tek satır önizleme. */
        val preview: String
            get() = messages.lastOrNull()?.text
                ?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim()?.take(90) ?: ""

        fun dateLabel(): String = try {
            SimpleDateFormat("d MMM HH:mm", Locale("tr", "TR")).format(Date(updatedAt))
        } catch (_: Throwable) {
            ""
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    // ── Okuma ────────────────────────────────────────────────

    /** Son kullanılan konu en üstte. */
    fun topics(ctx: Context): List<Topic> {
        val arr = readArray(ctx)
        val out = ArrayList<Topic>(arr.length())
        for (i in 0 until arr.length()) {
            parseTopic(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun load(ctx: Context, id: String): Topic? =
        topics(ctx).firstOrNull { it.id == id }

    /** Bu kitaba bağlı en son konuşma (kitaptan sohbet açılınca devam etmek için). */
    fun latestForBook(ctx: Context, bookId: String): Topic? =
        topics(ctx).firstOrNull { it.bookId == bookId }

    // ── Yazma ────────────────────────────────────────────────

    /**
     * Konuyu başlatır; aynı kimlik zaten varsa onu döndürür (eski mesajlarıyla).
     * Böylece "konu: lineer cebir" her yazıldığında yeni konu açılmaz.
     */
    fun startTopic(ctx: Context, title: String, bookId: String): Topic {
        val clean = title.trim().ifBlank { GENERIC }.take(60)
        val id = idFor(clean, bookId)
        val existing = load(ctx, id)
        if (existing != null) return existing
        val t = Topic(id, clean, bookId, System.currentTimeMillis(), emptyList())
        val list = topics(ctx) + t
        write(ctx, list)
        return t
    }

    /** Mesajı konuya ekler. Konu yoksa null döner (sessiz). */
    fun append(ctx: Context, topicId: String, fromUser: Boolean, text: String): Topic? {
        if (text.isBlank()) return null
        val list = topics(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == topicId }
        if (idx < 0) return null
        val old = list[idx]
        val msgs = (old.messages + Message(fromUser, text.take(MAX_TEXT), System.currentTimeMillis()))
            .takeLast(MAX_MESSAGES)
        val updated = old.copy(messages = msgs, updatedAt = System.currentTimeMillis())
        list[idx] = updated
        write(ctx, list)
        return updated
    }

    fun delete(ctx: Context, id: String) {
        write(ctx, topics(ctx).filterNot { it.id == id })
    }

    fun clearAll(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ChatHistoryStore.clearAll", t)
        }
    }

    /** Toplam istatistik (Özellik Künyesi için). */
    fun stats(ctx: Context): Pair<Int, Int> {
        val list = topics(ctx)
        return list.size to list.sumOf { it.messageCount }
    }

    // ── Konu tespiti ─────────────────────────────────────────

    /**
     * Sorudan konu başlığı üretir.
     * Sıra: açık "konu:" → ders adı → konu sözlüğü → kitap adı → "Genel Sohbet".
     */
    fun detectTitle(
        question: String,
        bookTitle: String?,
        courseNames: List<String>
    ): String {
        val q = norm(question)

        // 1) Kullanıcı konuyu elle verdiyse her şeyden önce gelir.
        Regex("(?:konu\\s*:|/konu\\s+)([^\\n]{2,60})")
            .find(question)
            ?.groupValues?.getOrNull(1)
            ?.trim()
            ?.trim('.', ',', '?', '!', ';')
            ?.takeIf { it.length >= 2 }
            ?.let { return it }

        // 2) Ders adı (kullanıcının kendi dersleri) — tüm anlamlı kelimeler geçmeli.
        // v5.11: ders KODU (BLG203, MAT101) soruda geçmese de eşleşsin;
        // yoksa "veri yapıları nedir" sorusu derse bağlanamıyordu.
        courseNames.filter { it.isNotBlank() }.forEach { name ->
            val tokens = norm(name).split(' ')
                .filter { it.length >= 3 && it.none { c -> c.isDigit() } }
            if (tokens.isNotEmpty() && tokens.all { q.contains(it) }) return name.trim()
        }

        // 3) Konu sözlüğü (ders adı yazılmadan sorulan genel sorular).
        for ((topic, keys) in KEYWORDS) {
            if (keys.any { q.contains(it) }) return topic
        }

        // 4) Açık kitabın adı geçiyorsa o kitabın konuşması.
        if (!bookTitle.isNullOrBlank()) {
            val tokens = norm(bookTitle).split(' ').filter { it.length >= 4 }
            if (tokens.any { q.contains(it) }) return bookTitle.trim().take(60)
        }

        return GENERIC
    }

    /**
     * v5.11 — Tespit edilen başlığı gerçek bir ders adıyla eşleştirir.
     * "Veri Yapıları" (sözlük) → "BLG203 Veri Yapıları" (kullanıcının dersi).
     * Eşleşme yoksa null (o zaman konu değiştirilmez).
     */
    fun matchCourse(title: String, courseNames: List<String>): String? {
        val t = norm(title).trim()
        if (t.isBlank()) return null
        val clean = courseNames.filter { it.isNotBlank() }
        clean.firstOrNull { norm(it).trim() == t }?.let { return it.trim() }
        if (t.length >= 5) {
            clean.firstOrNull { norm(it).contains(t) }?.let { return it.trim() }
        }
        clean.firstOrNull { t.contains(norm(it).trim()) && norm(it).trim().length >= 5 }
            ?.let { return it.trim() }
        return null
    }

    /** Konu kimliği: başlık + kitap (kitap bazlı konuşmalar ayrı kalsın). */
    fun idFor(title: String, bookId: String): String =
        norm(title).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48) + "|" + bookId

    private fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (raw in s.lowercase(Locale.ROOT)) {
            when (raw) {
                'ı', 'î', 'i' -> sb.append('i')
                'ş' -> sb.append('s')
                'ğ' -> sb.append('g')
                'ü', 'û' -> sb.append('u')
                'ö' -> sb.append('o')
                'ç' -> sb.append('c')
                'â' -> sb.append('a')
                '\u0307' -> { /* İ → i + birleşik nokta: yok say */ }
                else -> sb.append(raw)
            }
        }
        return sb.toString()
    }

    /** Konu sözlüğü — anahtarlar Türkçe karaktersiz/normalize yazılır. */
    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "Matematik" to listOf("matematik", "turev", "integral", "limit", "denklem", "fonksiyon", "matris", "determinant"),
        "Lineer Cebir" to listOf("lineer cebir", "linear algebra", "vektor uzayi", "ozdeger", "ozvektor"),
        "İstatistik" to listOf("istatistik", "olasilik", "standart sapma", "varyans", "hipotez"),
        "Fizik" to listOf("fizik", "kuvvet", "hiz", "ivme", "enerji", "momentum", "elektrik", "manyetizma"),
        "Kimya" to listOf("kimya", "mol", "asit", "baz", "reaksiyon", "bilesik", "organik"),
        "Biyoloji" to listOf("biyoloji", "hucre", "dna", "genetik", "protein", "enzim"),
        "Programlama" to listOf("programlama", "kod", "yazilim", "python", "java", "kotlin", "javascript", "algoritma", "fonksiyon yaz", "hata ayikla", "debug"),
        "Veri Yapıları" to listOf("veri yapilari", "bagli liste", "stack", "queue", "agac", "hash tablosu", "siralama algoritmasi"),
        "Veri Tabanı" to listOf("veri tabani", "sql", "sorgu", "tablo olustur", "primary key", "normalizasyon"),
        "Yapay Zekâ" to listOf("yapay zeka", "makine ogrenmesi", "derin ogrenme", "sinir agi", "model egitimi", "llm"),
        "Sınav Hazırlığı" to listOf("sinav", "vize", "final", "butunleme", "quiz", "cikmis soru", "deneme sinavi"),
        "Ödev / Proje" to listOf("odev", "proje", "rapor yaz", "sunum hazirla", "teslim"),
        "İngilizce" to listOf("ingilizce", "ceviri", "translate", "gramer", "kelime ogren"),
        "Uygulama Yardımı" to listOf("uygulama", "umay", "nasil kullan", "ayar", "ozellik", "ekolayzir", "tts", "sesli okuma")
    )

    // ── JSON ─────────────────────────────────────────────────

    private fun readArray(ctx: Context): JSONArray {
        return try {
            val f = file(ctx)
            if (!f.exists() || f.length() == 0L) JSONArray() else JSONArray(f.readText())
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ChatHistoryStore.read", t)
            JSONArray()
        }
    }

    private fun parseTopic(o: JSONObject?): Topic? {
        if (o == null) return null
        val id = o.optString("id", "")
        val title = o.optString("title", "").ifBlank { GENERIC }
        if (id.isBlank()) return null
        val msgs = ArrayList<Message>()
        val arr = o.optJSONArray("m")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val text = m.optString("t", "")
                if (text.isBlank()) continue
                msgs.add(Message(m.optBoolean("u", false), text, m.optLong("at", 0L)))
            }
        }
        return Topic(
            id = id,
            title = title,
            bookId = o.optString("book", ""),
            updatedAt = o.optLong("at", 0L),
            messages = msgs
        )
    }

    private fun write(ctx: Context, list: List<Topic>) {
        try {
            val arr = JSONArray()
            list.sortedByDescending { it.updatedAt }.take(MAX_TOPICS).forEach { t ->
                val o = JSONObject()
                o.put("id", t.id)
                o.put("title", t.title)
                o.put("book", t.bookId)
                o.put("at", t.updatedAt)
                val msgs = JSONArray()
                t.messages.takeLast(MAX_MESSAGES).forEach { m ->
                    msgs.put(
                        JSONObject()
                            .put("u", m.fromUser)
                            .put("t", m.text.take(MAX_TEXT))
                            .put("at", m.at)
                    )
                }
                o.put("m", msgs)
                arr.put(o)
            }
            val dst = file(ctx)
            val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(dst)) {
                dst.writeText(arr.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            BookStore.logError(ctx, "ChatHistoryStore.write", t)
        }
    }
}
