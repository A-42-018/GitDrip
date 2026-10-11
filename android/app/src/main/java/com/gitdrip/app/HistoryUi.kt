package com.gitdrip.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitdrip.app.data.*
import com.gitdrip.app.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm")
fun fmtTime(ms: Long): String = FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** One run in a list (U5: glass card, whole card = one button). [project] is shown on the dashboard only. */
@Composable
fun RunRow(e: ExecutionEntity, project: String?, onOpen: (Long) -> Unit) = GlassCard(Modifier.fillMaxWidth(), onClick = { onOpen(e.id) }, onClickLabel = "Open run details") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S.dp)) {
        StateBadge(e.state)
        if (project != null) Text(project, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
fun HistoryTab(vm: MainViewModel, p: ProjectEntity, onOpen: (Long) -> Unit) {
    val runs by vm.recentRuns(p.id).collectAsStateWithLifecycle(emptyList())
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val msg by vm.syncMsg.collectAsStateWithLifecycle()
    LaunchedEffect(p.id) { vm.sync(p) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.Screen.dp), verticalArrangement = Arrangement.spacedBy(Space.M.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M.dp)) {
                GlassButton(if (syncing) "Syncing…" else "Sync from Termux", { vm.sync(p) }, enabled = !syncing, style = GlassButtonStyle.Secondary)
                Text(ScreenLogic.runsCount(runs.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (syncing) item { SyncShimmer() }
        msg?.let { item { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) } }
        if (runs.isEmpty()) item { EmptyState("No runs yet", ScreenLogic.historyEmpty()) }
        else itemsIndexed(runs, key = { _, r -> r.id }) { i, r -> Box(Modifier.fadeInItem(i)) { RunRow(r, null, onOpen) } }
    }
}

@Composable
fun RunDetailScreen(vm: MainViewModel, id: Long, onBack: () -> Unit) {
    val e by vm.execution(id).collectAsStateWithLifecycle(null)
    val projects by vm.projects.collectAsStateWithLifecycle()
    val bridge by vm.bridge.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    GlassScaffold(topBar = { GlassTopBar("Run", onBack) }) { pad ->
        val r = e
        if (r == null) { Box(Modifier.padding(pad).padding(Space.Screen.dp)) { EmptyState("Run not found", "It may have been removed. Go back and sync again.") }; return@GlassScaffold }
        val p = projects.firstOrNull { it.id == r.projectId }
        val batch by produceState<BatchEntity?>(null, r.batchId) { value = r.batchId?.let { vm.batchById(it) } }
        Column(
            Modifier.padding(pad).padding(horizontal = Space.Screen.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.M.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth(), level = GlassLevel.L2) {
                StateBadge(r.state)
                Spacer(Modifier.height(Space.S.dp))
                Text(p?.name ?: "(deleted)", style = MaterialTheme.typography.titleLarge)
                Text(fmtTime(r.startedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                ScreenLogic.detailRows(listOf(
                    "Batch" to (batch?.let { "#${it.seq} ${it.message}" } ?: ""),
                    "Started" to fmtTime(r.startedAt),
                    "Finished" to (r.finishedAt?.let { fmtTime(it) } ?: ""),
                    "Duration" to (if (r.finishedAt != null) durationLabel(r.startedAt, r.finishedAt) else ""),
                    "Files changed" to (if (r.filesChanged > 0) "${r.filesChanged}" else ""),
                    "Attempt" to (if (r.attempt > 0) "${r.attempt}" else ""),
                    "Next retry (UTC)" to r.nextRetryAt,
                    "Error class" to r.errorClass,
                    "Reason" to (r.reason ?: ""),
                )).forEach { (k, v) -> FieldRow(k, v); Spacer(Modifier.height(Space.M.dp)) }
                r.commitHash?.takeIf { it.isNotBlank() }?.let { FieldRow("Commit", it, mono = true); Spacer(Modifier.height(Space.M.dp)) }
                FieldRow("Task", r.taskId, mono = true)
            }
            val url = r.commitHash?.let { h -> p?.let { commitUrl(it.repoUrl, h) } }
            if (url != null) GlassButton("Open commit on GitHub", { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, Modifier.fillMaxWidth(), style = GlassButtonStyle.Secondary)
            val b = batch
            if (p != null && b != null && ScreenLogic.canRetry(r.state, b.status))
                GlassButton(ScreenLogic.retryLabel(r.state), { vm.runBatch(p, b) }, Modifier.fillMaxWidth())
            bridge?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
            if (r.output.isNotBlank()) {
                SectionTitle("Output (redacted)")
                CodePanel(redact(r.output), "Copy output")
            }
            Spacer(Modifier.height(Space.L.dp))
        }
    }
}
