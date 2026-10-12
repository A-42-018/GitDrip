package com.gitdrip.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitdrip.app.MainViewModel
import com.gitdrip.app.asHeading
import com.gitdrip.app.data.ExecutionEntity
import com.gitdrip.app.fmtTime
import com.gitdrip.app.ui.*
import java.util.Calendar

/**
 * U2 Dashboard. TalkBack order: top-bar heading -> hero -> stats -> needs attention -> quick actions -> "Recent runs" heading -> runs.
 * Same ViewModel flows and the same four destinations as before (Projects, Termux setup, Settings, Sync); only the presentation changed.
 */
@Composable
fun DashboardScreen(
    vm: MainViewModel,
    onProjects: () -> Unit, onRun: (Long) -> Unit,
) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val schedules by vm.activeSchedules.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val failed by vm.failedToday.collectAsStateWithLifecycle()
    val cap by vm.dailyCap.collectAsStateWithLifecycle()
    val retrying by vm.retrying.collectAsStateWithLifecycle()
    val next by vm.nextRun.collectAsStateWithLifecycle()
    val runs by vm.latestRuns.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val msg by vm.syncMsg.collectAsStateWithLifecycle()
    val names = projects.associate { it.id to it.name }
    val attention = DashboardLogic.attentionLines(failed, retrying)
    val onBackdrop = MaterialTheme.colorScheme.onSurfaceVariant // read OUTSIDE any card: dark scheme, only for loose text on the backdrop

    GlassScaffold(topBar = {
        GlassTopBar("GitDrip") { // Sync lives in the top panel; Projects / Setup / Settings are in the bottom bar
            if (syncing) GlassSpinner("Syncing") else GlassIconButton(Icons.Default.Refresh, "Sync all from Termux", { vm.sync() })
        }
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(horizontal = Space.Screen.dp),
            verticalArrangement = Arrangement.spacedBy(Space.M.dp),
            contentPadding = PaddingValues(bottom = Space.Xl.dp),
        ) {
            item { Hero(stats.today, stats.streak, cap, DashboardLogic.nextRunLabel(next, ::fmtTime)) }

            item { StatRow(Modifier, StatSpec("Projects", projects.size, Icons.AutoMirrored.Filled.List), StatSpec("Active schedules", schedules, Icons.Default.DateRange)) }
            item { StatRow(Modifier, StatSpec("Commits today", stats.today, Icons.Default.Check), StatSpec("This week", stats.week, Icons.Default.DateRange)) }
            item { StatRow(Modifier, StatSpec("Streak (days)", stats.streak, Icons.Default.Favorite), StatSpec("Failed today", failed, Icons.Default.Warning)) }

            if (attention.isNotEmpty()) item { AttentionCard(attention) }

            if (syncing) item { SyncShimmer() }
            msg?.let { m ->
                item { Text(m, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = onBackdrop) }
            }

            item { Text("Recent runs", Modifier.asHeading(), style = MaterialTheme.typography.titleMedium) }
            if (runs.isEmpty()) item {
                EmptyState("No runs yet", "Create a project, import files and run a batch, or add a schedule.") {
                    GlassButton("Open projects", onProjects, style = GlassButtonStyle.Secondary)
                }
            }
            itemsIndexed(runs, key = { _, r -> r.id }) { i, r -> Box(Modifier.fadeInItem(i)) { RunCard(r, names[r.projectId] ?: "(deleted)", onRun) } }
        }
    }
}

@Composable
private fun Hero(today: Int, streak: Int, cap: Int, nextLine: String) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    GlassCard(Modifier.fillMaxWidth(), level = GlassLevel.L2) {
        Text(DashboardLogic.greeting(hour), style = MaterialTheme.typography.headlineSmall)
        Text(DashboardLogic.heroSummary(today, streak, cap), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.S.dp))
        NextRunPill(nextLine)
    }
}

@Composable
private fun NextRunPill(text: String) = Row(
    Modifier.neoInset(Radius.Pill).padding(horizontal = Space.M.dp, vertical = Space.S.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S.dp),
) { // dark well inside a light card: colours are explicit on-dark tokens (never the card's on-light scheme)
    Icon(Icons.Default.DateRange, null, tint = Color(Neo.AccentOnDark), modifier = Modifier.size(16.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = Color(Neo.TextOnDark), modifier = Modifier.weight(1f, fill = false))
}

private class StatSpec(val label: String, val value: Int, val icon: ImageVector)

@Composable
private fun StatRow(modifier: Modifier, a: StatSpec, b: StatSpec) = Row(
    modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.M.dp),
) {
    StatTile(a.label, a.value, Modifier.weight(1f).fillMaxHeight(), a.icon)
    StatTile(b.label, b.value, Modifier.weight(1f).fillMaxHeight(), b.icon)
}

/** Shown only when something failed today or is waiting to retry. Whole card is one TalkBack node. */
@Composable
private fun AttentionCard(lines: List<String>) = GlassCard(
    Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, level = GlassLevel.L2,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S.dp)) {
        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Text("Needs attention", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Text("Open a run below for the reason and a retry button.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RunCard(e: ExecutionEntity, project: String, onOpen: (Long) -> Unit) = GlassCard(Modifier.fillMaxWidth(), onClick = { onOpen(e.id) }, onClickLabel = "Open run details") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S.dp)) {
        StateBadge(e.state)
        Text(project, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(Space.Xs.dp))
    Text(
        DashboardLogic.runSummary(null, fmtTime(e.startedAt), null, e.filesChanged, e.reason, e.state),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    e.commitHash?.takeIf { it.isNotBlank() }?.let {
        Text(it.take(7), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
    }
}
