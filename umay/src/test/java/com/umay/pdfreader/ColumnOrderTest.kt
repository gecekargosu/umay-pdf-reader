package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * v5.4 (ONARIM) — İki sütunlu sayfada okuma sırası testleri.
 *
 * Gerçek cihaz bulgusu: slayt iki sütunluydu ve sesli okuma
 * "1. madde, 5. madde, 2. madde…" diye atlıyordu.
 */
class ColumnOrderTest {

    private fun box(x0: Float, y0: Float, x1: Float, y1: Float, text: String) =
        LineBox(x0, y0, x1, y1, text)

    @Test
    fun `iki sutunlu sayfada once sol sonra sag okunur`() {
        val lines = listOf(
            // Tam genişlik başlık
            box(0.05f, 0.05f, 0.95f, 0.10f, "HAFTA 2 Yol Haritası"),
            // Sol sütun
            box(0.05f, 0.20f, 0.45f, 0.25f, "1. Nitel veriler"),
            box(0.05f, 0.30f, 0.45f, 0.35f, "2. Nicel veriler"),
            box(0.05f, 0.40f, 0.45f, 0.45f, "3. Histogram"),
            // Sağ sütun
            box(0.55f, 0.20f, 0.95f, 0.25f, "5. Toplam"),
            box(0.55f, 0.30f, 0.95f, 0.35f, "6. Merkezi eğilim"),
            box(0.55f, 0.40f, 0.95f, 0.45f, "7. Değişkenlik")
        )

        val ordered = ColumnOrder.reorder(lines)
        assertNotNull(ordered)
        assertEquals(
            listOf(
                "HAFTA 2 Yol Haritası",
                "1. Nitel veriler",
                "2. Nicel veriler",
                "3. Histogram",
                "5. Toplam",
                "6. Merkezi eğilim",
                "7. Değişkenlik"
            ),
            ordered!!.map { it.text }
        )
    }

    @Test
    fun `tek sutunlu sayfa degismez`() {
        val lines = (1..8).map {
            box(0.08f, 0.05f * it, 0.92f, 0.05f * it + 0.03f, "Satır $it")
        }
        assertNull(ColumnOrder.reorder(lines))
        val raw = lines.joinToString("\n") { it.text }
        assertEquals(raw, ColumnOrder.reorderText(raw, lines))
    }

    @Test
    fun `az satirli sayfada dokunulmaz`() {
        val lines = listOf(
            box(0.05f, 0.1f, 0.4f, 0.2f, "a"),
            box(0.6f, 0.1f, 0.9f, 0.2f, "b"),
            box(0.05f, 0.3f, 0.4f, 0.4f, "c")
        )
        assertNull(ColumnOrder.reorder(lines))
    }
}
