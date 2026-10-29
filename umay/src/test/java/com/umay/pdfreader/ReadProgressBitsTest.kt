package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v36 — Okunma yüzdesi birim testi (31-ELESTIRI.md · B5 ve kullanıcı şikâyeti).
 *
 * Gerçek şikâyet: 100 sayfalık kitabın 99. sayfasına atlayınca program "%99 okundu"
 * diyordu. Artık yüzde **okunan sayfa / toplam sayfa**.
 */
class ReadProgressBitsTest {

    @Test
    fun `son sayfaya atlamak yuzde doksan dokuz yapmaz`() {
        var bits = ""
        bits = ReadProgressBits.mark(bits, 98, 100)      // 99. sayfa
        assertEquals("tek sayfa = %1", 1, ReadProgressBits.percent(bits, 100))
        assertEquals(1, ReadProgressBits.count(bits, 100))
    }

    @Test
    fun `ilk sayfadan baslayip duz okumak dogru yuzde verir`() {
        var bits = ""
        for (p in 0 until 30) bits = ReadProgressBits.mark(bits, p, 100)
        assertEquals("30/100 = %30", 30, ReadProgressBits.percent(bits, 100))
    }

    @Test
    fun `ayni sayfa iki kez isaretlenince sayi artmaz`() {
        val once = ReadProgressBits.mark("", 4, 10)
        val iki = ReadProgressBits.mark(once, 4, 10)
        assertEquals("değişiklik yoksa aynı metin dönmeli", once, iki)
        assertEquals(1, ReadProgressBits.count(iki, 10))
    }

    @Test
    fun `kitap buyuyunce dizi uzar ve eski isaretler korunur`() {
        var bits = ReadProgressBits.mark("", 2, 5)       // 5 sayfalık sanılıyordu
        bits = ReadProgressBits.mark(bits, 40, 60)       // kitap 60 sayfa çıktı
        assertEquals(60, bits.length)
        assertEquals("eski işaret korunmalı", '1', bits[2])
        assertEquals('1', bits[40])
        assertEquals(2, ReadProgressBits.count(bits, 60))
    }

    @Test
    fun `sinir disi sayfa yok sayilir`() {
        val bits = ReadProgressBits.mark("", 50, 10)     // 10 sayfalık kitapta 51. sayfa
        assertEquals("sınır dışı işaretlenmemeli", "", bits)
    }

    @Test
    fun `bozuk girdi cokmez`() {
        assertEquals(0, ReadProgressBits.percent("", 0))
        assertEquals(0, ReadProgressBits.percent("", -5))
        assertEquals(0, ReadProgressBits.count("", 100))
        assertEquals("", ReadProgressBits.mark("", -1, 10))
        assertEquals("tamamı okunmuş kısa dizi", 100, ReadProgressBits.percent("1111", 4))
    }

    @Test
    fun `tum sayfalar okununca yuzde yuz`() {
        var bits = ""
        for (p in 0 until 12) bits = ReadProgressBits.mark(bits, p, 12)
        assertEquals(100, ReadProgressBits.percent(bits, 12))
    }

    @Test
    fun `dizi sayfadan uzun olsa da yuzde yuzu asmaz`() {
        val bits = "1".repeat(200)                       // bozuk/eski kayıt
        assertEquals(100, ReadProgressBits.percent(bits, 10))
    }
}
