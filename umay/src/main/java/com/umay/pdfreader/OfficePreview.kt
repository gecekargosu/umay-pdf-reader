package com.umay.pdfreader

import android.content.Context
import java.io.File
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * v39 — UYGULAMA İÇİ BELGE GÖRÜNÜMÜ (DOCX / PPTX).
 *
 * Amaç: hocanın verdiği slayt/doküman **hiç değiştirilmeden** uygulama içinde
 * görülebilsin. Dosya PDF'e çevrilmez, kopyası bozulmaz; yalnızca paket
 * (ZIP) içeriği okunup HTML'e dökülür:
 *
 *   • PPTX → her slayt bir kutu; metin kutuları **kendi konumunda** (a:off /
 *     a:ext → piksel), resimler slayt içindeki yerinde gösterilir.
 *   • DOCX → akış düzeni; başlıklar, kalın/italik, tablolar ve gömülü
 *     resimler korunur.
 *
 * Resimler paketen çıkarılıp `cacheDir/office/<imza>/` altına yazılır; HTML
 * bunlara göreli yolla başvurur. Böylece:
 *   • internet GEREKMEZ (tamamen çevrimdışı),
 *   • sunucuya hiçbir şey gönderilmez (gizlilik),
 *   • orijinal dosya salt-okunur açılır, değişmez.
 *
 * Tam birebir (piksel piksel) çıktı yalnızca PowerPoint/Word/WPS gibi bir
 * hesap motoruyla olur; orada "Orijinali aç" düğmesi devreye girer. Buradaki
 * görünüm, slaytın METNİNİ ve RESİMLERİNİ yerinde gösterir.
 */
object OfficePreview {

    private val SLIDE_RE = Regex("ppt/slides/slide(\\d+)\\.xml$")
    private const val EMU_PER_PX = 9525.0      // 96 dpi
    private const val SLIDE_CSS_W = 960.0      // slayt tuvali genişliği (px)
    private const val MAX_IMAGE = 8L * 1024 * 1024

    /** Önizleme dosyalarını üretir; başarısızsa null. */
    fun build(ctx: Context, src: File, title: String): File? {
        val ext = DocumentConverter.effectiveExtension(src, title)
        if (ext != "docx" && ext != "pptx") return null
        val dir = File(
            ctx.cacheDir,
            "office/" + src.length() + "-" + title.hashCode().toString().replace('-', 'n')
        )
        return try {
            dir.mkdirs()
            val html = if (ext == "pptx") pptxHtml(src, title, dir) else docxHtml(src, title, dir)
            val out = File(dir, "index.html")
            out.writeText(html, Charsets.UTF_8)
            out
        } catch (t: Throwable) {
            BookStore.logError(ctx, "OfficePreview.build", t)
            null
        }
    }

    /** Eski önizleme klasörlerini temizler (önbellek şişmesin). */
    fun cleanup(ctx: Context) {
        try {
            val root = File(ctx.cacheDir, "office")
            val files = root.listFiles() ?: return
            val limit = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            files.forEach { f ->
                if (f.isDirectory && f.lastModified() < limit) f.deleteRecursively()
            }
        } catch (_: Throwable) {
        }
    }

    // ── HTML iskeleti ─────────────────────────────────────────

    private fun head(title: String): String = """
<!DOCTYPE html>
<html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=960">
<title>${esc(title)}</title>
<style>
  body{margin:0;background:#0E1620;color:#E8EEF6;
       font-family:Roboto,'Noto Sans',sans-serif}
  /* DOCX: kâğıt görünümü (beyaz sayfa, koyu yazı) */
  .doc{max-width:820px;margin:14px auto 22px;padding:30px 34px 40px;background:#fff;
       color:#111;box-shadow:0 2px 12px rgba(0,0,0,.55)}
  .slide{position:relative;width:960px;margin:0 auto 16px;background:#fff;
         color:#111;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,.55)}
  .shape{position:absolute;overflow:hidden;white-space:pre-wrap;line-height:1.24;
         color:#111}
  .shape p{margin:0 0 .28em}
  .free{position:absolute;left:0;right:0;padding:4px 26px;box-sizing:border-box;color:#111}
  .ft{position:relative;margin:0 0 10px;white-space:pre-wrap;line-height:1.35}
  .fimg{display:block;max-width:100%;height:auto;margin:10px 0}
  .pic{position:absolute;display:flex;align-items:center;justify-content:center}
  .pic img{max-width:100%;max-height:100%}
  .no{position:absolute;right:8px;bottom:6px;font-size:12px;color:#9aa;z-index:5}
  .h1{font-size:24px;font-weight:700;margin:.7em 0 .45em}
  .h2{font-size:20px;font-weight:700;margin:.6em 0 .4em}
  .h3{font-size:17px;font-weight:700;margin:.5em 0 .35em}
  .p{font-size:15.5px;margin:0 0 .65em;line-height:1.55}
  .doc img{max-width:100%;height:auto;display:block;margin:.5em 0}
  table{border-collapse:collapse;margin:.6em 0;width:100%}
  td{border:1px solid #cfd6df;padding:5px 7px;font-size:14.5px;vertical-align:top}
  .note{max-width:820px;margin:0 auto 14px;color:#8FA3B8;font-size:13px;
        padding:0 18px}
</style></head><body>
""".trimIndent()

    private fun foot(): String = "\n</body></html>"

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ── XML yardımcıları ──────────────────────────────────────

    private fun parser(xml: ByteArray): XmlPullParser {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(xml.inputStream(), "UTF-8")
        return p
    }

    /** Ön ekli ad ("a:off") → yerel ad ("off"). */
    private fun local(name: String?): String = name?.substringAfterLast(':') ?: ""

    /** Ad alanı ön ekine bakmadan özniteliği bulur ("x", "r:embed" → "embed"). */
    private fun attr(p: XmlPullParser, local: String): String? {
        for (i in 0 until p.attributeCount) {
            val n = p.getAttributeName(i) ?: continue
            if (n == local || n.substringAfterLast(':') == local) return p.getAttributeValue(i)
        }
        return null
    }

    /**
     * `mc:AlternateContent` yapısında aynı içerik hem Choice hem Fallback
     * altında durur. İkisini birlikte okumak metni İKİ KEZ yazıyordu ("aynı
     * kod bloğu 4 kez tekrar ediyor" görüntüsü). Fallback dalını atlarız.
     */
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

    private fun readText(zip: ZipFile, name: String): ByteArray? {
        return try {
            val e = zip.getEntry(name) ?: return null
            if (e.size > 24L * 1024 * 1024) return null
            zip.getInputStream(e).use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
    }

    /** .rels dosyasını Id → hedef (paket yolu) haritasına çevirir. */
    private fun rels(zip: ZipFile, relsPath: String, baseDir: String): Map<String, String> {
        val bytes = readText(zip, relsPath) ?: return emptyMap()
        val out = HashMap<String, String>()
        try {
            val p = parser(bytes)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p.name) == "Relationship") {
                    val id = attr(p, "Id")
                    val target = attr(p, "Target")
                    val mode = attr(p, "TargetMode") ?: ""
                    if (id != null && target != null && mode != "External") {
                        out[id] = normalize(baseDir, target)
                    }
                }
                ev = p.next()
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /** "../media/x.png" + "ppt/slides" → "ppt/media/x.png" */
    private fun normalize(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val parts = ArrayList<String>()
        (baseDir.trim('/').split('/') + target.split('/')).forEach { seg ->
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    /** Paketten resmi çıkarır; dosya adını döndürür (yoksa/çok büyükse null). */
    private fun media(zip: ZipFile, entry: String, dir: File): String? {
        return try {
            val e = zip.getEntry(entry) ?: return null
            if (e.size <= 0 || e.size > MAX_IMAGE) return null
            val safe = "m_" + entry.hashCode().toString().replace('-', 'n') +
                "." + entry.substringAfterLast('.', "png")
            val f = File(dir, safe)
            if (!f.exists() || f.length() != e.size) {
                zip.getInputStream(e).use { input -> f.outputStream().use { input.copyTo(it) } }
            }
            safe
        } catch (_: Throwable) {
            null
        }
    }

    private fun px(v: Long?, scale: Double): Double? =
        v?.let { it / EMU_PER_PX * scale }

    // ── PPTX ──────────────────────────────────────────────────

    private class Shape {
        var x: Long? = null
        var y: Long? = null
        var w: Long? = null
        var h: Long? = null
        var img: String? = null
        var size: Int? = null      // punto (a:rPr sz/100)
        var align: String? = null
        val body = StringBuilder()
        var text = false
    }

    private fun pptxHtml(src: File, title: String, dir: File): String {
        val sb = StringBuilder(head(title))
        sb.append("<div class=\"note\">").append(esc(title))
            .append(" — slayt görünümü (dosya değiştirilmedi)</div>\n")

        ZipFile(src).use { zip ->
            var cx = 12192000L
            var cy = 6858000L
            readText(zip, "ppt/presentation.xml")?.let { bytes ->
                try {
                    val p = parser(bytes)
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && local(p.name) == "sldSz") {
                            attr(p, "cx")?.toLongOrNull()?.let { if (it > 0) cx = it }
                            attr(p, "cy")?.toLongOrNull()?.let { if (it > 0) cy = it }
                        }
                        ev = p.next()
                    }
                } catch (_: Throwable) {
                }
            }

            val slides = ArrayList<Pair<Int, String>>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val m = SLIDE_RE.find(e.name) ?: continue
                slides.add(m.groupValues[1].toInt() to e.name)
            }
            slides.sortBy { it.first }

            val scale = SLIDE_CSS_W / (cx / EMU_PER_PX)
            val slideH = (cy / EMU_PER_PX) * scale

            slides.forEach { (num, name) ->
                val bytes = readText(zip, name) ?: return@forEach
                val rel = rels(zip, "ppt/slides/_rels/slide$num.xml.rels", "ppt/slides")
                sb.append(slideHtml(zip, bytes, rel, num, scale, slideH, dir))
            }
        }
        sb.append(foot())
        return sb.toString()
    }

    private fun slideHtml(
        zip: ZipFile,
        xml: ByteArray,
        rel: Map<String, String>,
        num: Int,
        scale: Double,
        slideH: Double,
        dir: File
    ): String {
        val shapes = ArrayList<Shape>()
        var cur: Shape? = null
        var inPara = false
        var inRun = false
        var runSize: Int? = null
        var runBold = false
        var runItalic = false
        var runColor: String? = null
        var align: String? = null
        var free = 0

        val p = parser(xml)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val n = local(p.name)
            when {
                ev == XmlPullParser.START_TAG -> when (n) {
                    "Fallback" -> skipSubtree(p)
                    "sp", "pic" -> cur = Shape()
                    "off" -> cur?.let { it.x = attr(p, "x")?.toLongOrNull(); it.y = attr(p, "y")?.toLongOrNull() }
                    "ext" -> cur?.let { it.w = attr(p, "cx")?.toLongOrNull(); it.h = attr(p, "cy")?.toLongOrNull() }
                    "p" -> {
                        inPara = true
                        align = null
                    }
                    "pPr" -> align = attr(p, "algn")
                    "r" -> inRun = true
                    "rPr" -> {
                        runSize = null; runBold = false; runItalic = false; runColor = null
                        attr(p, "sz")?.toDoubleOrNull()?.let { runSize = it.toInt() }
                        (attr(p, "b") ?: "").let { runBold = it == "1" || it == "true" }
                        (attr(p, "i") ?: "").let { runItalic = it == "1" || it == "true" }
                    }
                    "srgbClr" -> if (inRun) runColor = attr(p, "val")
                    "t" -> {
                        val t = try { p.nextText() } catch (_: Exception) { "" }
                        val c = cur
                        if (c != null && t.isNotEmpty()) {
                            c.text = true
                            if (c.size == null && runSize != null && runSize!! > 0) {
                                c.size = runSize!! / 100
                            }
                            if (c.align == null) c.align = align
                            val style = StringBuilder()
                            if (runBold) style.append("font-weight:700;")
                            if (runItalic) style.append("font-style:italic;")
                            runColor?.let { style.append("color:#").append(it).append(';') }
                            c.body.append("<span style=\"").append(style).append("\">")
                                .append(esc(t).replace("\n", "<br>")).append("</span>")
                        }
                    }
                    "br" -> cur?.body?.append("<br>")
                    "tab" -> cur?.body?.append("&nbsp;&nbsp;&nbsp;&nbsp;")
                    "blip" -> {
                        val id = attr(p, "embed") ?: attr(p, "link")
                        val target = id?.let { rel[it] }
                        val file = target?.let { media(zip, it, dir) }
                        if (file != null) cur?.img = file
                    }
                }

                ev == XmlPullParser.END_TAG -> when (n) {
                    "p" -> {
                        if (inPara) {
                            val c = cur
                            if (c != null && c.body.isNotEmpty()) c.body.append("\n")
                            inPara = false
                        }
                    }
                    "r" -> inRun = false
                    "sp", "pic" -> cur?.let { shapes.add(it) }
                }
            }
            ev = p.next()
        }

        // Slaytın şekilleri konum bilgisi taşıyor mu? Yer tutucular (title/body)
        // çoğu zaman xfrm içermez. O durumda 960x540 tuvale mutlak yerleştirmek
        // yerine AKIŞ düzeni kullanılır: başlık + maddeler okunur biçimde alt alta.
        val positioned = shapes.any { (it.x != null || it.y != null) && it.img == null }
        val sb = StringBuilder()
        sb.append("<div class=\"slide\" style=\"")
            .append(if (positioned) "height:${slideH.toInt()}px" else "min-height:160px;padding:26px 30px 34px;box-sizing:border-box")
            .append("\">\n")
        shapes.forEach { sh ->
            val left = px(sh.x, scale)
            val top = px(sh.y, scale)
            val wpx = px(sh.w, scale)
            val hpx = px(sh.h, scale)
            if (sh.img != null) {
                if (!positioned) {
                    sb.append("<img class=\"fimg\" src=\"").append(sh.img).append("\">\n")
                    return@forEach
                }
                val style = StringBuilder()
                if (left != null) style.append("left:").append(left.toInt()).append("px;")
                if (top != null) style.append("top:").append(top.toInt()).append("px;")
                if (wpx != null) style.append("width:").append(wpx.toInt()).append("px;")
                if (hpx != null) style.append("height:").append(hpx.toInt()).append("px;")
                sb.append("<div class=\"pic\" style=\"").append(style).append("\">")
                    .append("<img src=\"").append(sh.img).append("\"></div>\n")
            } else if (sh.text || sh.body.isNotEmpty()) {
                val fp = if (sh.size != null) (sh.size!! * 96.0 / 72.0 * scale).toInt().coerceIn(12, 64) else 18
                if (!positioned) {
                    val alignCss = when (sh.align) {
                        "ctr" -> "text-align:center;"
                        "r" -> "text-align:right;"
                        "just" -> "text-align:justify;"
                        else -> ""
                    }
                    sb.append("<div class=\"ft\" style=\"font-size:")
                        .append(fp).append("px;").append(alignCss).append("\">")
                        .append(sh.body).append("</div>\n")
                    return@forEach
                }
                val style = StringBuilder()
                if (left != null) style.append("left:").append(left.toInt()).append("px;")
                if (top != null) style.append("top:").append(top.toInt()).append("px;")
                if (wpx != null) style.append("width:").append(wpx.toInt()).append("px;")
                if (hpx != null) style.append("height:").append(hpx.toInt()).append("px;")
                style.append("font-size:").append(fp).append("px;")
                when (sh.align) {
                    "ctr" -> style.append("text-align:center;")
                    "r" -> style.append("text-align:right;")
                    "just" -> style.append("text-align:justify;")
                }
                if (left == null || top == null) {
                    // Konumu olmayan yer tutucular: slaytın üstünde alt alta
                    sb.append("<div class=\"free\" style=\"top:")
                        .append((18 + free * 34)).append("px;font-size:")
                        .append(fp).append("px\">").append(sh.body).append("</div>\n")
                    free++
                } else {
                    sb.append("<div class=\"shape\" style=\"").append(style).append("\">")
                        .append(sh.body).append("</div>\n")
                }
            }
        }
        sb.append("<div class=\"no\">").append(num).append("</div>\n</div>\n")
        return sb.toString()
    }


    // ── DOCX ──────────────────────────────────────────────────

    private fun docxHtml(src: File, title: String, dir: File): String {
        val sb = StringBuilder(head(title))
        sb.append("<div class=\"doc\">\n")
        ZipFile(src).use { zip ->
            val bytes = readText(zip, "word/document.xml")
                ?: throw IllegalArgumentException("word/document.xml yok")
            val rel = rels(zip, "word/_rels/document.xml.rels", "word")

            val body = StringBuilder()
            val tbl = StringBuilder()
            val row = StringBuilder()
            var cell: StringBuilder? = null
            var inTbl = false
            var para: StringBuilder? = null
            var inRun = false
            var bold = false
            var italic = false
            var sizeHalf: Int? = null
            var color: String? = null
            var styleName: String? = null
            var inCellPara = false
            var mediaCount = 0

            fun target() = cell ?: para ?: body
            fun closePara() {
                val b = para ?: return
                val cls = when {
                    styleName?.contains("Title", true) == true -> "h1"
                    styleName?.contains("Heading1", true) == true -> "h1"
                    styleName?.contains("Heading2", true) == true -> "h2"
                    styleName?.contains("Heading3", true) == true -> "h3"
                    else -> "p"
                }
                val extra = buildString {
                    sizeHalf?.let { append("font-size:").append(it / 2.0 * 96 / 72).append("px;") }
                    color?.let { append("color:#").append(it).append(';') }
                }
                val text = b.toString()
                val html = "<div class=\"$cls\"" +
                    (if (extra.isEmpty()) "" else " style=\"$extra\"") + ">" +
                    (if (text.isEmpty()) "&nbsp;" else text) + "</div>\n"
                target().append(html)
                para = null
                bold = false; italic = false; sizeHalf = null; color = null; styleName = null
            }

            val p = parser(bytes)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                val n = local(p.name)
                when {
                    ev == XmlPullParser.START_TAG -> when (n) {
                        "Fallback" -> skipSubtree(p)
                        "tbl" -> { inTbl = true; tbl.setLength(0) }
                        "tr" -> row.setLength(0)
                        "tc" -> { cell = StringBuilder(); inCellPara = false }
                        "p" -> { para = StringBuilder(); inCellPara = cell != null }
                        "pStyle" -> styleName = attr(p, "val")
                        "r" -> inRun = true
                        "rPr" -> {
                            bold = false; italic = false; sizeHalf = null; color = null
                        }
                        // w:sz w:val="24" → 12 pt (yarım punto)
                        "sz" -> if (inRun) sizeHalf = attr(p, "val")?.toIntOrNull()
                        "b" -> if (inRun) bold = true
                        "i" -> if (inRun) italic = true
                        "color" -> if (inRun) color = attr(p, "val")
                        "t" -> {
                            val t = try { p.nextText() } catch (_: Exception) { "" }
                            val buf = para
                            if (buf != null && t.isNotEmpty()) {
                                val st = StringBuilder()
                                if (bold) st.append("font-weight:700;")
                                if (italic) st.append("font-style:italic;")
                                buf.append("<span style=\"").append(st).append("\">")
                                    .append(esc(t)).append("</span>")
                            }
                        }
                        "br" -> para?.append("<br>")
                        "tab" -> para?.append("&nbsp;&nbsp;&nbsp;&nbsp;")
                        "blip" -> {
                            val id = attr(p, "embed") ?: attr(p, "link")
                            val t = id?.let { rel[it] }
                            val f = t?.let { media(zip, it, dir) }
                            if (f != null && mediaCount < 60) {
                                mediaCount++
                                target().append("<img src=\"").append(f).append("\">")
                            }
                        }
                    }

                    ev == XmlPullParser.END_TAG -> when (n) {
                        "p" -> {
                            if (para != null) {
                                if (inCellPara) {
                                    cell?.append(para)
                                    para = null
                                    inCellPara = false
                                } else closePara()
                            }
                        }
                        "r" -> inRun = false
                        "tc" -> {
                            row.append("<td>").append(cell?.toString() ?: "").append("</td>")
                            cell = null
                        }
                        "tr" -> tbl.append("<tr>").append(row).append("</tr>\n")
                        "tbl" -> {
                            inTbl = false
                            body.append("<table>").append(tbl).append("</table>\n")
                            tbl.setLength(0)
                        }
                    }
                }
                ev = p.next()
            }
            sb.append(body)
        }
        sb.append("\n</div>")
        sb.append(foot())
        return sb.toString()
    }
}
