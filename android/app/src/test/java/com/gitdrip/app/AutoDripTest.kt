package com.gitdrip.app

import com.gitdrip.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Random

class AutoDripTest {
    private val utc = ZoneId.of("UTC")
    private val day = LocalDate.parse("2026-10-06")
    private fun ms(s: String, z: ZoneId = utc) = ZonedDateTime.parse(s).withZoneSameInstant(z).toInstant().toEpochMilli()
    private val m = 60_000L
    private val seeds = listOf(1L, 7L, 42L, 2026L, 99999L)

    @Test fun tenSlotsInWindowSortedSpacedOnePerSegment() {
        val now = ms("2026-10-06T06:00:00Z"); val ws = ms("2026-10-06T09:00:00Z"); val we = ms("2026-10-06T22:00:00Z")
        for (seed in seeds) {
            val s = planAutoSlots(day, "09:00", "22:00", 10, now, utc, Random(seed))
            assertEquals(10, s.size); assertEquals(s.sorted(), s)
            assertTrue(s.all { it >= ws && it < we })
            s.zipWithNext().forEach { (a, b) -> assertTrue("gap ${(b - a) / m} min (seed $seed)", b - a >= 20 * m) }
            val seg = (we - ws) / 10
            s.forEachIndexed { i, t -> assertTrue("slot $i outside its segment", t >= ws + i * seg && t < ws + (i + 1) * seg) }
        }
    }

    @Test fun midDayPlanningOnlyUsesRestOfWindow() {
        val now = ms("2026-10-06T15:00:00Z")
        for (seed in seeds) {
            val s = planAutoSlots(day, "09:00", "22:00", 4, now, utc, Random(seed))
            assertEquals(4, s.size)
            assertTrue(s.all { it >= now + 5 * m })
        }
    }

    @Test fun windowAlreadyPassedOrNothingToPlan() {
        assertTrue(planAutoSlots(day, "09:00", "22:00", 3, ms("2026-10-06T21:58:00Z"), utc, Random(1)).isEmpty())
        assertTrue(planAutoSlots(day, "09:00", "22:00", 3, ms("2026-10-06T23:00:00Z"), utc, Random(1)).isEmpty())
        assertTrue(planAutoSlots(day, "09:00", "22:00", 0, ms("2026-10-06T06:00:00Z"), utc, Random(1)).isEmpty())
    }

    @Test fun tooManySlotsFallBackToEvenSpacingWithoutOverlap() {
        val now = ms("2026-10-06T06:00:00Z")
        val s = planAutoSlots(day, "09:00", "10:00", 10, now, utc, Random(3))   // 60 min / 10 = 6 min segments (< 20 min gap)
        assertEquals(10, s.size)
        s.zipWithNext().forEach { (a, b) -> assertEquals(6 * m, b - a) }
        assertTrue(s.all { it < ms("2026-10-06T10:00:00Z") })
        val few = planAutoSlots(day, "09:00", "09:30", 40, now, utc, Random(3))   // more slots than minutes: capped
        assertEquals(30, few.size); assertEquals(few.size, few.toSet().size)
    }

    @Test fun otherProjectsSlotsAreAtLeastFiveMinutesAway() {
        val now = ms("2026-10-06T06:00:00Z")
        val taken = (0 until 12).map { ms("2026-10-06T09:00:00Z") + it * 60 * m }   // another project every hour on the hour
        for (seed in seeds) {
            val s = planAutoSlots(day, "09:00", "22:00", 10, now, utc, Random(seed), taken = taken)
            s.forEach { t -> taken.forEach { o -> assertTrue("slot ${(t - o) / m} min from another project's", kotlin.math.abs(t - o) >= 5 * m) } }
            s.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 5 * m) }
        }
    }

    @Test fun slotsToPlanFormula() {
        assertEquals(3, slotsToPlan(10, 10, 4, 3))
        assertEquals(6, slotsToPlan(10, 10, 4, 99))
        assertEquals(0, slotsToPlan(10, 10, 10, 5))
        assertEquals(0, slotsToPlan(10, 10, 12, 5))     // used above the cap never goes negative
        assertEquals(0, slotsToPlan(10, 10, 0, 0))
        assertEquals(2, slotsToPlan(2, 10, 0, 7))
    }

    @Test fun modeRules() {
        assertEquals(DripMode.Fixed, dripMode(1, false)); assertEquals(DripMode.Fixed, dripMode(2, true))
        assertEquals(DripMode.Auto, dripMode(0, true)); assertEquals(DripMode.Off, dripMode(0, false))
    }

    @Test fun dstDayHasNoSlotInTheGap() {
        val berlin = ZoneId.of("Europe/Berlin"); val d = LocalDate.parse("2026-03-29")   // 02:00 -> 03:00 does not exist
        val now = ms("2026-03-28T20:00:00Z"); val ws = slotMs(d, "01:00", berlin); val we = slotMs(d, "05:00", berlin)
        assertEquals(3 * 60 * m, we - ws)    // the real window is 3 h, not 4
        for (seed in seeds) {
            val s = planAutoSlots(d, "01:00", "05:00", 6, now, berlin, Random(seed))
            assertEquals(6, s.size)
            s.forEach { t ->
                assertTrue(t >= ws && t < we)
                val lt = java.time.Instant.ofEpochMilli(t).atZone(berlin).toLocalTime()
                assertFalse("slot inside the DST gap: $lt", lt >= java.time.LocalTime.of(2, 0) && lt < java.time.LocalTime.of(3, 0))
            }
        }
    }

    @Test fun sameSeedSamePlan() {
        val now = ms("2026-10-06T06:00:00Z")
        assertEquals(planAutoSlots(day, "09:00", "22:00", 10, now, utc, Random(5)), planAutoSlots(day, "09:00", "22:00", 10, now, utc, Random(5)))
        assertNotEquals(planAutoSlots(day, "09:00", "22:00", 10, now, utc, Random(5)), planAutoSlots(day, "09:00", "22:00", 10, now, utc, Random(6)))
    }

    @Test fun windowValidation() {
        assertNull(autoWindowError("09:00", "22:00"))
        assertNull(autoWindowError("09:00", "09:30"))
        assertNotNull(autoWindowError("09:00", "09:29"))
        assertNotNull(autoWindowError("22:00", "09:00"))
        assertNotNull(autoWindowError("9:00", "22:00"))
        assertNotNull(autoWindowError("09:00", "09:00"))
    }
}
