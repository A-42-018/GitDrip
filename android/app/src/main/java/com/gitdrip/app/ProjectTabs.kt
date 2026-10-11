@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gitdrip.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitdrip.app.data.BatchEntity
import com.gitdrip.app.data.FileEntity
import com.gitdrip.app.data.Importer
import com.gitdrip.app.data.PrMode
import com.gitdrip.app.data.WatchTick
import com.gitdrip.app.data.ProjectEntity
import com.gitdrip.app.ui.*

private val TabPadding = PaddingValues(start = Space.Screen.dp, end = Space.Screen.dp, top = Space.S.dp, bottom = Space.Xl.dp)

@Composable
fun FilesTab(vm: MainViewModel, p: ProjectEntity) {
    val ctx = LocalContext.current
    val files by vm.files(p.id).collectAsStateWithLifecycle(emptyList())
    val batches by vm.batches(p.id).collectAsStateWithLifecycle(emptyList())
    val status by vm.status.collectAsStateWithLifecycle()
    var pick by remember { mutableStateOf<FileEntity?>(null) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) vm.importFrom(p, Importer.Kind.FOLDER, listOf(u))
    }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l ->
        if (l.isNotEmpty()) vm.importFrom(p, Importer.Kind.FILES, l)
    }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) vm.importFrom(p, Importer.Kind.ZIP, listOf(u))
    }
    val seq = remember(batches) { batches.associate { it.id to it.seq } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = TabPadding, verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Import", style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
                Spacer(Modifier.height(Space.S.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S.dp), verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                    GlassButton("Folder", { folder.launch(null) }, style = GlassButtonStyle.Secondary)
                    GlassButton("Files", { many.launch(arrayOf("*/*")) }, style = GlassButtonStyle.Secondary)
                    GlassButton("ZIP", { zip.launch(arrayOf("application/zip", "application/x-zip-compressed")) }, style = GlassButtonStyle.Secondary)
                }
            }
        }
        if (!vm.sharedAccess) item {
            NoticeCard(TabLogic.SHARED_ACCESS_NOTICE, actionLabel = "Open settings") {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
        }
        status?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Space.Xs.dp)) } }
        if (files.isEmpty()) item { EmptyState("No files yet", "Import a folder, files or a ZIP.") }
        else {
            item { Text(TabLogic.filesSummary(files.size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Space.Xs.dp)) }
            items(files, key = { it.id }) { f ->
                val tag = TabLogic.batchTag(f.batchId?.let { seq[it] })
                GlassCard(Modifier.fillMaxWidth(), onClick = { pick = f }, onClickLabel = "Assign ${TabLogic.fileName(f.path)} to a batch") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.M.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(f.path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            Text(TabLogic.fileSubtitle(f.size, f.sha256), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                        }
                        Text("Batch: $tag", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    pick?.let { f ->
        val opts = buildList<Pair<String, () -> Unit>> {
            add("Unassigned" to { vm.assign(p, f.id, null); pick = null })
            batches.forEach { b -> add(TabLogic.batchTitle(b.seq, b.message) to { vm.assign(p, f.id, b.id); pick = null }) }
        }
        GlassChoiceDialog("Assign ${TabLogic.fileName(f.path)}", opts) { pick = null }
    }
}

/** Which batch the "as pull request" dialog is for (null batch = next pending). */
private class PrPick(val batch: BatchEntity?)

@Composable
fun BatchesTab(vm: MainViewModel, p: ProjectEntity) {
    val batches by vm.batches(p.id).collectAsStateWithLifecycle(emptyList())
    val files by vm.files(p.id).collectAsStateWithLifecycle(emptyList())
    val status by vm.status.collectAsStateWithLifecycle()
    val bridge by vm.bridge.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<BatchEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var prPick by remember { mutableStateOf<PrPick?>(null) }
    var tickPick by remember { mutableStateOf(false) }
    val watchMsg by vm.watchMsg.collectAsStateWithLifecycle()
    val watchBusy by vm.watchBusy.collectAsStateWithLifecycle()
    val count = remember(files) { files.groupingBy { it.batchId }.eachCount() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = TabPadding, verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S.dp), verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                GlassButton("Auto-suggest", { vm.autoBatches(p) })
                GlassButton("New batch", { adding = true }, style = GlassButtonStyle.Secondary)
                GlassButton("Run next", { vm.runBatch(p, null) }, style = GlassButtonStyle.Secondary, icon = Icons.Default.PlayArrow, running = running)
                GlassButton("Run next as PR", { prPick = PrPick(null) }, style = GlassButtonStyle.Secondary, icon = Icons.Default.Share)
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(TabLogic.WATCH_TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
                Text(TabLogic.WATCH_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S.dp), verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                    GlassButton(TabLogic.WATCH_SCAN, { vm.watchScan(p, false) }, style = GlassButtonStyle.Secondary, enabled = !watchBusy)
                    GlassButton(TabLogic.WATCH_COMMIT, { vm.watchScan(p, true) }, style = GlassButtonStyle.Secondary, enabled = !watchBusy)
                    GlassButton(TabLogic.WATCH_TICK, { tickPick = true }, style = GlassButtonStyle.Text, enabled = !watchBusy)
                }
                watchMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        }
        status?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Space.Xs.dp)) } }
        bridge?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Space.Xs.dp)) } }
        item { Text(TabLogic.unassignedLabel(count[null] ?: 0), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Space.Xs.dp)) }
        if (batches.isEmpty()) item { EmptyState("No batches yet", "Use Auto-suggest to group files, or add a batch by hand.") }
        itemsIndexed(batches, key = { _, b -> b.id }) { i, b ->
            val title = TabLogic.batchTitle(b.seq, b.message, b.origin)
            GlassCard(Modifier.fillMaxWidth()) {
                // Only the text area is the edit button: a clickable parent would merge the action buttons below into one TalkBack node.
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(onClickLabel = "Edit message of batch #${b.seq}", role = Role.Button) { edit = b },
                    verticalArrangement = Arrangement.spacedBy(Space.S.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.S.dp), verticalAlignment = Alignment.CenterVertically) {
                        StateBadge(b.status)
                        Text(TabLogic.batchFiles(count[b.id] ?: 0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (TabLogic.canRun(b.status)) GlassIconButton(Icons.Default.PlayArrow, "Run batch #${b.seq} now", { vm.runBatch(p, b) })
                    if (TabLogic.canRun(b.status)) GlassIconButton(Icons.Default.Share, "Run batch #${b.seq} as pull request", { prPick = PrPick(b) })
                    GlassIconButton(Icons.Default.KeyboardArrowUp, "Move batch #${b.seq} up", { vm.moveBatch(p, b.id, -1) }, enabled = TabLogic.canMoveUp(i))
                    GlassIconButton(Icons.Default.KeyboardArrowDown, "Move batch #${b.seq} down", { vm.moveBatch(p, b.id, 1) }, enabled = TabLogic.canMoveDown(i, batches.size))
                    GlassIconButton(Icons.Default.Delete, "Delete batch #${b.seq}", { vm.deleteBatch(p, b.id) })
                }
            }
        }
    }
    prPick?.let { pk ->
        val go = { m: PrMode -> vm.runBatch(p, pk.batch, m); prPick = null }
        GlassChoiceDialog(
            TabLogic.prTitle(pk.batch?.seq),
            listOf(TabLogic.PR_OPEN to { go(PrMode.Open) }, TabLogic.PR_MERGE to { go(PrMode.OpenMerge) }),
        ) { prPick = null }
    }
    if (tickPick) {
        GlassChoiceDialog(
            TabLogic.tickTitle(),
            WatchTick.values().map { t -> TabLogic.tickLabel(t.arg) to { vm.watchScan(p, false, t); tickPick = false } },
        ) { tickPick = false }
    }
    if (adding || edit != null) {
        var text by remember(edit, adding) { mutableStateOf(edit?.message ?: "") }
        GlassDialog(
            onDismiss = { adding = false; edit = null }, title = if (edit == null) "New batch" else "Commit message", confirmText = "Save",
            onConfirm = {
                edit?.let { vm.rename(p, it.id, text) } ?: vm.addBatch(p, text)
                adding = false; edit = null
            },
        ) { GlassTextField(text, { text = it }, "feat: add …") }
    }
}
