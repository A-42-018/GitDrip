package com.gitdrip.app.ui

import android.view.animation.OvershootInterpolator
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

class NavTab(val label: String, val icon: ImageVector)

private val BarHeight = 66.dp

/**
 * Floating bottom bar (Neo-Tactile): dark inset-to-glass bar, a round active-blue button with glow AND a steady neon ring on the selected tab
 * (slides with a small overshoot, the icon rises into it), idle tabs = icon + label. The button, glow and ring are drawn above the bar's top
 * edge (no clipping on the parent). Each tab is one >= 48dp TalkBack "tab" node ("Projects, tab, selected"). Colours: Neo tokens only.
 */
@Composable
fun FloatNavBar(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val animate = LocalAnimations.current
    val overshoot = remember { OvershootInterpolator(0.9f) }
    val pos by animateFloatAsState(
        selected.toFloat(), tween(if (animate) 280 else 0, easing = Easing { overshoot.getInterpolation(it) }), label = "navPos",
    )
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bgA = Color(Neo.InsetFill); val bgB = Color(Neo.SurfHigh)
    val circleA = Color(Neo.ActiveStart); val circleB = Color(Neo.ActiveEnd)
    val glow = Color(Neo.ActiveGlow).copy(alpha = NeoAlpha.ActiveGlow * 1.5f)
    val idle = Color(Neo.Text2OnDark); val iconOn = Color(Neo.OnActive)
    val line = Color(Neo.Highlight).copy(alpha = 0.14f)
    val ringA = Color(Neo.RingStroke); val ringGlow = Color(Neo.RingGlow).copy(alpha = NeoAlpha.RingGlow)

    Box(modifier.fillMaxWidth().height(BarHeight + inset).systemGestureExclusion()) { // edge tabs must not lose taps to the system back gesture
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            drawRect(Brush.horizontalGradient(listOf(bgA, bgB), startX = 0f, endX = w), size = Size(w, size.height))
            drawRect(line, size = Size(w, 1.dp.toPx()))
            val slot = w / tabs.size
            val r = 28.dp.toPx()
            val c = Offset(slot * (pos + 0.5f), 4.dp.toPx()) // centre just under the top edge: most of the button rises above the bar
            drawCircle(Brush.radialGradient(0.45f to glow, 1f to glow.copy(alpha = 0f), center = c, radius = r * 1.9f), radius = r * 1.9f, center = c)
            drawCircle(Brush.linearGradient(listOf(circleA, circleB), start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r)), radius = r, center = c)
            // steady neon ring on the selected button (colour is never the only cue: the icon also rises into the circle)
            val ring = NeoDim.RingWidth.dp.toPx()
            drawCircle(ringGlow, radius = r + ring / 2f + 3.dp.toPx(), center = c, style = Stroke(6.dp.toPx()))
            drawCircle(ringA, radius = r - ring / 2f, center = c, style = Stroke(ring))
        }
        Row(Modifier.fillMaxWidth().height(BarHeight)) {
            tabs.forEachIndexed { i, t ->
                val near = NavBarLogic.near(pos, i)
                Box(
                    Modifier.weight(1f).fillMaxHeight().selectable(
                        selected = i == selected, onClick = { onSelect(i) }, role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ),
                ) {
                    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { (14.dp.toPx() + (4.dp.toPx() - 12.dp.toPx() - 14.dp.toPx()) * near).roundToInt() }
                    Icon(
                        t.icon, null, tint = if (near > 0.5f) iconOn else idle,
                        modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, rise) }.size(24.dp),
                    )
                    Text(
                        t.label, color = idle, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).alpha(1f - near),
                    )
                }
            }
        }
    }
}
