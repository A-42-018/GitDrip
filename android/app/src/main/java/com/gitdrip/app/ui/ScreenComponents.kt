package com.gitdrip.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gitdrip.app.asHeading

/** Section heading inside a screen (TalkBack heading). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.asHeading(), style = MaterialTheme.typography.titleMedium)

/** Small caption + selectable value (text stays selectable, so no merged node). */
@Composable
fun FieldRow(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
        }
    }
}

/** Dark code panel for logs / commands: L2 glass, monospace, horizontal scroll, selectable, with a Copy button. */
@Composable
fun CodePanel(text: String, copyLabel: String, modifier: Modifier = Modifier) {
    val clip = LocalClipboardManager.current
    Column(modifier.fillMaxWidth().neoInset(Radius.Control).padding(Space.M.dp), verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
        SelectionContainer {
            // Inset well = always white text, wherever the panel sits (card, dialog or loose on the backdrop).
            Text(text, Modifier.horizontalScroll(rememberScrollState()), color = Color(Neo.TextOnDark), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        GlassButton(copyLabel, { clip.setText(AnnotatedString(text)) }, style = GlassButtonStyle.Secondary)
    }
}

/** Setup/checklist row: icon + label + status text (never icon/colour only). [action] is shown only while not ok. */
@Composable
fun CheckCard(label: String, ok: Boolean, hint: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val col = Color(if (ok) Neo.SuccessOnLight else Neo.WarningOnLight) // the row lives in a light card
    val status = ScreenLogic.checkStatus(ok)
    GlassCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label: $status" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M.dp),
        ) {
            Icon(if (ok) Icons.Default.Check else Icons.Default.Warning, null, tint = col, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(status, style = MaterialTheme.typography.labelMedium, color = col)
            }
        }
        if (!ok) {
            Spacer(Modifier.height(Space.S.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}
