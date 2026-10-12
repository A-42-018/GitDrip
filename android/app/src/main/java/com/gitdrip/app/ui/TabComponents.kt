package com.gitdrip.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gitdrip.app.asHeading

/** Round Raised icon button: >= 48dp target, spoken [description], neon ring on focus, 50% alpha, no shadow and inert when disabled. */
@Composable
fun GlassIconButton(
    icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    inList: Boolean = LocalNeoInList.current,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val flat = rememberRaisedFlat(inList)
    Box(
        modifier.size(Touch.Min.dp).neoRing(focused && enabled, Radius.Pill, onLight = true).neoRaised(flat, Radius.Pill, Neo.RaisedButton, enabled, src)
            .clickable(src, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color(Neo.TextOnLight), modifier = Modifier.size(24.dp)) }
}

/** Warning-style card with optional action; icon + text, never colour only. Text is announced as one node. */
@Composable
fun NoticeCard(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) =
    GlassCard(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.M.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        if (actionLabel != null) GlassButton(actionLabel, onAction, style = GlassButtonStyle.Text)
    }

/** Raised 36dp modal that only offers choices (no confirm button): divider-separated rows, each >= 48dp with a chevron. */
@Composable
fun GlassChoiceDialog(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, shape = SheetShape, containerColor = Color(Neo.RaisedModal), tonalElevation = 0.dp,
    titleContentColor = Color(Neo.TextOnLight), textContentColor = Color(Neo.TextOnLight),
    title = { NeoRaisedTheme { Text(title, Modifier.asHeading()) } },
    text = {
        ModalContent {
            LazyColumn {
                items(options) { o ->
                  Column {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Touch.Min.dp).clickable(role = Role.Button, onClick = o.second).padding(horizontal = Space.S.dp, vertical = Space.M.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(o.first, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = Color(Neo.OutlineOnLight).copy(alpha = 0.35f))
                  }
                }
            }
        }
    },
    confirmButton = {},
    dismissButton = { NeoRaisedTheme { GlassButton("Cancel", onDismiss, style = GlassButtonStyle.Text) } },
)
