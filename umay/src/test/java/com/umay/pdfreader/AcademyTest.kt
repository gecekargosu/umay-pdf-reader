package com.umay.pdfreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Akademik modülün saf yardımcıları (`Academy`) — tarih/geri sayım testleri.
 * Tüm girdiler `Calendar` ile aynı saat diliminde kurulur → TZ bağımsız.
 */
class AcademyTest {

    private fun at(
        year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0
    ): Long = Calendar.getInstance().apply {
        set(year, month, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ── formatClock ─────────────────────────────────────────

    @Test
    fun formatClock_sifirDolgulu() {
        assertEquals("09:05", Academy.formatClock(9 * 60 + 5))
        assertEquals("00:00", Academy.formatClock(0))
        assertEquals("23:59", Academy.formatClock(23 * 60 + 59))
    }

    @Test
    fun formatClock_kapsamDisi_turev() {
        // 1440'ın üstü/altındaki değerler gün içine sarılır
        assertEquals("00:30", Academy.formatClock(1470))
        assertEquals("23:00", Academy.formatClock(-60))
    }

    // ── daysLeft / countdownPhrase ──────────────────────────

    @Test
    fun daysLeft_ayniGun_sifir() {
        val now = at(2026, Calendar.OCTOBER, 8, 9, 0)
        val later = at(2026, Calendar.OCTOBER, 8, 21, 30)
        assertEquals(0, Academy.daysLeft(later, now))
    }

    @Test
    fun daysLeft_gunFarki() {
        val now = at(2026, Calendar.OCTOBER, 8, 23, 0)
        val exam = at(2026, Calendar.OCTOBER, 12, 9, 0)
        assertEquals(4, Academy.daysLeft(exam, now))
        assertEquals(-2, Academy.daysLeft(at(2026, Calendar.OCTOBER, 6), now))
    }

    @Test
    fun countdownPhrase_beklenenMetinler() {
        assertEquals("bugün", Academy.countdownPhrase(0))
        assertEquals("yarın", Academy.countdownPhrase(1))
        assertEquals("4 gün kaldı", Academy.countdownPhrase(4))
        assertEquals("süresi geçti", Academy.countdownPhrase(-1))
    }

    @Test
    fun examPhrase_odevCumleleri() {
        assertEquals("Ayrık Matematik sınavına 4 gün kaldı", Academy.examPhrase("Ayrık Matematik", 4))
        assertEquals("Ayrık Matematik sınavı bugün!", Academy.examPhrase("Ayrık Matematik", 0))
        assertEquals("Proje teslimine 2 gün kaldı", Academy.homeworkPhrase("Proje", 2))
        assertEquals("Proje teslim tarihi geçti", Academy.homeworkPhrase("Proje", -3))
    }

    // ── weekDayOf / nextOccurrence ──────────────────────────

    @Test
    fun weekDayOf_pazartesiSifir() {
        // 2026-10-05 Pazartesi, 2026-10-11 Pazar
        assertEquals(0, Academy.weekDayOf(at(2026, Calendar.OCTOBER, 5, 10, 0)))
        assertEquals(6, Academy.weekDayOf(at(2026, Calendar.OCTOBER, 11, 10, 0)))
    }

    @Test
    fun nextOccurrence_gecmisSaat_gelecekHaftayaSarilir() {
        // Cumartesi 23:00'te, Salı 09:00 dersi → gelecek Salı
        val now = at(2026, Calendar.OCTOBER, 10, 23, 0)   // Cumartesi
        val next = Academy.nextOccurrence(1, 9 * 60, now)  // Salı 09:00

        assertTrue(next > now)
        assertEquals(1, Academy.weekDayOf(next))
        assertEquals(9 * 60, Academy.minutesOfDayOf(next))
        // En fazla 7 gün ileride
        assertTrue(next - now <= 7L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun nextOccurrence_ayniGun_saatIleriyse_bugun() {
        // Perşembe 08:00'de, Perşembe 15:00 dersi → bugün 15:00
        val now = at(2026, Calendar.OCTOBER, 8, 8, 0)      // Perşembe
        val next = Academy.nextOccurrence(3, 15 * 60, now) // Perşembe 15:00

        assertTrue(next > now)
        assertEquals(3, Academy.weekDayOf(next))
        assertEquals(15 * 60, Academy.minutesOfDayOf(next))
        assertEquals(Academy.daysLeft(next, now), Academy.daysLeft(next, now).coerceAtLeast(0))
        assertTrue(Academy.daysLeft(next, now) in 0..7)
    }

    @Test
    fun triggerAt_hatirlatmaGectiyseSonrakiHafta() {
        // Derse 5 dakika kaldı, 15 dakika önce hatırlatma → tetik geçmişte → +7 gün
        val now = at(2026, Calendar.OCTOBER, 8, 14, 55)
        val occurrence = at(2026, Calendar.OCTOBER, 8, 15, 0)
        val trigger = Academy.triggerAt(occurrence, 15, now)
        assertTrue(trigger > now)
        assertTrue(trigger <= occurrence + 7L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun triggerAt_normalDurum_ocurringEsiMinusDk() {
        val now = at(2026, Calendar.OCTOBER, 8, 8, 0)
        val occurrence = at(2026, Calendar.OCTOBER, 9, 9, 0)
        val trigger = Academy.triggerAt(occurrence, 30, now)
        assertEquals(occurrence - 30 * 60_000L, trigger)
    }

    // ── dayName / yeni kimlik ───────────────────────────────

    @Test
    fun dayName_turkce() {
        assertEquals("Pazartesi", Academy.dayName(0))
        assertEquals("Pazar", Academy.dayName(6))
        assertEquals("Pazartesi", Academy.dayName(7))   // mod
    }

    @Test
    fun newId_benzersiz() {
        val a = Academy.newId()
        val b = Academy.newId()
        assertTrue(a.length >= 8)
        assertTrue(a != b)
    }

    // ── v5: 20 dakika önceden melodili ders alarmı ────────────

    @Test
    fun remindDefault_20Dakika() {
        assertEquals(20, Academy.REMIND_DEFAULT)
    }

    @Test
    fun triggerAt_yirmiDakikaOnceTetikler() {
        val occurrence = 1_800_000_000_000L
        val now = occurrence - 60 * 60_000L            // 1 saat önceyiz
        val trigger = Academy.triggerAt(occurrence, 20, now)
        assertEquals(occurrence - 20 * 60_000L, trigger)
    }
}
