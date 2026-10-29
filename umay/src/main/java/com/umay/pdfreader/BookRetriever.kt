package com.umay.pdfreader

import kotlin.math.ln
import kotlin.math.min

/**
 * v5.11 — AKILLI KİTAP ARAMA (AI sohbetinin "gözü").
 *
 * Eski yöntem (`TextUtil.contextPages`) yalnızca "soru kelimesi sayfada kaç
 * kez geçiyor" diye bakıyor ve **tüm sayfayı** bağlama koyuyordu. Sonuç:
 *  - "veri", "sistem" gibi her sayfada geçen kelimeler sıralamayı ele geçiriyordu,
 *  - 6000 karakterlik bağlamın çoğu alakasız metinle doluyor, model cevabı
 *    bulamayınca "Bu kitapta geçmiyor" diyordu.
 *
 * Bu sürüm cümle düzeyinde çalışır ve gerçek bir arama motoru gibi puanlar:
 *  1. **IDF** — kitapta nadir geçen kelime daha değerli (ayırt edici).
 *  2. **Doymuş TF** — bir kelimenin 50 kez geçmesi 5 kat puan getirmez.
 *  3. **Uzunluk normalizasyonu** — kısa/öz cümle uzun paragraftan öndedir.
 *  4. **İfade bonusu** — "ikili arama ağacı" gibi tam öbek geçen cümle sıçrar.
 *  5. **Başlık ipucu** — kısa, noktayla bitmeyen satırlar (bölüm başlığı) ek puan.
 *  6. **Komşu sayfa** — seçilen sayfanın devamı bağlama eklenir (cümle bölünmesi).
 *  7. **Türkçe ek toleransı** — "türev" sorgusu "türevlerin"i de bulur.
 *
 * Saf Kotlin (Android bağımlı değil); boş/bozuk sayfa listesinde çökmez.
 */
object BookRetriever {

    /** Kitaptan seçilmiş bir bölüm (sayfa 0 tabanlı). */
    data class Passage(val page: Int, val text: String, val score: Double)

    /** Arama için anlamlı en kısa cümle. */
    private const val MIN_SENTENCE = 30
    /** Tek cümle en fazla bu kadar karakter taşır (dev paragraflar bölünür). */
    private const val MAX_SENTENCE = 700

    // ── Genel giriş ─────────────────────────────────────────

    /**
     * Soruya en uygun cümleleri sayfa sırasıyla döndürür.
     *
     * @param maxChars toplam karakter bütçesi (model bağlamı)
     */
    fun passages(
        pages: List<String>,
        question: String,
        maxChars: Int = 9000
    ): List<Passage> {
        if (pages.isEmpty() || maxChars <= 0) return emptyList()

        val terms = expandedTerms(question)
        if (terms.isEmpty()) {
            // Soru tamamen stopword ise ("bu ne") ilk metinli sayfalar verilir.
            return pages.withIndex()
                .filter { it.value.isNotBlank() }
                .take(2)
                .map { Passage(it.index, it.value.trim().take(maxChars), 0.0) }
        }

        // 1) Cümle havuzu
        data class Sent(val page: Int, val order: Int, val text: String, val norm: String)
        val sents = ArrayList<Sent>(2048)
        pages.forEachIndexed { pageIdx, pageText ->
            if (pageText.isBlank()) return@forEachIndexed
            splitSentences(pageText).forEachIndexed { order, raw ->
                val body = raw.trim()
                if (body.length < MIN_SENTENCE) return@forEachIndexed
                sents.add(Sent(pageIdx, order, body, TextUtil.normalize(body)))
            }
        }
        if (sents.isEmpty()) return emptyList()

        // 2) Belge frekansı → IDF (nadir kelime = ayırt edici)
        val df = HashMap<String, Int>(terms.size * 4)
        for (s in sents) {
            val tokens = s.norm.split(NON_WORD).filter { it.isNotEmpty() }
            for (t in terms) {
                if (tokens.any { tokenMatches(it, t) }) df[t] = (df[t] ?: 0) + 1
            }
        }
        val n = sents.size.toDouble()
        val idf = terms.associateWith { t ->
            val d = (df[t] ?: 0).toDouble()
            ln(1.0 + n / (1.0 + d))
        }

        // 3) Cümle puanlama
        val scored = ArrayList<Pair<Sent, Double>>(sents.size)
        for (s in sents) {
            val tokens = s.norm.split(NON_WORD).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            var score = 0.0
            var matched = 0
            for (t in terms) {
                var tf = 0
                for (tk in tokens) if (tokenMatches(tk, t)) tf++
                if (tf == 0) continue
                matched++
                // Doymuş TF: 1→0.45, 2→0.62, 3→0.71 …
                val sat = tf.toDouble() / (tf + 1.2)
                score += sat * (idf[t] ?: 0.0)
            }
            if (score <= 0.0) continue
            // Kapsam: sorunun kaçta kaçı bu cümlede var (hepsi geçen cümle önde)
            score *= 1.0 + 0.35 * (matched.toDouble() / terms.size)
            // Uzunluk normalizasyonu
            score /= (1.0 + ln(1.0 + s.text.length / 120.0))
            // Tam öbek geçiyorsa büyük bonus
            val phrase = TextUtil.normalize(question).replace(Regex("[^a-z0-9 ]"), " ").trim()
            if (phrase.length >= 8 && s.norm.contains(phrase)) score += 6.0
            // Başlık/etiket satırı ipucu
            if (s.text.length < 90 && !s.text.trimEnd().endsWith(".")) score += 0.35
            scored.add(s to score)
        }

        if (scored.isEmpty()) {
            // Hiç eşleşme yok: ilk metinli 2 sayfa (model yine de yorum yapabilsin)
            return pages.withIndex()
                .filter { it.value.isNotBlank() }
                .take(2)
                .map { Passage(it.index, it.value.trim().take(maxChars / 2), 0.0) }
        }

        // 4) En iyi cümlelerden bütçeye sığanları seç
        scored.sortByDescending { it.second }
        val chosen = LinkedHashMap<Int, MutableList<String>>()
        // v36 — sayfa başına en iyi cümle puanı: sonuç sırası artık sayfa
        // numarası değil ALAKALILIK. (Test: "veri ağacı düğüm" sorusunda
        // 1. sayfa değil, cevabı taşıyan 2. sayfa önde olmalı.)
        val pageBest = HashMap<Int, Double>()
        var used = 0
        for ((s, sc) in scored) {
            if ((pageBest[s.page] ?: 0.0) < sc) pageBest[s.page] = sc
            val line = s.text.trim()
            val cost = line.length + 12
            if (used + cost > maxChars) continue
            val bucket = chosen.getOrPut(s.page) { ArrayList() }
            if (bucket.none { it == line }) {
                bucket.add(line)
                used += cost
            }
            if (used >= maxChars - 200) break
        }

        // 5) Komşu sayfa: cümlenin devamı kaçmasın (bütçe varsa)
        val pickedPages = chosen.keys.sorted()
        for (p in pickedPages) {
            if (used >= maxChars) break
            val next = p + 1
            if (next !in pages.indices || chosen.containsKey(next)) continue
            val tail = pages[next].trim()
            if (tail.isBlank()) continue
            val room = min(420, maxChars - used)
            if (room < 160) continue
            chosen.getOrPut(next) { ArrayList() }.add(
                tail.take(room) + if (tail.length > room) "…" else ""
            )
            used += room
        }

        // 6) Önce en alakalı sayfa, sonra puanı düşenler (komşu sayfalar sonda)
        return chosen.entries
            .sortedWith(
                compareByDescending<Map.Entry<Int, MutableList<String>>> { pageBest[it.key] ?: -1.0 }
                    .thenBy { it.key }
            )
            .map { (page, lines) ->
                Passage(page, lines.joinToString("\n"), pageBest[page] ?: 0.0)
            }
    }

    /**
     * API'ye gönderilecek bağlam metni: "Sayfa 42:\n…" blokları.
     * Hiçbir şey bulunamazsa boş metin döner (çağıran karar verir).
     */
    fun contextFor(pages: List<String>, question: String, maxChars: Int = 9000): String =
        passages(pages, question, maxChars)
            .joinToString("\n\n") { "Sayfa ${it.page + 1}:\n${it.text}" }

    /** Açık sayfayı (okuma ekranı) bağlamın en başına ekler; aynı sayfa tekrarlanmaz. */
    fun withOpenPage(
        pages: List<String>,
        question: String,
        focusPage: Int,
        maxChars: Int = 9000
    ): String {
        val base = contextFor(pages, question, maxChars)
        if (focusPage !in pages.indices) return base
        val text = pages[focusPage].trim()
        if (text.isBlank()) return base
        val head = "Sayfa ${focusPage + 1} (kullanıcının o an açık olduğu sayfa):\n" +
            text.take(2600)
        val marker = "Sayfa ${focusPage + 1}:"
        if (!base.contains(marker)) return head + "\n\n" + base
        // v36 — Açık sayfa bağlamda zaten varsa (komşu sayfa olarak eklenmiş
        // olabilir) tekrarlamıyoruz ama yine de EN BAŞA taşıyoruz: model
        // kullanıcının baktığı sayfayı ilk görmeli.
        val rest = base.split("\n\n").filterNot { it.startsWith(marker) }
        return (listOf(head) + rest).joinToString("\n\n")
    }

    // ── Yardımcılar ─────────────────────────────────────────

    private val NON_WORD = Regex("[^a-z0-9]+")

    /**
     * Sorgu terimleri + küçük eşanlamlı genişletme.
     * Türkçe ek toleransı için gövde (ilk 5 harf) de eklenir; böylece
     * "türevlerin" → "turevlerin" ve "turev" birlikte aranır.
     */
    private fun expandedTerms(question: String): List<String> {
        val base = TextUtil.queryTerms(question)
        if (base.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (t in base) {
            out.add(t)
            if (t.length >= 5) out.add(t.take(5))
            SYNONYMS[t]?.forEach { out.add(it) }
        }
        // 1-2 harflik gövde gürültü yapar
        return out.filter { it.length >= 3 }.toList()
    }

    /** Sorgu terimi cümlenin kelimesiyle eşleşiyor mu? (Türkçe ek toleranslı) */
    private fun tokenMatches(token: String, term: String): Boolean {
        if (token == term) return true
        if (term.length >= 4 && token.startsWith(term)) return true     // türev → türevlerin
        if (token.length >= 4 && term.startsWith(token)) return true     // türevlerin → türev
        return false
    }

    /** Cümlelere böl; aşırı uzun paragrafları ~700 karakterde kes. */
    private fun splitSentences(text: String): List<String> {
        val cleaned = text.replace(Regex("[ \\t]+"), " ")
        val rough = cleaned.split(Regex("(?<=[.!?…:])\\s+|(?<=\\n)"))
        val out = ArrayList<String>(rough.size)
        for (r in rough) {
            val t = r.trim()
            if (t.isEmpty()) continue
            if (t.length <= MAX_SENTENCE) {
                out.add(t)
            } else {
                var start = 0
                while (start < t.length) {
                    var end = (start + MAX_SENTENCE).coerceAtMost(t.length)
                    if (end < t.length) {
                        val sp = t.lastIndexOf(' ', end)
                        if (sp > start + 300) end = sp
                    }
                    out.add(t.substring(start, end).trim())
                    start = end
                }
            }
        }
        return out
    }

    /** Küçük iki dilli eşanlamlı sözlüğü (Türkçe kitap + İngilizce terim). */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "turev" to listOf("derivative"),
        "integral" to listOf("integral", "alan"),
        "ozdeger" to listOf("eigenvalue", "ozdeger"),
        "ozvektor" to listOf("eigenvector", "ozvektor"),
        "matris" to listOf("matrix", "matris"),
        "olasilik" to listOf("probability", "olasilik"),
        "regresyon" to listOf("regression", "regresyon"),
        "algoritma" to listOf("algorithm", "algoritma"),
        "karmasiklik" to listOf("complexity", "karmasiklik"),
        "yigin" to listOf("stack", "yigin"),
        "kuyruk" to listOf("queue", "kuyruk"),
        "agac" to listOf("tree", "agac"),
        "siralama" to listOf("sorting", "siralama"),
        "arama" to listOf("search", "arama"),
        "dizi" to listOf("array", "dizi"),
        "isaretci" to listOf("pointer", "isaretci"),
        "sinif" to listOf("class", "sinif"),
        "kalitim" to listOf("inheritance", "kalitim"),
        "arayuz" to listOf("interface", "arayuz"),
        "veritabani" to listOf("database", "veritabani"),
        "sorgu" to listOf("query", "sorgu"),
        "normalizasyon" to listOf("normalization", "normalizasyon"),
        "varyans" to listOf("variance", "varyans"),
        "hipotez" to listOf("hypothesis", "hipotez"),
        "hucre" to listOf("cell", "hucre"),
        "enzim" to listOf("enzyme", "enzim"),
        "kuvvet" to listOf("force", "kuvvet"),
        "enerji" to listOf("energy", "enerji"),
        "hiz" to listOf("velocity", "hiz"),
        "ivme" to listOf("acceleration", "ivme"),
        "devre" to listOf("circuit", "devre"),
        "fonksiyon" to listOf("function", "fonksiyon"),
        "denklem" to listOf("equation", "denklem"),
        "limit" to listOf("limit"),
        "sureklilik" to listOf("continuity", "sureklilik")
    )
}
