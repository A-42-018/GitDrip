package com.gitdrip.app.ui

/** Pure Neo-Tactile rules (no Compose/Android imports): fallbacks, motion maths, shadow maths and the contrast registry. */
object NeoLogic {
    /** Real blur (window blur behind dialogs) exists from Android 12 (API 31). */
    const val BlurApi = 31
    /** Soft limit of dual-shadow raised elements per screen; anything beyond it, and everything in a list, uses the flat style. */
    const val MaxRaisedPerScreen = 8

    /** Panels fall back to a solid #293545 at 92% below API 31 or when the user turned "Solid surfaces" on. */
    fun solidPanels(setting: Boolean, sdk: Int): Boolean = setting || sdk < BlurApi

    fun useRealBlur(sdk: Int, solid: Boolean, inList: Boolean): Boolean = sdk >= BlurApi && !solid && !inList

    /** Glass fill alpha for level 1..3 (anything else = 3); 92% when [solid]. */
    fun panelAlpha(level: Int, solid: Boolean): Float =
        if (solid) NeoAlpha.PanelSolid else when (level) { 1 -> NeoAlpha.GlassL1; 2 -> NeoAlpha.GlassL2; else -> NeoAlpha.GlassL3 }

    /** The frost tint under the fill is part of the translucent look only; the solid panel has none. */
    fun shadeAlpha(solid: Boolean): Float = if (solid) 0f else NeoAlpha.GlassShade

    /** 0 when animations are off (system "Remove animations"), otherwise [base]. */
    fun ms(base: Int, animate: Boolean): Int = if (animate) base else 0

    /** Shadow blur / offset while pressed: shrinks by up to half (the surface "sinks"). */
    fun sunk(base: Float, sink: Float): Float = base * (1f - 0.5f * sink.coerceIn(0f, 1f))

    /** Downward translation in dp: 1dp when fully pressed, up 1dp when lifted (focus / hover); pressed wins over lifted. */
    fun translateDp(sink: Float, lift: Float): Float {
        val s = sink.coerceIn(0f, 1f); val l = lift.coerceIn(0f, 1f) * (1f - s)
        return NeoDim.PressTranslate * s - NeoDim.LiftTranslate * l
    }

    /** Dual shadow (dark + light) for standalone controls, single soft shadow for the flat style. */
    fun lightShadowAlpha(flat: Boolean): Float = if (flat) 0f else NeoAlpha.ShadowLight
    fun darkShadowBlur(flat: Boolean): Float = if (flat) NeoDim.FlatBlur else NeoDim.ShadowBlur
    fun darkShadowDy(flat: Boolean): Float = if (flat) NeoDim.FlatDy else NeoDim.ShadowDy
    fun shadowSteps(flat: Boolean): Int = if (flat) NeoDim.FlatSteps.toInt() else NeoDim.ShadowSteps.toInt()

    /** Flat style when inside a list/card or when a screen already has too many raised elements. */
    fun useFlat(inList: Boolean, raisedOnScreen: Int): Boolean = inList || raisedOnScreen >= MaxRaisedPerScreen

    /** Disabled = 50% alpha and no shadow. */
    fun alphaFor(enabled: Boolean): Float = if (enabled) 1f else NeoAlpha.Disabled
    fun shadowsOn(enabled: Boolean): Boolean = enabled

    /** Neon ring pulse over one 1.2 s period: alpha between 0.65 and 1.0; fixed 1.0 when animations are off. */
    fun pulseAlpha(t: Float, animate: Boolean): Float =
        if (!animate) 1f else (0.65f + 0.35f * (0.5f - 0.5f * Math.cos(2.0 * Math.PI * t).toFloat())).coerceIn(0.65f, 1f)

    /** Horizontal knob travel of the switch in dp (track width - knob - 4dp padding on both sides). */
    fun knobTravelDp(): Float = NeoDim.SwitchW - NeoDim.SwitchKnob - 8f

    /** Soft shadow = [steps] stacked rounded rects (works on every API, no setShadowLayer): spread of rect [i], widest first (+blur/2) down to just above -blur/2. */
    fun shadowSpread(i: Int, steps: Int, blur: Float): Float = if (steps <= 0) 0f else blur * (0.5f - i.toFloat() / steps)

    /** Alpha of each stacked rect; the centre accumulates to about [total]. */
    fun shadowStepAlpha(total: Float, steps: Int): Float = if (steps <= 0) 0f else total / steps

    /**
     * Per-layer alphas for a soft shadow of [steps] stacked rects (widest first). The cumulative darkness follows a smoothstep from the faint outer
     * edge to [total] at the core, so the shadow feathers like a gaussian instead of the banded linear ramp of equal alphas.
     */
    fun shadowStepAlphas(total: Float, steps: Int): List<Float> {
        if (steps <= 0) return emptyList()
        var prev = 0f
        return (0 until steps).map { i ->
            val x = (i + 1f) / steps
            val cum = total * (x * x * (3f - 2f * x))      // smoothstep
            val a = if (prev >= 1f) 0f else (1f - (1f - cum) / (1f - prev)).coerceIn(0f, 1f)
            prev = cum; a
        }
    }

    /** Glow ring head angle in degrees for turn fraction [t]; a fixed head when animations are off (the ring is then drawn steady). */
    fun glowAngle(t: Float, animate: Boolean): Float = if (animate) (t.coerceIn(0f, 1f) * 360f) else 0f

    /**
     * Alphas around the glow ring (n + 1 samples, first == last so the sweep closes): a comet whose head sits at turn fraction [t] and whose tail
     * fades over [tail] of the circle, on top of a faint steady [floor]. Sample k is at angle k / n of a turn (clockwise from 3 o'clock).
     */
    fun glowAlphas(t: Float, n: Int = 36, tail: Float = 0.55f, floor: Float = 0.15f): List<Float> {
        val head = ((t % 1f) + 1f) % 1f
        fun at(k: Int): Float {
            val pos = (k % n).toFloat() / n
            val behind = ((head - pos) % 1f + 1f) % 1f          // distance behind the head, 0 = at the head
            val comet = if (behind < tail) Math.pow((1f - behind / tail).toDouble(), 1.6).toFloat() else 0f
            return maxOf(floor, comet)
        }
        return (0..n).map { at(it) }
    }

    /** Neon ring outer glow alpha follows the pulse. */
    fun ringGlowAlpha(pulse: Float): Float = NeoAlpha.RingGlow * pulse.coerceIn(0f, 1f)

    /** Focus / selection stroke colour: pale cyan vanishes on the light raised surfaces (1.0-1.2:1), so those get the blue accent (>= 4.5:1). */
    fun ringColor(onLight: Boolean): Long = if (onLight) Neo.AccentOnLight else Neo.RingStroke

    /** Fill width of a determinate bar (px); [fraction] is clamped to 0..1. */
    fun fillWidth(fraction: Float, width: Float): Float = width * fraction.coerceIn(0f, 1f)

    /** Indeterminate bar: the moving segment as [start, end] px, clipped to 0..[width]; sweeps from fully left of the bar to fully right of it as [t] goes 0..1. */
    fun barSegment(t: Float, width: Float, frac: Float = 0.35f): Pair<Float, Float> {
        val w = width * frac; val x = (width + w) * t.coerceIn(0f, 1f) - w
        return x.coerceAtLeast(0f) to (x + w).coerceAtMost(width)
    }

    /** Toggle knob offset from the left edge of the track in dp: 4dp padding, then [travel] as [t] goes 0 (off) .. 1 (on). */
    fun knobX(t: Float): Float = 4f + knobTravelDp() * t.coerceIn(0f, 1f)

    /** Spinner arc: sweep in degrees (a 270 degree arc, static start angle when animations are off). */
    fun spinnerAngle(t: Float, animate: Boolean): Float = if (animate) (t.coerceIn(0f, 1f) * 360f) else 0f
}

/** A text/icon colour with the real surfaces it can sit on and the WCAG minimum it must reach there (4.5 text, 3.0 non-text). */
class ContrastPair(val name: String, val fg: Long, val bgs: List<Long>, val min: Double) {
    fun worst(): Double = bgs.minOf { Contrast.ratio(fg, it) }
}

/** Real surface colours, worst cases included (lightest backdrop corner with and without each orb). */
object NeoSurfaces {
    private fun panel(alpha: Float, shade: Float, bg: Long) = Contrast.over(Neo.GlassFill, alpha, Contrast.over(Neo.GlassShade, shade, bg))
    private fun orb(o: Long, a: Float, bg: Long) = Contrast.over(o, a, bg)

    /** Backdrop colours a panel can sit on (lightest corner, under each orb, middle, darkest). */
    val backdrops: List<Long> = listOf(
        Neo.BgStart, orb(Neo.OrbBlue, NeoAlpha.OrbBlue, Neo.BgStart), orb(Neo.OrbCyan, NeoAlpha.OrbCyan, Neo.BgStart), Neo.BgMid, Neo.BgEnd,
    )
    /** Backdrop under text that is NOT inside a panel (must stay below the first panel, where the gradient has reached the middle stop). */
    val loose: List<Long> = listOf(Neo.BgMid, orb(Neo.OrbBlue, NeoAlpha.OrbBlue, Neo.BgMid), orb(Neo.OrbCyan, NeoAlpha.OrbCyan, Neo.BgMid), Neo.BgEnd)

    val glass: List<Long> = listOf(NeoAlpha.GlassL1, NeoAlpha.GlassL2, NeoAlpha.GlassL3).flatMap { a -> backdrops.map { panel(a, NeoAlpha.GlassShade, it) } }
    val solid: List<Long> = backdrops.map { panel(NeoAlpha.PanelSolid, 0f, it) }
    val raised: List<Long> = listOf(Neo.RaisedButton, Neo.RaisedCard, Neo.RaisedModal)
    val inset: List<Long> = listOf(Neo.InsetFill)
    val active: List<Long> = listOf(Neo.ActiveStart, Neo.ActiveMid, Neo.ActiveEnd)
    /** Status badge fills: white 55% on raised surfaces, black 25% on glass / solid panels. */
    val badgeOnLight: List<Long> = raised.map { Contrast.over(0xFFFFFFFFL, NeoAlpha.BadgeLight, it) }
    val badgeOnDark: List<Long> = (glass + solid).map { Contrast.over(0xFF000000L, NeoAlpha.BadgeDark, it) }
    /** Selected segment tint (ActiveStart 50%) on the inset bar. */
    val selectedSegment: List<Long> = listOf(Contrast.over(Neo.ActiveStart, 0.5f, Neo.InsetFill))
    /** Floating nav bar gradient stops (NT4). */
    val navBar: List<Long> = listOf(Neo.InsetFill, Neo.SurfHigh)
}

/** Every text / icon colour against every surface it really sits on. NeoLogicTest asserts all of them and that no text token is missing. */
object NeoPairs {
    private const val T = 4.5
    private const val N = 3.0
    private val onDarkText = listOf(
        "TextOnDark" to Neo.TextOnDark, "Text2OnDark" to Neo.Text2OnDark, "AccentOnDark" to Neo.AccentOnDark, "RingStroke(cyan text)" to Neo.RingStroke,
        "DangerOnDark" to Neo.DangerOnDark, "SuccessOnDark" to Neo.SuccessOnDark, "WarningOnDark" to Neo.WarningOnDark,
    )
    private val onLightText = listOf(
        "TextOnLight" to Neo.TextOnLight, "Text2OnLight" to Neo.Text2OnLight, "AccentOnLight" to Neo.AccentOnLight, "TealOnLight" to Neo.TealOnLight,
        "DangerOnLight" to Neo.DangerOnLight, "SuccessOnLight" to Neo.SuccessOnLight, "WarningOnLight" to Neo.WarningOnLight,
    )

    val all: List<ContrastPair> = buildList {
        onDarkText.forEach { (n, c) ->
            add(ContrastPair("$n on glass", c, NeoSurfaces.glass, T))
            add(ContrastPair("$n on solid panel", c, NeoSurfaces.solid, T))
            add(ContrastPair("$n on inset well", c, NeoSurfaces.inset, T))
        }
        onLightText.forEach { (n, c) -> add(ContrastPair("$n on raised surfaces", c, NeoSurfaces.raised, T)) }
        // Loose text (outside any panel): primary only, and only below the first panel
        add(ContrastPair("TextOnDark on loose backdrop", Neo.TextOnDark, NeoSurfaces.loose, T))
        add(ContrastPair("RingStroke on loose backdrop", Neo.RingStroke, NeoSurfaces.loose, T))
        // Active button and navigation
        NeoSurfaces.active.forEach { add(ContrastPair("OnActive label on gradient stop ${java.lang.Long.toHexString(it).takeLast(6)}", Neo.OnActive, listOf(it), T)) }
        add(ContrastPair("OnActive icon on active gradient", Neo.OnActive, NeoSurfaces.active, N))
        add(ContrastPair("Text2OnDark nav label on nav panel", Neo.Text2OnDark, NeoSurfaces.glass + NeoSurfaces.solid, T))
        // Non-text
        add(ContrastPair("GlassRim (switch rim, focus) on glass", Neo.GlassRim, NeoSurfaces.glass, N))
        add(ContrastPair("RingStroke (neon ring) on glass", Neo.RingStroke, NeoSurfaces.glass, N))
        add(ContrastPair("RingStroke (neon ring) on solid panel", Neo.RingStroke, NeoSurfaces.solid, N))
        add(ContrastPair("OutlineOnLight on raised surfaces", Neo.OutlineOnLight, NeoSurfaces.raised, N))
        add(ContrastPair("FillStart on inset well", Neo.FillStart, NeoSurfaces.inset, N))
        add(ContrastPair("FillEnd on inset well", Neo.FillEnd, NeoSurfaces.inset, N))
        add(ContrastPair("Track on inset well", Neo.Track, NeoSurfaces.inset, N))
        add(ContrastPair("ToggleKnob on blue track", Neo.ToggleKnob, listOf(Neo.ToggleTrack), N))
        add(ContrastPair("ToggleKnob on inset (off) track", Neo.ToggleKnob, NeoSurfaces.inset, N))
        add(ContrastPair("Raised button on glass", Neo.RaisedButton, NeoSurfaces.glass, N))
        // NT3: status badges, selected segment, toggle
        listOf("DangerOnLight" to Neo.DangerOnLight, "SuccessOnLight" to Neo.SuccessOnLight, "WarningOnLight" to Neo.WarningOnLight, "TealOnLight" to Neo.TealOnLight, "Text2OnLight" to Neo.Text2OnLight)
            .forEach { (n, c) -> add(ContrastPair("$n on badge (light)", c, NeoSurfaces.badgeOnLight, T)) }
        listOf("DangerOnDark" to Neo.DangerOnDark, "SuccessOnDark" to Neo.SuccessOnDark, "WarningOnDark" to Neo.WarningOnDark, "RingStroke" to Neo.RingStroke, "Text2OnDark" to Neo.Text2OnDark)
            .forEach { (n, c) -> add(ContrastPair("$n on badge (dark)", c, NeoSurfaces.badgeOnDark, T)) }
        add(ContrastPair("TextOnDark on selected segment", Neo.TextOnDark, NeoSurfaces.selectedSegment, T))
        add(ContrastPair("DangerOnDark (error border) on inset well", Neo.DangerOnDark, NeoSurfaces.inset, N))
        // NT4: floating nav bar, pill in a card, code panel
        add(ContrastPair("Text2OnDark idle tab label on nav bar", Neo.Text2OnDark, NeoSurfaces.navBar, T))
        add(ContrastPair("RingStroke (selected tab ring) on nav bar", Neo.RingStroke, NeoSurfaces.navBar, N))
        add(ContrastPair("AccentOnDark (next-run icon) on inset well", Neo.AccentOnDark, NeoSurfaces.inset, N))
        // NT5: focus ring colour per surface (non-text >= 3:1)
        add(ContrastPair("Focus ring (light surfaces) on raised surfaces", NeoLogic.ringColor(true), NeoSurfaces.raised, N))
        add(ContrastPair("Focus ring (dark surfaces) on inset well", NeoLogic.ringColor(false), NeoSurfaces.inset, N))
        add(ContrastPair("Focus ring (dark surfaces) on active gradient", NeoLogic.ringColor(false), NeoSurfaces.active, N))
    }

    /** Colour tokens that are text/icon colours (each must appear in at least one pair above). */
    val textTokens: Map<String, Long> = mapOf(
        "TextOnDark" to Neo.TextOnDark, "Text2OnDark" to Neo.Text2OnDark, "TextOnLight" to Neo.TextOnLight, "Text2OnLight" to Neo.Text2OnLight,
        "AccentOnDark" to Neo.AccentOnDark, "AccentOnLight" to Neo.AccentOnLight, "TealOnLight" to Neo.TealOnLight,
        "DangerOnLight" to Neo.DangerOnLight, "DangerOnDark" to Neo.DangerOnDark, "SuccessOnLight" to Neo.SuccessOnLight, "SuccessOnDark" to Neo.SuccessOnDark,
        "WarningOnLight" to Neo.WarningOnLight, "WarningOnDark" to Neo.WarningOnDark, "OnActive" to Neo.OnActive, "RingStroke" to Neo.RingStroke,
    )
}

/** Counts the full dual-shadow ("raised") elements on one screen; the 9th and later fall back to the flat style, list items never take a slot. */
class RaisedBudget {
    var used = 0
        private set

    /** True = full style (holds one slot until [release]); false = flat. */
    fun acquire(inList: Boolean): Boolean {
        if (NeoLogic.useFlat(inList, used)) return false
        used++
        return true
    }

    fun release() { if (used > 0) used-- }
}
