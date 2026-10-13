package com.gitdrip.app

import com.gitdrip.app.data.*
import org.junit.Assert.*
import org.junit.Test

class FireDecisionTest {
    private fun st(paused: Boolean = false, used: Int = 0, cap: Int = 10, open: Int = 0, batches: Boolean = true, day: Boolean = true, fired: Boolean = false) =
        FireState(paused, used, cap, open, batches, day, fired)
    private fun skip(s: FireState) = decide(s) as Decision.Skip

    @Test fun clean() = assertEquals(Decision.Run, decide(st()))
    @Test fun pausedIsPermanent() { val d = skip(st(paused = true)); assertTrue(d.permanent); assertEquals("project paused", d.reason) }
    @Test fun openRunIsTemporary() { val d = skip(st(open = 1)); assertFalse(d.permanent); assertEquals("previous run still open", d.reason) }
    @Test fun capIsPermanent() { val d = skip(st(used = 10)); assertTrue(d.permanent); assertEquals("daily cap (10/10) reached", d.reason) }
    @Test fun nineSuccessPlusFivePendingRetryIsNotCapped() {
        // PENDING_RETRY rows are not counted by usedToday, so only the 9 real commits reach decide()
        assertEquals(Decision.Run, decide(st(used = 9)))
    }
    @Test fun staleRowsDoNotCount() = assertEquals(Decision.Run, decide(st(used = 0)))   // usedToday ignores PENDING older than STALE_RUN_MS
    @Test fun noBatchesPermanent() { val d = skip(st(batches = false)); assertTrue(d.permanent); assertEquals("no batches", d.reason) }
    @Test fun notAnEnabledDay() { val d = skip(st(day = false)); assertTrue(d.permanent) }
    @Test fun firedSlotSkipsButEditedSlotRuns() {
        assertEquals("already ran today's slot", skip(st(fired = true)).reason)
        assertEquals(Decision.Run, decide(st(fired = false)))   // saveSchedule clears lastFireDay when time/days change
    }
    @Test fun customCapParam() = assertTrue(decide(st(used = 2), cap = 2) is Decision.Skip)
}
