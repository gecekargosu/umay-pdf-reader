package com.umay.pdfreader

import android.util.Xml
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * PDF dışındaki metin dosyalarını PDF'e çevirir:
 *   TXT, MD, CSV, LOG, DOCX  →  source.pdf
 *
 * Böylece tüm dosya türleri AYNI okuma akışından geçer:
 *   kaynak → source.pdf → metin çıkarma (PDFBox) → görüntü (PdfRenderer) → TTS
 *
 * Metin, cihazda zaten bulunan Roboto fontuyla (Türkçe tam destekli) sayfaya
 * yazılır; satırlar otomatik bölünür, sayfa başına sığdırılır.
 */
object DocumentConverter {

    private val SUPPORTED = setOf("txt", "md", "markdown", "csv", "log", "docx")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isSupported(name: String): Boolean = SUPPORTED.contains(extension(name))

    /** Desteklenen dosyayı PDF'e çevirir; sayfa sayısını döndürür. */
    fun convert(src: File, dest: File, name: String): Int {
        val paragraphs: List<String> = when (extension(name)) {
            "docx" -> docxParagraphs(src)
            else -> textParagraphs(src)
        }
        val title = name.substringBeforeLast('.')
        return writePdf(paragraphs, dest, title)
    }

    // ── Metin okuma ───────────────────────────────────────

    private fun textParagraphs(src: File): List<String> {
        val bytes = src.readBytes()
        val text = if (looksUtf8(bytes)) {
            String(bytes, Charsets.UTF_8)
        } else {
            // Windows-1254 (Türkçe) gibi kodlamalar
            try {
                String(bytes, Charsets.ISO_8859_1)
            } catch (e: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
    }

    /** Basit UTF-8 doğrulaması (bozuk dizgi varsa false). */
    private fun looksUtf8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return !String(bytes, Charsets.UTF_8).contains('\uFFFD')
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
        val pull = Xml.newPullParser()
        pull.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        pull.setInput(ByteArrayInputStream(xml), "UTF-8")

        var para: StringBuilder? = StringBuilder()
        var event = pull.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when {
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

    // ── PDF çizimi ────────────────────────────────────────

    private fun writePdf(paragraphs: List<String>, dest: File, title: String): Int {
        PDDocument().use { doc ->
            val font: PDFont = try {
                val ttf = File("/system/fonts/Roboto-Regular.ttf")
                if (ttf.exists()) PDType0Font.load(doc, ttf) else PDType1Font.HELVETICA
            } catch (e: Exception) {
                PDType1Font.HELVETICA
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

            fun drawLine(text: String) {
                if (y - leading < bottomLimit) newPage()
                val s = stream ?: return
                s.beginText()
                s.setFont(font, size)
                s.newLineAtOffset(margin, y)
                s.showText(sanitize(text))
                s.endText()
                y -= leading
            }

            newPage()

            // Başlık
            if (title.isNotBlank()) {
                stream?.beginText()
                stream?.setFont(font, size + 4f)
                stream?.newLineAtOffset(margin, y)
                stream?.showText(sanitize(title))
                stream?.endText()
                y -= leading + 8f
                // Ayırıcı çizgi
                stream?.setLineWidth(0.8f)
                stream?.moveTo(margin, y)
                stream?.lineTo(PDRectangle.A4.width - margin, y)
                stream?.stroke()
                y -= leading
            }

            for (raw in paragraphs) {
                for (line in wrap(raw.replace("\t", "    "), font, size, maxWidth)) {
                    drawLine(line)
                }
            }

            stream?.close()
            doc.save(dest)
            return doc.numberOfPages
        }
    }

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
    private fun wrap(line: String, font: PDFont, size: Float, maxW: Float): List<String> {
        val clean = line.trim()
        if (clean.isEmpty()) return listOf("")

        fun widthOf(s: String): Float = try {
            font.getStringWidth(s) / 1000f * size
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
