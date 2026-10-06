package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextUtil birim testleri.
 * Cümle ayırma, çevrimdışı arama, dil algılama ve özetleme.
 */
class TextUtilTest {

    // ── Cümle ayırma ──────────────────────────────────────

    @Test
    fun speechChunks_kisaCumleleriBirlestirir() {
        // Kısa cümleler 450 karakter sınırına kadar tek parçada birleşir
        // (seslendirme küçük parça sevmez).
        val text = "Birinci cümle. İkinci cümle! Üçüncü cümle?"
        val chunks = TextUtil.speechChunks(text)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].contains("Birinci"))
        assertTrue(chunks[0].contains("İkinci"))
        assertTrue(chunks[0].contains("Üçüncü"))
    }

    @Test
    fun speechChunks_bosMetin_bosDoner() {
        assertTrue(TextUtil.speechChunks("   \n  ").isEmpty())
    }

    @Test
    fun speechChunks_uzunCumle450KarakteriGecmez() {
        val long = buildString {
            repeat(60) { append("Bu çok uzun bir cümle ve sınırı aşıyor. ") }
        }
        val chunks = TextUtil.speechChunks(long)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= 450 })
        // Toplam metin kaybolmamalı (yaklaşık da olsa)
        assertTrue(chunks.joinToString(" ").length > long.length / 2)
    }

    // ── Dil algılama ──────────────────────────────────────

    @Test
    fun looksTurkish_turkceMetniTanimlar() {
        assertTrue(TextUtil.looksTurkish("Bu bir deneme sınavıdır ve çok önemlidir."))
    }

    @Test
    fun looksTurkish_ingilizceMetniTanimlamaz() {
        assertFalse(
            TextUtil.looksTurkish(
                "This is an example of the english text that we use for testing."
            )
        )
    }

    @Test
    fun needsTranslation_kapaliyken_hicDonmez() {
        assertFalse(
            TextUtil.needsTranslation(
                "The book is on the table and it is for the students.",
                false
            )
        )
    }

    @Test
    fun needsTranslation_acikIngilizceMetni_ister() {
        assertTrue(
            TextUtil.needsTranslation(
                "The book is on the table and it is for the students of the class.",
                true
            )
        )
        // Türkçe metin çevrilmemeli
        assertFalse(
            TextUtil.needsTranslation(
                "Bu kitap sınıfın öğrencileri için çok önemlidir ve ders kitabıdır.",
                true
            )
        )
    }

    // ── Çevrimdışı arama ──────────────────────────────────

    private val pages = listOf(
        "Fizik 1 dersi. Newton'un hareket yasaları burada anlatılır. " +
            "İkinci yasaları net kuvvet ve ivme ilişkisini açıklar.",
        "Tarih dersi. Osmanlı Devleti kuruldu. " +
            "İstanbul'un fethi 1453 yılında gerçekleşmiştir.",
        "Matematik dersi. Türev alma kuralları. İntegral almanın temelleri."
    )

    @Test
    fun search_ilgiliCumleleriBulur() {
        val hits = TextUtil.search(pages, "newton'un hareket yasaları nedir?", 4)
        assertTrue(hits.isNotEmpty())
        assertEquals(0, hits.first().page)
        assertTrue(hits.first().sentence.contains("Newton"))
    }

    @Test
    fun search_sayfaNumarasiDogru() {
        val hits = TextUtil.search(pages, "1453 yılında fethi", 4)
        assertTrue(hits.isNotEmpty())
        assertEquals(1, hits.first().page)
    }

    @Test
    fun search_turkceKarakterDuyarsiz() {
        // "türev" ile "turev" aynı bulunabilmeli (normalize)
        val hits = TextUtil.search(pages, "turev alma", 4)
        assertTrue(hits.isNotEmpty())
    }

    @Test
    fun search_bosSoru_bosDoner() {
        assertTrue(TextUtil.search(pages, " ve ile bir ", 4).isEmpty())
    }

    // ── Özetleme ──────────────────────────────────────────

    @Test
    fun summarize_doluListe_ozetDoner() {
        val out = TextUtil.summarize(pages)
        assertTrue(out.contains("Sayfa"))
        assertTrue(out.contains("dersi"))   // her sayfanın ilk cümlesi
    }

    @Test
    fun summarize_bosListe_mesajDoner() {
        assertTrue(TextUtil.summarize(emptyList()).contains("metin yok"))
    }

    // ── Normalizasyon ─────────────────────────────────────

    @Test
    fun normalize_turkceKarakterleriSadelestirir() {
        assertEquals("cogdus", TextUtil.normalize("çöğdüş"))
        assertEquals("istanbul", TextUtil.normalize("İstanbul"))
    }

    // ── Sembol → konuşma metni ────────────────────────────

    @Test
    fun forSpeech_matematikSembolleriniOkunurYapar() {
        val out = TextUtil.forSpeech("x² + y² ≤ 100")
        assertTrue(out.contains("kare"))
        assertTrue(out.contains("artı"))
        assertTrue(out.contains("küçük veya eşittir"))
        assertFalse(out.contains("²"))
        assertFalse(out.contains("≤"))
    }

    @Test
    fun forSpeech_yuzdeyiTurkceOkur() {
        assertEquals("yüzde 50 artış", TextUtil.forSpeech("%50 artış").trim())
        assertEquals("yüzde 5", TextUtil.forSpeech("5%").trim())
    }

    @Test
    fun forSpeech_yazilimIsaretleriniOkur() {
        val out = TextUtil.forSpeech("a == b && c != d")
        assertTrue(out.contains("eşittir"))
        assertTrue(out.contains("ve"))
        assertTrue(out.contains("eşit değil"))
        assertFalse(out.contains("&&"))
    }

    @Test
    fun forSpeech_duzTireyiBozmaz() {
        // "e-posta" → "e eksi posta" OLMAAMALI (yaygın metin bozulurdu)
        val out = TextUtil.forSpeech("e-posta adresi 2026-10-03 tarihinde")
        assertTrue(out.contains("e-posta"))
        assertTrue(out.contains("2026-10-03"))
        assertFalse(out.contains("eksi"))
    }

    @Test
    fun forSpeech_htmlEtiketleriniCikarir() {
        val out = TextUtil.forSpeech("Merhaba <b>dünya</b> ve <i>italik</i>")
        assertFalse(out.contains("<"))
        assertFalse(out.contains(">"))
        assertTrue(out.contains("dünya"))
    }

    @Test
    fun forSpeech_duzMetniDegistirmez() {
        val plain = "Kitap masanın üstünde duruyor."
        assertEquals(plain, TextUtil.forSpeech(plain))
        assertEquals("", TextUtil.forSpeech(""))
    }

    @Test
    fun forSpeech_fazlaBoslukBirakmaz() {
        val out = TextUtil.forSpeech("a<=b; c>=d")
        assertFalse(out.contains("  "))
        assertFalse(out.endsWith(" "))
        assertFalse(out.startsWith(" "))
    }

    // ── Karaoke: satır eşleştirme ──────────────────────

    private val karaokeLines = listOf(
        "Newton birinci yasayı açıklar.",
        "İkinci yasa net kuvvet ve ivme ilişkisidir.",
        "Üçüncü yasa etki-tepki eşitliğidir."
    )

    @Test
    fun matchingLines_eslesenSatirlariDoner() {
        val hits = TextUtil.matchingLines(
            "İkinci yasa net kuvvet ve ivme ilişkisidir.", karaokeLines
        )
        assertEquals(listOf(1), hits)
    }

    @Test
    fun matchingLines_satirSiniriniKeser() {
        // Cümle iki satıra yayılır → ikisi de vurgulanır
        val lines = listOf("Birinci satır sonu.", "İkinci satır başı.")
        val chunk = "Birinci satır sonu. İkinci satır başı."
        assertEquals(listOf(0, 1), TextUtil.matchingLines(chunk, lines))
    }

    @Test
    fun matchingLines_eslesmezseBosDoner() {
        assertTrue(TextUtil.matchingLines("Bu hiç geçmiyor.", karaokeLines).isEmpty())
        assertTrue(TextUtil.matchingLines("   ", karaokeLines).isEmpty())
        assertTrue(TextUtil.matchingLines("x", emptyList()).isEmpty())
    }

    @Test
    fun matchingLines_boslukFarkliligiOnemliDegil() {
        // Sayfa metnindeki çoklu boşluk / satır sonu farkları eşleştirmeyi bozmaz
        val lines = listOf("A  çok   boşluklu\nsatır")
        assertEquals(listOf(0), TextUtil.matchingLines("A çok boşluklu satır", lines))
    }

    @Test
    fun forSpeech_usSimgeleriniKelimelerleYazar() {
        val out = TextUtil.forSpeech("a^2 + b^3 + c^4")
        assertTrue(out.contains("kare"))
        assertTrue(out.contains("küp"))
        assertTrue(out.contains("üzeri 4"))
        assertFalse(out.contains("^"))
    }

    // ── Doğal sıralama (tarama: sayfa_9 < sayfa_10) ──────

    @Test
    fun naturalKey_sayiyaDuyarliSiralar() {
        val names = listOf("sayfa_10.jpg", "sayfa_2.jpg", "sayfa_1.jpg")
        val sorted = names.sortedBy { TextUtil.naturalKey(it) }
        assertEquals(
            listOf("sayfa_1.jpg", "sayfa_2.jpg", "sayfa_10.jpg"),
            sorted
        )
    }

    @Test
    fun naturalKey_rakamOlmayanAdlardaSiraBozulmaz() {
        val names = listOf("c.jpg", "a.jpg", "b.jpg")
        assertEquals(
            listOf("a.jpg", "b.jpg", "c.jpg"),
            names.sortedBy { TextUtil.naturalKey(it) }
        )
    }

    // ── Sohbet bağlamı seçimi ────────────────────────────

    private val chatPages = listOf(
        "Bu fizik kitabıdır. Newton'un hareket yasaları burada anlatılır.",
        "Tarih dersi: Osmanlı Devleti kuruluş dönemini kapsar.",
        "Matematik: türev, integral ve limit konuları işlenir.",
        "Kimya: periyodik tablo ve elementlerin özellikleri burada verilir.",
        "Yasin suresi 83 ayettir. Kuran mealinde sure numaraları böyledir.",
        "Bu sayfada konuyla ilgisi olmayan başka bir metin bulunur."
    )

    @Test
    fun contextPages_ilgiliSayfayiSecer() {
        val ctx = TextUtil.contextPages(
            chatPages, "yasin süresi kaç ayet", maxPages = 3, maxChars = 6000
        )
        assertTrue(ctx.isNotEmpty())
        // İlgili sayfa (5. sayfa, indeks 4) en başta olmalı
        assertTrue(ctx.first().startsWith("Sayfa 5:"))
        assertTrue(ctx.first().contains("83"))
    }

    @Test
    fun contextPages_eslesmeYoksaIlkSayfalariDoner() {
        val ctx = TextUtil.contextPages(
            chatPages, "antidisestablishmentarianism", maxPages = 3, maxChars = 6000
        )
        // Eşleşme yoksa bile model boş bağlamla "kitapta yok" demesin
        assertEquals(3, ctx.size)
        assertTrue(ctx.first().startsWith("Sayfa 1:"))
    }

    @Test
    fun contextPages_bosSayfalariAtlarVeButceyiSaglar() {
        val pages = listOf("", "   ", "İkinci sayfanın dolu metni burada.")
        val ctx = TextUtil.contextPages(pages, "sayfa", maxPages = 8, maxChars = 6000)
        assertEquals(1, ctx.size)
        assertTrue(ctx.first().startsWith("Sayfa 3:"))

        val small = TextUtil.contextPages(chatPages, "fizik", maxPages = 8, maxChars = 10)
        assertTrue(small.isNotEmpty())
        assertTrue(small.sumOf { it.length } <= 10 + "Sayfa 1: ".length + 20)
    }

    @Test
    fun contextPages_hepsiBosBosDoner() {
        assertTrue(TextUtil.contextPages(listOf("", "  "), "herhangi", 5, 1000).isEmpty())
    }

    // ── ADIM 2: okuma yüzdesi (Hero raf + kapak rozeti) ──────

    @Test
    fun readPercent_ilkSayfaYuzde1() {
        assertEquals(1, TextUtil.readPercent(page = 0, pageCount = 100))
    }

    @Test
    fun readPercent_sonSayfaYuzde100() {
        assertEquals(100, TextUtil.readPercent(page = 9, pageCount = 10))
        assertEquals(100, TextUtil.readPercent(page = 50, pageCount = 10)) // sınırın ötesi
    }

    @Test
    fun readPercent_tekSayfalikKitapHep100() {
        assertEquals(100, TextUtil.readPercent(page = 0, pageCount = 1))
    }

    @Test
    fun readPercent_gecersizGirdiSifir() {
        assertEquals(0, TextUtil.readPercent(page = 0, pageCount = 0))   // sayfa yok
        assertEquals(0, TextUtil.readPercent(page = -1, pageCount = 10)) // hiç okunmamış
        assertEquals(50, TextUtil.readPercent(page = 4, pageCount = 10)) // normal
    }
}
