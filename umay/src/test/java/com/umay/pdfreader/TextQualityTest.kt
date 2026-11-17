package com.umay.pdfreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v36.9 — "bozuk kitap" ölçüsü. Örnekler cihazdan alınan GERÇEK metinlerdir.
 */
class TextQualityTest {

    private val zipDump = """
        4¹j(•-LoL °î?  PK ! O\ + ppt/slides/slide7.xmlX[o6 ~
        °éaK‡)¶%ù’´Na647é€>
        ´D„©H*µ[·±¯{‎¯ R’s©skZ` q Q$ ï|ç“p^½‍ ‌ .htf@$ &®7; '!fiBœ
    """.trimIndent()

    private val mojibake = ",,. .... ,.,  ı�ı:i.....  �"

    private val turkish = """
        VERİ YAPILARI
        3. BASKI — Hiç
        24 bölüm + Görsel ve Sınav Sistemi eki: görseller, adım adım izleme,
        gerçek sınav soruları, 45 mini sınav sorusu ve tam çözümler.
    """.trimIndent()

    private val arabic = "بِسْمِ اللهِ الرَّحْمٰنِ الرَّحِيمِ ١"

    private val code = """
        public static void main(String[] args) {
            int[] dizi = new int[10];
            for (int i = 0; i < dizi.length; i++) dizi[i] = i * 2;
            System.out.println("Toplam: " + dizi.length);
        }
    """.trimIndent()

    @Test
    fun `zip dokumu cop sayilir`() {
        assertTrue(TextQuality.isGarbage(zipDump))
    }

    @Test
    fun `bozuk font metni cop sayilir`() {
        assertTrue(TextQuality.isGarbage(mojibake))
    }

    @Test
    fun `turkce metin temiz`() {
        assertFalse(TextQuality.isGarbage(turkish))
    }

    @Test
    fun `arapca metin temiz`() {
        assertFalse(TextQuality.isGarbage(arabic))
    }

    @Test
    fun `kod sayfasi cop degil`() {
        assertFalse(TextQuality.isGarbage(code))
    }

    @Test
    fun `icindekiler noktalari cop sayilmaz`() {
        // v44 — gerçek olay: "Kaynak Kitap.pdf" içindekiler sayfası yüzünden
        // "bozuk" sanılıp sarı lamba yakıyordu. Metin sağlamdı.
        val toc = """
            3.4 Değişken Tanımlama (Variable
            Initialization) ................................................ 36
            3.4.1 Değişken Bildirimi ........................................ 37
            3.5 İşleçler (Operators) ......................................... 38
        """.trimIndent()
        assertFalse("içindekiler", TextQuality.isGarbage(toc))
        assertFalse("içindekiler mojibake", TextQuality.isMojibake(toc))
    }

    @Test
    fun `bos sayfa cop degil`() {
        assertFalse(TextQuality.isGarbage("   \n  "))
    }

    @Test
    fun `sayfa ornegi cogunlukla copsa kitap bozuk`() {
        assertTrue(TextQuality.mostlyGarbage(listOf(zipDump, zipDump, mojibake, turkish)))
        assertFalse(TextQuality.mostlyGarbage(listOf(turkish, turkish, turkish, zipDump)))
    }

    // ── v37 — MOJIBAKE (ZIP baytı metin sanılmıĢ) ────────────────
    // AĢağıdaki iki örnek, cihazdaki bozuk DOCX/PPTX kitaplarının gerçek
    // sayfa metinlerinden alındı (windows-1254 ile çözülmüĢ ZIP baytları).

    private val realMojibake1 = """
        source PK ! vz™ ± [Content_Types].xml ¢ ( ´•MK@ †ï‚!U­ D¤© ?*¨àu»;i ÷‹Ik½“~
        ‘´)¶¹ ’™y
    """.trimIndent()

    private val realMojibake2 = """
        ‡‘ˆùî‰ wOh~9 Bn8O > €Kç£ùe<‍_~9ç— Y–q]B KZ; “‎|q1
        °­6Y f%(o•‌ù §¯ç½S «‌* )´ d)“my&;­I–ï‘ $¯R.[@†“±5
    """.trimIndent()

    @Test
    fun `gercek bozuk donusum sayfalari yakalanir`() {
        assertTrue(TextQuality.isMojibake(realMojibake1))
        assertTrue(TextQuality.isMojibake(realMojibake2))
        assertTrue(
            TextQuality.mostlyMojibake(listOf(realMojibake1, realMojibake2, turkish))
        )
    }

    @Test
    fun `gercek metinler mojibake sayilmaz`() {
        assertFalse("Türkçe", TextQuality.isMojibake(turkish))
        assertFalse("Arapça", TextQuality.isMojibake(arabic))
        assertFalse("Kod", TextQuality.isMojibake(code))
        assertFalse(
            "Matematik",
            TextQuality.isMojibake(
                """
                Çözüm: A = [[2, 1], [1, 3]] matrisinin determinantı
                det(A) = 2*3 - 1*1 = 5 → A⁻¹ = (1/5) * [[3, -1], [-1, 2]].
                ∫₀¹ (x² + 2x) dx = 1/3 + 1 = 4/3
                """.trimIndent()
            )
        )
        assertFalse(TextQuality.mostlyMojibake(listOf(turkish, arabic, code)))
    }
}
