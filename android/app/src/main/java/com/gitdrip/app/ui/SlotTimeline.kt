package com.gitdrip.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Auto Drip day timeline: a track from the window start to its end with one marker per slot, a "now" tick, and the next slot glowing.
 * Shapes differ per state (filled = done, hollow = planned, small = skipped, square = failed), so colour is never the only cue; the whole
 * thing is one TalkBack node reading [TimelineLogic.summary]. Sits on a dark inset well, so it uses the on-dark tokens.
 */
@Composable
fun SlotTimeline(
    slots: List<Pair<Long, String>>, windowStart: Long, windowEnd: Long, nowMs: Long, fmt: (Long) -> String, modifier: Modifier = Modifier,
) {
    val animate = LocalAnimations.current
    val markers = TimelineLogic.markers(slots, windowStart, windowEnd, nowMs)
    val nowFrac = TimelineLogic.nowFraction(nowMs, windowStart, windowEnd)
    val progress = if (nowMs > windowEnd) 1f else nowFrac ?: 0f
    val hasNext = markers.any { it.kind == MarkerKind.Next }
    val pulse: State<Float> =
        if (animate && hasNext) rememberInfiniteTransition(label = "timeline").animateFloat(0f, 1f, infiniteRepeatable(tween(NeoMs.Pulse, easing = LinearEasing), RepeatMode.Restart), label = "p")
        else remember { mutableStateOf(0f) }
    val summary = TimelineLogic.summary(slots, nowMs, fmt)
    Column(
        modifier.fillMaxWidth().neoInset(Radius.Control).padding(horizontal = Space.M.dp, vertical = Space.S.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Today's plan: $summary" },
        verticalArrangement = Arrangement.spacedBy(Space.Xs.dp),
    ) {
        Text(summary, style = MaterialTheme.typography.labelMedium, color = Color(Neo.Text2OnDark))
        Canvas(Modifier.fillMaxWidth().height(34.dp)) {
            val padX = 8.dp.toPx(); val w = size.width - 2 * padX; val y = size.height / 2f
            val track = 3.dp.toPx()
            drawLine(Color(Neo.Track), Offset(padX, y), Offset(padX + w, y), track, StrokeCap.Round)
            if (progress > 0f) drawLine(
                Brush.horizontalGradient(listOf(Color(Neo.FillStart), Color(Neo.FillEnd)), startX = padX, endX = padX + w),
                Offset(padX, y), Offset(padX + w * progress, y), track, StrokeCap.Round,
            )
            nowFrac?.let { f ->
                val x = padX + w * f
                drawLine(Color(Neo.AccentOnDark).copy(alpha = 0.7f), Offset(x, y - 12.dp.toPx()), Offset(x, y + 12.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
            }
            markers.forEach { m ->
                val c = Offset(padX + w * m.fraction, y)
                when (m.kind) {
                    MarkerKind.Done -> drawCircle(Color(Neo.SuccessOnDark), 6.dp.toPx(), c)
                    MarkerKind.Sent -> { drawCircle(Color(Neo.AccentOnDark), 6.dp.toPx(), c); drawCircle(Color(Neo.InsetFill), 2.5.dp.toPx(), c) }
                    MarkerKind.Planned -> { drawCircle(Color(Neo.InsetFill), 6.dp.toPx(), c); drawCircle(Color(Neo.Text2OnDark), 6.dp.toPx(), c, style = Stroke(2.dp.toPx())) }
                    MarkerKind.Skipped -> drawCircle(Color(Neo.Text2OnDark).copy(alpha = 0.6f), 3.5.dp.toPx(), c)
                    MarkerKind.Failed -> { val s = 10.dp.toPx(); drawRoundRect(Color(Neo.DangerOnDark), Offset(c.x - s / 2, c.y - s / 2), Size(s, s), CornerRadius(2.dp.toPx())) }
                    MarkerKind.Next -> {
                        val a = NeoLogic.pulseAlpha(pulse.value, animate)
                        for (i in 3 downTo 1) drawCircle(Color(Neo.RingGlow).copy(alpha = NeoAlpha.RingGlow * a / 3f), (7 + 3 * i).dp.toPx(), c)   // soft halo
                        drawCircle(Color(Neo.RingStroke).copy(alpha = a), 7.dp.toPx(), c)
                        drawCircle(Color(Neo.InsetFill), 2.5.dp.toPx(), c)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(windowStart), style = MaterialTheme.typography.labelSmall, color = Color(Neo.Text2OnDark))
            Text(fmt(windowEnd), style = MaterialTheme.typography.labelSmall, color = Color(Neo.Text2OnDark))
        }
    }
}
