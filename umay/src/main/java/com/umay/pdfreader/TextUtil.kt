package com.umay.pdfreader

import java.util.Locale

/**
 * Metin yardımcıları: cümle ayırma, Türkçe/İngilizce anlaşılması,
 * çevrimdışı soru-cevap araması ve özetleme.
 */
object TextUtil {

    // ── Cümle ayırma ──────────────────────────────────────────

    /**
     * Sayfa metnini seslendirilecek parçalara böler.
     * Noktalama ve satır sonlarına göre cümleleştirir, çok kısa
     * parçaları birleştirir, tek parça 450 karakteri geçmez.
     */
    fun speechChunks(pageText: String): List<String> {
        val cleaned = pageText
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
        if (cleaned.isEmpty()) return emptyList()

        // Cümlelere böl
        val parts = cleaned
            .split(Regex("(?<=[.!?…])\\s+|(?<=\\n)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = mutableListOf<String>()
        val buf = StringBuilder()

        fun flush() {
            if (buf.isNotBlank()) chunks.add(buf.toString().trim())
            buf.setLength(0)
        }

        for (p in parts) {
            if (buf.length + p.length + 1 > 450) {
                // Tek başına çok uzun bir parça: zorla böl
                if (buf.isEmpty() && p.length > 450) {
                    var start = 0
                    while (start < p.length) {
                        var end = (start + 450).coerceAtMost(p.length)
                        if (end < p.length) {
                            val space = p.lastIndexOf(' ', end)
                            if (space > start + 300) end = space
                        }
                        chunks.add(p.substring(start, end).trim())
                        start = end
                    }
                } else {
                    flush()
                    buf.append(p)
                }
            } else {
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(p)
            }
        }
        flush()
        return chunks
    }

    // ── Dil anlaşılması ──────────────────────────────────────

    private val trChars = Regex("[çÇğĞıİöÖşŞüÜ]")

    /** Metin Türkçe görünüyor mu? */
    fun looksTurkish(text: String): Boolean {
        val sample = text.take(3000)
        if (trChars.containsMatchIn(sample)) return true
        val lower = " " + sample.lowercase(Locale.ROOT) + " "
        val trWords = listOf(
            " ve ", " bir ", " için ", " olarak ", " olan ", " değildir ",
            " daha ", " çok ", " ile ", " bu ", " ne ", " nasıl "
        )
        val hits = trWords.count { lower.contains(it) }
        return hits >= 4
    }

    /**
     * Bu sayfa çevrilmeli mi? (Ayar açık + İngilizce görünüyor + Türkçe değil)
     */
    fun needsTranslation(pageText: String, translateEnabled: Boolean): Boolean {
        if (!translateEnabled) return false
        val t = pageText.trim()
        if (t.length < 20) return false
        if (looksTurkish(t)) return false
        // İngilizce ipuçları
        val lower = " " + t.lowercase(Locale.ROOT).take(3000) + " "
        val enWords = listOf(
            " the ", " and ", " of ", " to ", " in ", " is ", " are ",
            " for ", " that ", " with ", " as ", " on ", " this "
        )
        val hits = enWords.count { lower.contains(it) }
        return hits >= 3
    }

    // ── Çevrimdışı soru-cevap ────────────────────────────────

    data class Hit(val page: Int, val sentence: String, val score: Double)

    /** Türkçe küçük harfe çevirip karakterleri sadeleştirir (arama anahtarı). */
    private val trLocale = Locale.forLanguageTag("tr-TR")

    /** Türkçe karakterleri sadeleştiren küçük harfli normalizasyon. */
    fun normalize(s: String): String =
        // tr locale: "İ" doğru şekilde "i" olur (ROOT'da nokta + i kalırdı)
        s.lowercase(trLocale)
            .replace("ı", "i").replace("ş", "s").replace("ğ", "g")
            .replace("ç", "c").replace("ö", "o").replace("ü", "u")

    /** Küçük Türkçe-İngilizce stopword listesi. */
    private val stop = setOf(
        "ve", "veya", "ile", "icin", "bir", "bu", "su", "o", "the", "of",
        "to", "in", "is", "are", "for", "and", "or", "on", "at", "by",
        "ne", "nedir", "nasil", "mi", "mu", "miydi", "what", "how", "when",
        "mi?", "olarak", "olan", "daha", "cok", "very", "with", "that", "this"
    )

    /** Sorudaki anlamlı kelimeler. */
    fun queryTerms(q: String): List<String> =
        normalize(q)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in stop }
            .distinct()

    /**
     * PDF metni üzerinde çevrimdışı arama: soru kelimelerini içeren
     * cümleleri puanlayıp sayfa numarasıyla döndürür.
     */
    fun search(pages: List<String>, question: String, maxHits: Int = 4): List<Hit> {
        val terms = queryTerms(question)
        if (terms.isEmpty()) return emptyList()

        val hits = mutableListOf<Hit>()
        pages.forEachIndexed { pageIndex, pageText ->
            if (pageText.isBlank()) return@forEachIndexed
            val sentences = splitRough(pageText)
            for (s in sentences) {
                if (s.length < 25) continue
                val ns = normalize(s)
                var score = 0.0
                for (t in terms) {
                    if (ns.contains(t)) score += 1.0
                }
                if (score > 0) {
                    // Terim yoğunluğu (kısa cümleler öncelikli)
                    score /= Math.sqrt(s.length.coerceAtLeast(50) / 50.0)
                    hits.add(Hit(pageIndex, s.trim(), score))
                }
            }
        }
        return hits.sortedByDescending { it.score }.take(maxHits)
    }

    private fun splitRough(text: String): List<String> =
        text.split(Regex("(?<=[.!?…])\\s+|(?<=\\n)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Doğal (sayıya duyarlı) sıralama anahtarı: `sayfa_9` → `sayfa_000000000009`,
     * böylece `sayfa_10` ondan sonra gelir. Rakam içermeyen adlarda sıra bozulmaz.
     * Kitap taramada sayfalar dosya adına göre sıralanır (page_0001.jpg …).
     */
    fun naturalKey(name: String): String =
        name.lowercase(Locale.ROOT)
            .replace(Regex("\\d+")) { m -> m.value.padStart(12, '0') }

    /**
     * Sohbet (Gemini) için bağlam sayfaları.
     *
     * `search` cümle bazlı ve katıydı: "Yasin süresi kaç ayet" gibi bir soruda
     * hiç eşleşme çıkabiliyor ve model boş bağlamla "kitapta geçmiyor"
     * diyordu. Burada sayfa bazlı, kısmi eşleşmeli puanlama yapılıyor:
     * sorudaki her kelime (gövdesi dahil) sayfada kaç kez geçiyor → en yüksek
     * puanlı sayfalar sırayla bağlama giriyor. Eşleşme yoksa kitabın ilk
     * metinli sayfaları gönderilir.
     *
     * @param maxPages bağlağa girecek azami sayfa
     * @param maxChars toplam karakter bütçesi (token maliyeti/süre sınırı)
     */
    fun contextPages(
        pages: List<String>,
        question: String,
        maxPages: Int = 8,
        maxChars: Int = 6000
    ): List<String> {
        val terms = queryTerms(question)

        val candidates = pages.mapIndexedNotNull { i, text ->
            if (text.isBlank()) return@mapIndexedNotNull null
            if (terms.isEmpty()) return@mapIndexedNotNull i to 0.0
            val nt = normalize(text)
            var hits = 0.0
            for (t in terms) {
                var at = nt.indexOf(t)
                while (at >= 0) {
                    hits += 1.0
                    at = nt.indexOf(t, at + t.length)
                }
            }
            if (hits > 0.0) i to hits else null
        }

        val ranked = if (candidates.isEmpty()) {
            pages.indices.filter { pages[it].isNotBlank() }.take(maxPages)
        } else {
            candidates.sortedByDescending { it.second }
                .take(maxPages)
                .map { it.first }
                .sorted()                       // okuma sırasını koru
        }

        val out = mutableListOf<String>()
        var used = 0
        for (i in ranked) {
            if (used >= maxChars) break
            val chunk = pages[i].trim().take(maxChars - used)
            if (chunk.isEmpty()) continue
            out += "Sayfa ${i + 1}: $chunk"
            used += chunk.length
        }
        return out
    }

    // ── Karaoke: parça hangi satırların üzerinden geçiyor? ────

    /** Boşluk kümelerini tek boşluğa indirger (eşleştirme anahtarı). */
    fun collapseWs(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /**
     * Seslendirilen parça (chunk) hangi satırların üzerinden geçiyor?
     * Karaoke vurgusu için: dönen değer [lineTexts] içindeki 0 tabanlı
     * indeksler. Metinler değişebilir (çeviri, sembol dönüşümü) veya
     * kutular eksik olabilir → eşleşme yoksa boş liste (vurgusuz devam).
     */
    fun matchingLines(chunk: String, lineTexts: List<String>): List<Int> {
        val target = collapseWs(chunk)
        if (target.isEmpty() || lineTexts.isEmpty()) return emptyList()

        // Satırları tek boşlukla birleştir; her satırın aralığını tut
        val joined = StringBuilder()
        val starts = IntArray(lineTexts.size)
        val ends = IntArray(lineTexts.size)
        lineTexts.forEachIndexed { i, raw ->
            if (joined.isNotEmpty()) joined.append(' ')
            starts[i] = joined.length
            joined.append(collapseWs(raw))
            ends[i] = joined.length
        }
        val hay = joined.toString()

        var at = hay.indexOf(target)
        if (at < 0) {
            // Büyük/küçük harf duarsız yedek (OCR bazı harfleri değiştirir)
            at = hay.lowercase(Locale.ROOT).indexOf(target.lowercase(Locale.ROOT))
        }
        if (at < 0) return emptyList()

        val end = at + target.length
        val hit = mutableSetOf<Int>()
        for (i in lineTexts.indices) {
            // [starts[i], ends[i]) aralığı [at, end) ile kesişiyorsa
            if (starts[i] < end && ends[i] > at) hit.add(i)
        }
        return hit.toList().sorted()
    }

    /** "Özet yaz" komutu için sayfalar arasına dağılmış özet parçaları. */
    fun summarize(pages: List<String>, maxChars: Int = 1400): String {
        val nonEmpty = pages.mapIndexedNotNull { i, t -> if (t.isNotBlank()) i to t else null }
        if (nonEmpty.isEmpty()) return "Bu PDF'te henüz metin yok."

        val sb = StringBuilder()
        // Giriş + aralıklı sayfalardan ilk cümleler
        val step = (nonEmpty.size / 10).coerceAtLeast(1)
        var idx = 0
        var i = 0
        while (i < nonEmpty.size && sb.length < maxChars) {
            val (pageNo, text) = nonEmpty[i]
            val first = splitRough(text).firstOrNull() ?: ""
            if (first.isNotBlank()) {
                sb.append("• Sayfa ${pageNo + 1}: ")
                sb.append(first.take(220))
                sb.append("\n\n")
                idx++
            }
            i += step
        }
        if (sb.isEmpty()) sb.append("Özet üretilemedi.")
        return sb.toString().trim()
    }

    /**
     * Okuma yüzdesi (ADIM 2 — kapak rozetleri ve Hero raf için).
     * 0 tabanlı `page` verilir; 1. sayfa %1, son sayfa %100 demektir.
     * Geçersiz girdilerde 0 döner (kitap hiç okunmamış sayılır).
     */
    fun readPercent(page: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        if (page < 0) return 0
        val p = page.coerceAtMost(pageCount - 1)
        return ((p + 1) * 100 / pageCount).coerceIn(0, 100)
    }

    // ── Sembol → konuşma metni ──────────────────────────────

    /** İki karakterli yazılım işlemcileri (tek karakterlilerden ÖNCE uygulanır). */
    private val TWO_CHAR = listOf(
        "===" to " eşittir ", "==" to " eşittir ",
        "!==" to " eşit değil ", "!=" to " eşit değil ",
        "<=" to " küçük veya eşittir ", ">=" to " büyük veya eşittir ",
        "&&" to " ve ", "||" to " veya ",
        "+=" to " artı eşittir ", "-=" to " eksi eşittir ",
        "*=" to " çarpı eşittir ", "/=" to " bölü eşittir ",
        "->" to " ise ", "=>" to " ise ",
        "::" to " ", "++" to " artı ", "--" to " ",
        "//" to " ", "/*" to " ", "*/" to " ",
        "\r\n" to " \n"
    )

    /** Tek karakterli / tek codepoint semboller → Türkçe okunuşu. */
    private val SYMBOLS = listOf(
        // Temel matematik
        "=" to " eşittir ", "<" to " küçüktür ", ">" to " büyüktür ",
        // DİKKAT: düz tire "-" okunmaz (kelime, tarih aralığı bozulmasın);
        // yalnızca Unicode eksi işareti (−) söylenir.
        "+" to " artı ", "−" to " eksi ", "×" to " çarpı ", "✕" to " çarpı ",
        "÷" to " bölü ", "±" to " artı eksi ", "^" to " üzeri ",
        "≈" to " yaklaşık eşittir ", "≠" to " eşit değil ", "≡" to " eşittir ",
        "≤" to " küçük veya eşittir ", "≥" to " büyük veya eşittir ",
        "√" to " karekök ", "∛" to " küp kök ",
        // Analiz / cebir
        "∑" to " toplam ", "∏" to " çarpım ", "∫" to " integral ",
        "∞" to " sonsuz ", "∂" to " kısmi türev ", "∇" to " gradyan ",
        "∀" to " tümü için ", "∃" to " vardır ", "∝" to " orantılıdır ",
        // Kümeler / mantık
        "∈" to " üyesidir ", "∉" to " üyesi değil ", "∪" to " birleşimi ",
        "∩" to " kesişimi ", "⊂" to " alt kümesi ", "⊆" to " alt kümesi veya eşit ",
        "∅" to " boş küme ", "⊥" to " dik ", "∥" to " paralel ",
        "→" to " ise ", "⇒" to " ise ", "⟶" to " ise ",
        "←" to " geri ", "⇐" to " geri ", "⇔" to " eşdeğerdir ",
        // Yazılım / genel
        "&" to " ve ", "@" to " et ", "#" to " numara ", "|" to " ",
        ";" to " , ", "~" to " yaklaşık ", "_" to " ",
        "{" to " ", "}" to " ", "[" to " ", "]" to " ", "\\" to " ", "`" to " ",
        // Para birimleri
        "₺" to " TL ", "€" to " avro ", "$" to " dolar ", "£" to " sterlin ",
        "¥" to " yen ", "¢" to " sent ",
        // Derece, telif, noktalama
        "°" to " derece ", "©" to " telif hakkı ", "®" to " tescilli ",
        "™" to " marka ", "§" to " paragraf ", "¶" to " paragraf ",
        "†" to " yıldız ",
        // Bullet / çizgi / tırnak (okunmaz, yalnız ayırır)
        "•" to " ", "‣" to " ", "◦" to " ", "●" to " ", "▪" to " ", "·" to " ",
        "—" to " , ", "–" to " , ", "―" to " , ",
        "“" to " ", "”" to " ", "„" to " ", "«" to " ", "»" to " ",
        "‘" to " ", "’" to " ",
        // Ligatür (PDF fontlarından gelir)
        "ﬁ" to "fi", "ﬂ" to "fl", "ﬀ" to "ff", "ﬃ" to "ffi", "ﬄ" to "ffl",
        "µ" to " mikro "
    )

    /** Yunan harfleri (matematik/fizik formülleri). */
    private val GREEK = listOf(
        "α" to " alfa ", "β" to " beta ", "γ" to " gamma ", "δ" to " delta ",
        "ε" to " epsilon ", "ζ" to " zeta ", "η" to " eta ", "θ" to " teta ",
        "ι" to " iota ", "κ" to " kappa ", "λ" to " lambda ", "μ" to " mi ",
        "ν" to " ni ", "ξ" to " ksi ", "ο" to " omikron ", "π" to " pi ",
        "ρ" to " ro ", "σ" to " sigma ", "ς" to " sigma ", "τ" to " tau ",
        "υ" to " ipsilon ", "φ" to " fi ", "χ" to " hi ", "ψ" to " psi ",
        "ω" to " omega ",
        "Α" to " alfa ", "Β" to " beta ", "Γ" to " gamma ", "Δ" to " delta ",
        "Θ" to " teta ", "Λ" to " lambda ", "Ξ" to " ksi ", "Π" to " pi ",
        "Σ" to " sigma ", "Φ" to " fi ", "Ψ" to " psi ", "Ω" to " omega "
    )

    /** Üst simgeler: x², x³ anlamlı; diğerleri düz rakama. */
    private val SUPERSCRIPT = listOf(
        "²" to " kare ", "³" to " küp ",
        "¹" to " 1 ", "⁰" to " 0 ", "⁴" to " 4 ", "⁵" to " 5 ",
        "⁶" to " 6 ", "⁷" to " 7 ", "⁸" to " 8 ", "⁹" to " 9 ",
        "₀" to " 0 ", "₁" to " 1 ", "₂" to " 2 ", "₃" to " 3 ",
        "₄" to " 4 ", "₅" to " 5 ", "₆" to " 6 ", "₇" to " 7 ",
        "₈" to " 8 ", "₉" to " 9 "
    )

    /** LaTeX parçacıkları (matematik kitaplarında OCR'dan kaçabilir). */
    private val LATEX = listOf(
        "\\frac" to " ", "\\sqrt" to " karekök ", "\\times" to " çarpı ",
        "\\div" to " bölü ", "\\cdot" to " ", "\\pm" to " artı eksi ",
        "\\le" to " küçük veya eşittir ", "\\ge" to " büyük veya eşittir ",
        "\\neq" to " eşit değil ", "\\approx" to " yaklaşık eşittir ",
        "\\infty" to " sonsuz ", "\\sum" to " toplam ", "\\int" to " integral ",
        "\\pi" to " pi ", "\\alpha" to " alfa ", "\\beta" to " beta ",
        "\\gamma" to " gamma ", "\\delta" to " delta ", "\\theta" to " teta ",
        "\\lambda" to " lambda ", "\\mu" to " mi ", "\\sigma" to " sigma ",
        "\\omega" to " omega ", "\\Delta" to " delta ", "\\rightarrow" to " ise ",
        "\\to" to " ise ", "\\in" to " üyesidir ", "\\subset" to " alt kümesi ",
        "\\cup" to " birleşimi ", "\\cap" to " kesişimi ", "\\ldots" to " ... ",
        "\\quad" to " ", "\\qquad" to " ", "\\;" to " ", "\\," to " ", "\\!" to " "
    )

    /** Yaygın Türkçe kısaltmalar (motor "vesi" gibi yanlış okur). */
    private val ABBREV = listOf(
        Regex("(?<![A-Za-z])vb\\.") to " ve benzeri ",
        Regex("(?<![A-Za-z])vs\\.") to " vesaire ",
        Regex("(?<![A-Za-z])örn\\.", RegexOption.IGNORE_CASE) to " örnek ",
        Regex("(?<![A-Za-z])ör\\.") to " örnek ",
        Regex("(?<![A-Za-z])Dr\\.") to " Doktor ",
        Regex("(?<![A-Za-z])Prof\\.") to " Profesör ",
        Regex("(?<![A-Za-z])Doç\\.") to " Doçent "
    )

    /**
     * Sembolleri Türkçe okunuşlarıyla değiştirir; TTS'e giden metin
     * bu fonksiyondan geçer (ekrandaki metin DEĞİŞMEZ).
     *
     * Örnek: "x² + y² ≤ 100 → %50 artış" →
     *        "x kare artı y kare küçük veya eşittir 100 ise yüzde 50 artış"
     */
    fun forSpeech(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = raw

        // HTML/XML etiketleri tamamen çık (matematikte < harfle başlamaz:
        // "n<10" gibi ifadelere dokunmaz)
        s = s.replace(Regex("</?[A-Za-z][A-Za-z0-9]*(?:\\s[^<>]*?)?/?>"), " ")

        LATEX.forEach { (k, v) -> s = s.replace(k, v) }
        TWO_CHAR.forEach { (k, v) -> s = s.replace(k, v) }

        // Üst simge kalıpları: x^2 → x kare, x^4 → x üzeri 4
        s = s.replace(Regex("\\^2"), " kare ")
        s = s.replace(Regex("\\^3"), " küp ")
        s = s.replace(Regex("\\^(\\d+)")) { " üzeri " + it.groupValues[1] + " " }
        // Alt simge kalıpları: a_1 → a 1
        s = s.replace(Regex("_([A-Za-z0-9]+)")) { " " + it.groupValues[1] + " " }
        SUPERSCRIPT.forEach { (k, v) -> s = s.replace(k, v) }

        // Yüzde: hem "5%" hem "%5" → "yüzde 5" (Türkçe okunuş bu)
        s = s.replace(Regex("(\\d[\\d.,]*)\\s*%")) { "yüzde " + it.groupValues[1] }
        s = s.replace(Regex("%\\s*(\\d[\\d.,]*)")) { "yüzde " + it.groupValues[1] }

        // Yıldızlar: **bold** / 5*3 → çarpı
        s = s.replace(Regex("\\*+"), " çarpı ")

        GREEK.forEach { (k, v) -> s = s.replace(k, v) }
        SYMBOLS.forEach { (k, v) -> s = s.replace(k, v) }
        ABBREV.forEach { (re, v) -> s = re.replace(s, v) }

        // Sıkışmış boşlukları aç, noktalama öncesi fazla boşluğu temizle
        s = s.replace(Regex("[ \\t]{2,}"), " ")
        s = s.replace(Regex(" +([,.;:!?])"), "$1")
        s = s.replace(Regex("\\n +"), "\n")
        return s.trim()
    }
}
