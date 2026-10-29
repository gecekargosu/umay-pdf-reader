package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v5.8 — Edge TTS v2 token doğrulaması.
 *
 * Microsoft'un Read Aloud servisi v1 token'ı 403 ile reddediyor. Port edilen
 * `drm.py` algoritması, upstream'in yayımladığı test vektörünü birebir
 * üretmek zorunda; aksi hâlde doğal ses yine çalışmaz.
 */
class EdgeTokenTest {

    @Test
    fun `published v2 vector matches`() {
        val token = EdgeToken.generateV2(1_791_888_000.0)
        assertEquals(
            "492D7694A183A45E-" +
                "56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
            token
        )
    }

    @Test
    fun `token has v2 shape`() {
        val token = EdgeToken.generateV2(1_791_888_000.0)
        val parts = token.split("-")
        assertEquals(2, parts.size)
        assertEquals(16, parts[0].length)
        assertEquals(64, parts[1].length)
        assertEquals(token.uppercase(), token)
    }

    @Test
    fun `same five minute window gives same token`() {
        val a = EdgeToken.generateV2(1_791_888_000.0)
        val b = EdgeToken.generateV2(1_791_888_000.0 + 120)
        assertEquals(a, b)
        val c = EdgeToken.generateV2(1_791_888_000.0 + 300)
        org.junit.Assert.assertNotEquals(a, c)
    }
}
