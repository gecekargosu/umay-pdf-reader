package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v16 (GÖREV 3) — TTS süzgeç testleri (`TextUtil.speechSafe` / `forSpeech`).
 *
 * Kapsanan riskler:
 *  - Arapça (Kur'an metni) ağır metin tespiti → dil yönlendirmesi
 *  - Biçim/sıfır-genişlik karakterleri (ZWNJ, LRM, RLM, ZWJ, BOM)
 *  - YALNIZ kalan surrogate (yarım emoji) — Edge TTS WebSocket'ini patlatan çökme
 *  - Emoji/çizim sembollerinin seslendirmeye girmemesi
 *  - Türkçe sembol okunuşlarının süzgeçten sonraki korunması
 */
class TextUtilSpeechTest {

    // ── speechSafe: bozulma kaynağı olan karakterler ────────

    @Test
    fun speechSafe_yetimSurrogateTemizlenir() {
        // Yalnız yüksek surrogate (yarım emoji) — en kritik çökme kaynağı
        val broken = "Oku\uD83Dabc"
        val out = TextUtil.speechSafe(broken)
        assertEquals("Okuabc", out)
        // Çıktıda HİÇ surrogate kalmamalı
        assertFalse(out.any { it.code in 0xD800..0xDFFF })
    }

    @Test
    fun speechSafe_sifirGenislikKarakterleri() {
        // ZWNJ (200C) + ZWJ (200D) + LRM (200E) + BOM (FEFF)
        val in2 = "متن\u200C\u200D\u200E\uFEFF oku"
        assertEquals("متن oku", TextUtil.speechSafe(in2))
    }

    @Test
    fun speechSafe_kontrolKarakterleri() {
        val in2 = "A\u0000B\u0007C\nD"
        assertEquals("A B C\nD", TextUtil.speechSafe(in2))
    }

    @Test
    fun speechSafe_emojiAtilir() {
        val out = TextUtil.speechSafe("Merhaba \uD83D\uDE00 dünya")
        assertEquals("Merhaba dünya", out)
    }

    @Test
    fun speechSafe_bosGirdiGuvenli() {
        assertEquals("", TextUtil.speechSafe(""))
        assertEquals("", TextUtil.speechSafe("\u0000\u200B"))
    }

    // ── Arapça tespiti ──────────────────────────────────────

    @Test
    fun isArabicHeavy_kuranMetni() {
        assertTrue(
            TextUtil.isArabicHeavy("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ الْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ")
        )
    }

    @Test
    fun isArabicHeavy_turkceMetinDegil() {
        assertFalse(TextUtil.isArabicHeavy("Merhaba dünya, bu bir Türkçe ders notudur."))
    }

    @Test
    fun isArabicHeavy_karisikAgirlik() {
        // Arapça ağırlıklı ama küçük bir İngilizce kelime içeren metin
        assertTrue(TextUtil.isArabicHeavy("الكتاب very güzel هو"))
        // Tam tersi: az miktarda Arapça → evet sayılır mı? HAYIR (sınır %20)
        assertFalse(TextUtil.isArabicHeavy("This is an English paragraph with a Arabic word مدرسة inside."))
    }

    // ── forSpeech: süzgeçten sonra semboller korunur ───────

    @Test
    fun forSpeech_emojiVeBozukKarakterTemizlenir() {
        val out = TextUtil.forSpeech("x² \uD83D\uDE00 y\u200B² ≤ 100")
        assertFalse(out.contains("\uD83D\uDE00"))
        assertFalse(out.contains("\u200B"))
        assertTrue(out.contains("kare"))
    }

    @Test
    fun forSpeech_bosMetinGuvende() {
        assertEquals("", TextUtil.forSpeech(""))
        assertEquals("abc", TextUtil.forSpeech("abc\u0000"))
    }

    // ── v31: bozuk metin katmanı (Kur'an meali font eşlemesi) ──

    /** Gerçek bozuk sayfa örneği: seslendirmede anlamsız sözcükler kalmamalı. */
    @Test
    fun dropGarbageWords_fontCopunuAtar() {
        val broken = "recek, 8 fakat [bu dünyadaki imtihan gere- 0.,f ::; r� > Q.1. .),  r� �j r� :::.- ts.)... 6: . :; ;Jıj"
        val out = TextUtil.dropGarbageWords(broken)
        assertTrue("gerçek sözcükler korunmalı: $out", out.contains("fakat"))
        assertTrue(out.contains("dünyadaki"))
        assertFalse("font çöpü kalmamalı: $out", out.contains("::;"))
        assertFalse(out.contains("Q.1."))
        assertFalse(out.contains("0.,f"))
    }

    /** Temiz sayfa (OCR çıktısı) neredeyse hiç değişmemeli. */
    @Test
    fun dropGarbageWords_temizMetneDokunmaz() {
        val clean = "15. Allah onlara alay etmenin cezasını verecek, fakat bu dünyadaki imtihan gereği onları şimdilik kendi hâllerine bırakmakta."
        assertEquals(clean, TextUtil.dropGarbageWords(clean))
    }

    /** Arapça sözcükler ve harekeler çöp sayılmamalı. */
    @Test
    fun dropGarbageWords_arapcayiKorur() {
        val arabic = "بِسْمِ اللّٰهِ الرَّحْمٰنِ الرَّح۪يمِ ٢"
        val out = TextUtil.dropGarbageWords(arabic)
        assertTrue(out.contains("الرَّحْمٰنِ"))
    }

    /** Bozuk/temiz sayfa ayrımı eşik değerde doğru çalışmalı. */
    @Test
    fun looksBrokenExtraction_ayrimi() {
        val broken = "0.,f ::; r > Q.1. .), r j r :::.- ts.)... 6: . :; ;Jıj"
        val clean = "Bu ayetteki ifade birçok mealde farklı şekilde karşılanmıştır."
        assertTrue(TextUtil.looksBrokenExtraction(broken))
        assertFalse(TextUtil.looksBrokenExtraction(clean))
    }

    /** Karışık sayfa: Arapça ve Türkçe ayrı ses parçalarına bölünür. */
    @Test
    fun splitByScript_arapcaVeTurkceAyrilir() {
        val parts = TextUtil.splitByScript(
            listOf("بِسْمِ اللّٰهِ الرَّحْمٰنِ الرَّح۪يمِ", "Elif-Lam-Mim. Bu Kur'an'dır.")
        )
        assertEquals(2, parts.size)
        assertTrue(TextUtil.arabicRatio(parts[0]) > 0.9)
        assertEquals(0.0, TextUtil.arabicRatio(parts[1]), 0.001)
    }
}
