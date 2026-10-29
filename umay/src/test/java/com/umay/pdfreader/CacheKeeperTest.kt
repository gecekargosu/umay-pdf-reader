package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v15 (ADIM B.3 / #12) — CacheKeeper 100 MB bütçe denetimi testi.
 *
 * Kabul: bütçe aşıldığında en ESKİ dosyalardan başlanarak silinir,
 * en yeniler korunur; yarım inmiş (0 bayt) dosyalar anında temizlenir.
 */
class CacheKeeperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fake(dir: java.io.File, name: String, bytes: Int, timeMillis: Long): java.io.File {
        val f = java.io.File(dir, name)
        f.writeBytes(ByteArray(bytes))
        f.setLastModified(timeMillis)
        return f
    }

    @Test
    fun `butce altinda hicbir dosya silinmez`() {
        val dir = tmp.newFolder("cache")
        val now = System.currentTimeMillis()
        fake(dir, "t1.pdf", 1_000, now - 3_000)
        fake(dir, "t2.pdf", 1_000, now - 2_000)
        val remaining = CacheKeeper.trim(dir, "t", maxBytes = 10_000)
        assertEquals(2_000L, remaining)
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun `butce asilinca en eski dosya once silinir`() {
        val dir = tmp.newFolder("cache")
        val now = System.currentTimeMillis()
        fake(dir, "t1.pdf", 6_000, now - 3_000)   // en eski
        fake(dir, "t2.pdf", 6_000, now - 2_000)
        fake(dir, "t3.pdf", 6_000, now - 1_000)   // en yeni
        val remaining = CacheKeeper.trim(dir, "t", maxBytes = 13_000)
        // 18 KB → 13 KB bütçe → en eskiden 6 KB silinir
        assertEquals(12_000L, remaining)
        assertTrue("en eski dosya silinmemiş", !java.io.File(dir, "t1.pdf").exists())
        assertTrue("en yeni dosya silinmiş!", java.io.File(dir, "t3.pdf").exists())
    }

    @Test
    fun `sifir baytlik yarim dosyalar aninda temizlenir`() {
        val dir = tmp.newFolder("cache")
        val now = System.currentTimeMillis()
        fake(dir, "t0.pdf", 0, now)
        fake(dir, "t1.pdf", 500, now)
        val remaining = CacheKeeper.trim(dir, "t", maxBytes = 100_000)
        assertEquals(500L, remaining)
        assertTrue(!java.io.File(dir, "t0.pdf").exists())
    }

    @Test
    fun `onek disindaki dosyalara dokunulmaz`() {
        val dir = tmp.newFolder("cache")
        val now = System.currentTimeMillis()
        fake(dir, "x1.pdf", 9_000, now - 5_000)
        fake(dir, "t1.pdf", 1_000, now)
        CacheKeeper.trim(dir, "t", maxBytes = 1)
        assertTrue("onek disi dosya silinmiş!", java.io.File(dir, "x1.pdf").exists())
    }

    @Test
    fun `human bicimlendirme calisir`() {
        assertEquals("512 B", CacheKeeper.human(512))
        assertEquals("2 KB", CacheKeeper.human(2 * 1024))
        assertEquals("1.0 MB", CacheKeeper.human(1024L * 1024L))
    }
}
