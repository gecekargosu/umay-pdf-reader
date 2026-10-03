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

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT)
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
}
