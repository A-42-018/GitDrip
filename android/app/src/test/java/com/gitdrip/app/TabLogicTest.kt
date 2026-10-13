package com.gitdrip.app

import com.gitdrip.app.data.DripMode
import com.gitdrip.app.ui.StateKind
import com.gitdrip.app.ui.StateStyle
import com.gitdrip.app.ui.TabLogic
import org.junit.Assert.*
import org.junit.Test

/** U4: Files / Batches / Schedule tab text and rules (pure JVM). */
class TabLogicTest {
    @Test fun sizes() {
        assertEquals("0 B", TabLogic.formatSize(0)); assertEquals("1023 B", TabLogic.formatSize(1023))
        assertEquals("1.0 KB", TabLogic.formatSize(1024)); assertEquals("1.5 KB", TabLogic.formatSize(1536))
        assertEquals("2.0 MB", TabLogic.formatSize(2L * 1024 * 1024))
        assertEquals("12 B · abcdef0123", TabLogic.fileSubtitle(12, "abcdef0123456789"))
    }

    @Test fun filesAndBatches() {
        assertEquals("1 file · secrets (.env, keys) are never copied", TabLogic.filesSummary(1))
        assertEquals("3 files · secrets (.env, keys) are never copied", TabLogic.filesSummary(3))
        assertEquals("c.kt", TabLogic.fileName("a/b/c.kt")); assertEquals("c.kt", TabLogic.fileName("c.kt"))
        assertEquals("#4", TabLogic.batchTag(4)); assertEquals("Unassigned", TabLogic.batchTag(null))
        assertEquals("#2 feat: x", TabLogic.batchTitle(2, "feat: x")); assertEquals("#2", TabLogic.batchTitle(2, ""))
        assertEquals("1 file", TabLogic.batchFiles(1)); assertEquals("0 files", TabLogic.batchFiles(0))
        assertEquals("Unassigned files: 5", TabLogic.unassignedLabel(5))
    }

    @Test fun runAndMove() {
        for (s in listOf("PENDING", "committed", "FAILED")) assertTrue(s, TabLogic.canRun(s))
        for (s in listOf("SUCCESS", "NOCHANGE", "RUNNING", "PENDING_RETRY")) assertFalse(s, TabLogic.canRun(s))
        assertFalse(TabLogic.canMoveUp(0)); assertTrue(TabLogic.canMoveUp(1))
        assertTrue(TabLogic.canMoveDown(0, 2)); assertFalse(TabLogic.canMoveDown(1, 2)); assertFalse(TabLogic.canMoveDown(0, 0))
    }

    @Test fun days() {
        assertEquals(7, TabLogic.DAY_NAMES.size)
        var d = 0b1111111
        d = TabLogic.toggleDay(d, 6)
        assertFalse(TabLogic.dayOn(d, 6)); assertTrue(TabLogic.dayOn(d, 0))
        assertEquals(0b1111111, TabLogic.toggleDay(d, 6))
    }

    @Test fun scheduleText() {
        assertEquals("09:00 · Every day", TabLogic.scheduleTitle("09:00", "Every day"))
        assertEquals("Device zone · Skip missed slots", TabLogic.scheduleSubtitle("", "skip"))
        assertEquals("Asia/Dhaka · Run when back", TabLogic.scheduleSubtitle("Asia/Dhaka", "run-now"))
        assertEquals("Off", TabLogic.nextLine(false, "x")); assertEquals("No upcoming run", TabLogic.nextLine(true, null))
        assertEquals("Next: 1/2/26", TabLogic.nextLine(true, "1/2/26"))
        assertTrue(TabLogic.scheduleHelp(3).contains("Cap 3/day"))
    }

    @Test fun scheduleErrorOrder() {
        assertEquals("Time must be HH:MM (24h)", TabLogic.scheduleError(false, false, 0))
        assertTrue(TabLogic.scheduleError(true, false, 0)!!.startsWith("Unknown zone"))
        assertEquals("Pick at least one day", TabLogic.scheduleError(true, true, 0))
        assertNull(TabLogic.scheduleError(true, true, 1))
    }
    @Test fun prDialogTexts() {
        assertEquals("Run next as pull request", TabLogic.prTitle(null))
        assertEquals("Run batch #4 as pull request", TabLogic.prTitle(4))
        assertTrue(TabLogic.PR_OPEN.contains("review") && TabLogic.PR_MERGE.contains("merge"))
    }
    @Test fun watchTexts() {
        assertEquals("Off", TabLogic.tickLabel("off")); assertEquals("Off", TabLogic.tickLabel("x"))
        assertEquals("Scan only (queue batches)", TabLogic.tickLabel("scan")); assertEquals("Scan + commit one batch", TabLogic.tickLabel("commit"))
        assertEquals("2 new batches", TabLogic.watchSummary("SUCCESS", "2 new batches")); assertEquals("Scan done", TabLogic.watchSummary("SUCCESS", " "))
        assertTrue(TabLogic.watchSummary("PENDING", "").contains("busy"))
        assertEquals("Watch: no watch dir configured", TabLogic.watchSummary("ERROR", "no watch dir configured")); assertEquals("Watch: no answer", TabLogic.watchSummary("", ""))
    }

    @Test fun autoDripModeTexts() {
        assertEquals("Fixed times", TabLogic.modeChip(DripMode.Fixed)); assertEquals("Auto Drip", TabLogic.modeChip(DripMode.Auto)); assertEquals("Off", TabLogic.modeChip(DripMode.Off))
        assertEquals("1 fixed time enabled", TabLogic.modeWhy(DripMode.Fixed, 1)); assertEquals("2 fixed times enabled", TabLogic.modeWhy(DripMode.Fixed, 2))
        assertEquals("myproject · auto", TabLogic.autoName("myproject"))
    }
    @Test fun autoDripSlotsAndLimits() {
        assertEquals("10 commits per day", TabLogic.perDayLabel(10)); assertEquals("1 commit per day", TabLogic.perDayLabel(1))
        assertEquals(5, TabLogic.clampPerDay(8, 5)); assertEquals(1, TabLogic.clampPerDay(0, 5))
        assertNull(TabLogic.perDayNote(5, 10)); assertNotNull(TabLogic.perDayNote(10, 5))
        assertEquals("PLANNED", TabLogic.slotState("planned")); assertEquals("PENDING", TabLogic.slotState("weird"))
        assertEquals("14:37", TabLogic.slotLine("14:37", "PLANNED", "x"))
        assertEquals("14:37 · no batches left", TabLogic.slotLine("14:37", "SKIPPED", "no batches left"))
        assertTrue(TabLogic.hasPlanned(listOf("DONE", "PLANNED"))); assertFalse(TabLogic.hasPlanned(listOf("DONE")))
    }
    @Test fun oemHints() {
        assertTrue(TabLogic.oemHint("Xiaomi")!!.contains("Autostart")); assertTrue(TabLogic.oemHint("POCO")!!.contains("Termux"))
        assertNull(TabLogic.oemHint("Google"))
    }
    @Test fun doneAndSentStatesGetBadgeKinds() {
        assertEquals(StateKind.Ok, StateStyle.kind("DONE")); assertEquals(StateKind.Running, StateStyle.kind("SENT")); assertEquals(StateKind.Neutral, StateStyle.kind("PLANNED"))
    }
}
