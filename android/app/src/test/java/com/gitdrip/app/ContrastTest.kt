package com.gitdrip.app

import com.gitdrip.app.ui.*
import org.junit.Assert.*
import org.junit.Test

/** NT5: Neo-only contrast / state / touch rules (the pre-Neo colour pairs are gone; the full pair registry runs in NeoLogicTest). */
class ContrastTest {
    private fun worst(fg: Long, bgs: List<Long>) = bgs.minOf { Contrast.ratio(fg, it) }
    private fun atLeast(name: String, min: Double, fg: Long, bgs: List<Long>) {
        val w = worst(fg, bgs); assertTrue("$name worst=${"%.2f".format(w)} < $min", w >= min)
    }

    @Test fun overCompositing() {
        assertEquals(0xFF808080L, Contrast.over(0xFFFFFFFFL, 0.5f, 0xFF000000L))
        assertEquals(0xFF10131AL, Contrast.over(0xFFFFFFFFL, 0f, 0xFF10131AL))
    }
    @Test fun registryIsNotEmpty() = assertTrue("only ${NeoPairs.all.size} pairs", NeoPairs.all.size >= 60)

    /** Focus: the ring must be visible (>= 3:1) on every surface it is drawn on, with the colour [NeoLogic.ringColor] picks. */
    @Test fun focusRingOnEverySurface() {
        atLeast("ring on light raised", 3.0, NeoLogic.ringColor(true), NeoSurfaces.raised)
        atLeast("ring on inset", 3.0, NeoLogic.ringColor(false), NeoSurfaces.inset)
        atLeast("ring on glass", 3.0, NeoLogic.ringColor(false), NeoSurfaces.glass)
        atLeast("ring on solid panel", 3.0, NeoLogic.ringColor(false), NeoSurfaces.solid)
        atLeast("ring on active gradient", 3.0, NeoLogic.ringColor(false), NeoSurfaces.active)
        atLeast("ring on nav bar", 3.0, NeoLogic.ringColor(false), NeoSurfaces.navBar)
    }
    @Test fun pale_cyan_would_be_invisible_on_light_surfaces() = assertTrue(worst(Neo.RingStroke, NeoSurfaces.raised) < 1.5)

    /** Disabled = 50% alpha, no shadow, no glow (WCAG exempts disabled text, the state must still read as dimmed). */
    @Test fun disabledIsDimmedHalf() {
        assertEquals(0.5f, NeoAlpha.Disabled, 0f); assertEquals(0.5f, NeoLogic.alphaFor(false), 0f); assertEquals(1f, NeoLogic.alphaFor(true), 0f)
        assertFalse(NeoLogic.shadowsOn(false)); assertTrue(NeoLogic.shadowsOn(true))
    }
    @Test fun touchTargetIs48() = assertEquals(48, Touch.Min)

    @Test fun stateKinds() {
        assertEquals(StateKind.Ok, StateStyle.kind("SUCCESS")); assertEquals(StateKind.Failed, StateStyle.kind("FAILED"))
        assertEquals(StateKind.Failed, StateStyle.kind("ERROR")); assertEquals(StateKind.Retry, StateStyle.kind("PENDING_RETRY"))
        assertEquals(StateKind.Running, StateStyle.kind("running")); assertEquals(StateKind.Neutral, StateStyle.kind("COMMITTED"))
        assertEquals(StateKind.Neutral, StateStyle.kind("whatever"))
        assertEquals("Pending retry", StateStyle.label("PENDING_RETRY"))
    }
}
