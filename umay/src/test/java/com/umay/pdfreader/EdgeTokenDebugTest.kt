package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeTokenDebugTest {

    private val data = "1343636160000000006A5AA1D4EAFF4E9FB37E23D68491D6F4".toByteArray()

    @Test
    fun digestMatches() {
        val hex = EdgeToken.digestForTest(data)
            .joinToString("") { String.format("%02X", it) }
        assertEquals(
            "56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
            hex
        )
    }

    @Test
    fun cs64Terms() {
        val digest = EdgeToken.digestForTest(data)
        val d = EdgeToken.cs64TermsForTest(data, digest)
        assertEquals("terms[0]", 0xacad87c5L, d[0])
        assertEquals("terms[14]", 0x36e566a2L, d[1])
        assertEquals("offsets[14]", 0xcdb51901L, d[2] and 0xFFFFFFFFL)
        assertEquals("count", 28L, d[3])
        assertEquals("words", 14L, d[4])
        val res = EdgeToken.cs64DebugForTest(data, digest)
        assertEquals("cs0", 0x94762d49L, res[2] and 0xFFFFFFFFL)
        assertEquals("cs1", 0x5ea483a1L, res[3] and 0xFFFFFFFFL)
    }
}
