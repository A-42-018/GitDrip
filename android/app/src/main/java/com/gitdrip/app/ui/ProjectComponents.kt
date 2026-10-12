package com.gitdrip.app.ui

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** False when the system "Remove animations" setting (ANIMATOR_DURATION_SCALE = 0) is on. Read once per composition tree. */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val r = LocalContext.current.contentResolver
    return remember { DashboardLogic.animationsEnabled(Settings.Global.getFloat(r, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)) }
}

/**
 * Progress bar for a card (Neo inset track + blue fill). [label] is the TalkBack text; with [decorative] = true the bar is hidden from
 * accessibility because the surrounding card already reads the same information as text.
 */
@Composable
fun GlassProgress(fraction: Float, label: String, modifier: Modifier = Modifier, decorative: Boolean = false) {
    val f = fraction.coerceIn(0f, 1f)
    val sem = if (decorative) Modifier.clearAndSetSemantics { } else Modifier.semantics {
        contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
    }
    NeoProgress(f, modifier.then(sem))
}

/** Round active-blue "add" button (>= 56dp) with glow, press scale and neon focus ring. Label is read as "<label>, button". */
@Composable
fun GlassFab(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Box(
        modifier.size(56.dp).neoRing(focused, Radius.Pill).pressScale(src).neoGlow(true)
            .clip(CircleShape).background(Brush.linearGradient(listOf(Color(Neo.ActiveStart), Color(Neo.ActiveMid), Color(Neo.ActiveEnd))), CircleShape)
            .clickable(src, null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Add, label, tint = Color(Neo.OnActive)) }
}

/**
 * Segmented tabs: dark Inset bar. Each segment is a Role.Tab >= 48dp; the selected one gets a blue tint, the neon ring AND bold text (never colour-only).
 * Four in a row, or 2x2 when the font is large / the screen narrow, so labels never clip. Labels are white (the bar is always dark).
 */
@Composable
fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cols = ProjectLogic.tabColumns(LocalDensity.current.fontScale, LocalConfiguration.current.screenWidthDp)
    val animate = rememberAnimationsEnabled()
    Column(
        modifier.fillMaxWidth().neoInset(Radius.Card).padding(Space.Xs.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(Space.Xs.dp),
    ) {
        labels.withIndex().toList().chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.Xs.dp)) {
                row.forEach { (i, l) -> Segment(l, i == selected, animate, Modifier.weight(1f)) { onSelect(i) } }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, animate: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val spec = if (animate) tween<Color>(NeoMs.Press) else snap()
    val fill by animateColorAsState(if (selected) Color(Neo.ActiveStart).copy(alpha = 0.5f) else Color.Transparent, spec, label = "segFill")
    val shape = RoundedCornerShape((Radius.Card - Space.Xs).dp)
    Box(
        modifier.heightIn(min = Touch.Min.dp).neoRing(selected, Radius.Card - Space.Xs).clip(shape).background(fill, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick).padding(horizontal = Space.S.dp, vertical = Space.S.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, color = Color(Neo.TextOnDark), textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
        )
    }
}
