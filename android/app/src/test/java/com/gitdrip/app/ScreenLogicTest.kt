package com.gitdrip.app

import com.gitdrip.app.ui.ScreenLogic
import org.junit.Assert.*
import org.junit.Test

/** U5: History / Run detail / GitHub / Setup / Settings text and rules (pure JVM). */
class ScreenLogicTest {
    @Test fun detailRowsDropBlanks() {
        val r = ScreenLogic.detailRows(listOf("A" to "x", "B" to "", "C" to "  ", "D" to "y"))
        assertEquals(listOf("A" to "x", "D" to "y"), r)
    }

    @Test fun retryRules() {
        assertTrue(ScreenLogic.canRetry("FAILED", "FAILED")); assertTrue(ScreenLogic.canRetry("pending_retry", "PENDING"))
        assertFalse(ScreenLogic.canRetry("FAILED", "SUCCESS")); assertFalse(ScreenLogic.canRetry("SUCCESS", "PENDING"))
        assertEquals("Retry this batch", ScreenLogic.retryLabel("FAILED")); assertEquals("Retry now", ScreenLogic.retryLabel("PENDING_RETRY"))
    }

    @Test fun setup() {
        assertEquals("Ready", ScreenLogic.checkStatus(true)); assertEquals("Needs attention", ScreenLogic.checkStatus(false))
        assertEquals("All 3 checks passed", ScreenLogic.setupSummary(3, 3)); assertEquals("1 of 3 checks ready", ScreenLogic.setupSummary(1, 3))
        assertEquals("0 of 0 checks ready", ScreenLogic.setupSummary(0, 0))
    }

    @Test fun github() {
        assertEquals("private", ScreenLogic.repoMeta(true, false, false, true))
        assertEquals("public · fork: commits won't count · archived · no push access", ScreenLogic.repoMeta(false, true, true, false))
        assertEquals("main (default)", ScreenLogic.branchLabel("main", "main")); assertEquals("dev", ScreenLogic.branchLabel("dev", "main"))
        assertEquals("octo · fine-grained token", ScreenLogic.accountLine("octo", "fine-grained"))
        assertTrue(ScreenLogic.tokenMsgOk("Token stored in Termux")); assertTrue(ScreenLogic.tokenMsgOk("Saved token removed"))
        assertFalse(ScreenLogic.tokenMsgOk("Engine said no"))
    }

    @Test fun settings() {
        assertTrue(ScreenLogic.backupMsgError("Backup failed")); assertTrue(ScreenLogic.backupMsgError("Termux: no answer"))
        assertFalse(ScreenLogic.backupMsgError("Backup saved"))
        assertEquals("Config · Oct 4", ScreenLogic.backupTitle(true, "Oct 4")); assertEquals("Backup · Oct 4", ScreenLogic.backupTitle(false, "Oct 4"))
        assertEquals("Restore backup from Oct 4", ScreenLogic.backupActionDescription(false, "Oct 4"))
        assertEquals("Apply config from Oct 4", ScreenLogic.backupActionDescription(true, "Oct 4"))
        assertEquals("1 run", ScreenLogic.runsCount(1)); assertEquals("0 runs", ScreenLogic.runsCount(0))
    }
}
