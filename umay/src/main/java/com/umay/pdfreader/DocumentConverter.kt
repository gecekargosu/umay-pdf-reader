package com.umay.pdfreader

import android.util.Xml
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * PDF dışındaki dosyaları PDF'e çevirir:
 *   TXT, MD, CSV, LOG, DOCX, **PPTX**  →  source.pdf
 *   Eski ikili formatlar DOC / PPT      →  best-effort metin çıkarımı
 *
 * Böylece tüm dosya türleri AYNI okuma akışından geçer:
 *   kaynak → source.pdf → metin çıkarma (PDFBox) → görüntü (PdfRenderer) → TTS
 *
 * Metin, cihazda zaten bulunan Roboto fontuyla (Türkçe tam destekli) sayfaya
 * yazılır; satırlar otomatik bölünür, sayfa başına sığdırılır.
 */
object DocumentConverter {

    /**
     * JVM unit test'te de çalışan pull parser üretir.
     * `android.util.Xml.newPullParser()` test ortamında "not mocked" hatası
     * veriyor; XmlPullParserFactory kxml2'yi hem cihazda hem JVM'de bulur.
     */
    private fun newXmlPullParser(): XmlPullParser =
        org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()


    private val SUPPORTED = setOf("txt", "md", "markdown", "csv", "log", "docx", "pptx", "doc", "ppt", "epub")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isSupported(name: String): Boolean = SUPPORTED.contains(extension(name))

    /**
     * v37 — SLAYT/SINIF SINIRI.
     *
     * Sunum metni tek A4'e sığdığında 60 slaytlık ders "1 sayfa" oluyordu;
     * sayfa numarası, okunma yüzdesi ve ilerleme anlamsızlaşıyordu. Her slayt
     * artık kendi sayfasında başlar.
     */
    internal const val PAGE_BREAK = "\u0000UMAY_SAYFA\u0000"

    /**
     * v38 — BELGE ÇEVRİMİ KAPATILDI.
     *
     * Kullanıcı isteği: DOCX/PPTX/DOC/PPT dosyaları PDF'e çevrilmesin; orijinal
     * dosya (grafikler, renkler, şekiller) hiç değişmesin. Bu türler artık
     * `BookStore` içinde BELGE MODUNDA saklanır. Buradaki koruma, ileride
     * yanlışlıkla yeniden açılmasın diye var.
     */
    private fun ensureNotOfficeDoc(ext: String, name: String) {
        if (ext in setOf("docx", "pptx", "doc", "ppt")) {
            throw IllegalStateException(
                "'$name' belge biçimi PDF'e çevrilmez (orijinal korunur)"
            )
        }
    }

    /** Desteklenen dosyayı PDF'e çevirir; sayfa sayısını döndürür. */
    fun convert(src: File, dest: File, name: String): Int {
        val ext = effectiveExtension(src, name)
        ensureNotOfficeDoc(ext, name)
        // PPTX'te her slayt yeni sayfa; diğer türlerde metin akışı korunur.
        val paragraphs = if (ext == "pptx") pptxParagraphs(src, markSlides = true)
        else paragraphs(src, name)
        val title = name.substringBeforeLast('.')
        // PPTX'te ayrı başlık sayfası YOK: sayfa n = slayt n olsun.
        return writePdf(paragraphs, dest, title, withTitlePage = ext != "pptx")
    }

    /**
     * v17 (EVRENSEL PARSER — GÖREV 1): Dosyadan ham paragraf listesi çıkarır
     * (PDF'e çevirmeden). `UniversalParser.safeParagraphs` bunu çağırır;
     * hata yutulmaz → çağıran taraf `CrashLog.recordParse` ile loglar.
     */
    internal fun paragraphs(src: File, name: String): List<String> {
        val ext = effectiveExtension(src, name)
        return when (ext) {
            "docx" -> docxParagraphs(src)
            "pptx" -> pptxParagraphs(src)
            "doc", "ppt" -> legacyParagraphs(src, ext)
            "epub" -> epubParagraphs(src)
            else -> textParagraphs(src)
        }
    }

    /** ZIP tabanlı (paket) biçimler — uzantısı ne olursa olsun ZIP olmalı. */
    private val ZIP_ONLY = setOf("docx", "pptx", "epub")

    /**
     * v37 — GERÇEK TÜR TESPİTİ (bozuk kitap salgınının kökü).
     *
     * Eski hata: tür YALNIZCA dosya adının uzantısına bakılarak seçiliyordu.
     * Ad "source.pdf" gibi uzantısını yitirmiş geldiğinde (SAF/file://
     * aktarımlarında ad okunamazsa) `else` dalı çalışıp **ZIP/PPTX baytları
     * düz metin sanılıyordu**. Sonuç: 60 slaytlık sunum 1460 sayfalık,
     * sesli okumada "PK ! ppt/slides/slide2.xml…" diyen bir kitaba dönüşüyordu.
     *
     * Artık önce İÇERİK okunur: dosya ZIP ise içindeki paket yapısına bakılır
     * (word/document.xml → docx, ppt/slides → pptx, META-INF/container.xml →
     * epub). ZIP ama desteklenmeyen bir paket (xlsx, rar/zip arşivi) ise bozuk
     * kitap üretmek yerine anlaşılır hata verilir.
     */
    internal fun effectiveExtension(src: File, name: String): String {
        val declared = extension(name)
        if (UniversalParser.isZip(src)) {
            val kind = zipKind(src)
            if (kind != null) return kind
            throw IllegalArgumentException(
                "'$name' bir ZIP/paket dosyası ama DOCX/PPTX/EPUB değil " +
                    "(ör. XLSX ya da sıkıştırılmış arşiv). Metin olarak okunamaz"
            )
        }
        if (declared in ZIP_ONLY) {
            throw IllegalArgumentException(
                "'$name' bozuk ya da eksik paket dosyası (ZIP yapısı yok). " +
                    "Dosyayı yeniden indirip/kaydedip tekrar deneyin"
            )
        }
        return declared
    }

    /** ZIP içindeki paket türü: docx / pptx / epub / null. */
    private fun zipKind(src: File): String? {
        val slide = Regex("ppt/slides/slide\\d+\\.xml$")
        return try {
            java.util.zip.ZipInputStream(src.inputStream().buffered()).use { zip ->
                var n = 0
                var entry = zip.nextEntry
                while (entry != null && n < 400) {
                    val en = entry.name
                    when {
                        en == "word/document.xml" -> return "docx"
                        en == "META-INF/container.xml" -> return "epub"
                        slide.matches(en) -> return "pptx"
                    }
                    n++
                    entry = zip.nextEntry
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ── Metin okuma ───────────────────────────────────────

    private fun textParagraphs(src: File): List<String> {
        // v17 (EVRENSEL PARSER): UTF-8/UTF-16 BOM + windows-1256 (Arapça/
        // Kur'an) + windows-1254 (Türkçe) geri düşüşlü güvenli kodlama
        // çözümü. Eski kod yalnız UTF-8/ISO-8859-1 biliyordu → Arapça metinde
        // bozuk karakter (�) çıkıyordu; artık doğru okunur. Kod/indentasyon
        // satırları AYNI KALIR (yalnız satır sonları normalize edilir).
        // v37 — SON KAPI: ikili bir dosya (PDF/ZIP/EXE/…) buraya düşerse
        // çözülen metin anlamsız olur. Önce bayt düzeyinde "bu bir metin mi?"
        // kontrolü (NUL/kontrol karakteri oranı), sonra çözülen metnin
        // kalite kontrolü. Eskiden burada hiçbir kapı yoktu → sessizce
        // okunamayan çöp kitap oluşuyordu.
        if (src.length() > 0L && !UniversalParser.looksLikeText(src)) {
            throw IllegalArgumentException(
                "Dosya metin değil (ikili biçim) — okunabilir metin bulunamadı"
            )
        }
        val text = UniversalParser.decodeFile(src).text
        if (text.isNotBlank() && TextQuality.isGarbage(text.take(6000))) {
            throw IllegalArgumentException(
                "Dosya metin değil (ikili biçim) — okunabilir metin bulunamadı"
            )
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
    }

    // ── EPUB (zip + container.xml + OPF spine sırası) ─────

    /**
     * EPUB → düz paragraf listesi (v5.1 ADIM B.2 / #4).
     *
     * Yol: META-INF/container.xml → OPF dosyası → `<spine>` sırası →
     * her XHTML'den block etiketlerinden metin (`<p>`, `<h1..6>`, `<li>`,
     * `<div>`, `<blockquote>`). Resim/script/style atlanır.
     * HTML varlıklar (&amp; vb.) çözülür; HTML kırıntıları temizlenir.
     */
    /** Birim testten erişilebilir (internal) — EPUB metin çıkarma. */
    internal fun epubParagraphsForTest(src: File): List<String> = epubParagraphs(src)

    private fun epubParagraphs(src: File): List<String> {
        val out = mutableListOf<String>()
        java.util.zip.ZipInputStream(src.inputStream().buffered()).use { zip ->
            // 1) container.xml → OPF yolu
            var opfPath = ""
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "META-INF/container.xml") {
                    val xml = zip.readBytes()
                    val pull = newXmlPullParser()
                    pull.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    pull.setInput(ByteArrayInputStream(xml), "UTF-8")
                    var ev = pull.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && pull.name == "rootfile") {
                            opfPath = pull.getAttributeValue(null, "full-path") ?: ""
                            if (opfPath.isEmpty()) {
                                pull.getAttributeValue("urn:oasis:names:tc:opendocument:xmlns:container", "full-path")
                                    ?.let { opfPath = it }
                            }
                            if (opfPath.isNotEmpty()) break
                        }
                        ev = pull.next()
                    }
                }
                if (opfPath.isNotEmpty()) break
                entry = zip.nextEntry
            }
            require(opfPath.isNotEmpty()) { "Geçerli EPUB değil: container.xml bulunamadı" }

            // 2) OPF → manifest (id → href) + spine (idref sırası)
            var opfBytes: ByteArray? = null
            var baseDir = ""
            entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == opfPath) {
                    opfBytes = zip.readBytes()
                    baseDir = opfPath.substringBeforeLast('/', "")
                    break
                }
                entry = zip.nextEntry
            }
            val opf = requireNotNull(opfBytes) { "Geçerli EPUB değil: OPF ($opfPath) bulunamadı" }

            val idToHref = mutableMapOf<String, String>()
            val spineIds = mutableListOf<String>()
            run {
                val pull = newXmlPullParser()
                pull.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                pull.setInput(ByteArrayInputStream(opf), "UTF-8")
                var ev = pull.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when {
                        ev == XmlPullParser.START_TAG && pull.name == "item" -> {
                            val id = pull.getAttributeValue(null, "id")
                            val href = pull.getAttributeValue(null, "href")
                            if (!id.isNullOrEmpty() && !href.isNullOrEmpty()) idToHref[id] = href
                        }
                        ev == XmlPullParser.START_TAG && pull.name == "itemref" -> {
                            pull.getAttributeValue(null, "idref")?.let { if (it.isNotEmpty()) spineIds.add(it) }
                        }
                    }
                    ev = pull.next()
                }
            }

            // 3) Tüm içerik XHTML'lerini tek geçişte oku (zip tek yönlü)
            //    Entry adları OPF dizini önekli olabilir (OEBPS/ch1.xhtml);
            //    href'ler OPF'e göreli → öneki düşürerek eşleştir.
            val wanted = spineIds.mapNotNull { idToHref[it]?.substringBefore('#') }
                .filter { it.isNotEmpty() }.toSet()
            val docsByHref = mutableMapOf<String, ByteArray>()
            entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.removePrefix("./")
                    val rel = if (baseDir.isNotEmpty() && name.startsWith("$baseDir/")) {
                        name.removePrefix("$baseDir/")
                    } else name
                    if (wanted.contains(rel)) {
                        docsByHref[rel] = zip.readBytes()
                    }
                }
                entry = zip.nextEntry
            }

            // 4) Spine sırasına göre metin çıkarma
            for (id in spineIds) {
                val href = idToHref[id]?.substringBefore('#') ?: continue
                val bytes = docsByHref[href] ?: continue
                extractXhtmlParagraphs(bytes, out)
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("EPUB'ta metin bulunamadı")
        return out
    }

    /** Bir XHTML/HTML içeriğinden paragraf metinlerini çıkarır. */
    private fun extractXhtmlParagraphs(bytes: ByteArray, out: MutableList<String>) {
        try {
            val pull = newXmlPullParser()
            pull.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            pull.setInput(ByteArrayInputStream(bytes), "UTF-8")
            val BLOCKS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "div", "blockquote", "dt", "dd")
            var sb: StringBuilder? = null
            var ev = pull.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when {
                    ev == XmlPullParser.START_TAG && BLOCKS.contains(pull.name) -> {
                        if (sb != null && sb.isNotBlank()) out.add(sb.toString().trim())
                        sb = StringBuilder()
                    }
                    ev == XmlPullParser.TEXT -> sb?.append(pull.text)
                    ev == XmlPullParser.END_TAG && BLOCKS.contains(pull.name) -> {
                        sb?.toString()?.trim()?.let { if (it.isNotBlank()) out.add(it) }
                        sb = null
                    }
                }
                ev = pull.next()
            }
            sb?.toString()?.trim()?.let { if (it.isNotBlank()) out.add(it) }
        } catch (_: Exception) {
            // Bozuk XHTML → bu bölüm atlanır, diğerleri devam eder
        }
    }

    // ── DOCX (zip + word/document.xml) ────────────────────

    private fun docxParagraphs(src: File): List<String> {
        val xml = ZipInputStream(src.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != "word/document.xml") entry = zip.nextEntry
            requireNotNull(entry) { "Geçerli DOCX değil: word/document.xml bulunamadı" }
            zip.readBytes()
        }

        val out = mutableListOf<String>()
        val pull = newXmlPullParser()
        pull.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        pull.setInput(ByteArrayInputStream(xml), "UTF-8")

        var para: StringBuilder? = StringBuilder()
        var event = pull.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when {
                // v39 — aynı metnin Choice + Fallback kopyası iki kez okunmasın
                event == XmlPullParser.START_TAG && pull.name == "Fallback" ->
                    skipSubtree(pull)

                event == XmlPullParser.START_TAG && pull.name == "p" ->
                    para = StringBuilder()

                event == XmlPullParser.START_TAG && pull.name == "t" -> {
                    val text = try { pull.nextText() } catch (e: Exception) { "" }
                    (para ?: StringBuilder().also { para = it }).append(text)
                }

                event == XmlPullParser.START_TAG && pull.name == "tab" ->
                    para?.append('\t')

                event == XmlPullParser.END_TAG && pull.name == "p" -> {
                    para?.let { out.add(it.toString()) }
                    para = StringBuilder()
                }
            }
            event = pull.next()
        }
        para?.let { if (it.isNotBlank()) out.add(it.toString()) }
        if (out.isEmpty()) throw IllegalArgumentException("DOCX'te metin bulunamadı")
        return out
    }

    // ── PPTX (zip + ppt/slides/slideN.xml) ─────────────────

    private val SLIDE_NUM = Regex("ppt/slides/slide(\\d+)\\.xml$")

    /**
     * Sunum dosyasını slayt slayt PDF'e çevirir: her slayt için başlık satırı
     * + o slayttaki tüm metin kutuları (`<a:t>`). Slaytlar doğal olarak sıralanır
     * (slide2 → slide10).
     */
    private fun pptxParagraphs(src: File, markSlides: Boolean = false): List<String> {
        val slides = ArrayList<Pair<Int, String>>()
        java.util.zip.ZipInputStream(src.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val m = SLIDE_NUM.find(entry.name)
                if (m != null && !entry.isDirectory) {
                    val xml = zip.readBytes()
                    slides.add(m.groupValues[1].toInt() to xmlTexts(xml))
                }
                entry = zip.nextEntry
            }
        }
        require(slides.isNotEmpty()) { "PPTX'te okunabilir slayt bulunamadı" }

        val out = mutableListOf<String>()
        slides.sortedBy { it.first }.forEach { (num, text) ->
            if (markSlides) out += PAGE_BREAK
            out += "— Slayt $num —"
            if (text.isBlank()) out += "(bu slaytta metin yok)"
            else out += text
            out += ""
        }
        return out
    }

    /**
     * v37 — PPTX'teki slayt sayısı (paket içindeki slideN.xml sayısı).
     * Onarım, "sayfa sayısı slayt sayısından az" olan sunumları yeniden üretir
     * (eski hata: 60 slayt tek sayfaya sıkışıyordu).
     */
    internal fun slideCount(src: File): Int = try {
        var n = 0
        java.util.zip.ZipInputStream(src.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && SLIDE_NUM.find(entry.name) != null) n++
                entry = zip.nextEntry
            }
        }
        n
    } catch (_: Throwable) {
        0
    }

    /** XML içindeki tüm `<a:t>` / `<w:t>` metinlerini tek parça olarak toplar. */
    private fun xmlTexts(xml: ByteArray): String {
        val pull = newXmlPullParser()
        pull.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        pull.setInput(ByteArrayInputStream(xml), "UTF-8")
        val sb = StringBuilder()
        var event = pull.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            // v39 — mc:AlternateContent: aynı metin hem Choice hem Fallback
            // altında durur. İkisini okumak metni İKİ KEZ ekliyordu (sesli
            // okuma aynı cümleyi tekrar ediyordu). Fallback atlanır.
            if (event == XmlPullParser.START_TAG && pull.name == "Fallback") {
                skipSubtree(pull)
            } else if (event == XmlPullParser.START_TAG && pull.name == "t") {
                val t = try { pull.nextText() } catch (e: Exception) { "" }
                if (t.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(t)
                }
            }
            if (event != XmlPullParser.END_DOCUMENT) event = pull.next()
        }
        return sb.toString()
    }

    /** Bir XML alt ağacını (açılış etiketinden sonra) tamamen atlar. */
    private fun skipSubtree(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    // ── Eski ikili formatlar (.doc / .ppt) ──────────────────

    /**
     * Office 97-2003 (OLE2) dosyaları için best-effort metin çıkarımı.
     *
     * WordDocument/PowerPoint akışlarını birebir çözümlemek yerine, dosyadaki
     * uzun YAZILABİLİR dizgiler toplanır:
     *  - UTF-16LE dizgiler (Office'in metni saklama şekli)
     *  - Tek bayt dizgiler → **windows-1254** (Türkçe) ile çözülür
     * (CP1254, İngilizce CP1252'den yalnız 5 baytta ayrılır → ikisi de doğru okur.)
     *
     * Kalite PDFBox'ın DOCX/PPTX çıkarmasından düşük olabilir; yine de kitap
     * kütüphaneye girer, seslendirilir ve okunur — "açılamıyor" hatası yoktur.
     */
    private fun legacyParagraphs(src: File, ext: String): List<String> {
        val bytes = src.readBytes()
        val singleByte = decodeRuns(bytes, utf16 = false)
        val utf16 = decodeRuns(bytes, utf16 = true)
        fun weight(s: String) = s.replace(Regex("\\s+"), "").length
        val text = if (weight(utf16) > weight(singleByte)) utf16 else singleByte

        require(weight(text) >= 10) {
            ".$ext dosyasından metin çıkarılamadı (eski/binary format)"
        }
        return text.split(Regex("\\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** Yazılabilir bayt/karakter dizgilerini toplar (min 30 karakter). */
    private fun decodeRuns(bytes: ByteArray, utf16: Boolean): String {
        if (utf16) {
            val out = StringBuilder()
            val cur = StringBuilder()
            fun flush() {
                if (cur.length >= 30) { out.append(cur).append("\n\n") }
                cur.setLength(0)
            }
            var i = 0
            while (i + 1 < bytes.size) {
                val lo = bytes[i].toInt() and 0xff
                val hi = bytes[i + 1].toInt() and 0xff
                if (hi == 0 && (lo in 32..126 || lo == 9)) cur.append(lo.toChar()) else flush()
                i += 2
            }
            flush()
            return out.toString()
        }

        val out = java.io.ByteArrayOutputStream()
        val cur = java.io.ByteArrayOutputStream()
        fun flush() {
            if (cur.size() >= 30) {
                out.write(cur.toByteArray())
                out.write("\n\n".toByteArray(Charsets.US_ASCII))
            }
            cur.reset()
        }
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v in 32..126 || v == 9 || v == 10 || v == 13 || v in 160..255) {
                cur.write(v)
            } else flush()
        }
        flush()
        return try {
            String(out.toByteArray(), charset("windows-1254"))
        } catch (e: Exception) {
            String(out.toByteArray(), Charsets.ISO_8859_1)
        }
    }

    // ── PDF çizimi ────────────────────────────────────────

    private fun writePdf(
        paragraphs: List<String>,
        dest: File,
        title: String,
        withTitlePage: Boolean = true
    ): Int {
        PDDocument().use { doc ->
            // v17 (ONARIM — umay_error.log): Tek font Roboto, Arapça/Farsça
            // glyph'larında "No glyph for U+0643" fırlatıp İÇE AKTARMAYI
            // TAMAMEN düşürüyordu (dosyalar hiç açılmıyordu). Artık:
            //  • Latin için Roboto, Arapça için sistemdeki Noto Arabic fontu
            //    satır bazında otomatik seçilir,
            //  • hiçbir font desteklemiyorsa desteklenmeyen karakterler
            //    ayıklanır (dosya yine de kütüphaneye girer),
            //  • showText asla dışarı hata fırlatmaz (import kırılmaz).
            val fonts = loadFontCandidates(doc)
            val primary: PDFont? = fonts.firstOrNull()

            fun chooseFont(text: String): PDFont? {
                val p = primary ?: return null
                if (text.all { it.code < 128 }) return p   // hızlı yol
                return fonts.firstOrNull { supports(it, text) } ?: p
            }

            val margin = 50f
            val size = 11f
            val leading = 14f
            val maxWidth = PDRectangle.A4.width - margin * 2
            val bottomLimit = margin + 14f

            var stream: PDPageContentStream? = null
            var y = 0f

            fun newPage() {
                stream?.close()
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                stream = PDPageContentStream(doc, page)
                y = PDRectangle.A4.height - margin
            }

            /** Tek satırı, satıra en uygun fontla hatasız biçimde çizer. */
            fun drawOne(line: String, f: PDFont?) {
                if (y - leading < bottomLimit) newPage()
                if (f == null) { y -= leading; return }   // font yoksa boş satır
                val s = stream ?: return
                val text = if (supports(f, line)) line else fitTo(f, line)
                if (text.isEmpty()) { y -= leading; return }
                var opened = false
                try {
                    s.beginText()
                    opened = true
                    s.setFont(f, size)
                    s.newLineAtOffset(margin, y)
                    s.showText(text)
                } catch (t: Throwable) {
                    // Son çare: tek tek desteklenen karakterleri yaz
                    try {
                        if (opened) s.endText()
                        opened = false
                        val alt = fitTo(f, text)
                        if (alt.isNotEmpty()) {
                            s.beginText(); opened = true
                            s.setFont(f, size)
                            s.newLineAtOffset(margin, y)
                            s.showText(alt)
                        }
                    } catch (_: Throwable) {
                        // Bu satır çizilemiyor — BELGE KURTARILIR, import sürer
                    }
                } finally {
                    if (opened) try { s.endText() } catch (_: Throwable) {}
                }
                y -= leading
            }

            fun drawLine(text: String) {
                val clean = sanitize(text)
                if (clean.isEmpty()) return
                val f = chooseFont(clean)
                for (line in wrap(clean, f, size, maxWidth)) drawOne(line, f)
            }

            newPage()

            // Başlık
            if (withTitlePage && title.isNotBlank()) {
                val cleanTitle = sanitize(title)
                val tf = chooseFont(cleanTitle)
                drawOne(cleanTitle, tf)
                y -= 8f
                // Ayırıcı çizgi
                stream?.setLineWidth(0.8f)
                stream?.moveTo(margin, y)
                stream?.lineTo(PDRectangle.A4.width - margin, y)
                stream?.stroke()
                y -= leading
            }

            // Sayfa başında sayfa kesme işareti gelirse boş sayfa açma.
            var wroteAny = withTitlePage && title.isNotBlank()
            for (raw in paragraphs) {
                if (raw == PAGE_BREAK) {
                    if (wroteAny) {
                        newPage()
                        wroteAny = false
                    }
                    continue
                }
                drawLine(raw.replace("\t", "    "))
                if (raw.isNotBlank()) wroteAny = true
            }

            stream?.close()
            doc.save(dest)
            return doc.numberOfPages
        }
    }

    /**
     * v17 (ONARIM): Kullanılabilir font adayları — Latin (Roboto) önce,
     * ardından Arapça/Farsça (Noto Arabic) destekli sistem fontları.
     * Yüklenemeyen fontlar sessizce atlanır; hiçbiri yoksa Helvetica.
     */
    private fun loadFontCandidates(doc: PDDocument): List<PDFont> {
        val out = mutableListOf<PDFont>()
        val paths = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/NotoNaskhArabic-Regular.ttf",
            "/system/fonts/NotoSansArabic-Regular.ttf",
            "/system/fonts/NotoSansArabicUI-Regular.ttf",
            "/system/fonts/DroidSansFallback.ttf"
        )
        for (p in paths) {
            try {
                val f = File(p)
                if (f.exists()) out.add(PDType0Font.load(doc, f))
            } catch (_: Throwable) {
                // font açılamadı → diğer adaya geç
            }
        }
        // DİKKAT (v17): PDType1Font.HELVETICA (AFM kaynak eksikliği yüzünden)
        // ExceptionInInitializerError fırlatıyor — hem JVM testinde hem
        // cihazda Roboto bulunamazsa İÇE AKTARMAYI patlatıyordu. Artık font
        // YOKSA metin çizilmez, belge yine de üretilir (import kırılmaz).
        return out
    }

    /** Font bu metnin tamamını kodlayabiliyor mu? (glyph var mı?) */
    private fun supports(font: PDFont, s: String): Boolean =
        if (s.isEmpty()) true
        else try { font.encode(s); true } catch (_: Throwable) { false }

    /** Fontun desteklemediği karakterleri ayıklayarak metni kurtarır. */
    private fun fitTo(font: PDFont, s: String): String =
        s.filter { ch -> supports(font, ch.toString()) }

    /** PDF'te geçersiz olabilecek karakterleri temizler. */
    private fun sanitize(s: String): String = buildString {
        for (ch in s) {
            val c = ch.code
            if (ch == '\n' || ch == '\t') append(' ')
            else if (c in 0..8 || c in 11..12 || c in 14..31) append(' ')
            else append(ch)
        }
    }.take(4000)

    /** Satır maxWidth'i aşarsa kelimelerden kırarak böler. */
    private fun wrap(line: String, font: PDFont?, size: Float, maxW: Float): List<String> {
        val clean = line.trim()
        if (clean.isEmpty()) return listOf("")

        fun widthOf(s: String): Float = try {
            if (font == null) s.length * size * 0.55f
            else font.getStringWidth(s) / 1000f * size
        } catch (e: Exception) {
            s.length * size * 0.55f   // ölçülemeyen glyph için kaba tahmin
        }

        if (widthOf(clean) <= maxW) return listOf(clean)

        val out = mutableListOf<String>()
        var cur = StringBuilder()

        fun flush() { if (cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder() } }

        for (word in clean.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            val candidate = if (cur.isEmpty()) word else "$cur $word"
            if (widthOf(candidate) <= maxW) {
                if (cur.isEmpty()) cur.append(word) else { cur.append(' '); cur.append(word) }
                continue
            }
            flush()
            // Tek kelime baştan büyükse karakterden böl
            var rest = word
            while (widthOf(rest) > maxW && rest.length > 1) {
                var cut = rest.length
                while (cut > 1 && widthOf(rest.substring(0, cut)) > maxW) cut--
                out.add(rest.substring(0, cut))
                rest = rest.substring(cut)
            }
            cur.append(rest)
        }
        flush()
        return out
    }
}
