package com.umay.pdfreader

/**
 * v36.9 — METİN KALİTE ÖLÇÜSÜ (saf Kotlin, Android'siz → test edilebilir).
 *
 * Neden: kütüphanedeki DOCX/PPTX kitaplarının bir kısmı eski bir sürümde
 * **ZIP baytları metin sanılarak** PDF'e çevrilmiş. O kitaplar sesli okumada
 * "rà ?§[Np®ùq > f ..." gibi anlamsız şeyler söylüyordu.
 *
 * Bu sınıf iki şeyi ayırır:
 *  • gerçek yazı (Türkçe/İngilizce/Arapça, kod sayfaları dâhil)
 *  • çöp (ZIP/XML dökümü, bozuk font eşlemesi, kontrol karakterleri)
 *
 * Kural: "harf oranı" tek başına yetmez (şekil/tablo sayfaları da az harf
 * içerir) → ZIP imzası, değiştirme karakteri ve "anlamsız sembol" oranı
 * birlikte değerlendirilir.
 */
object TextQuality {

    /** ZIP/XML dökümünün kesin izleri (PPTX/DOCX/EPUB içi). */
    private val ZIP_MARKERS = listOf(
        "PK\u0001\u0002", "PK\u0003\u0004", "ppt/slides/", "ppt/_rels/",
        "word/document.xml", "xl/workbook.xml", "[Content_Types].xml",
        "META-INF/container.xml", "docProps/"
    )

    /** Gerçek metinde sık görülen noktalama (çöp sayılmayan). */
    private const val COMMON_PUNCT =
        ".,;:!?()[]{}<>+-*/=%@&#\"'|\\_^~`’‘“”…–—•·°،؛؟«»"

    /** Metin çöp mü? (boş metin çöp DEĞİLDİR: taranmış sayfa olabilir) */
    fun isGarbage(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        for (m in ZIP_MARKERS) if (t.contains(m, ignoreCase = true)) return true

        val sample = dotLeadersOut(if (t.length > 4000) t.take(4000) else t)
        val n = sample.length.toDouble()

        var letters = 0
        var digits = 0
        var spaces = 0
        var weird = 0
        var replacement = 0
        for (c in sample) {
            when {
                c == '\uFFFD' -> { replacement++; weird++ }
                // NOT: Arapça harekeler (fetha, esre…) birleşen işaret (Mn)
                // sınıfındadır; `isLetter()` bunlara false der → metin
                // yanlışlıkla "çöp" görünüyordu. Onları da harf sayıyoruz.
                c.isLetter() ||
                    Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> letters++
                c.isDigit() -> digits++
                c.isWhitespace() -> spaces++
                c in COMMON_PUNCT -> { /* normal noktalama */ }
                c.code in 0xE000..0xF8FF -> weird++      // özel kullanım alanı (bozuk font)
                c.code < 0x20 -> weird++                  // kontrol karakteri
                else -> weird++
            }
        }
        val meaningful = (letters + digits + spaces) / n
        val weirdShare = weird / n
        val replacementShare = replacement / n

        // Değiştirme karakteri yoğunsa kesin bozuk
        if (replacementShare > 0.02) return true
        // Anlamsız semboller çoksa (ZIP çöpünde ~%50)
        if (weirdShare > 0.35) return true
        // Neredeyse hiç harf/rakam/boşluk yoksa
        return meaningful < 0.55
    }

    /**
     * v44 — İÇİNDEKİLER NOKTALARI ("1.1 Giris........ 9").
     *
     * Gerçek olay: "Kaynak Kitap.pdf" bozuk sanılıyordu; oysa metin sağlamdı,
     * sadece içindekiler sayfasındaki nokta dizileri "anlamsız sembol" gibi
     * ölçülüp harf oranını düşürüyordu. Nokta dizileri ölçümden önce boşluğa
     * çevrilir (sayfa metnine dokunulmaz).
     */
    private fun dotLeadersOut(t: String): String =
        if (t.contains("...")) t.replace(Regex("\\.{3,}"), " ") else t

    /** Harf oranı (0..1) — rapor/ölçüm için. */
    fun letterShare(text: String): Double {
        if (text.isEmpty()) return 0.0
        return text.count { it.isLetter() }.toDouble() / text.length
    }

    /** Sayfa metinlerinden kitap düzeyinde karar: örneklerin çoğu çöp mü? */
    fun mostlyGarbage(pages: List<String>): Boolean {
        val nonBlank = pages.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return false
        val bad = nonBlank.count { isGarbage(it) }
        return bad >= (nonBlank.size * 2 + 2) / 3   // ~2/3
    }

    /**
     * v37 — BOZUK DÖNÜŞÜM (mojibake) İMZASI.
     *
     * Gerçek olay: eski sürüm, PPTX/DOCX dosyalarının **ZIP baytlarını** düz
     * metin sanıp windows-1254 ile çözmüştü. Ortaya çıkan sayfa metni
     * "PK ! vz™ ± [Content_Types].xml ¢ ( ´•MK@" gibidir: harf oranı ~%30,
     * geri kalanı sembol/aksan yığını.
     *
     * Ölçüm (cihazdan alınan gerçek sayfalar):
     *  • bozuk sayfalar : anlamsız sembol oranı %20–30
     *  • Türkçe metin   : %0–1
     *  • kod/matematik  : %1–3   (parantez, =, +, / COMMON_PUNCT'te)
     *  • Arapça meal    : %0–1
     *
     * Bu yüzden %12 eşiği güvenli bir ayrım noktasıdır.
     */
    fun isMojibake(text: String): Boolean {
        val t = text.trim()
        if (t.length < 60) return false
        val sample = dotLeadersOut(if (t.length > 4000) t.take(4000) else t)
        var weird = 0
        for (c in sample) {
            when {
                c.isLetterOrDigit() || c.isWhitespace() -> {}
                // Arapça harekeler birleşen işaret (Mn) — gerçek metindir
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}
                c in COMMON_PUNCT -> {}
                else -> weird++
            }
        }
        return weird.toDouble() / sample.length >= 0.12
    }

    /** Sayfa metinlerinden: örneklerin çoğu bozuk dönüşüm mü? */
    fun mostlyMojibake(pages: List<String>): Boolean {
        val nonBlank = pages.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return false
        val bad = nonBlank.count { isMojibake(it) }
        return bad >= (nonBlank.size * 2 + 2) / 3
    }
}
