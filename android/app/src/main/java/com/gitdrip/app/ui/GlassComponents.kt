@file:OptIn(ExperimentalMaterial3Api::class)

package com.gitdrip.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gitdrip.app.LabeledSwitch
import com.gitdrip.app.asHeading

/*
 * Neo-Tactile component kit (NT3). Same names and signatures as the glass kit, new look:
 *   cards / secondary buttons / icon buttons = light Raised surfaces (content inside is dark-on-light via NeoRaisedTheme)
 *   text fields / chips / progress           = dark Inset wells (always white text)
 *   primary button                           = active blue gradient pill + glow
 *   top bar                                  = glass panel (white text stays readable over the light top-left backdrop)
 * Everything inside a card is flat (LocalNeoInList), so a screen never exceeds the 8-slot raised budget by its cards.
 */

private val MinTouch = Touch.Min.dp
private val PillShape = RoundedCornerShape(50)

/** Root of every screen: Neo backdrop + transparent Scaffold (system-bar insets handled by Scaffold). */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) = NeoBackdrop(modifier) {
    Scaffold(
        containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = topBar, floatingActionButton = floatingActionButton, content = content,
    )
}

/** Glass title strip (heading semantics, labelled Back button >= 48dp). It handles the status-bar inset itself. */
@Composable
fun GlassTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.Screen.dp, vertical = Space.S.dp)
            .neoGlass(2, Radius.Card).heightIn(min = 56.dp).padding(horizontal = Space.S.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S.dp),
    ) {
        if (onBack != null) GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        Text(title, Modifier.weight(1f).padding(horizontal = Space.S.dp).asHeading(), style = MaterialTheme.typography.titleLarge)
        actions()
    }
}

private fun cardColor(level: GlassLevel): Long = when (level) { GlassLevel.L1 -> Neo.RaisedCard; GlassLevel.L2 -> Neo.RaisedButton; GlassLevel.L3 -> Neo.RaisedModal }

/**
 * Raised card (L1 = card, L2 = lighter / emphasised, L3 = darker). With [onClick] the whole card is one >= 48dp button target (press sink + neon focus ring).
 * Content is composed inside [NeoRaisedTheme] and [LocalNeoInList], so text is dark-on-light and nested raised controls are flat.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    level: GlassLevel = GlassLevel.L1,
    inList: Boolean = LocalNeoInList.current,
    content: @Composable ColumnScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val flat = rememberRaisedFlat(inList)
    var m = modifier.neoRing(focused && onClick != null, Radius.Card, onLight = true).neoRaised(flat, Radius.Card, cardColor(level), true, src)
    if (onClick != null) m = m.heightIn(min = MinTouch).clickable(src, LocalIndication.current, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
    NeoRaisedTheme {
        CompositionLocalProvider(LocalNeoInList provides true) { Column(m.padding(Space.L.dp), content = content) }
    }
}

/** One number + label. Merged into a single TalkBack node "<label>: <value>". [accent] is ignored (callers pass dark-scheme colours; the icon uses the on-light accent). */
@Composable
fun StatTile(label: String, value: Int, modifier: Modifier = Modifier, icon: ImageVector? = null, @Suppress("UNUSED_PARAMETER") accent: Color? = null) =
    GlassCard(modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" }, inList = true) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text("$value", style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

enum class GlassButtonStyle { Primary, Secondary, Text }

/** Primary = active gradient pill + glow, Secondary = Raised pill, Text = flat label. All >= 48dp, neon ring on focus, 50% alpha and no glow/shadow when disabled. */
@Composable
fun GlassButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    style: GlassButtonStyle = GlassButtonStyle.Primary, enabled: Boolean = true, icon: ImageVector? = null,
    inList: Boolean = LocalNeoInList.current,
    running: Boolean = false,
) {
    val label = when (style) {
        GlassButtonStyle.Primary -> Color(Neo.OnActive)
        GlassButtonStyle.Secondary -> Color(Neo.TextOnLight)
        GlassButtonStyle.Text -> MaterialTheme.colorScheme.primary
    }
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val flat = if (style == GlassButtonStyle.Secondary) rememberRaisedFlat(inList) else true
    val ringOnLight = style != GlassButtonStyle.Primary && !isDarkTheme()
    val base = modifier.heightIn(min = MinTouch).neoRing(focused && enabled, Radius.Pill, ringOnLight).neoGlowRing(running, Radius.Pill, ringOnLight)
    val shaped = when (style) {
        GlassButtonStyle.Primary -> base.pressScale(src).graphicsLayer { alpha = NeoLogic.alphaFor(enabled) }.neoGlow(enabled)
            .clip(PillShape).background(Brush.linearGradient(listOf(Color(Neo.ActiveStart), Color(Neo.ActiveMid), Color(Neo.ActiveEnd))), PillShape)
        GlassButtonStyle.Secondary -> base.neoRaised(flat, Radius.Pill, Neo.RaisedButton, enabled, src)
        GlassButtonStyle.Text -> base.pressScale(src).graphicsLayer { alpha = NeoLogic.alphaFor(enabled) }.clip(PillShape)
    }
    Row(
        shaped.clickable(src, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = Space.L.dp, vertical = Space.S.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = label, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(Space.S.dp)) }
        Text(text, color = label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** Dark inset well with white text (on any surface), neon ring while focused, red border + message under it on error. */
@Composable
fun GlassTextField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    singleLine: Boolean = true, error: String? = null, hint: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val none = Color.Transparent
    val shape = RoundedCornerShape(Radius.Control.dp)
    Column(modifier.fillMaxWidth()) {
        Inset(Modifier.fillMaxWidth().neoRing(focused, Radius.Control)) {
            TextField(
                value, onChange, Modifier.fillMaxWidth().let { if (error != null) it.border(2.dp, Color(Neo.DangerOnDark), shape) else it },
                label = { Text(label) }, singleLine = singleLine, isError = error != null, keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation, interactionSource = src, shape = shape,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color(Neo.TextOnDark), unfocusedTextColor = Color(Neo.TextOnDark), errorTextColor = Color(Neo.TextOnDark),
                    focusedContainerColor = none, unfocusedContainerColor = none, errorContainerColor = none, disabledContainerColor = none,
                    focusedIndicatorColor = none, unfocusedIndicatorColor = none, errorIndicatorColor = none, disabledIndicatorColor = none,
                    cursorColor = Color(Neo.RingStroke), errorCursorColor = Color(Neo.RingStroke),
                    focusedLabelColor = Color(Neo.AccentOnDark), unfocusedLabelColor = Color(Neo.Text2OnDark), errorLabelColor = Color(Neo.DangerOnDark),
                ),
            )
        }
        (error ?: hint)?.let {
            Text(
                it, Modifier.padding(start = Space.L.dp, top = Space.Xs.dp), style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Toggle chip: inset pill; selected = neon ring + check icon + bold label (never colour only). One Role.Checkbox target >= 48dp. */
@Composable
fun GlassChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(
        modifier.heightIn(min = MinTouch).padding(2.dp).neoRing(selected || focused, Radius.Pill).neoInset(Radius.Pill)
            .toggleable(selected, src, LocalIndication.current, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = Space.M.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S.dp),
    ) {
        val lead = if (selected) Icons.Default.Check else icon
        if (lead != null) Icon(lead, null, tint = Color(Neo.TextOnDark), modifier = Modifier.size(18.dp))
        Text(label, color = Color(Neo.TextOnDark), style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/** Surface-aware status colour: on-light tokens inside raised content, on-dark tokens elsewhere (isDarkTheme() is false inside [NeoRaisedTheme]). */
@Composable
private fun kindColor(k: StateKind): Color {
    val dark = isDarkTheme(); val s = MaterialTheme.colorScheme
    return when (k) {
        StateKind.Ok -> Color(if (dark) Neo.SuccessOnDark else Neo.SuccessOnLight)
        StateKind.Failed -> Color(if (dark) Neo.DangerOnDark else Neo.DangerOnLight)
        StateKind.Retry -> Color(if (dark) Neo.WarningOnDark else Neo.WarningOnLight)
        StateKind.Running -> s.secondary
        StateKind.Neutral -> s.onSurfaceVariant
    }
}

private fun kindIcon(k: StateKind): ImageVector = when (k) {
    StateKind.Ok -> Icons.Default.Check
    StateKind.Failed -> Icons.Default.Warning
    StateKind.Retry -> Icons.Default.Refresh
    StateKind.Running -> Icons.Default.PlayArrow
    StateKind.Neutral -> Icons.Default.Info
}

/** Status pill: icon + text + colour (never colour-only). Read as "State: <label>". Fill is white 55% on light surfaces / black 25% on dark ones (contrast-safe, see NeoSurfaces.badge*). */
@Composable
fun StateBadge(state: String, modifier: Modifier = Modifier) {
    val k = StateStyle.kind(state); val col = kindColor(k); val label = StateStyle.label(state)
    val fill = if (isDarkTheme()) Color.Black.copy(alpha = NeoAlpha.BadgeDark) else Color.White.copy(alpha = NeoAlpha.BadgeLight)
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "State: $label" }
            .neoGlowRing(k == StateKind.Running, Radius.Pill, onLight = !isDarkTheme())
            .clip(PillShape).background(fill).padding(horizontal = Space.M.dp, vertical = Space.Xs.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Xs.dp),
    ) {
        Icon(kindIcon(k), null, tint = col, modifier = Modifier.size(14.dp))
        Text(label, color = col, style = MaterialTheme.typography.labelMedium)
    }
}

/** Modal content: dark-on-light, flat raised controls, real blur behind the dialog window (API 31+, off with Solid surfaces). */
@Composable
internal fun ModalContent(content: @Composable () -> Unit) {
    NeoDialogBlur()
    NeoRaisedTheme { CompositionLocalProvider(LocalNeoInList provides true) { content() } }
}

/** Raised 36dp modal (light #D7DEE8). */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit, title: String, confirmText: String, onConfirm: () -> Unit,
    dismissText: String? = "Cancel", confirmEnabled: Boolean = true, content: @Composable () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss, shape = SheetShape, containerColor = Color(Neo.RaisedModal), tonalElevation = 0.dp,
    titleContentColor = Color(Neo.TextOnLight), textContentColor = Color(Neo.TextOnLight),
    title = { NeoRaisedTheme { Text(title, Modifier.asHeading()) } }, text = { ModalContent(content) },
    confirmButton = { NeoRaisedTheme { GlassButton(confirmText, onConfirm, style = GlassButtonStyle.Text, enabled = confirmEnabled) } },
    dismissButton = dismissText?.let { { NeoRaisedTheme { GlassButton(it, onDismiss, style = GlassButtonStyle.Text) } } },
)

/** Row around the Neo toggle ([LabeledSwitch]: whole row toggles, Role.Switch, >= 48dp). Sits on a card or modal, so it draws no surface of its own. */
@Composable
fun GlassSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().padding(horizontal = Space.S.dp)) { LabeledSwitch(label, checked, onChange) }

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) =
    GlassCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            action?.invoke()
        }
    }

/** Progress bar with semantics: determinate for a [fraction] in 0..1, indeterminate (sweeping segment) when null. */
@Composable
fun GlassProgress(description: String, modifier: Modifier = Modifier, fraction: Float? = null) =
    NeoProgress(
        fraction,
        modifier.semantics {
            contentDescription = description
            progressBarRangeInfo = if (fraction == null) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
        },
    )

/** Busy indicator in a 48dp slot (same footprint as a [GlassIconButton]); [description] is what TalkBack says. */
@Composable
fun GlassSpinner(description: String, modifier: Modifier = Modifier) =
    Box(modifier.size(MinTouch).semantics { contentDescription = description }, contentAlignment = Alignment.Center) { NeoSpinner() }
