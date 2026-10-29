package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v36 — TTS sağlayıcı katmanı testleri (31-ELESTIRI.md · C1).
 *
 * Bu kurallar önceden `TtsManager` içine dağılmıştı ve hiç test edilmiyordu;
 * yanlış kural = ya okumanın susması ya da kullanıcının istemediği mekanik ses.
 */
class TtsEnginePolicyTest {

    private fun base(
        mode: String = "edge",
        online: Boolean = true,
        blockedUntil: Long = 0L,
        now: Long = 1_000_000L,
        edgeDisabled: Boolean = false,
        builtinStuck: Boolean = false,
        machineAllowed: Boolean = true
    ) = TtsEnginePolicy.State(
        mode = mode,
        edgeBlockedUntil = blockedUntil,
        now = now,
        online = online,
        edgeDisabledSession = edgeDisabled,
        builtinStuck = builtinStuck,
        builtinMachineAllowed = machineAllowed
    )

    @Test
    fun `cevrimici ve edge secili makinadan dogal ses once`() {
        val plan = TtsEnginePolicy.plan(base())
        assertEquals(listOf(TtsEnginePolicy.Engine.EDGE, TtsEnginePolicy.Engine.BUILTIN), plan)
    }

    @Test
    fun `cevrimdisi dogrudan yerlesik motora duser`() {
        val plan = TtsEnginePolicy.plan(base(online = false))
        assertEquals(listOf(TtsEnginePolicy.Engine.BUILTIN), plan)
    }

    @Test
    fun `kullanici yerlesik ses sectiyse edge hic denenmez`() {
        val plan = TtsEnginePolicy.plan(base(mode = "builtin"))
        assertEquals(listOf(TtsEnginePolicy.Engine.BUILTIN), plan)
    }

    @Test
    fun `403 sonrasi yirmi dort saat edge kapali`() {
        val s = base(blockedUntil = 2_000_000L, now = 1_000_000L)
        assertTrue(TtsEnginePolicy.edgeBlocked(s))
        assertFalse(TtsEnginePolicy.edgePreferred(s))
        assertEquals(listOf(TtsEnginePolicy.Engine.BUILTIN), TtsEnginePolicy.plan(s))
    }

    @Test
    fun `kilit suresi dolunca edge tekrar denenir`() {
        val s = base(blockedUntil = 500_000L, now = 1_000_000L)
        assertFalse(TtsEnginePolicy.edgeBlocked(s))
        assertEquals(TtsEnginePolicy.Engine.EDGE, TtsEnginePolicy.plan(s).first())
    }

    @Test
    fun `oturumda edge kapatildiysa edge atlanir`() {
        val s = base(edgeDisabled = true)
        assertEquals(TtsEnginePolicy.Engine.BUILTIN, TtsEnginePolicy.plan(s).first())
    }

    @Test
    fun `cevrimdisiyken mekanik ses kapali olsa da okuma calisir`() {
        // Tasarım kuralı: çevrimdışıyken Edge hiç mümkün değil, bu yüzden
        // "mekanik sesi kapat" ayarı çevrimdışı okumayı ENGELLEMEZ.
        val plan = TtsEnginePolicy.plan(base(online = false, machineAllowed = false))
        assertEquals(listOf(TtsEnginePolicy.Engine.BUILTIN), plan)
    }

    @Test
    fun `yerlesik motor kilitlenirse edge kurtarmaya gelir`() {
        val s = base(mode = "builtin", builtinStuck = true)
        assertTrue(TtsEnginePolicy.edgeUsable(s))
        assertEquals(TtsEnginePolicy.Engine.EDGE, TtsEnginePolicy.plan(s).first())
    }

    @Test
    fun `edge hatasindan sonra yerlesik motor secilir`() {
        val s = base()
        assertEquals(
            TtsEnginePolicy.Engine.BUILTIN,
            TtsEnginePolicy.afterEdgeFailure(s, "HTTP 500", 1)
        )
        assertEquals(
            TtsEnginePolicy.Engine.BUILTIN,
            TtsEnginePolicy.afterEdgeFailure(s, "403 Forbidden", 0)
        )
    }

    @Test
    fun `mekanik ses kapaliyken edge hatasinda parca atlanir`() {
        val s = base(machineAllowed = false)
        assertEquals(null, TtsEnginePolicy.afterEdgeFailure(s, "timeout", 1))
    }

    @Test
    fun `kapatma suresi yirmi dort saat`() {
        assertEquals(24L * 60 * 60 * 1000, TtsEnginePolicy.blockDurationMs())
    }
}
