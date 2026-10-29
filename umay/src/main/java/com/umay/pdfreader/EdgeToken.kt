package com.umay.pdfreader

/**
 * v5.8 — EDGE NEURAL TTS "Sec-MS-GEC" v2 TOKEN ÜRETİCİ.
 *
 * NEDEN VAR:
 *   Microsoft, Read Aloud servisinde Ekim 2026'da v1 token'ı (64 hex SHA-256)
 *   reddetmeye başladı: websocket el sıkışması **HTTP 403 Forbidden** dönüyor,
 *   doğal ses tamamen çalışmıyordu (tanılama raporu: "Expected HTTP 101
 *   response but was '403 Forbidden'"). Yeni biçim:
 *
 *       <16 hex CS64>-<64 hex özel SHA-256>
 *
 *   ve `Sec-MS-GEC-Version=2-143.0.3650.75` ile birlikte gönderilir.
 *
 * KAYNAK: rany2/edge-tts `drm.py` (v7.x) — birebir port. Algoritma bilinçli
 * olarak "değiştirilmiş" SHA-256 sabitleri + CS64 karışımı kullanır; bu yüzden
 * standart `MessageDigest` ile üretilemez. Test vektörü:
 *   unix 1791888000 → 492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF3
 *                     79EC36D510213555BBBB9EB17EF15876
 */
object EdgeToken {

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH = 11644473600L
    private const val MASK = 0xFFFFFFFFL

    private val SHA256_INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L
    )

    private val SHA256_STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L
    )

    private val SHA256_SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
        0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
        0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
        0xD258B060L, 0x72766564L
    )

    private val SHA256_ROUND = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
        0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
        0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
        0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
        0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
        0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
        0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
        0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
        0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L
    )

    private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0L

    /** İlk 14 round sabiti + değiştirilmiş 15. sabit + kalanlar. */
    private val SHA256_BLOCK: LongArray = LongArray(64) { i ->
        when {
            i < 14 -> SHA256_ROUND[i]
            i == 14 -> SHA256_ADJUSTMENT
            else -> SHA256_ROUND[i]
        }
    }

    private val CS64_COEFFICIENTS = longArrayOf(
        0xE82E91B1L, 0xB32700D0L, 0x44DE24C6L, 0x88DFF51DL,
        0x3BEB65BCL, 0x29514038L, 0xC89C5933L, 0xB328CBDFL,
        0x8D1EC211L, 0xD405B4D5L, 0x314EF5D5L, 0x26E55F3CL,
        0xE92491B1L, 0x607801C6L, 0xF0A5B9D5L, 0x82B2C1FBL,
        0x06064CF3L, 0xFA51A3DAL, 0xDA79FA60L, 0x207CC464L,
        0xBED20560L, 0x3C3C74F3L, 0x89F9C9F8L, 0xD2D14B61L,
        0x35FA38C6L, 0xBB04A2C1L, 0xCB49E3C6L, 0xC5626167L
    )

    private val CS64_OFFSETS = longArrayOf(
        0xB32700D0L, 0x88DFF51DL, 0x29514038L, 0xB328CBDFL,
        0xD405B4D5L, 0x26E55F3CL, 0x607801C6L, 0x82B2C1FBL,
        0xFA51A3DAL, 0x207CC464L, 0x3C3C74F3L, 0xD2D14B61L,
        0xBB04A2C1L, 0xC5626167L
    )

    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

    /** 32-bit sağa döndürme (ROTR) — SHA-256 round'larında ŞART. */
    private fun rotr(value: Long, bits: Int): Long {
        val v = value and MASK
        return ((v ushr bits) or (v shl (32 - bits))) and MASK
    }

    private fun bigSigma0(v: Long): Long = rotr(v, 2) xor rotr(v, 13) xor rotr(v, 22)
    private fun bigSigma1(v: Long): Long = rotr(v, 6) xor rotr(v, 11) xor rotr(v, 25)

    private fun rol16(value: Long): Long {
        val v = value and MASK
        return ((v shl 16) or (v ushr 16)) and MASK
    }

    private fun sigma0(value: Long): Long =
        ((value ushr 7) xor (value shl 25) xor (value ushr 18) xor
            (value shl 14) xor (value ushr 3)) and MASK

    private fun sigma1(value: Long): Long =
        ((value ushr 17) xor (value shl 15) xor (value ushr 19) xor
            (value shl 13) xor (value ushr 10)) and MASK

    /** Python `_sha256_rounds` portu. */
    private fun sha256Rounds(state: LongArray, words: LongArray, firstRound: Int): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val constants = if (firstRound == 14) SHA256_BLOCK else SHA256_ROUND
        val aligned = minOf(64, ((firstRound + 7) / 8) * 8)

        for (index in firstRound until aligned) {
            val t1 = (h + bigSigma1(e) +
                (g xor (e and (f xor g))) +
                constants[index] + words[index])
            val t2 = bigSigma0(a) + ((a and b) or (c and (a or b)))
            val newA = (t1 + t2) and MASK
            val newE = (d + t1) and MASK
            h = g; g = f; f = e; e = newE; d = c; c = b; b = a; a = newA
        }

        var index = aligned
        while (index < 64) {
            var t1 = (h + bigSigma1(e) +
                (g xor (e and (f xor g))) +
                constants[index] + words[index])
            d = (d + t1) and MASK
            h = (t1 + bigSigma0(a) + ((a and b) or (c and (a or b)))) and MASK

            t1 = (g + bigSigma1(d) + (f xor (d and (e xor f))) +
                constants[index + 1] + words[index + 1])
            c = (c + t1) and MASK
            g = (t1 + bigSigma0(h) + ((h and a) or (b and (h or a)))) and MASK

            t1 = (f + bigSigma1(c) + (e xor (c and (d xor e))) +
                constants[index + 2] + words[index + 2])
            b = (b + t1) and MASK
            f = (t1 + bigSigma0(g) + ((g and h) or (a and (g or h)))) and MASK

            t1 = (e + bigSigma1(b) + (d xor (b and (c xor d))) +
                constants[index + 3] + words[index + 3])
            a = (a + t1) and MASK
            e = (t1 + bigSigma0(f) + ((f and g) or (h and (f or g)))) and MASK

            t1 = (d + bigSigma1(a) + (c xor (a and (b xor c))) +
                constants[index + 4] + words[index + 4])
            h = (h + t1) and MASK
            d = (t1 + bigSigma0(e) + ((e and f) or (g and (e or f)))) and MASK

            t1 = (c + bigSigma1(h) + (b xor (h and (a xor b))) +
                constants[index + 5] + words[index + 5])
            g = (g + t1) and MASK
            c = (t1 + bigSigma0(d) + ((d and e) or (f and (d or e)))) and MASK

            t1 = (b + bigSigma1(g) + (a xor (g and (h xor a))) +
                constants[index + 6] + words[index + 6])
            f = (f + t1) and MASK
            b = (t1 + bigSigma0(c) + ((c and d) or (e and (c or d)))) and MASK

            t1 = (a + bigSigma1(f) + (h xor (f and (g xor h))) +
                constants[index + 7] + words[index + 7])
            e = (e + t1) and MASK
            a = (t1 + bigSigma0(b) + ((b and c) or (d and (b or c)))) and MASK

            index += 8
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(words: MutableList<Long>, firstWord: Int) {
        var i = firstWord
        while (i < 64) {
            val x = words[i - 2]
            val y = words[i - 15]
            val s1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
            val s0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
            words.add((s1 + words[i - 7] + s0 + words[i - 16]) and MASK)
            i++
        }
    }

    private fun sha256Init(data: ByteArray): LongArray {
        val words = ArrayList<Long>(64)
        repeat(14) { words.add(0L) }
        words.add(((data[0].toLong() and 0xFF) shl 16) or
            ((data[1].toLong() and 0xFF) shl 8) or
            (data[2].toLong() and 0xFF))
        words.add(((data[3].toLong() and 0xFF) shl 24) or
            ((data[4].toLong() and 0xFF) shl 16) or
            ((data[5].toLong() and 0xFF) shl 8) or
            (data[6].toLong() and 0xFF))
        for (index in 16..29) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) sigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                else sigma1(words[index - 2])
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) sigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                else sigma0(words[index - 15])
            }
            words.add(value and MASK)
        }
        words.add((sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
            SHA256_ADJUSTMENT - SHA256_ROUND[14]) and MASK)
        expandSchedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, words.toLongArray(), 14)
        return LongArray(8) { (SHA256_INITIAL[it] + working[it]) and MASK }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt en az 7 bayt olmalı" }
        val length = 57 + data.size
        val out = java.io.ByteArrayOutputStream()
        out.write(data, 7, data.size - 7)
        out.write(0x80)
        repeat(((55 - length) % 64 + 64) % 64) { out.write(0) }
        var bits = (length * 8).toLong()
        val lenBytes = ByteArray(8)
        for (i in 7 downTo 0) {
            lenBytes[i] = (bits and 0xFF).toByte()
            bits = bits ushr 8
        }
        out.write(lenBytes)
        val padded = out.toByteArray()

        var state = sha256Init(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < padded.size) {
            // DİKKAT: liste YALNIZ ilk 16 kelimeyle başlamalı; 64'lük boş
            // dizi verilirse genişletme 64. indeksten başlar ve 16..63 sıfır
            // kalır (port sırasında yaşanan hata — test vektörü yakaladı).
            val list = ArrayList<Long>(64)
            for (i in 0 until 16) {
                val p = offset + i * 4
                list.add(
                    ((padded[p].toLong() and 0xFF) shl 24) or
                        ((padded[p + 1].toLong() and 0xFF) shl 16) or
                        ((padded[p + 2].toLong() and 0xFF) shl 8) or
                        (padded[p + 3].toLong() and 0xFF)
                )
            }
            expandSchedule(list, 16)
            val working = sha256Rounds(state, list.toLongArray(), 0)
            state = LongArray(8) { (state[it] + working[it]) and MASK }
            offset += 64
        }
        val digest = ByteArray(32)
        for (i in 0 until 8) {
            digest[i * 4] = ((state[i] ushr 24) and 0xFF).toByte()
            digest[i * 4 + 1] = ((state[i] ushr 16) and 0xFF).toByte()
            digest[i * 4 + 2] = ((state[i] ushr 8) and 0xFF).toByte()
            digest[i * 4 + 3] = (state[i] and 0xFF).toByte()
        }
        return digest
    }

    /** Python `_cs64_digest` portu. */
    private fun cs64Digest(data: ByteArray, digest: ByteArray): LongArray {
        var keyA = ((digest[0].toLong() and 0xFF) or
            ((digest[1].toLong() and 0xFF) shl 8) or
            ((digest[2].toLong() and 0xFF) shl 16) or
            ((digest[3].toLong() and 0xFF) shl 24)) or 1L
        var keyB = ((digest[4].toLong() and 0xFF) or
            ((digest[5].toLong() and 0xFF) shl 8) or
            ((digest[6].toLong() and 0xFF) shl 16) or
            ((digest[7].toLong() and 0xFF) shl 24)) or 1L
        keyA = keyA and MASK
        keyB = keyB and MASK
        val factors = longArrayOf((keyA * CS64_FACTOR) and MASK, (keyB * CS64_FACTOR) and MASK)

        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        for (i in 0 until 14) {
            terms.add((CS64_COEFFICIENTS[i * 2] * factors[i % 2]) and MASK)
            offsets.add(CS64_OFFSETS[i])
        }

        val words = ArrayList<Long>()
        val src = byteArrayOf(0) + data
        val paddedLen = src.size + ((4 - src.size % 4) % 4)
        var p = 0
        while (p < paddedLen) {
            var v = 0L
            for (k in 0 until 4) {
                val b = if (p + k < src.size) (src[p + k].toLong() and 0xFF) else 0L
                v = v or (b shl (8 * k))
            }
            words.add(v)
            p += 4
        }
        if (words.size % 2 == 1) words.add(0L)

        for ((index, word) in words.withIndex()) {
            var term = (word * (if (index % 2 == 0) keyA else keyB)) and MASK
            var offset = (rol16(word) * (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL)) and MASK
            // DİKKAT: ADJUSTMENT1 YALNIZ ilk kelimede eklenir (kaynak
            // `drm.py` içinde iki satır da `if index == 0` bloğundadır).
            if (index == 0) {
                term = (term + CS64_ADJUSTMENT0 * factors[0]) and MASK
                offset = (offset + CS64_ADJUSTMENT1) and MASK
            }
            terms.add(term)
            offsets.add(offset)
        }

        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]
            val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and MASK)
            var q = (t * 0x76F50F34L) and MASK
            swapAcc = (swapAcc + ((rol16(q) * 0xC3EFEA97L + q) and MASK)) and MASK
            t = rol16((offsets[i + 1] + y) and MASK)
            q = (t * 0x1881CE3CL) and MASK
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK

            state = (state * keyA + x) and MASK
            state = (rol16(state) * 0xCF98B111L) and MASK
            state = (rol16(state) * 0x87085B9FL) and MASK
            state = (rol16(state) * 0x12CEB96DL) and MASK
            state = (rol16(state) * 0x257E1D83L) and MASK
            acc = (acc + state) and MASK

            t = (state * keyB + y) and MASK
            t = (rol16(t) * 0xA27416F5L) and MASK
            t = (rol16(t) * 0xD38396FFL) and MASK
            state = (rol16((rol16(t) * 0x7C932B89L) and MASK) * 0xBFA49F69L) and MASK
            acc = (acc + state) and MASK
            i += 2
        }
        return longArrayOf(swapLast xor state, swapAcc xor acc)
    }

    /** Verilen unix zamanı için v2 token üretir (test edilebilir). */
    fun generateV2(unixSeconds: Double = System.currentTimeMillis() / 1000.0): String {
        var ticks = unixSeconds + WIN_EPOCH
        ticks -= ticks % 300.0
        ticks *= 1e9 / 100.0
        val str = String.format(java.util.Locale.US, "%.0f", ticks) + TRUSTED_CLIENT_TOKEN
        val data = str.toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val cs = cs64Digest(data, digest)
        // Python `struct.pack("<II", ...).hex()` → her 32-bit kelime LITTLE-ENDIAN
        // yazılır (bayt sırası ters). Aynı biçimi birebir üretiyoruz.
        fun le(w: Long): String = String.format(
            java.util.Locale.US, "%02X%02X%02X%02X",
            w and 0xFF, (w ushr 8) and 0xFF, (w ushr 16) and 0xFF, (w ushr 24) and 0xFF
        )
        val csHex = le(cs[0] and MASK) + le(cs[1] and MASK)
        val shaHex = digest.joinToString("") { String.format(java.util.Locale.US, "%02X", it) }
        return "$csHex-$shaHex"
    }
}
