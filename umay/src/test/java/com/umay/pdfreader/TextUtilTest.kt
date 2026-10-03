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
}
