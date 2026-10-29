package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v36 — Ders ses kayıtları: raf kartındaki süre yazısı.
 *
 * Derste alınan kayıtlar ders rafında "4:32" / "1:07:10" biçiminde görünür.
 * Süre okunamazsa (bozuk dosya) "--:--" yazılır; kullanıcıya "0:00" gibi
 * yanıltıcı bir değer gösterilmez.
 */
class CourseAudioFormatTest {

    @Test
    fun `sifir ve negatif sure tire dondurur`() {
        assertEquals("--:--", CourseAudioStore.formatDuration(0))
        assertEquals("--:--", CourseAudioStore.formatDuration(-1500))
    }

    @Test
    fun `dakika saniye bicimi`() {
        assertEquals("0:05", CourseAudioStore.formatDuration(5_000))
        assertEquals("1:05", CourseAudioStore.formatDuration(65_000))
        assertEquals("59:59", CourseAudioStore.formatDuration(3_599_000))
    }

    @Test
    fun `bir saatten uzun kayitlar saat dakika saniye`() {
        assertEquals("1:00:00", CourseAudioStore.formatDuration(3_600_000))
        assertEquals("1:07:10", CourseAudioStore.formatDuration(4_030_000))
        assertEquals("12:34:56", CourseAudioStore.formatDuration(45_296_000))
    }

    @Test
    fun `saniyeler asagi yuvarlanir`() {
        assertEquals("0:01", CourseAudioStore.formatDuration(1_999))
    }
}
