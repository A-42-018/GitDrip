package com.gitdrip.app.ui

/**
 * Neo-Tactile design tokens. Pure Kotlin (no Compose imports) so palette/contrast logic stays JVM-testable.
 * Colours are 0xAARRGGBB and mirror res/values/colors.xml one-to-one (NeoLogicTest fails if they drift).
 * Screens never use hex literals: they go through Neo* tokens, MaterialTheme (mapped in Theme.kt) or the components in this package.
 */
object Neo {
    /** Backdrop gradient, top-left */
    const val BgStart = 0xFFA0B1C3
    /** Backdrop gradient, middle */
    const val BgMid = 0xFF414E5E
    /** Backdrop gradient, bottom-right (also window background) */
    const val BgEnd = 0xFF10131A
    /** Backdrop orb, blue (alpha 18%) */
    const val OrbBlue = 0xFF467EF7
    /** Backdrop orb, cyan (alpha 12%) */
    const val OrbCyan = 0xFF73F1F0
    /** Glass panel fill (alpha 55-65%, solid fallback 92%) */
    const val GlassFill = 0xFF293545
    /** Frost tint under the glass fill (alpha 40%): keeps text >= 4.5:1 over the light top-left of the backdrop */
    const val GlassShade = 0xFF10131A
    /** Glass rim, top-left */
    const val GlassRim = 0xFFAABFD4
    /** Glass rim glow, bottom/right (alpha 35%) */
    const val GlassGlow = 0xFF73F1F0
    /** Raised surface: button */
    const val RaisedButton = 0xFFE7EEF6
    /** Raised surface: card */
    const val RaisedCard = 0xFFDFE6F0
    /** Raised surface: modal / dialog */
    const val RaisedModal = 0xFFD7DEE8
    /** Inner highlight on the top-left edge (alpha 70%) */
    const val Highlight = 0xFFFFFFFF
    /** Raised shadow, dark side (alpha 25%) */
    const val ShadowDark = 0xFF000000
    /** Raised shadow, light side (alpha 40%) */
    const val ShadowLight = 0xFFFFFFFF
    /** Active button gradient start (darker than the 6B9BFF spec so a white label stays >= 4.5:1) */
    const val ActiveStart = 0xFF3A6BEF
    /** Active gradient middle */
    const val ActiveMid = 0xFF3466E8
    /** Active gradient end */
    const val ActiveEnd = 0xFF2B58D6
    /** Blue glow under active controls (alpha 40%) */
    const val ActiveGlow = 0xFF467EF7
    /** Label / icon on the active gradient */
    const val OnActive = 0xFFFFFFFF
    /** Inset well fill */
    const val InsetFill = 0xFF28303B
    /** Slider / progress track */
    const val Track = 0xFF6E7F91
    /** Blue fill start (4170DA spec is 2.9:1 on the well, this is 3.4:1) */
    const val FillStart = 0xFF4A7BE8
    /** Blue fill end */
    const val FillEnd = 0xFF4E97FD
    /** Neon ring stroke (2dp) and cyan text on dark */
    const val RingStroke = 0xFF9EFCFF
    /** Neon ring outer glow (alpha 40%, 12dp) */
    const val RingGlow = 0xFF73F1F0
    /** Switch track, on */
    const val ToggleTrack = 0xFF3F75F3
    /** Switch knob */
    const val ToggleKnob = 0xFFD9DDE8
    /** Switch knob rim (2dp, lighter) */
    const val ToggleKnobRim = 0xFFF2F4FA
    /** Primary text on dark surfaces */
    const val TextOnDark = 0xFFFFFFFF
    /** Secondary text on dark surfaces */
    const val Text2OnDark = 0xFFC3CCDA
    /** Primary text on light surfaces */
    const val TextOnLight = 0xFF0B1220
    /** Secondary text on light surfaces (5B6475 spec is 4.40:1 on the modal, this is 4.83:1) */
    const val Text2OnLight = 0xFF545E6F
    /** Links / text buttons on dark surfaces */
    const val AccentOnDark = 0xFFBFD3FF
    /** Links / text buttons on light surfaces */
    const val AccentOnLight = 0xFF2756C8
    /** Running state text on light surfaces */
    const val TealOnLight = 0xFF0B6A82
    /** Danger accent (fills) */
    const val Danger = 0xFFF87171
    /** Danger text on light surfaces */
    const val DangerOnLight = 0xFFB91C1C
    /** Danger text on dark surfaces */
    const val DangerOnDark = 0xFFFECACA
    /** Success accent (fills) */
    const val Success = 0xFF34D399
    /** Success text on light surfaces (047857 spec is 4.05:1 on the modal) */
    const val SuccessOnLight = 0xFF066B4A
    /** Success text on dark surfaces */
    const val SuccessOnDark = 0xFF6EE7B7
    /** Warning accent (fills) */
    const val Warning = 0xFFFBBF24
    /** Warning text on light surfaces */
    const val WarningOnLight = 0xFF92400E
    /** Warning text on dark surfaces */
    const val WarningOnDark = 0xFFFCD34D
    /** Borders / dividers on light surfaces */
    const val OutlineOnLight = 0xFF6E7F91
    /** Opaque dark container, low */
    const val SurfLow = 0xFF1B2330
    /** Opaque dark container, mid */
    const val SurfMid = 0xFF222C3A
    /** Opaque dark container, high */
    const val SurfHigh = 0xFF293545
    /** Opaque dark container, highest */
    const val SurfHighest = 0xFF344258
}

/** Alphas (0..1) that go with the [Neo] colours. */
object NeoAlpha {
    const val OrbBlue = 0.18f
    const val OrbCyan = 0.12f
    const val GlassL1 = 0.55f
    const val GlassL2 = 0.60f
    const val GlassL3 = 0.65f
    const val GlassShade = 0.40f
    const val PanelSolid = 0.92f      // "solid surfaces" / API < 31 fallback panel
    const val GlassGlow = 0.35f
    const val Highlight = 0.70f
    const val ShadowDark = 0.26f      // key shadow (smoothstep falloff, see NeoLogic.shadowStepAlphas)
    const val ShadowLight = 0.16f     // top-left lift: kept faint so cards do not get a white halo
    const val Ambient = 0.16f         // tight contact shadow under every raised surface
    const val ActiveGlow = 0.40f
    const val RingGlow = 0.40f
    const val Disabled = 0.50f
    const val PressedDarken = 0.06f
    const val BadgeFill = 0.16f
    const val BadgeLight = 0.55f      // status badge fill (white) on light raised surfaces
    const val BadgeDark = 0.25f       // status badge fill (black) on dark / glass surfaces
}

/** Dimensions in dp. */
object NeoDim {
    const val ShadowDx = 4f
    const val ShadowDy = 6f
    const val ShadowBlur = 16f
    const val LightDx = -3f
    const val LightDy = -3f
    const val LightBlur = 10f
    const val AmbientDy = 1f
    const val AmbientBlur = 4f
    const val AmbientSteps = 4
    const val GlowSpinMs = 2400     // one turn of the running glow ring
    const val FlatDy = 2f           // flat raised style (lists, anything inside a card): one soft shadow
    const val FlatBlur = 6f
    const val GlowBlur = 14f        // blue glow under active controls
    const val RimWidth = 1.5f
    const val RingWidth = 2f
    const val RingGlowBlur = 12f
    const val PressTranslate = 1f
    const val LiftTranslate = 1f
    const val ShadowSteps = 10
    const val FlatSteps = 6
    const val SwitchW = 52f
    const val SwitchH = 32f
    const val SwitchKnob = 24f
    const val SwitchKnobRim = 2f
    const val SwitchRim = 1f
    const val BgMidStop = 0.40f     // where #414E5E sits on the diagonal (light zone stays small so loose text over the backdrop keeps contrast)
}

/** Corner radii in dp. Controls are pills (999) or 18dp, cards 24dp, panels and sheets 36dp. */
object Radius {
    const val Pill = 999
    const val Control = 18
    const val Card = 24
    const val Panel = 36
    const val Sheet = 36
}

/** Motion durations in ms (all ease-out). Every one is 0 when system animations are off, see [NeoLogic.ms]. */
object NeoMs {
    const val Press = 120
    const val Lift = 160
    const val Pulse = 1200
    const val Spin = 900
}

/** Minimum touch target in dp (WCAG / Material). Every interactive component uses it. */
object Touch { const val Min = 48 }

/** Spacing in dp (4dp grid). */
object Space {
    const val Xs = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val Xl = 24
    const val Screen = 16
}

/** WCAG relative luminance / contrast ratio for 0xAARRGGBB colors (alpha ignored). */
object Contrast {
    /** Source-over composite of [fg] at [alpha] onto opaque [bg] (0xAARRGGBB, result opaque). */
    fun over(fg: Long, alpha: Float, bg: Long): Long {
        fun mix(sh: Int) = Math.round(((fg shr sh) and 0xFFL) * alpha + ((bg shr sh) and 0xFFL) * (1 - alpha)).toLong()
        return 0xFF000000L or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
    private fun chan(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
    fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFFL).toInt(); val g = ((argb shr 8) and 0xFFL).toInt(); val b = (argb and 0xFFL).toInt()
        return 0.2126 * chan(r) + 0.7152 * chan(g) + 0.0722 * chan(b)
    }
    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
