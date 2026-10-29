package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v36.5 — lambda uyarı sesi: kullanıcı isteği "3 saniye olsun".
 * Bu testler sesin SÜRESİNİ ve iki uyarının FARKLI olduğunu sabitler.
 */
class LampToneBuilderTest {

    @Test
    fun `uyari sesi tam 3 saniye`() {
        val s = LampToneBuilder.build(critical = false)
        assertEquals(LampToneBuilder.SAMPLE_RATE * 3, s.size)
    }

    @Test
    fun `hata sesi de tam 3 saniye`() {
        val s = LampToneBuilder.build(critical = true)
        assertEquals(LampToneBuilder.SAMPLE_RATE * 3, s.size)
    }

    @Test
    fun `ses sessiz degil ve kirpilmiyor`() {
        for (critical in listOf(false, true)) {
            val s = LampToneBuilder.build(critical)
            val peak = LampToneBuilder.peakOf(s)
            assertTrue("tepe çok küçük: $peak", peak > 5_000)
            assertTrue("kırpılmış: $peak", peak <= Short.MAX_VALUE)
        }
    }

    @Test
    fun `saniyenin ortasinda da ses var`() {
        val s = LampToneBuilder.build(critical = true)
        val mid = s.size / 2
        val peak = LampToneBuilder.peakOf(s, mid - 2_000, mid + 2_000)
        assertTrue("ortada sessizlik var", peak > 5_000)
    }

    @Test
    fun `iki uyari birbirinden farkli`() {
        val warn = LampToneBuilder.build(critical = false)
        val fail = LampToneBuilder.build(critical = true)
        var diff = 0
        for (i in 0 until minOf(warn.size, fail.size)) if (warn[i] != fail[i]) diff++
        // En az %80 farklı örnek: gerçekten başka bir ses
        assertTrue("sesler neredeyse aynı ($diff fark)", diff > warn.size * 0.8)

        // Tek örnek bakmak yanıltıcı (sinüs sıfırdan geçebilir): 1 sn'lik
        // pencerede ortalama fark ölçülür.
        val from = LampToneBuilder.SAMPLE_RATE / 2
        val to = from + LampToneBuilder.SAMPLE_RATE
        var sum = 0.0
        for (i in from until to) sum += kotlin.math.abs(warn[i] - fail[i]).toDouble()
        val avgDiff = sum / (to - from)
        assertTrue("ortalama fark çok küçük ($avgDiff)", avgDiff > 500.0)
    }

    @Test
    fun `sifir ses seviyesinde sessiz`() {
        val s = LampToneBuilder.build(critical = false, volume = 0f)
        assertEquals(0, LampToneBuilder.peakOf(s))
    }
}
