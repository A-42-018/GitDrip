package com.gitdrip.app

import com.gitdrip.app.ui.DashboardLogic
import org.junit.Assert.*
import org.junit.Test

/** U2: Dashboard text rules, layout switch and animation gate (pure JVM). */
class DashboardLogicTest {
    @Test fun greetingByHour() {
        assertEquals("Good night", DashboardLogic.greeting(0)); assertEquals("Good night", DashboardLogic.greeting(4))
        assertEquals("Good morning", DashboardLogic.greeting(5)); assertEquals("Good morning", DashboardLogic.greeting(11))
        assertEquals("Good afternoon", DashboardLogic.greeting(12)); assertEquals("Good afternoon", DashboardLogic.greeting(17))
        assertEquals("Good evening", DashboardLogic.greeting(18)); assertEquals("Good evening", DashboardLogic.greeting(22))
        assertEquals("Good night", DashboardLogic.greeting(23))
        for (h in 0..23) assertTrue(DashboardLogic.greeting(h).startsWith("Good "))
    }

    @Test fun heroSummary() {
        assertEquals("No commits yet today", DashboardLogic.heroSummary(0, 0))
        assertEquals("1 commit today · 1-day streak", DashboardLogic.heroSummary(1, 1))
        assertEquals("3 commits today · 5-day streak", DashboardLogic.heroSummary(3, 5))
        assertEquals("No commits yet today · 2-day streak", DashboardLogic.heroSummary(0, 2))
        assertEquals("2 commits today", DashboardLogic.heroSummary(2, 0))
        assertEquals("4/10 commits today · 3-day streak", DashboardLogic.heroSummary(4, 3, 10))
        assertEquals("0/10 commits today", DashboardLogic.heroSummary(0, 0, 10))
    }

    @Test fun nextRunLabel() {
        assertEquals("No upcoming runs (add a schedule)", DashboardLogic.nextRunLabel(null) { "x" })
        assertEquals("Next run: alif · T1", DashboardLogic.nextRunLabel("alif" to 1L) { "T$it" })
    }

    @Test fun attentionLines() {
        assertTrue(DashboardLogic.attentionLines(0, 0).isEmpty())
        assertEquals(listOf("1 run failed today"), DashboardLogic.attentionLines(1, 0))
        assertEquals(listOf("2 runs waiting to retry in Termux"), DashboardLogic.attentionLines(0, 2))
        assertEquals(listOf("3 runs failed today", "1 run waiting to retry in Termux"), DashboardLogic.attentionLines(3, 1))
    }

    @Test fun quickActionColumns() {
        assertEquals(4, DashboardLogic.quickActionColumns(1.0f, 412)); assertEquals(4, DashboardLogic.quickActionColumns(1.15f, 360))
        assertEquals(2, DashboardLogic.quickActionColumns(1.3f, 412)); assertEquals(2, DashboardLogic.quickActionColumns(1.5f, 412))
        assertEquals(2, DashboardLogic.quickActionColumns(1.0f, 320)); assertEquals(2, DashboardLogic.quickActionColumns(1.0f, 339))
    }

    @Test fun runSummary() {
        assertEquals("p · Oct 4, 13:10 · abcdef1 · 3 files", DashboardLogic.runSummary("p", "Oct 4, 13:10", "abcdef123456", 3, "ignored", "SUCCESS"))
        assertEquals("Oct 4, 13:10 · 1 file", DashboardLogic.runSummary(null, "Oct 4, 13:10", null, 1, null, "SUCCESS"))
        assertEquals("Oct 4, 13:10 · push rejected", DashboardLogic.runSummary(null, "Oct 4, 13:10", "", 0, "push rejected", "FAILED"))
        assertEquals("Oct 4, 13:10", DashboardLogic.runSummary(null, "Oct 4, 13:10", null, 0, "   ", "FAILED"))
        val long = DashboardLogic.runSummary(null, "t", null, 0, "y".repeat(200), "FAILED")
        assertEquals("t · " + "y".repeat(60), long)
    }

    @Test fun animationGate() {
        assertFalse(DashboardLogic.animationsEnabled(0f)); assertTrue(DashboardLogic.animationsEnabled(1f)); assertTrue(DashboardLogic.animationsEnabled(0.5f))
    }
}
