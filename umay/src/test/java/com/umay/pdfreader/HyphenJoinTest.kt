package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v40 — SATIR SONUNDA BÖLÜNEN KELİME (gerçek kullanıcı bildirimi).
 *
 * PDF: "Freebuff ve Cengiz proje gel-" / "iştiriyorlar"
 * TTS bunu "gel" + duraklama + "iştiriyorlar" diye okuyordu; cümlenin anlamı
 * bozuluyordu. Çözüm [TextUtil.dehyphenate]: yalnızca
 * "harf + tire + boşluk/satır sonu + KÜÇÜK harf" kalıbı birleştirilir.
 */
class HyphenJoinTest {

    @Test
    fun `satir sonunda bolunen kelime birlesir`() {
        assertEquals(
            "Freebuff ve Cengiz proje geliştiriyorlar",
            TextUtil.dehyphenate("Freebuff ve Cengiz proje gel-\niştiriyorlar")
        )
    }

    @Test
    fun `seslendirme parcasi bolunmus kelime icermez`() {
        val chunks = TextUtil.speechChunks(
            "Freebuff ve Cengiz proje gel-\niştiriyorlar ve devam ediyorlar."
        )
        assertTrue("Parça üretilmedi", chunks.isNotEmpty())
        val all = chunks.joinToString(" ")
        assertTrue("Birleşmemiş: $all", all.contains("geliştiriyorlar"))
        assertFalse("Yarım kelime kaldı: $all", all.contains("gel- "))
    }

    @Test
    fun `gercek tireli ifade korunur`() {
        assertEquals("yapay-zeka dersi", TextUtil.dehyphenate("yapay-zeka dersi"))
        assertEquals("2020-2024 arası", TextUtil.dehyphenate("2020-2024 arası"))
        assertEquals("Türk-Alman ilişkileri", TextUtil.dehyphenate("Türk-Alman ilişkileri"))
    }

    @Test
    fun `bosluklu ve uzun tireli bolunmeler de birlesir`() {
        // v44 — bazı PDF'ler tireyi ayrı metin kutusu olarak veriyor: "gel -",
        // bazıları uzun tire (—) kullanıyor. İkisi de birleşmeli.
        assertEquals(
            "proje geliştiriyorlar",
            TextUtil.dehyphenate("proje gel - iştiriyorlar")
        )
        assertEquals(
            "proje geliştiriyorlar",
            TextUtil.dehyphenate("proje gel—\niştiriyorlar")
        )
        assertEquals(
            "proje geliştiriyorlar",
            TextUtil.dehyphenate("proje gel–\niştiriyorlar")
        )
    }

    @Test
    fun `bosluklu uzun tire araligi korunur`() {
        // "2020 – 2024" bir aralıktır, kelime bölünmesi değildir.
        assertEquals("2020 – 2024 arası", TextUtil.dehyphenate("2020 – 2024 arası"))
    }

    @Test
    fun `yumusak tire silinir`() {
        assertEquals("geliştiriyor", TextUtil.dehyphenate("geli\u00ADştiriyor"))
    }

    @Test
    fun `tiresiz satir sonu degistirilmez`() {
        // Satır sonu korunur (parçalama ayrı adım); yalnız boşluk toplama
        // sırasında tek boşluğa iner.
        assertEquals("bir\nkelime", TextUtil.dehyphenate("bir\nkelime"))
        assertEquals("bir kelime", TextUtil.collapseWs("bir\nkelime"))
    }

    @Test
    fun `turkce karakterler korunur`() {
        assertEquals(
            "ölçme ve değerlendirme çalışıyor",
            TextUtil.dehyphenate("ölçme ve değerlendirme çalışıyor")
        )
    }

    @Test
    fun `karaoke vurgusu birlesmis kelimede de bulunur`() {
        val lines = listOf("Freebuff ve Cengiz proje gel-", "iştiriyorlar ve devam ediyorlar.")
        val hits = TextUtil.matchingLines("Freebuff ve Cengiz proje geliştiriyorlar", lines)
        assertEquals("İki satır da vurgulanmalı", listOf(0, 1), hits)
    }

    @Test
    fun `forSpeech cikisi bolunmus kelime icermez`() {
        val spoken = TextUtil.forSpeech("proje gel-\niştiriyorlar")
        assertTrue("Birleşmemiş: $spoken", spoken.contains("geliştiriyorlar"))
    }
}
