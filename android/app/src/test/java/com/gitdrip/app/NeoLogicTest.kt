package com.gitdrip.app

import com.gitdrip.app.ui.*
import org.junit.Assert.*
import org.junit.Test

/** Neo-Tactile tokens: contrast of every text/surface pair, fallbacks, motion maths (pure JVM). */
class NeoLogicTest {
    @Test fun everyContrastPairPasses() {
        val bad = NeoPairs.all.filter { it.worst() < it.min }.map { "${it.name}: %.2f < ${it.min}".format(it.worst()) }
        assertTrue("failing pairs: $bad", bad.isEmpty())
    }
    @Test fun everyTextTokenIsCovered() {
        NeoPairs.textTokens.forEach { (n, c) -> assertTrue("$n has no contrast pair", NeoPairs.all.any { it.fg == c }) }
    }
    @Test fun specSecondaryTextWasTooLight() = assertTrue(Contrast.ratio(0xFF8D95A2L, Neo.RaisedModal) < 4.5)
    @Test fun solidFallback() {
        assertTrue(NeoLogic.solidPanels(false, 30)); assertTrue(NeoLogic.solidPanels(true, 34)); assertFalse(NeoLogic.solidPanels(false, 31))
        assertEquals(0.92f, NeoLogic.panelAlpha(1, true), 0f); assertEquals(0.55f, NeoLogic.panelAlpha(1, false), 0f); assertEquals(0f, NeoLogic.shadeAlpha(true), 0f)
        assertTrue(NeoLogic.useRealBlur(31, false, false)); assertFalse(NeoLogic.useRealBlur(31, false, true)); assertFalse(NeoLogic.useRealBlur(30, false, false))
    }
    @Test fun motionAndShadows() {
        assertEquals(0, NeoLogic.ms(NeoMs.Press, false)); assertEquals(120, NeoLogic.ms(NeoMs.Press, true))
        assertEquals(9f, NeoLogic.sunk(18f, 1f), 0.001f); assertEquals(18f, NeoLogic.sunk(18f, 0f), 0.001f)
        assertEquals(1f, NeoLogic.translateDp(1f, 1f), 0.001f); assertEquals(-1f, NeoLogic.translateDp(0f, 1f), 0.001f)
        assertEquals(1f, NeoLogic.pulseAlpha(0.5f, false), 0f); assertTrue(NeoLogic.pulseAlpha(0f, true) in 0.65f..1f)
        assertEquals(0.5f, NeoLogic.alphaFor(false), 0f); assertFalse(NeoLogic.shadowsOn(false))
        assertTrue(NeoLogic.useFlat(true, 0)); assertTrue(NeoLogic.useFlat(false, 8)); assertFalse(NeoLogic.useFlat(false, 7))
        assertEquals(0f, NeoLogic.lightShadowAlpha(true), 0f)
    }
    @Test fun colorsXmlMatchesTokens() {
        val xml = java.io.File("src/main/res/values/colors.xml").takeIf { it.exists() } ?: return
        val txt = xml.readText()
        fun hex(n: String) = Regex("name=\"$n\">#([0-9A-Fa-f]{6})").find(txt)!!.groupValues[1].uppercase()
        assertEquals("%06X".format(Neo.BgStart and 0xFFFFFF), hex("neo_bg_start"))
        assertEquals("%06X".format(Neo.Text2OnLight and 0xFFFFFF), hex("neo_text2_on_light"))
        assertEquals("%06X".format(Neo.ActiveEnd and 0xFFFFFF), hex("neo_active_end"))
    }
    @Test fun softShadowSteps() {
        val steps = 5; val blur = 18f
        assertEquals(9f, NeoLogic.shadowSpread(0, steps, blur), 0.001f)
        assertTrue(NeoLogic.shadowSpread(steps - 1, steps, blur) > -9f)
        assertTrue((0 until steps).map { NeoLogic.shadowSpread(it, steps, blur) }.zipWithNext().all { (a, b) -> a > b })
        assertEquals(0.25f, NeoLogic.shadowStepAlpha(0.25f, steps) * steps, 0.001f)
        assertEquals(0f, NeoLogic.shadowSpread(0, 0, blur), 0f); assertEquals(0f, NeoLogic.shadowStepAlpha(0.25f, 0), 0f)
        assertEquals(0.4f, NeoLogic.ringGlowAlpha(1f), 0.001f); assertEquals(0f, NeoLogic.ringGlowAlpha(-1f), 0f)
    }
    @Test fun raisedBudget() {
        val b = RaisedBudget()
        repeat(NeoLogic.MaxRaisedPerScreen) { assertTrue(b.acquire(false)) }
        assertEquals(8, b.used); assertFalse(b.acquire(false)); assertEquals(8, b.used)
        b.release(); assertEquals(7, b.used); assertTrue(b.acquire(false))
        val c = RaisedBudget(); assertFalse(c.acquire(true)); assertEquals(0, c.used)
        c.release(); assertEquals(0, c.used)
    }
    @Test fun barAndKnobMaths() {
        assertEquals(50f, NeoLogic.fillWidth(0.5f, 100f), 0.001f); assertEquals(0f, NeoLogic.fillWidth(-1f, 100f), 0f); assertEquals(100f, NeoLogic.fillWidth(3f, 100f), 0f)
        val a = NeoLogic.barSegment(0f, 100f); assertEquals(0f, a.first, 0f); assertEquals(0f, a.second, 0.001f)
        val m = NeoLogic.barSegment(0.5f, 100f); assertTrue(m.first >= 0f && m.second <= 100f && m.second > m.first)
        val z = NeoLogic.barSegment(1f, 100f); assertEquals(100f, z.first, 0.001f); assertEquals(100f, z.second, 0f)
        assertEquals(4f, NeoLogic.knobX(0f), 0f); assertEquals(24f, NeoLogic.knobX(1f), 0.001f); assertEquals(24f, NeoLogic.knobX(9f), 0.001f)
        assertEquals(NeoDim.SwitchW, NeoLogic.knobX(1f) + NeoDim.SwitchKnob + 4f, 0.001f)
    }
    @Test fun badgeAndSegmentContrast() {
        val names = NeoPairs.all.map { it.name }
        assertTrue(names.any { it.endsWith("on badge (light)") }); assertTrue(names.any { it.endsWith("on badge (dark)") }); assertTrue(names.contains("TextOnDark on selected segment"))
        NeoPairs.all.filter { it.name.contains("badge") || it.name.contains("segment") || it.name.contains("error border") }.forEach { assertTrue(it.name + " " + it.worst(), it.worst() >= it.min) }
    }
    @Test fun navBarAndPillContrast() {
        val rows = NeoPairs.all.filter { it.name.contains("nav bar") || it.name.contains("next-run") }
        assertEquals(3, rows.size)
        rows.forEach { assertTrue(it.name + " " + it.worst(), it.worst() >= it.min) }
    }
    @Test fun ringColorPerSurface() {
        assertEquals(Neo.AccentOnLight, NeoLogic.ringColor(true)); assertEquals(Neo.RingStroke, NeoLogic.ringColor(false))
    }
    @Test fun smoothShadowAlphas() {
        for (steps in listOf(4, 6, 10)) {
            val a = NeoLogic.shadowStepAlphas(0.26f, steps)
            assertEquals(steps, a.size); assertTrue(a.all { it in 0f..1f })
            val cum = 1f - a.fold(1f) { acc, x -> acc * (1f - x) }          // darkness at the core
            assertEquals(0.26f, cum, 0.002f)
            assertTrue("outer edge feathers softer than a uniform step", a.first() < 0.26f / steps)
        }
        assertTrue(NeoLogic.shadowStepAlphas(0.2f, 0).isEmpty())
    }
    @Test fun glowAngle() {
        assertEquals(0f, NeoLogic.glowAngle(0.5f, false), 0f); assertEquals(180f, NeoLogic.glowAngle(0.5f, true), 0.01f); assertEquals(360f, NeoLogic.glowAngle(2f, true), 0.01f)
    }
    @Test fun glowAlphasCometAndFloor() {
        val a = NeoLogic.glowAlphas(0.5f, 36)
        assertEquals(37, a.size); assertEquals(a.first(), a.last(), 0f)
        assertEquals(1f, a[18], 0.001f)                 // head at half a turn
        assertTrue(a.all { it >= 0.15f - 1e-6f && it <= 1f })
        assertTrue("tail fades behind the head", a[17] < a[18] && a[10] < a[17])
        assertEquals(0.15f, a[19], 0.05f)               // just ahead of the head: steady floor only
        assertEquals(NeoLogic.glowAlphas(1f, 36), NeoLogic.glowAlphas(0f, 36))   // a full turn wraps
    }
}
