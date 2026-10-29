package com.umay.pdfreader

import java.util.Locale

/**
 * Metin yardımcıları: cümle ayırma, Türkçe/İngilizce anlaşılması,
 * çevrimdışı soru-cevap araması ve özetleme.
 */
object TextUtil {

    /**
     * v5 — Ders programından çıkarılan tek satır (OCR/PDF içe aktarma).
     * `day` 0=Pazartesi … 6=Pazar, `minuteOfDay` ders BAŞLANGICI.
     */
    data class ParsedClass(
        val day: Int,
        val minuteOfDay: Int,
        val name: String,
        val room: String
    )

    // ── Cümle ayırma ──────────────────────────────────────────

    /**
     * Sayfa metnini seslendirilecek parçalara böler.
     * Noktalama ve satır sonlarına göre cümleleştirir, çok kısa
     * parçaları birleştirir, tek parça 450 karakteri geçmez.
     */
    /**
     * v33 — KOD SATIRI SÜZGECİ (kullanıcı isteği).
     *
     * "Veri Yapıları" gibi kitaplarda sayfanın yarısı C/Java kodu oluyor;
     * sesli okuma kodu sembol sembol heceliyor ve anlaşılmaz gürültü
     * üretiyor. Bu yüzden kod satırları SESLENDİRİLMEZ; sayfadaki düz metin
     * normal okunur. Ekrandaki görüntü ve vurgulama değişmez.
     */
    private val codeLineStart = Regex(
        "^\\s*(@{1,2}[A-Za-z]|#include|#define|#import|import\\s|from\\s+\\S+\\s+import|package\\s|using\\s+namespace|namespace\\s|" +
            "public\\s|private\\s|protected\\s|internal\\s|static\\s|final\\s|class\\s|interface\\s|enum\\s|struct\\s|" +
            "fun\\s|def\\s|void\\s|int\\s+[A-Za-z_]|float\\s|double\\s|char\\s|bool\\s|boolean\\s|" +
            "String\\s+[a-z]|var\\s|val\\s|let\\s|const\\s|return\\b|break\\b|continue\\b|throw\\s|" +
            "for\\s*\\(|while\\s*\\(|if\\s*\\(|else\\s*\\{|switch\\s*\\(|case\\s|try\\s*\\{|catch\\s*\\(|" +
            "System\\.out|console\\.log|printf\\s*\\(|cout\\s*<<|cin\\s*>>|print\\s*\\(|" +
            "SELECT\\s|INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|DELETE\\s+FROM|CREATE\\s+TABLE)"
    )

    /** Satır kod mu (sesli okumada atlanacak mı)? */
    fun looksLikeCodeLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        // 0) Yalnız noktalama ("}", ")", ";") — okunacak bir şey yok
        if (t.length < 5) return t.none { it.isLetterOrDigit() }
        // 1) Yorum satırları ve önişlemci (C/Java/JS) — kod sayılır
        if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*/") ||
            t.startsWith("* ") || t.startsWith("#include") || t.startsWith("#define") ||
            t.startsWith("-- ")
        ) return true
        // 2) Kod anahtar sözcüğüyle başlıyor
        if (codeLineStart.containsMatchIn(t)) return true
        // 2) Sembol yoğunluğu yüksek (kod noktalaması)
        val symbols = t.count { it in "{}[]();=<>|&*/#\\_\$@" }
        if (symbols >= 3 && symbols * 6 >= t.length) return true
        // 3) Satır kod bloğu kapanışı/açılışı gibi bitiyor
        if ((t.endsWith("{") || t.endsWith("}") || t.endsWith(";")) && symbols >= 1) return true
        return false
    }

    /**
     * v33 — ARAPÇA NORMALİZASYONU.
     *
     * PDF'ten gelen Arapça bazen "sunum biçimi" (U+FB50–U+FEFF) harflerle ve
     * tatweel (U+0640) ile geliyor; ses motoru bunları hece hece okuyup
     * anlaşılmaz hale getiriyordu. NFKC bu harfleri normal biçime çevirir.
     */
    fun normalizeArabic(text: String): String {
        if (text.isEmpty() || text.none { isArabicChar(it) }) return text
        return try {
            java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .replace("\u0640", "")
        } catch (_: Throwable) {
            text
        }
    }

    fun speechChunks(pageText: String): List<String> {
        // v33 — kod satırları seslendirilmez (düz metin okunur)
        val textOnly = if (pageText.contains('\n')) {
            pageText.lines().filterNot { looksLikeCodeLine(it) }.joinToString("\n")
        } else pageText
        val cleaned = normalizeArabic(textOnly)
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
     * v16 (YENİ GÖREV 7) — FORMÜL SANİTİZASYONU: LaTeX / Markdown matematik
     * kalıplarını DÜZ METNE çevirir (\hat{y} → y, $\sigma^2$ → sigma kare,
     * \frac{a}{b} → a / b). Böylece istatistik/regresyon formülleri TTS'e
     * "yanıt vermedi" hatası olarak geri dönmez.
     *
     * Sıra ÖNEMLİ: yapısal dönüşümler burada, Yunan/simge sözlükleri
     * `forSpeech` içinde çalışır → bilinmeyen `\komut`lar da LATEX sonrası
     * temizlenir.
     */
    fun sanitizeText(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = raw
        // 1) Matematik sınırlayıcıları: $$…$$, $…$, \(…\), \[…\]
        s = s.replace(Regex("""\$\$([^$]*)\$\$"""), " $1 ")
        s = s.replace(Regex("""\$([^$]*)\$"""), " $1 ")
        s = s.replace(Regex("""\$"""), " ")
        s = s.replace(Regex("""\\\(|\\\)|\\\[|\\\]"""), " ")
        // 2) Aksan/vurgu komutları: içeriği KORU, komutu at
        s = s.replace(
            Regex("""\\(?:hat|bar|tilde|vec|dot|ddot|widehat|overline)\{([^{}]*)\}""")
        ) { it.groupValues[1] }
        // 3) Metin sarmalayıcıları: \text{…}, \mathrm{…}, \mathbf{…}
        s = s.replace(
            Regex("""\\(?:text|mathrm|mathbf|textbf|operatorname)\{([^{}]*)\}""")
        ) { it.groupValues[1] }
        // 4) \frac{a}{b} → a / b · \sqrt{x} → karekök x (LATEX sözlüğü yutar)
        s = s.replace(
            Regex("""\\frac\{([^{}]*)\}\{([^{}]*)\}""")
        ) { " ${it.groupValues[1]} / ${it.groupValues[2]} " }
        s = s.replace(Regex("""\\sqrt\{([^{}]*)\}""")) { " karekök ${it.groupValues[1]} " }
        // 5) Artık boş kalmış gruplar: {y} → y
        s = s.replace(Regex("""\{([^{}]*)\}"""), " $1 ")
        return speechSafe(s)
    }

    /**
     * TTS'e GİDEN metnin son süzgeci (v16 GÖREV 3 — TTS ÇÖKME KORUMASI).
     *
     * Neden gerekli:  • Yetim surrogate (yarım emoji) Edge TTS WebSocket'ini
     * patlatır  • Biçim kontrol karakterleri (LRM/RLM/ZWJ, U+200B…U+FEFF)
     * ses motorunda kilitlenmeye yolaçar  • Arapça karışık metin Türkçe
     * locale'de okunamaz (dil yönlendirmesi ister)  • Bilinmeyen çizim
     * sembolleri "kutu" sesi üretir.
     *
     * SAF fonksiyon (Android bağımlı değil) → TextUtilSpeechTest ile sınanır.
     */
    /**
     * v16 (GÖREV 3) — SÜZGEÇ TABLOLARI (regex DEĞİL, code-point döngüsüyle okunur).
     *
     * NEDEN REGEX DEĞİL: Java `Pattern` surrogate ÇİFTLERİNİ (emoji, nadir yazı
     * sistemleri) `[\uD800-\uDBFF][\uDC00-\uDFFF]` karakter sınıfıyla
     * **eşleştirmez** (JDK 17 ile doğrulandı: `matcher.find() == false`).
     * Bu yüzden `speechSafe` tek geçişte `codePointAt` ile gezer.
     */
    private const val CP_ORPHAN_LO = 0xD800
    private const val CP_ORPHAN_HI = 0xDFFF

    /** Sıfır-genişlik + yön/biçim işaretleri (ZWNJ/RLM/ALM/emoji ZWJ/BOM…). */
    private val INVISIBLE_CODEPOINTS = setOf(
        0x00AD, 0x061C, 0x180E,
        0x200B, 0x200C, 0x200D, 0x200E, 0x200F,
        0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
        0x2060, 0x2061, 0x2062, 0x2063, 0x2064,
        0x2066, 0x2067, 0x2068, 0x2069,
        0xFEFF
    )

    /** Emoji/BMP çizim sembolleri (TTS bunları "kalp" diye okur → okunmamalı). */
    private fun isBmpDecoration(cp: Int): Boolean =
        cp in 0x2190..0x21FF || cp in 0x2300..0x23FF || cp in 0x25A0..0x27BF ||
            cp in 0x2B00..0x2BFF || cp in 0xFE00..0xFE0F

    /** Arapça alfabesi (Kur'an metinleri): Arapça-Latince karışık kontrolü. */
    private val ARABIC_RE = Regex("""[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]+""")
    private val LATIN_WORD_RE = Regex("""[A-Za-zÇĞİÖŞÜçğıöşü]{3,}""")

    /** Metin ağırlıklı olarak Arapça mı? (>%20 Arapça token → evet) */
    fun isArabicHeavy(text: String): Boolean {
        if (text.isBlank()) return false
        val sample = text.take(4000)
        val arabicChars = ARABIC_RE.findAll(sample).sumOf { it.value.length }
        val latinWords = LATIN_WORD_RE.findAll(sample).count()
        val total = arabicChars + latinWords * 3
        if (total == 0) return false
        return arabicChars * 100 / total >= 20
    }

    // ── v5.12: YAZI SİSTEMİNE GÖRE PARÇALAMA (Arapça + Türkçe karışık) ──────

    /** Arapça/İbranice bloklardaki bir karakter mi? */
    fun isArabicChar(c: Char): Boolean =
        c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' ||
            c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF'

    /** Metindeki Arapça karakter oranı (0..1) — ses seçimi için. */
    fun arabicRatio(text: String): Double {
        if (text.isBlank()) return 0.0
        var ar = 0
        var letters = 0
        for (c in text) {
            if (c.isLetter()) {
                letters++
                if (isArabicChar(c)) ar++
            }
        }
        return if (letters == 0) 0.0 else ar.toDouble() / letters
    }

    // ── v31 (OKUMA KALİTESİ) — BOZUK METİN KATMANI SÜZGECİ ────────────────
    //
    // Bazı PDF'ler (ör. Kur'an meali) gömülü Arapça fontları Unicode'a
    // eşlenmediği için metin katmanı çöp üretir: "0.,f� ::; r� > Q.1. .)".
    // Bu satırlar hem ekranda hem sesde anlamsız okunuyordu. Aşağıdaki iki
    // fonksiyon bu çöpü ayıklar; Arapça gerçekten okunacaksa OCR metni
    // (ArabicOcr) kullanılır.

    /**
     * Sözcük gerçekten okunabilir mi?
     *  • En az 2 harf (Arapça harekeler de harf sayılır) ve yarısından
     *    fazlası harf → gerçek sözcük.
     *  • Sadece sayı/noktalama → sayı (formül, dipnot) → korunur.
     *  • "0.,f", "::;", "Q.1.", "ts.)..." gibi font çöpü → atılır.
     */
    private fun isSpeakableWord(w: String): Boolean {
        if (w.isEmpty()) return false
        var forms = 0
        for (c in w) {
            if (c.isLetter() || isArabicChar(c)) forms++
        }
        if (forms >= 2 && forms.toDouble() / w.length >= 0.5) return true
        if (w.all { it.isDigit() || it in ".,%-+/=" }) return true
        return false
    }

    /** Metindeki "çöp sözcük" oranı (0..1). Bozuk font eşlemesinde yükselir. */
    fun garbageTokenShare(text: String): Double {
        if (text.isBlank()) return 0.0
        val sample = if (text.length > 8000) text.take(8000) else text
        var total = 0
        var bad = 0
        for (tok in sample.split(' ', '\n', '\t', '\r')) {
            if (tok.isBlank()) continue
            total++
            if (!isSpeakableWord(tok)) bad++
        }
        return if (total == 0) 0.0 else bad.toDouble() / total
    }

    /** Metinde bozuk font eşlemesi izi (değiştirme / özel kullanım karakteri) var mı? */
    fun hasFontBreakage(text: String): Boolean =
        text.any { it == '\uFFFD' || it in '\uE000'..'\uF8FF' }

    /**
     * Metin katmanının bozuk olup olmadığına dair sezgi.
     *
     * DİKKAT: yalnız "çok çöp sözcük" yeterli değil — kod sayfaları (C/Java)
     * da bol sembol üretir; onları yanlışlıkla "bozuk" sayıp okumayı
     * kırpmamak için asıl kanıt **değiştirme karakteri** aranır.
     */
    fun looksBrokenExtraction(text: String): Boolean {
        if (text.isBlank()) return false
        val sample = if (text.length > 8000) text.take(8000) else text
        if (hasFontBreakage(sample)) return true
        return garbageTokenShare(sample) >= 0.45
    }

    /**
     * Bozuk metin katmanındaki çöp sözcükleri atar; gerçek sözcükler,
     * sayılar ve noktalama korunur (satır yapısı bozulmaz).
     */
    fun dropGarbageWords(text: String): String {
        if (text.isBlank()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                out.append('\n')
                i++
                continue
            }
            if (c.isWhitespace()) {
                var j = i
                while (j < text.length && text[j].isWhitespace() && text[j] != '\n') j++
                out.append(' ')
                i = j
                continue
            }
            var j = i
            while (j < text.length && !text[j].isWhitespace()) j++
            var word = text.substring(i, j)
            if (word.any { it == '\uFFFD' || it in '\uE000'..'\uF8FF' }) {
                word = word.filter { it != '\uFFFD' && it !in '\uE000'..'\uF8FF' }
            }
            if (word.isNotEmpty() && isSpeakableWord(word)) {
                out.append(word).append(' ')
            }
            i = j
        }
        return out.toString().replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    /**
     * Parçaları yazı sistemine göre böler: bir ses parçası ASLA hem Arapça
     * hem Latin metin taşımaz. Aksi hâlde tek bir Edge sesi seçilir ve diğer
     * dil yanlış (anlaşılmaz) okunur — meal/tefsir sayfalarında tam bu oluyordu.
     *
     * Nötr karakterler (boşluk, rakam, noktalama) o anki bloğa eklenir.
     */
    fun splitByScript(chunks: List<String>): List<String> {
        val out = ArrayList<String>(chunks.size + 8)
        for (chunk in chunks) {
            if (chunk.isBlank()) continue
            val pieces = ArrayList<String>()
            val buf = StringBuilder()
            var current: Boolean? = null   // true = Arapça blok
            for (c in chunk) {
                val isArabic = isArabicChar(c)
                val neutral = !c.isLetter()
                if (!neutral && current != null && isArabic != current) {
                    // Çok kısa blokları ("و" gibi) ayrı parça yapma
                    if (buf.count { it.isLetter() } >= 2) {
                        pieces.add(buf.toString().trim())
                        buf.setLength(0)
                    }
                }
                if (!neutral) current = isArabic
                buf.append(c)
            }
            if (buf.isNotBlank()) pieces.add(buf.toString().trim())
            out.addAll(pieces.filter { it.isNotBlank() })
        }
        return out
    }

    /**
     * TTS güvenli sadeleştirme: kontrol/biçim karakterleri + yetim surrogate
     * + emoji temizlenir, boşluklar toplanır. Metin DEĞİL yalnızca bozulma
     * kaynağı olan karakterler atılır (görünen metne dokunulmaz).
     */
    fun speechSafe(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)

            // 1) Yalnız kalan surrogate (çift olmayan yarım emoji parçası) → SİL
            if (cp in CP_ORPHAN_LO..CP_ORPHAN_HI) continue
            // 1b) EK DİL ÖTESİ (emoji + nadir yazı sistemleri) — `codePointAt`
            //     çiftleri tek code-point olarak VERİR; charCount==2 → atla
            //     (çökme kaynağı; TTS yalnız BMP metni güvenle okur).
            if (Character.charCount(cp) == 2) { sb.append(' '); continue }

            // 2) Kontrol karakterleri (\n hariç) → boşluk
            if (cp < 0x20) {
                if (cp == '\n'.code || cp == '\r'.code) sb.append('\n') else sb.append(' ')
                continue
            }
            if (cp == 0x7F || cp in 0x80..0x9F) { sb.append(' '); continue }

            // 3) Sıfır-genişlik / yön / biçim işaretleri → tamamen at
            if (cp in INVISIBLE_CODEPOINTS) continue

            // 4) BMP emoji/çizim sembolleri → boşluk (paragraf noktalaması korunur)
            if (isBmpDecoration(cp)) { sb.append(' '); continue }

            sb.appendCodePoint(cp)
        }
        // 5) Boşlukları topla, noktalama öncesi fazla boşluğu temizle
        var s = sb.toString().replace(Regex("[ \\t]{2,}"), " ")
        s = s.replace(Regex(" +([,.;:!?])"), "$1")
        return s.trim()
    }

    /**
     * Sembolleri Türkçe okunuşlarıyla değiştirir; TTS'e giden metin
     * bu fonksiyondan geçer (ekrandaki metin DEĞİŞMEZ).
     *
     * Örnek: "x² + y² ≤ 100 → %50 artış" →
     *        "x kare artı y kare küçük veya eşittir 100 ise yüzde 50 artış"
     *
     * v16 (GÖREV 3): ilk satırda `speechSafe` uygulanır (çökme koruması)
     * ve Arapça ağır metinler TTS'e verilmeden ÖNCE ayıklanır.
     */
    fun forSpeech(raw: String): String {
        if (raw.isEmpty()) return raw
        // v16 (YENİ GÖREV 7): önce formül sanitizasyonu (LaTeX → düz metin),
        // ardından karakter süzgeci SpeechSafe dahil edilmiş hâlde devam eder.
        var s = sanitizeText(raw)

        // HTML/XML etiketleri tamamen çık (matematikte < harfle başlamaz:
        // "n<10" gibi ifadelere dokunmaz)
        s = s.replace(Regex("</?[A-Za-z][A-Za-z0-9]*(?:\\s[^<>]*?)?/?>"), " ")

        LATEX.forEach { (k, v) -> s = s.replace(k, v) }
        // v16 (YENİ GÖREV 7): LATEX sözlüğünde olmayan kalan `\komut`lar
        // da temizlenir (TTS "hat" gibi anlamsız parçalar okumasın)
        s = s.replace(Regex("""\\[A-Za-z]+"""), " ")
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

    // ── v5: Ders programı satır ayrıştırma (ADIM A) ────────────────

    /**
     * Türkçe gün adları → 0..6 (Pazartesi=0 … Pazar=6).
     *
     * **v5.1 ÇÖKME DÜZELTMESİ (9 Ekim 2026):**
     *  - Android `Pattern` motoru `UNICODE_CHARACTER_CLASS` bayrağını
     *    DESTEKLEMEZ (AOSP'te `CANON_EQ` ile birlikte bilinçli çıkarılmıştır).
     *    Bu `object`'in `<clinit>`'inde fırlatılan `IllegalArgumentException`,
     *    `ExceptionInInitializerError` olarak tüm uygulamayı açılışta
     *    çöküyordu (`refreshContinueCard` → `TextUtil.readPercent` → ilk erişim).
     *  - Sadece bayrağı kaldırmak da YETMEZ: `\b` (kelime sınırı) bu bayrak
     *    olmadan Türkçe karakterleri (ı, ç, ş, ğ, ö, ü) kelime SAYMAZ →
     *    "Salı" yalnız "Sal" kalır, "Çarşamba" hiç eşleşmezdi.
     *    Bu yüzden sınır `\b` yerine açık karakter sınıflı **lookaround** ile
     *    kuruldu: JVM ve Android'de birebir aynı davranış (test = üretim).
     *  - `UNICODE_CASE` korundu (Android destekler): "SALI"/"ÇARŞAMBA" gibi
     *    Türkçe büyük harfli OCR çıktısı da eşleşsin.
     */
    private val DAY_PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile(
        """(?<![A-Za-z0-9_çğıöşüÇĞİÖŞÜ])(""" +
            """pazartesi|pazar|pzt|salı|sali|sal|çarşamba|carsamba|çar|car|""" +
            """perşembe|persembe|per|cuma|cumartesi|cum|cmt|pazr|""" +
            """monday|tuesday|wednesday|thursday|friday|saturday|sunday|""" +
            """mon|tue|wed|thu|fri|sat|sun)""" +
            """(?![A-Za-z0-9_çğıöşüÇĞİÖŞÜ])""",
        java.util.regex.Pattern.CASE_INSENSITIVE or
            java.util.regex.Pattern.UNICODE_CASE
    )

    private val TIME_RE = Regex("""\b(\d{1,2})[:.](\d{2})\b""")

    /** Sondaki derslik kodu: "B-204", "A101", "D-3" gibi. */
    private val ROOM_RE = Regex(
        """\s+([A-Za-zÇĞİÖŞÜçğıöşü]{1,12}\s*-\s*\d{1,4}[A-Za-z]?)\s*$"""
    )

    /** Tek başına sonda duran sayısal derslik: " 204", " 101". */
    private val ROOM_NUM_RE = Regex("""\s+(\d{3,4})\s*$""")

    /**
     * Ders programı satırlarından ders listesi çıkarır (ADIM A).
     *
     * Girdi: OCR/PDF metin satırları. Her satırda EN AZ bir gün adı ve
     * bir saat (`09:00` / `09.00`, `09:00-11:00` aralıkları) aranır;
     * gün/saat içermeyen satırlar (başlık, açıklama) atlanır.
     * Kalan metin ders adı, sondaki `B-204` gibi kod dersliktir.
     *
     * **Saf fonksiyon** (Android bağımlılığı yok) → TextUtilTest ile sınanır.
     */
    fun parseSchedule(lines: List<String>): List<ParsedClass> {
        val out = mutableListOf<ParsedClass>()
        val seen = mutableSetOf<Triple<Int, Int, String>>()

        for (raw in lines) {
            // v5.1: Tek bozuk/taşkın OCR satırı (sınır değeri aşan sayı,
            // garip noktalama yığını…) tüm aktarımı düşürmesin — satır
            // atlanır, diğer satırlarla devam edilir.
            try {
                val line = raw.trim()
                if (line.length < 3 || line.length > 500) continue

                val day = findDay(line) ?: continue
                val start = findStartTime(line) ?: continue

                // Gün ve tüm saat ifadelerini temizle → kalan: ders + derslik
                var rest = DAY_PATTERN.matcher(line).replaceAll(" ")
                rest = TIME_RE.replace(rest, " ")
                rest = rest.replace(Regex("""\d{1,2}\s*[-–—]\s*\d{1,2}(?:[:.]\d{2})?"""), " ")

                var room = ""
                ROOM_RE.find(rest)?.let { m ->
                    room = m.groupValues[1].replace(Regex("\\s+"), "").uppercase(Locale.US)
                    rest = rest.removeRange(m.range)
                } ?: ROOM_NUM_RE.find(rest)?.let { m ->
                    room = m.groupValues[1]
                    rest = rest.removeRange(m.range)
                }

                val name = rest
                    .replace(Regex("""[\s:;,.|/·•\-–—]+"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                if (name.length < 2 || name.length > 120) continue

                // Aynı gün/saat/ders tekrarlanmasın (OCR satır bölünmeleri)
                val key = Triple(day, start, name.lowercase(Locale.US))
                if (!seen.add(key)) continue

                out += ParsedClass(day, start, name, room)
            } catch (_: RuntimeException) {
                // IndexOutOfBounds / IllegalArgument vb. → yalnız bu satır atlanır
                continue
            }
        }
        return out
    }

    /** Satırda gün adı varsa 0..6, yoksa null. */
    private fun findDay(line: String): Int? {
        val m = DAY_PATTERN.matcher(line)
        if (!m.find()) return null
        return dayIndexOf(foldDayToken(m.group(1)))
    }

    /**
     * v5.1: Gün adını ASCII'ye katlar (SALI→sali, Çarşamba→carsamba,
     * CUMARTESİ→cumartesi).
     *
     * Neden: `"CUMARTESİ".lowercase()` bazı JVM/Android sürümlerinde
     * `i` + `U+0307` (birleşik nokta) olarak İNİYOR → `dayIndexOf` hiçbir
     * anahtarla eşleşmiyordu (büyük harfli OCR satırları sessizce
     * kayboluyordu). Char-char katlama hem bu tuzağı hem de Türkçe
     * büyük harf varyantlarını tek hamlede çözer; `dayIndexOf`'taki
     * ASCII anahtarlar (sali, carsamba, persembe …) ile birebir eşleşir.
     */
    private fun foldDayToken(t: String): String = buildString(t.length) {
        for (ch in t) {
            when (ch) {
                'İ', 'I' -> append('i')
                'ı' -> append('i')
                'Ğ', 'ğ' -> append('g')
                'Ş', 'ş' -> append('s')
                'Ç', 'ç' -> append('c')
                'Ö', 'ö' -> append('o')
                'Ü', 'ü' -> append('u')
                '\u0307' -> Unit   // birleşik nokta (İ→i dönüşümünden kalır) → atla
                else -> append(Character.toLowerCase(ch))
            }
        }
    }

    private fun dayIndexOf(token: String): Int? = when (token) {
        "pazartesi", "pzt", "monday", "mon" -> 0
        "salı", "sali", "sal", "tuesday", "tue" -> 1
        "çarşamba", "carsamba", "çar", "car", "wednesday", "wed" -> 2
        "perşembe", "persembe", "per", "thursday", "thu" -> 3
        "cuma", "cum", "friday", "fri" -> 4
        "cumartesi", "cmt", "saturday", "sat" -> 5
        "pazar", "pazr", "sunday", "sun" -> 6
        else -> null
    }

    /** Satırdaki İLK saatin dakikası (0..1439); yoksa null. */
    private fun findStartTime(line: String): Int? {
        val m = TIME_RE.find(line) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }
}
