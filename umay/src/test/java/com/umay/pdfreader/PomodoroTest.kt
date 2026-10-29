package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v15 (ADIM B.1 / #19) — Pomodoro sayaç biçimlendirme testi.
 */
class PomodoroTest {

    @Test
    fun `25 dakika tam sayi olarak gozukur`() {
        assertEquals("25:00", PomodoroManager.formatRemaining(25 * 60_000L))
    }

    @Test
    fun `saniye yuvarlanir`() {
        // 1500 ms → kalan süre en az 2 saniye gösterilir (üst yuvarlama)
        assertEquals("00:02", PomodoroManager.formatRemaining(1500))
        // 999 ms → yine "00:01" (süre dolmak üzere)
        assertEquals("00:01", PomodoroManager.formatRemaining(999))
    }

    @Test
    fun `sıfır saniye sifir olarak gozukur`() {
        assertEquals("00:00", PomodoroManager.formatRemaining(0))
    }

    @Test
    fun `bir saatten uzun saat formatina gecer`() {
        // 90 dk → "1:30:00"
        assertEquals("1:30:00", PomodoroManager.formatRemaining(90 * 60_000L))
    }
}
