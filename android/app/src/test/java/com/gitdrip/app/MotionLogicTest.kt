package com.gitdrip.app

import com.gitdrip.app.ui.MotionLogic
import org.junit.Assert.*
import org.junit.Test

/** U6: motion / appearance rules (pure JVM). */
class MotionLogicTest {
    @Test fun pressScale() {
        assertEquals(0.98f, MotionLogic.pressScale(true, true), 0f)
        assertEquals(1f, MotionLogic.pressScale(false, true), 0f)
        assertEquals(1f, MotionLogic.pressScale(true, false), 0f)
    }

    @Test fun staggerCapped() {
        assertEquals(0, MotionLogic.enterDelayMs(0, true))
        assertEquals(80, MotionLogic.enterDelayMs(2, true))
        assertEquals(320, MotionLogic.enterDelayMs(50, true))
        assertEquals(0, MotionLogic.enterDelayMs(3, false))
        assertEquals(0, MotionLogic.enterDelayMs(-1, true))
    }

    @Test fun entryOnlyForFirstRows() {
        assertTrue(MotionLogic.shouldAnimateEntry(0, true)); assertTrue(MotionLogic.shouldAnimateEntry(7, true))
        assertFalse(MotionLogic.shouldAnimateEntry(8, true)); assertFalse(MotionLogic.shouldAnimateEntry(0, false)); assertFalse(MotionLogic.shouldAnimateEntry(-1, true))
    }

    @Test fun shimmerAndHint() {
        assertEquals(0.5f, MotionLogic.shimmerPos(0.9f, false), 0f)
        assertEquals(1f, MotionLogic.shimmerPos(1.7f, true), 0f); assertEquals(0f, MotionLogic.shimmerPos(-1f, true), 0f)
        assertNotEquals(MotionLogic.solidHint(true), MotionLogic.solidHint(false))
    }
}
