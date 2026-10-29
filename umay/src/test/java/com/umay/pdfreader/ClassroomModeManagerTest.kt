package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v5.2 (ADIM C.1 / #23) — Sınıf modu süre/özet matematiği (saf, Context'siz).
 */
class ClassroomModeManagerTest {

    @Test
    fun `gecersiz baslangic sifir`() {
        assertEquals(0L, ClassroomModeManager.sessionMillis(0L, 100_000L))
        assertEquals(0L, ClassroomModeManager.sessionMillis(-5L, 100_000L))
    }

    @Test
    fun `now baslangictan onceyse sifir`() {
        assertEquals(0L, ClassroomModeManager.sessionMillis(50_000L, 40_000L))
    }

    @Test
    fun `gecerli sure hesabi`() {
        assertEquals(60_000L, ClassroomModeManager.sessionMillis(1_000L, 61_000L))
        assertEquals(45 * 60_000L, ClassroomModeManager.sessionMillis(0L + 1, 1 + 45 * 60_000L))
    }

    @Test
    fun `ozet bicimlendirmesi`() {
        assertEquals("0 dk", ClassroomModeManager.sessionSummary(0L, 5_000L))
        assertEquals("45dk", ClassroomModeManager.sessionSummary(1L, 1 + 45 * 60_000L))
        assertEquals("1s", ClassroomModeManager.sessionSummary(1L, 1 + 60 * 60_000L))
        assertEquals("1s 5dk", ClassroomModeManager.sessionSummary(1L, 1 + 65 * 60_000L))
    }
}
