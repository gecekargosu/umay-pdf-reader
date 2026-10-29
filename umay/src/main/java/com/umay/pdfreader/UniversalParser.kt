package com.umay.pdfreader

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * v17 — **EVRENSEL DOSYA VE METİN OKUMA PARSER'I** (GÖREV 1).
 *
 * NEDEN VAR:
 * Eski kodda format sınıflandırması dağınıktı: uzantıya bakılıp
 * `DocumentConverter`'a geçiliyordu, dosyanın İÇERİĞİ (magic bytes) hiç
 * okunmuyordu. Sonuç: uzantısı PDF olan ama aslında metin olan dosyalarda
 * `PDDocument.load` patlıyor, desteklenmeyen uzantıdaki `.tex` (LaTeX)
 * veya Kur'an metni tamamen reddediliyordu.
 *
 * Bu sınıfın görevleri (hepsi ASLA fırlatmaz):
 *  1. **İçerik sınıflandırması** — uzantıya güvenme, magic byte'a bak:
 *     `%PDF-`, `PK` (ZIP/DOCX/PPTX/EPUB) ya da düz metin.
 *  2. **Güvenli kodlama çözümü** — UTF-8 (BOM'lu/BOM'suz), UTF-16 LE/BE,
 *     Windows-1256 (Arapça), Windows-1254 (Türkçe) geri düşüşü.
 *     Böylece Kur'an-ı Kerim / Arapça metinler bozuk karakter (�) yerine
 *     doğru okunur.
 *  3. **İçerik analizi** — Arapça/RTL, LaTeX ve kod (indentasyon) izleri.
 *  4. **Güvenli parse** — parser patlasa bile hata yutulur, çağırana
 *     `Outcome(ok=false, error=…)` döner; uygulama çökmez.
 *
 * NEDEN YENİ KÜTÜPHANE YOK: Apache POI gibi ağır DOC/PPT kütüphaneleri
 * offline derlemeyi (metro modu) kırar ve APK'yı şişirir; mevcut yığın
 * (PDFBox + DocumentConverter'ın zip-XML parser'ları + buradaki kodlama
 * çözümü) görevdeki TÜM formatları kapsar.
 */
object UniversalParser {

    /** Kodlama çözümü sonucu. */
    data class Decoded(val text: String, val charset: String)

    /** Metin içeriğinin özelliği (TTS + raporlama sinyalleri). */
    data class Features(
        val arabic: Boolean,   // Arapça/İbranice/RTL blok
        val latex: Boolean,    // $..$, \frac, \begin{} vb.
        val code: Boolean,     // girintili (indent) kod satırları
        val charset: String
    )

    /** Güvenli parse sonucu. */
    data class Outcome(
        val paragraphs: List<String>,
        val features: Features,
        val ok: Boolean,
        val error: Throwable? = null
    )

    /** OOM koruması: metin çıkarımında okunacak en fazla bayt (4 MB). */
    private const val MAX_TEXT_BYTES = 4 * 1024 * 1024

    // ── 1) Magic byte sınıflandırma ─────────────────────────────

    /** Dosyanın ilk byte'larını okur (hatasız; okunamazsa boş). */
    private fun head(src: File, n: Int): ByteArray = try {
        src.inputStream().use { s ->
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = s.read(buf, read, n - read)
                if (r < 0) break
                read += r
            }
            if (read == n) buf else buf.copyOf(read)
        }
    } catch (_: Throwable) {
        ByteArray(0)
    }

    /** İçerik gerçekten PDF mi? (uzantıya güvenme) */
    fun isPdf(src: File): Boolean = try {
        String(head(src, 1024), Charsets.ISO_8859_1).contains("%PDF-")
    } catch (_: Throwable) {
        false
    }

    /** İçerik ZIP mi? (DOCX/PPTX/EPUB = PK\x03\x04) */
    fun isZip(src: File): Boolean = try {
        val h = head(src, 4)
        h.size >= 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() &&
            (h[2] == 3.toByte() || h[2] == 5.toByte() || h[2] == 7.toByte())
    } catch (_: Throwable) {
        false
    }

    /** Dosya düz metin gibi mi görünüyor? (uzantı bağımsız) */
    fun looksLikeText(src: File): Boolean {
        return try {
            if (isPdf(src) || isZip(src)) return false
            val h = head(src, 16 * 1024)
            if (h.isEmpty()) return false
            // UTF-16 BOM'luysa yine metindir
            if (h.size >= 2 && (
                    (h[0] == 0xFF.toByte() && h[1] == 0xFE.toByte()) ||
                        (h[0] == 0xFE.toByte() && h[1] == 0xFF.toByte())
                    )
            ) return true
            val nul = h.count { it == 0.toByte() }
            // NUL byte oranı yüksekse ikili dosya (UTF-16 BOM yukarıda ele alındı)
            if (nul * 50 > h.size) return false
            // Kontrol karakterleri (tab/newline/carriage-return hariç) > %2 → ikili
            val ctrl = h.count {
                val v = it.toInt() and 0xFF
                v < 0x09 || v in 0x0E..0x1F
            }
            ctrl * 50 <= h.size
        } catch (_: Throwable) {
            false
        }
    }

    /** Uzantı + içerik birlikte: bu dosya metin parser'ından geçebilir mi? */
    fun isTextConvertible(src: File, name: String): Boolean {
        if (DocumentConverter.isSupported(name)) return true
        val ext = DocumentConverter.extension(name)
        if (ext.isEmpty()) return false   // uzantısızsa içerik PDF değilse yine de dene
        return looksLikeText(src)
    }

    // ── 2) Güvenli kodlama çözümü ───────────────────────────────

    /** Belirli charset'te katı çözümleme; charset yoksa/bozuksa null. */
    private fun tryCharset(bytes: ByteArray, cs: String): String? = try {
        val dec = Charset.forName(cs).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        dec.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    } catch (_: Throwable) {
        null
    }

    /**
     * Byte dizisini doğru kodlamayla çözer. ÖNCELİK SIRASI:
     *  UTF-8 BOM → UTF-16 BOM → UTF-16 NUL sezgisi → katı UTF-8 →
     *  windows-1256 (Arapça) → windows-1254 (Türkçe) → ISO-8859-1 (son çare).
     */
    fun decode(bytes: ByteArray): Decoded {
        return try {
            if (bytes.isEmpty()) return Decoded("", "empty")

            // BOM kontrolü
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            ) {
                val body = bytes.copyOfRange(3, bytes.size)
                return Decoded(tryCharset(body, "UTF-8") ?: String(body, Charsets.UTF_8), "UTF-8-BOM")
            }
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                val body = bytes.copyOfRange(2, bytes.size)
                return Decoded(
                    tryCharset(body, "UTF-16LE") ?: String(body, Charsets.UTF_16LE),
                    "UTF-16LE-BOM"
                )
            }
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                val body = bytes.copyOfRange(2, bytes.size)
                return Decoded(
                    tryCharset(body, "UTF-16BE") ?: String(body, Charsets.UTF_16BE),
                    "UTF-16BE-BOM"
                )
            }

            // UTF-16 sezgisi: BOM yok ama her ikinci byte NUL ise
            if (bytes.size >= 4) {
                val sample = bytes.copyOfRange(0, minOf(bytes.size, 4096))
                val half = sample.size / 2
                val evenNul = (0 until half).count { sample[it * 2] == 0.toByte() }
                val oddNul = (0 until half).count { sample[it * 2 + 1] == 0.toByte() }
                if (oddNul > half * 3 / 4) {
                    tryCharset(sample, "UTF-16LE")?.let { return Decoded(it, "UTF-16LE") }
                }
                if (evenNul > half * 3 / 4) {
                    tryCharset(sample, "UTF-16BE")?.let { return Decoded(it, "UTF-16BE") }
                }
            }

            // Katı UTF-8 (en yaygın; Türkçe + Kur'an UTF-8 dosyaları buradan geçer)
            tryCharset(bytes, "UTF-8")?.let { return Decoded(it, "UTF-8") }

            // Bozuk UTF-8 → dil bazlı geri düşüş
            tryCharset(bytes, "windows-1256")?.let { return Decoded(it, "windows-1256") }
            tryCharset(bytes, "windows-1254")?.let { return Decoded(it, "windows-1254") }
            Decoded(String(bytes, Charsets.ISO_8859_1), "ISO-8859-1")
        } catch (_: Throwable) {
            // Hata durumunda bile bozuk metin üret (çökme yok)
            try {
                Decoded(String(bytes, Charsets.ISO_8859_1), "ISO-8859-1-fallback")
            } catch (_: Throwable) {
                Decoded("", "unreadable")
            }
        }
    }

    /** Dosyayı güvenli şekilde çözümler (ilk MAX_TEXT_BYTES bayt). */
    fun decodeFile(src: File): Decoded = try {
        val bytes = src.inputStream().use { s ->
            s.readNBytesCompat(MAX_TEXT_BYTES)
        }
        decode(bytes)
    } catch (_: Throwable) {
        Decoded("", "unreadable")
    }

    /** Java 8 uyumlu readNBytes (API 26'da InputStream.readNBytes yok). */
    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < n) {
            val r = read(buf, 0, minOf(buf.size, n - total))
            if (r < 0) break
            out.write(buf, 0, r)
            total += r
        }
        return out.toByteArray()
    }

    // ── 3) İçerik analizi (TTS + raporlama sinyalleri) ──────────

    private fun hasArabicBlock(text: String): Boolean {
        var checked = 0
        for (ch in text) {
            val c = ch.code
            if (c in 0x0600..0x06FF || c in 0x0750..0x077F ||
                c in 0x08A0..0x08FF || c in 0xFB50..0xFDFF ||
                c in 0xFE70..0xFEFF
            ) return true
            if (++checked > 65536) break   // dev metinlerde tarama bütçesi
        }
        return false
    }

    private fun hasLatex(text: String): Boolean {
        if (text.contains("\\frac") || text.contains("\\sqrt") ||
            text.contains("\\begin{") || text.contains("\\hat{") ||
            text.contains("\\text{") || text.contains("\\sum")
        ) return true
        // iki $ işareti → satır içi matematik notasyonu
        return text.count { it == '$' } >= 2
    }

    private fun hasCodeBlock(text: String): Boolean {
        var indented = 0
        for (line in text.lineSequence()) {
            if (line.startsWith("\t") || line.startsWith("    ")) {
                if (++indented >= 3) return true
            }
        }
        return false
    }

    /** Metnin özelliklerini çıkar (TTS sanitizasyonu ve log satırı için). */
    fun features(text: String, charset: String = "unknown"): Features = Features(
        arabic = hasArabicBlock(text),
        latex = hasLatex(text),
        code = hasCodeBlock(text),
        charset = charset
    )

    /** Log satırı için tek cümlelik tanım: `tur=txt kodlama=UTF-8 arapca=true…` */
    fun describe(kind: String, f: Features): String =
        "tur=$kind kodlama=${f.charset} arapca=${f.arabic} latex=${f.latex} kod=${f.code}"

    // ── 4) Güvenli parse (asla fırlatmaz) ───────────────────────

    /**
     * Dosyayı güvenli biçimde paragraflara böler.
     *
     * - PDF: `ok=true` ve boş liste (PDF metin katmanı BookStore/Reader
     *   tarafında PDFBox + OCR ile çıkarılır; burada yalnızca sınıflandırma).
     * - Metin (TXT/MD/CSV/LaTeX/…): kodlama çözülür, satırlar OLDUĞU GİBİ
     *   korunur — indentasyon bozulmaz (kod kitapları monospaced akar).
     * - DOCX/PPTX/EPUB/DOC/PPT: `DocumentConverter` parser'ları çağrılır.
     * - Herhangi bir parser patlarsa hata yutulur, `ok=false` + `error`
     *   döner; istenirse `onError` ile `CrashLog.recordParse`'e yazılır.
     */
    fun safeParagraphs(
        src: File,
        name: String,
        onError: ((where: String, t: Throwable) -> Unit)? = null
    ): Outcome {
        return try {
            if (!src.exists() || !src.isFile) {
                // Dosya yok/erişilemiyor → yutma, net hata döndür (çökme yok)
                val e = java.io.FileNotFoundException("Dosya yok veya okunamıyor: ${src.name}")
                onError?.invoke("UniversalParser.safeParagraphs(missing)", e)
                return Outcome(
                    emptyList(), Features(false, false, false, "missing"),
                    ok = false, error = e
                )
            }
            if (isPdf(src)) {
                return Outcome(emptyList(), Features(false, false, false, "pdf"), ok = true)
            }
            val ext = DocumentConverter.extension(name)
            if (ext.isEmpty() || !looksLikeText(src)) {
                // DOCX/PPTX/EPUB/DOC/PPT (ZIP veya ikili) → DocumentConverter
                val paras = DocumentConverter.paragraphs(src, name)
                val sample = paras.take(50).joinToString("\n")
                return Outcome(paras, features(sample, "zip/xml"), ok = true)
            }
            // Düz metin (TXT/MD/CSV/LaTeX/Kur'an/…) → kodlama çöz + satırları koru
            val decoded = decodeFile(src)
            val paras = decoded.text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
            Outcome(paras, features(decoded.text.take(65536), decoded.charset), ok = true)
        } catch (t: Throwable) {
            onError?.invoke("UniversalParser.safeParagraphs($name)", t)
            Outcome(
                emptyList(),
                Features(false, false, false, "error"),
                ok = false,
                error = t
            )
        }
    }
}
