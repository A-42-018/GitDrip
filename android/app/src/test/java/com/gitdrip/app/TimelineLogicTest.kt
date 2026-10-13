package com.gitdrip.app

import com.gitdrip.app.ui.*
import org.junit.Assert.*
import org.junit.Test

class TimelineLogicTest {
    private val m = 60_000L
    private val start = 9 * 60 * m; private val end = 22 * 60 * m

    @Test fun fractionsAndNow() {
        assertEquals(0f, TimelineLogic.fraction(start, start, end), 0f)
        assertEquals(1f, TimelineLogic.fraction(end, start, end), 0f)
        assertEquals(0.5f, TimelineLogic.fraction((start + end) / 2, start, end), 0.001f)
        assertEquals(0f, TimelineLogic.fraction(start - 5, start, end), 0f)   // clamped
        assertEquals(0f, TimelineLogic.fraction(5, 10, 10), 0f)               // empty window
        assertNull(TimelineLogic.nowFraction(start - 1, start, end)); assertNull(TimelineLogic.nowFraction(end + 1, start, end))
        assertNotNull(TimelineLogic.nowFraction(start + m, start, end))
    }

    @Test fun nextIsEarliestFuturePlanned() {
        val now = 12 * 60 * m
        val slots = listOf(10 * 60 * m to "DONE", 11 * 60 * m to "SKIPPED", 13 * 60 * m to "PLANNED", 15 * 60 * m to "PLANNED", 12 * 60 * m + 1 to "SENT")
        val mk = TimelineLogic.markers(slots, start, end, now)
        assertEquals(listOf(MarkerKind.Done, MarkerKind.Skipped, MarkerKind.Sent, MarkerKind.Next, MarkerKind.Planned), mk.map { it.kind })
        assertEquals(listOf(10 * 60 * m, 11 * 60 * m, 12 * 60 * m + 1, 13 * 60 * m, 15 * 60 * m), mk.map { it.atMs })   // sorted by time
    }

    @Test fun overdueOnlyFallsBackToEarliestPlanned() {
        val mk = TimelineLogic.markers(listOf(10 * 60 * m to "PLANNED", 11 * 60 * m to "PLANNED"), start, end, 20 * 60 * m)
        assertEquals(listOf(MarkerKind.Next, MarkerKind.Planned), mk.map { it.kind })
    }

    @Test fun summaryText() {
        val fmt = { t: Long -> "%02d:%02d".format(t / 60 / m, t / m % 60) }
        assertEquals("No slots planned today", TimelineLogic.summary(emptyList(), 0, fmt))
        val s = listOf(10 * 60 * m to "DONE", 11 * 60 * m to "DONE", 14 * 60 * m + 37 * m to "PLANNED")
        assertEquals("2 of 3 done · next 14:37", TimelineLogic.summary(s, 12 * 60 * m, fmt))
        assertEquals("3 of 3 done", TimelineLogic.summary(listOf(1L to "DONE", 2L to "DONE", 3L to "DONE"), 10, fmt))
    }

    @Test fun kindsAndLabels() {
        assertEquals(MarkerKind.Failed, TimelineLogic.baseKind("failed")); assertEquals(MarkerKind.Planned, TimelineLogic.baseKind("weird"))
        assertEquals("Next", TimelineLogic.kindLabel(MarkerKind.Next))
    }
}
