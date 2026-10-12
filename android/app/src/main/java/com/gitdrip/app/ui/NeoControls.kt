package com.gitdrip.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * Neo-Tactile controls (NT3), drawing only: no strings, no click handling. Callers (GlassSwitchRow / LabeledSwitch / GlassProgress / GlassSpinner)
 * add the semantics, the touch target and the labels. All motion is instant / static when system animations are off ([LocalAnimations]).
 */

private fun c(v: Long) = Color(v)

/** 52x32 toggle: inset well that turns blue when on, #D9DDE8 knob with a 2dp light rim. [focused] draws the neon ring (keep >= 12dp free around it). */
@Composable
fun NeoToggle(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, focused: Boolean = false) {
    val animate = LocalAnimations.current
    val t by animateFloatAsState(if (checked) 1f else 0f, tween(NeoLogic.ms(NeoMs.Press, animate)), label = "neoToggle")
    Box(
        modifier.size(NeoDim.SwitchW.dp, NeoDim.SwitchH.dp)
            .graphicsLayer { alpha = NeoLogic.alphaFor(enabled) }
            .neoRing(focused && enabled, Radius.Pill)
            .clip(CircleShape)
            .background(lerp(c(Neo.InsetFill), c(Neo.ToggleTrack), t), CircleShape)
            .drawBehind {
                val w = NeoDim.SwitchRim.dp.toPx() * 2f
                drawRoundRect(
                    c(Neo.ShadowDark).copy(alpha = 0.45f * (1f - t) + 0.20f * t), Offset(w / 2f, w / 2f),
                    Size((size.width - w).coerceAtLeast(0f), (size.height - w).coerceAtLeast(0f)), CornerRadius(size.height / 2f), style = Stroke(w),
                )
            },
    ) {
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset { IntOffset((NeoLogic.knobX(t) * density).roundToInt(), 0) }
                .size(NeoDim.SwitchKnob.dp)
                .drawBehind { drawCircle(c(Neo.ShadowDark).copy(alpha = 0.30f), radius = size.minDimension / 2f + 1.dp.toPx(), center = center + Offset(0f, 2.dp.toPx())) }
                .clip(CircleShape)
                .background(c(Neo.ToggleKnob), CircleShape)
                .border(NeoDim.SwitchKnobRim.dp, c(Neo.ToggleKnobRim), CircleShape),
        )
    }
}

/** Loading arc: 270 degree cyan arc turning once per 900 ms (a static arc when animations are off). Decorative: the caller names the busy state. */
@Composable
fun NeoSpinner(modifier: Modifier = Modifier, diameter: Dp = 24.dp) {
    val animate = LocalAnimations.current
    val turn = if (animate) {
        val inf = rememberInfiniteTransition(label = "neoSpin")
        val v by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(NeoMs.Spin, easing = LinearEasing), RepeatMode.Restart), label = "spin")
        v
    } else 0f
    val start = NeoLogic.spinnerAngle(turn, animate) - 90f
    Canvas(modifier.size(diameter)) {
        val w = 3.dp.toPx()
        val tl = Offset(w / 2f, w / 2f)
        val sz = Size((size.width - w).coerceAtLeast(0f), (size.height - w).coerceAtLeast(0f))
        drawArc(c(Neo.RingStroke).copy(alpha = 0.22f), 0f, 360f, false, tl, sz, style = Stroke(w))
        drawArc(c(Neo.RingStroke), start, 270f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Inset track with a blue fill: determinate when [fraction] is 0..1, a sweeping segment when null. Decorative: [GlassProgress] adds the semantics. */
@Composable
fun NeoProgress(fraction: Float?, modifier: Modifier = Modifier) {
    val animate = LocalAnimations.current
    val pos = if (fraction == null && animate) {
        val inf = rememberInfiniteTransition(label = "neoBar")
        val v by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(NeoMs.Spin * 2, easing = LinearEasing), RepeatMode.Restart), label = "barPos")
        v
    } else 0.5f
    Inset(modifier.fillMaxWidth().height(10.dp), corner = 5) {
        Box(
            Modifier.fillMaxSize().padding(2.dp).drawBehind {
                val (x0, x1) = if (fraction == null) NeoLogic.barSegment(pos, size.width) else 0f to NeoLogic.fillWidth(fraction, size.width)
                if (x1 > x0) drawRoundRect(
                    Brush.horizontalGradient(listOf(c(Neo.FillStart), c(Neo.FillEnd)), startX = x0, endX = x1.coerceAtLeast(x0 + 1f)),
                    Offset(x0, 0f), Size(x1 - x0, size.height), CornerRadius(size.height / 2f),
                )
            },
        )
    }
}

/** Soft blue glow under an active control (4 stacked rounded rects, 4dp down). Draws nothing when [enabled] is false. */
fun Modifier.neoGlow(enabled: Boolean = true, corner: Int = Radius.Pill, color: Long = Neo.ActiveGlow): Modifier =
    if (!enabled) this else drawBehind {
        val steps = 4
        val r = minOf(corner.dp.toPx(), size.minDimension / 2f)
        val blur = NeoDim.GlowBlur.dp.toPx()
        val dy = 4.dp.toPx()
        val alphas = NeoLogic.shadowStepAlphas(NeoAlpha.ActiveGlow, steps)
        for (i in 0 until steps) {
            val grow = blur * (steps - i) / steps
            drawRoundRect(c(color).copy(alpha = alphas[i]), Offset(-grow, -grow + dy), Size(size.width + 2f * grow, size.height + 2f * grow), CornerRadius(r + grow))
        }
    }
