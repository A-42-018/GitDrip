package com.gitdrip.app.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/*
 * Neo-Tactile primitives (NT2). Drawing only: no state, no ViewModel, no strings, no semantics (the NT3 components add those).
 *   NeoBackdrop  diagonal gradient + 2 orbs, drawn once at the root
 *   neoGlass     frosted panel (frost tint + fill + rim + bottom glow); solid 92% fallback
 *   neoRaised    light raised surface: highlight + dual stepped shadow, press sink, focus lift, flat style in lists
 *   neoInset     sunken well (inner shadow)
 *   neoRing      neon focus / selection ring (stroke + glow + 1.2 s pulse)
 * Shadows are stacked rounded rects (NeoLogic.shadowSteps), not Paint.setShadowLayer: that only draws shapes on API 28+ and minSdk is 26.
 */

private fun c(v: Long) = Color(v)

/** Full-style raised elements on the current screen (max [NeoLogic.MaxRaisedPerScreen]); [NeoBackdrop] provides a fresh one per screen. */
val LocalRaisedCount = staticCompositionLocalOf { RaisedBudget() }

/** True below a list / card: raised surfaces there use the flat style and take no slot of the budget. */
val LocalNeoInList = compositionLocalOf { false }

// ---------------------------------------------------------------------------------------------------------------------------------- drawing helpers

private fun DrawScope.shadowRect(color: Color, topLeft: Offset, size: Size, radius: Float, spread: Float) {
    val w = (size.width + 2f * spread).coerceAtLeast(0f)
    val h = (size.height + 2f * spread).coerceAtLeast(0f)
    drawRoundRect(color, Offset(topLeft.x - spread, topLeft.y - spread), Size(w, h), CornerRadius(minOf(radius + spread, w / 2f, h / 2f).coerceAtLeast(0f)))
}

private fun DrawScope.softShadow(color: Color, total: Float, blur: Float, dx: Float, dy: Float, steps: Int, radius: Float) {
    val alphas = NeoLogic.shadowStepAlphas(total, steps)   // smoothstep feather: no visible bands
    for (i in 0 until steps) shadowRect(color.copy(alpha = alphas[i]), Offset(dx, dy), size, radius, NeoLogic.shadowSpread(i, steps, blur))
}

/** Stroke of [width] drawn fully inside the bounds. */
private fun DrawScope.insideStroke(brush: Brush, width: Float, radius: Float) {
    val half = width / 2f
    drawRoundRect(brush, Offset(half, half), Size((size.width - width).coerceAtLeast(0f), (size.height - width).coerceAtLeast(0f)), CornerRadius((radius - half).coerceAtLeast(0f)), style = Stroke(width))
}

// ---------------------------------------------------------------------------------------------------------------------------------- backdrop

/** App background: diagonal slate gradient with a blue and a cyan orb. Drawn once per screen root, never per card. */
@Composable
fun NeoBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val budget = remember { RaisedBudget() }
    CompositionLocalProvider(LocalRaisedCount provides budget) {
        Box(
            modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.linearGradient(
                        0f to c(Neo.BgStart), NeoDim.BgMidStop to c(Neo.BgMid), 1f to c(Neo.BgEnd),
                        start = Offset.Zero, end = Offset(size.width, size.height),
                    ),
                )
                fun orb(col: Long, a: Float, cx: Float, cy: Float, r: Float) {
                    val center = Offset(cx * size.width, cy * size.height)
                    drawCircle(Brush.radialGradient(listOf(c(col).copy(alpha = a), Color.Transparent), center, r * size.width), radius = r * size.width, center = center)
                }
                orb(Neo.OrbBlue, NeoAlpha.OrbBlue, 0.15f, 0.10f, 0.75f)
                orb(Neo.OrbCyan, NeoAlpha.OrbCyan, 0.95f, 0.45f, 0.65f)
            },
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------- glass

/**
 * Frosted panel. [level] 1..3 picks the fill alpha (55/60/65%). Below API 31 or with "Solid surfaces" it is an opaque 92% panel without the frost tint.
 * Content colours: white / secondary-on-dark tokens (see NeoPairs). Real blur is NOT applied here: only dialogs blur, via [NeoDialogBlur].
 */
@Composable
fun Modifier.neoGlass(level: Int = 2, corner: Int = Radius.Panel): Modifier {
    val solid = NeoLogic.solidPanels(LocalSolidSurfaces.current, Build.VERSION.SDK_INT)
    val fillA = NeoLogic.panelAlpha(level, solid)
    val shadeA = NeoLogic.shadeAlpha(solid)
    return this.clip(RoundedCornerShape(corner.dp)).drawBehind {
        if (shadeA > 0f) drawRect(c(Neo.GlassShade).copy(alpha = shadeA))
        drawRect(c(Neo.GlassFill).copy(alpha = fillA))
        val r = minOf(corner.dp.toPx(), size.minDimension / 2f)
        val w = NeoDim.RimWidth.dp.toPx()
        val end = Offset(size.width, size.height)
        insideStroke(Brush.linearGradient(0f to c(Neo.GlassRim), 0.5f to c(Neo.GlassRim).copy(alpha = 0.35f), 1f to Color.Transparent, start = Offset.Zero, end = end), w, r)
        insideStroke(Brush.linearGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to c(Neo.GlassGlow).copy(alpha = NeoAlpha.GlassGlow), start = Offset.Zero, end = end), w, r)
    }
}

@Composable
fun GlassPanel(modifier: Modifier = Modifier, level: Int = 2, corner: Int = Radius.Panel, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.neoGlass(level, corner), content = content)

/**
 * Real backdrop blur behind a dialog window (API 31+, never with "Solid surfaces"). Call once inside the dialog content.
 * No effect on lists, below API 31, or on devices where the system disables window blur.
 */
@Composable
fun NeoDialogBlur(radiusDp: Int = 24) {
    val sdk = Build.VERSION.SDK_INT
    val solid = LocalSolidSurfaces.current
    val enabled = NeoLogic.useRealBlur(sdk, solid, inList = false)
    val view = LocalView.current
    val px = with(LocalDensity.current) { radiusDp.dp.roundToPx() }
    DisposableEffect(view, enabled, px) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (enabled && window != null && sdk >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { it.blurBehindRadius = px }
        }
        onDispose {
            if (enabled && window != null && sdk >= Build.VERSION_CODES.S) window.attributes = window.attributes.also { it.blurBehindRadius = 0 }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------- raised

/**
 * Whether the calling raised element must use the flat style. Takes one slot of the screen budget while it is composed and gives it back on leaving.
 * Use once per raised element (NT3 components call it, then pass the result to [neoRaised]).
 */
@Composable
fun rememberRaisedFlat(inList: Boolean = LocalNeoInList.current): Boolean {
    val budget = LocalRaisedCount.current
    val full = remember(budget, inList) { budget.acquire(inList) }
    DisposableEffect(budget, full) { onDispose { if (full) budget.release() } }
    return !full
}

/**
 * Light raised surface. Layers: dual soft shadow (dark bottom-right + light top-left; one soft shadow when [flat]), body colour, 70% white highlight on the
 * top-left edge. [source] drives the press sink (120 ms: shadows shrink, 1dp down, 6% darker) and the focus lift (160 ms: 1dp up).
 * Disabled = 50% alpha and no shadow. All motion is instant when system animations are off.
 */
@Composable
fun Modifier.neoRaised(
    flat: Boolean,
    corner: Int = Radius.Card,
    color: Long = Neo.RaisedCard,
    enabled: Boolean = true,
    source: InteractionSource? = null,
): Modifier {
    val animate = LocalAnimations.current
    val src = source ?: remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val focused by src.collectIsFocusedAsState()
    val sink = animateFloatAsState(if (pressed && enabled) 1f else 0f, tween(NeoLogic.ms(NeoMs.Press, animate)), label = "neoSink")
    val lift = animateFloatAsState(if (focused && enabled && !pressed) 1f else 0f, tween(NeoLogic.ms(NeoMs.Lift, animate)), label = "neoLift")
    val shape = RoundedCornerShape(corner.dp)
    val body = c(color)
    val shadows = NeoLogic.shadowsOn(enabled)
    val dim = NeoLogic.alphaFor(enabled)
    return this
        .graphicsLayer {
            alpha = dim
            translationY = NeoLogic.translateDp(sink.value, lift.value) * density
        }
        .drawBehind {
            if (shadows) {
                val s = sink.value
                val r = minOf(corner.dp.toPx(), size.minDimension / 2f)
                val steps = NeoLogic.shadowSteps(flat)
                val blur = NeoLogic.sunk(NeoLogic.darkShadowBlur(flat), s).dp.toPx()
                val dy = NeoLogic.sunk(NeoLogic.darkShadowDy(flat), s).dp.toPx()
                val dx = if (flat) 0f else NeoLogic.sunk(NeoDim.ShadowDx, s).dp.toPx()
                val lightA = NeoLogic.lightShadowAlpha(flat)
                if (lightA > 0f) softShadow(c(Neo.ShadowLight), lightA, NeoLogic.sunk(NeoDim.LightBlur, s).dp.toPx(), NeoDim.LightDx.dp.toPx(), NeoDim.LightDy.dp.toPx(), steps, r)
                softShadow(c(Neo.ShadowDark), NeoAlpha.ShadowDark, blur, dx, dy, steps, r)
                softShadow(c(Neo.ShadowDark), NeoAlpha.Ambient, NeoLogic.sunk(NeoDim.AmbientBlur, s).dp.toPx(), 0f, NeoDim.AmbientDy.dp.toPx(), NeoDim.AmbientSteps, r)   // tight contact shadow
            }
        }
        .clip(shape)
        .background(body, shape)
        .drawBehind {
            val r = minOf(corner.dp.toPx(), size.minDimension / 2f)
            val end = Offset(size.width, size.height)
            insideStroke(
                Brush.linearGradient(0f to c(Neo.Highlight).copy(alpha = NeoAlpha.Highlight), 0.45f to Color.Transparent, start = Offset.Zero, end = end),
                NeoDim.RimWidth.dp.toPx(), r,
            )
            val dark = NeoAlpha.PressedDarken * sink.value
            if (dark > 0f) drawRect(Color.Black.copy(alpha = dark))
        }
}

/** [neoRaised] as a box that manages its own slot of the per-screen budget. Content colours: use [NeoRaisedTheme] (dark text on light). */
@Composable
fun Raised(
    modifier: Modifier = Modifier,
    corner: Int = Radius.Card,
    color: Long = Neo.RaisedCard,
    enabled: Boolean = true,
    inList: Boolean = LocalNeoInList.current,
    source: InteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val flat = rememberRaisedFlat(inList)
    Box(modifier.neoRaised(flat, corner, color, enabled, source), content = content)
}

// ---------------------------------------------------------------------------------------------------------------------------------- inset

/** Sunken well: dark inner edge top-left, faint light edge bottom-right. Content colours: white / secondary-on-dark (see NeoPairs "on inset well"). */
@Composable
fun Modifier.neoInset(corner: Int = Radius.Control): Modifier {
    val shape = RoundedCornerShape(corner.dp)
    return this.clip(shape).background(c(Neo.InsetFill), shape).drawBehind {
        val r = minOf(corner.dp.toPx(), size.minDimension / 2f)
        insideStroke(
            Brush.linearGradient(
                0f to c(Neo.ShadowDark).copy(alpha = 0.55f), 0.40f to Color.Transparent, 0.65f to Color.Transparent, 1f to c(Neo.ShadowLight).copy(alpha = 0.10f),
                start = Offset.Zero, end = Offset(size.width, size.height),
            ),
            3.dp.toPx(), r,
        )
    }
}

@Composable
fun Inset(modifier: Modifier = Modifier, corner: Int = Radius.Control, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.neoInset(corner), content = content)

// ---------------------------------------------------------------------------------------------------------------------------------- ring

/**
 * Neon ring: 2dp stroke + 12dp glow (40%), pulsing 1.2 s between 65% and 100%; a steady ring when animations are off. Draws nothing unless [visible].
 * The glow extends outside the bounds: keep a parent that clips (lists, scroll areas) at least 12dp away or accept the cut.
 * Used for focus everywhere and for the selected tab / chip.
 */
@Composable
fun Modifier.neoRing(visible: Boolean, corner: Int = Radius.Pill, onLight: Boolean = false): Modifier {
    val animate = LocalAnimations.current
    val pulse: State<Float> =
        if (animate && visible) rememberInfiniteTransition(label = "neoRing").animateFloat(0f, 1f, infiniteRepeatable(tween(NeoMs.Pulse, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
        else remember { mutableStateOf(0f) }
    if (!visible) return this
    val stroke = NeoLogic.ringColor(onLight)
    return this.drawWithContent {
        drawContent()
        val a = NeoLogic.pulseAlpha(pulse.value, animate)
        val r = minOf(corner.dp.toPx(), size.minDimension / 2f)
        val glow = NeoDim.RingGlowBlur.dp.toPx()
        val ring = NeoDim.RingWidth.dp.toPx()
        val steps = 4
        for (i in 0 until steps) {
            val grow = glow * (steps - i) / steps
            drawRoundRect(
                c(Neo.RingGlow).copy(alpha = NeoLogic.ringGlowAlpha(a) / steps),
                Offset(-grow / 2f, -grow / 2f), Size(size.width + grow, size.height + grow), CornerRadius(r + grow / 2f), style = Stroke(ring + grow),
            )
        }
        insideStroke(Brush.linearGradient(listOf(c(stroke).copy(alpha = a), c(stroke).copy(alpha = a))), ring, r)
    }
}

/**
 * Running glow ring (the cyan comet of the Neo-Tactile reference): a faint steady ring with a bright head that circles the element once per
 * [NeoDim.GlowSpinMs], plus a soft halo. Steady full ring when system animations are off. Draws nothing unless [active]; the halo extends
 * [NeoDim.RingGlowBlur] dp outside the bounds, so keep clipping parents away. Never replaces the state text / icon (colour is not the only cue).
 */
@Composable
fun Modifier.neoGlowRing(active: Boolean, corner: Int = Radius.Pill, onLight: Boolean = false): Modifier {
    val animate = LocalAnimations.current
    val turn: State<Float> =
        if (animate && active) rememberInfiniteTransition(label = "neoGlowRing").animateFloat(0f, 1f, infiniteRepeatable(tween(NeoDim.GlowSpinMs, easing = LinearEasing), RepeatMode.Restart), label = "turn")
        else remember { mutableStateOf(0f) }
    if (!active) return this
    val stroke = c(NeoLogic.ringColor(onLight)); val halo = c(Neo.RingGlow)
    return this.drawWithContent {
        drawContent()
        val r = minOf(corner.dp.toPx(), size.minDimension / 2f)
        val ring = NeoDim.RingWidth.dp.toPx(); val glow = NeoDim.RingGlowBlur.dp.toPx()
        val centre = Offset(size.width / 2f, size.height / 2f)
        val alphas = NeoLogic.glowAlphas(turn.value)
        fun sweep(col: Color, scale: Float): Brush =
            if (!animate) SolidColor(col.copy(alpha = 0.9f * scale))
            else Brush.sweepGradient(*alphas.mapIndexed { k, a -> (k.toFloat() / (alphas.size - 1)) to col.copy(alpha = a * scale) }.toTypedArray(), center = centre)
        val steps = 3
        for (i in 0 until steps) {
            val grow = glow * (steps - i) / steps
            drawRoundRect(
                sweep(halo, NeoAlpha.RingGlow / steps * 1.6f), Offset(-grow / 2f, -grow / 2f), Size(size.width + grow, size.height + grow),
                CornerRadius(r + grow / 2f), style = Stroke(ring + grow),
            )
        }
        insideStroke(sweep(stroke, 1f), ring, r)
    }
}
