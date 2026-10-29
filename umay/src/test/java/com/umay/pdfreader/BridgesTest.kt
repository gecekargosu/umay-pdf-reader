package com.umay.pdfreader

import com.umay.pdfreader.data.repository.CacheBudgetRepositoryImpl
import com.umay.pdfreader.data.repository.FocusRepositoryImpl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v5.2 — CLEAN ARCHITECTURE köprü denetimi:
 * data/repository impl'ları özgün store'larla AYNI sonucu üretmeli
 * (saf/girişimsiz: Context gerektirmeyen yüzeyler üzerinden).
 */
class BridgesTest {

    @Test
    fun `cache butce koprusu ayni sonucli`() {
        val repo = CacheBudgetRepositoryImpl()
        assertEquals(CacheKeeper.MAX_CACHE_BYTES, repo.maxBytes)
        assertEquals(CacheKeeper.human(512), repo.human(512))
        assertEquals("1.0 MB", repo.human(1024L * 1024L))
        assertEquals(CacheKeeper.human(2048), repo.human(2048))
    }

    @Test
    fun `odak koprusu format kalibi ayni`() {
        val repo = FocusRepositoryImpl()
        assertEquals(PomodoroManager.formatRemaining(0), repo.formatRemaining(0))
        assertEquals(PomodoroManager.formatRemaining(90_000), repo.formatRemaining(90_000))
        assertEquals("25:00", repo.formatRemaining(25L * 60_000L))
        // Yeni köprü varsayılan sessiz/iptal edilmiş sayılır
        assertEquals(false, repo.isRunning())
    }

    @Test
    fun `odak manager varsayilan sure 25 dk`() {
        assertEquals(25, PomodoroManager.DEFAULT_MINUTES)
        assertEquals("25:00", PomodoroManager.formatRemaining(25L * 60_000L))
        assertEquals("00:05", PomodoroManager.formatRemaining(5_000L))
    }
}
