package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v36 — OKUMA DOĞRULUK SETİ (metin hattı senaryoları).
 *
 * `35-OKUMA-DOGRULUK-SETI.md` içindeki 10 senaryodan **metin katmanında
 * ölçülebilenleri** burada otomatikleştiriyoruz. PDF görüntüleme/OCR gibi
 * cihaz gerektiren senaryolar tabloda "elle" olarak işaretli.
 *
 * Amaç: bir düzeltmeden sonra daha önce doğru çalışan bir davranışın sessizce
 * bozulmasını yakalamak (regresyon).
 */
class ReadingQualityFixturesTest {

    // ── Senaryo 3: matematik/formül korunumu ─────────────────────
    @Test
    fun `matematik sembolleri okunabilir soze cevrilir`() {
        // Tasarım: TTS sembol okumaz, sözcüğe çevirir ("x²" → "x kare").
        val spoken = TextUtil.forSpeech("E = mc² ve ∫ f(x) dx ≈ 5 ≤ 10")
        assertTrue("² sembolü 'kare' olarak okunmalı: $spoken", spoken.contains("kare"))
        assertTrue("∫ 'integral' olarak okunmalı: $spoken", spoken.contains("integral"))
        assertFalse("ham sembol kalmamalı", spoken.contains("∫"))
    }

    // ── Senaryo 6: kod blokları ──────────────────────────────────
    @Test
    fun `kod satiri taninir ve okunmaz`() {
        assertTrue(TextUtil.looksLikeCodeLine("    int x = 5;"))
        assertTrue(TextUtil.looksLikeCodeLine("for (int i = 0; i < n; i++) {"))
        assertFalse(
            "normal cümle kod sayılmamalı",
            TextUtil.looksLikeCodeLine("İkili arama ağacında düğümler sıralı tutulur.")
        )
    }

    @Test
    fun `sayfada kod varsa seslendirme kodu atlar`() {
        val page = buildString {
            appendLine("Aşağıdaki döngü diziyi ters çevirir ve sonucu yazdırır.")
            appendLine("for (int i = 0; i < n; i++) { arr[i] = 0; }")
            appendLine("Bu örnekte bellek kullanımı sabittir.")
        }
        val spoken = TextUtil.speechChunks(page).joinToString(" ")
        assertFalse("kod satırı okunmamalı", spoken.contains("arr[i]"))
        assertTrue("düz metin okunmalı", spoken.contains("döngü"))
    }

    // ── Senaryo 8: Türkçe karakter / karışık dil ──────────────────
    @Test
    fun `turkce karakterler normalize edilirken icerik korunur`() {
        val n = TextUtil.normalize("Çğıöşü İstanbul Ağacı")
        assertFalse("Türkçe harfler sadeleşmeli", n.contains("ş"))
        assertTrue(n.contains("istanbul"))
    }

    // ── Senaryo 4/5: tablo, şekil, bozuk metin ───────────────────
    @Test
    fun `bozuk font eşlemesi taninir`() {
        // Bozuk eşlemenin kanıtı: değiştirme karakteri veya özel kullanım alanı
        // (U+E000..U+F8FF). Kur'an mealinde bu izler görülüyordu.
        val broken = "\uE000\uE001 metin \uFFFDbilgi"
        assertTrue(TextUtil.hasFontBreakage(broken))
        assertFalse(
            "normal Türkçe metin bozuk sayılmamalı",
            TextUtil.hasFontBreakage("Veri yapıları dersinde yığın anlatılır.")
        )
    }

    // ── Senaryo 10: Arapça ağırlıklı sayfa ───────────────────────
    @Test
    fun `arapca orani olculur`() {
        val arabic = "بِسْمِ اللّٰهِ الرَّحْمٰنِ الرَّح۪يمِ"
        assertTrue(TextUtil.arabicRatio(arabic) > 0.8)
        assertTrue(TextUtil.arabicRatio("Türkçe bir cümle") < 0.1)
    }

    // ── Senaryo 9: uzun kitap / sayfa bölütleme ──────────────────
    @Test
    fun `uzun paragraf seslendirme icin bolunur`() {
        val long = ("Bu cümle oldukça uzun bir paragrafın parçasıdır ve tek seferde " +
            "okunması dinleyici için yorucu olur. ").repeat(20)
        val chunks = TextUtil.speechChunks(long)
        assertTrue("birden çok parça olmalı", chunks.size > 2)
        chunks.forEach {
            assertTrue("parça 450 karakteri geçmemeli (${it.length})", it.length <= 460)
        }
    }

    // ── Senaryo 1/2: normal ve taranmış sayfa metni ──────────────
    @Test
    fun `taranmis sayfa metni bos donerse hazirlik denetimi bos der`() {
        val r = PrepAudit.audit("scan", listOf("", "", ""))
        assertEquals(3, r.empty)
        assertEquals(0, r.readablePercent)
    }

    @Test
    fun `iki sutunlu sayfa metni cumle butunlugu korur`() {
        val page = "Birinci sütunun ilk cümlesi burada yer alıyor.\n" +
            "İkinci sütunun ilk cümlesi de burada yer alıyor."
        val chunks = TextUtil.speechChunks(page)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.joinToString(" ").contains("Birinci sütunun"))
    }
}
