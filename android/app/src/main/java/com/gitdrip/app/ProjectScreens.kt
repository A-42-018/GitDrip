package com.gitdrip.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitdrip.app.data.ProjectEntity
import com.gitdrip.app.ui.*
import kotlinx.coroutines.launch

/** Project list / new / detail. Moved out of MainActivity.kt in U2; restyled with the glass kit in U3 (same signatures, same VM calls). */

@Composable
fun ProjectList(vm: MainViewModel, onBack: (() -> Unit)? = null, onNew: () -> Unit, onOpen: (Long) -> Unit) { // onBack == null: top-level tab, no back arrow
    val projects by vm.projects.collectAsStateWithLifecycle()
    GlassScaffold(
        topBar = { GlassTopBar("Projects", onBack) },
        floatingActionButton = { if (projects.isNotEmpty()) GlassFab("New project", onNew) },
    ) { pad ->
        if (projects.isEmpty()) {
            Box(Modifier.padding(pad).padding(Space.Screen.dp).fillMaxSize()) {
                EmptyState("No projects yet", "Create a project to import files, split them into batches and push them on a schedule.") {
                    GlassButton("New project", onNew)
                }
            }
        } else LazyColumn(
            Modifier.padding(pad).padding(horizontal = Space.Screen.dp),
            verticalArrangement = Arrangement.spacedBy(Space.M.dp),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            item { Text(ProjectLogic.projectCount(projects.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(projects, key = { it.id }) { p -> ProjectCard(vm, p) { onOpen(p.id) } }
        }
    }
}

/** One glass card per project: name (+ paused badge), repo/branch, pushed-batches progress. Whole card = one button. */
@Composable
private fun ProjectCard(vm: MainViewModel, p: ProjectEntity, onOpen: () -> Unit) {
    val flow = remember(p.id) { vm.batches(p.id) }
    val batches by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val prog = ProjectLogic.progress(batches.map { it.status })
    val label = ProjectLogic.progressLabel(prog)
    GlassCard(Modifier.fillMaxWidth(), onClick = onOpen, onClickLabel = "Open project ${p.name}") {
        val s = MaterialTheme.colorScheme // read INSIDE the card: on-light scheme
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S.dp)) {
            Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.paused) StateBadge("PAUSED")
        }
        Text(ProjectLogic.subtitle(p.repoUrl, p.branch), style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant)
        Spacer(Modifier.height(Space.M.dp))
        GlassProgress(prog.fraction, label, decorative = true)
        Spacer(Modifier.height(Space.S.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = s.onSurfaceVariant)
    }
}

@Composable
fun NewProject(vm: MainViewModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var error by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val s = MaterialTheme.colorScheme
    if (picker) RepoPickerDialog(vm, { r, b -> repo = r; branch = b; picker = false }, { picker = false })
    GlassScaffold(topBar = { GlassTopBar("New project", onDone) }) { pad ->
        Column(
            Modifier.padding(pad).imePadding().padding(horizontal = Space.Screen.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.M.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.M.dp)) {
                    GlassTextField(
                        name, { name = it }, "Name (slug)", hint = ProjectLogic.NAME_HINT,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false, keyboardType = KeyboardType.Ascii),
                    )
                    GlassTextField(
                        repo, { repo = it }, "GitHub repo URL (optional)",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false, keyboardType = KeyboardType.Uri),
                    )
                    GlassButton("Pick from my GitHub repos", { picker = true }, Modifier.fillMaxWidth(), GlassButtonStyle.Secondary, icon = Icons.Default.Search)
                    GlassTextField(
                        branch, { branch = it }, "Branch",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false, keyboardType = KeyboardType.Ascii),
                    )
                }
            }
            error?.let { e ->
                Row(
                    Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
                    verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.S.dp),
                ) {
                    Icon(Icons.Default.Warning, null, tint = s.error, modifier = Modifier.size(20.dp))
                    Text(e, style = MaterialTheme.typography.bodyMedium, color = s.error)
                }
            }
            GlassButton(
                if (saving) "Saving…" else "Save",
                {
                    if (!saving) { saving = true; scope.launch { error = vm.create(name, repo, branch); saving = false; if (error == null) onDone() } }
                },
                Modifier.fillMaxWidth(), enabled = !saving,
            )
            Spacer(Modifier.height(Space.Xl.dp))
        }
    }
}

@Composable
fun ProjectDetail(vm: MainViewModel, id: Long, onRun: (Long) -> Unit, onBack: () -> Unit) {
    val projectFlow = remember(id) { vm.project(id) }
    val pendingFlow = remember(id) { vm.pending(id) }
    val project by projectFlow.collectAsStateWithLifecycle(initialValue = null)
    val pending by pendingFlow.collectAsStateWithLifecycle(initialValue = 0)
    var tab by rememberSaveable { mutableIntStateOf(ProjectLogic.TAB_FILES) }
    var confirm by remember { mutableStateOf(false) }
    val animate = rememberAnimationsEnabled()
    val p = project
    LaunchedEffect(p?.id) { p?.let { vm.reconcile(it) } }
    GlassScaffold(
        topBar = {
            GlassTopBar(p?.name ?: "", onBack) {
                GlassIconButton(Icons.Default.Delete, "Delete project", { confirm = true })
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(Space.M.dp)) {
            if (p != null) DetailHeader(p, pending, Modifier.padding(horizontal = Space.Screen.dp))
            SegmentedTabs(ProjectLogic.TAB_LABELS, tab, { tab = it }, Modifier.padding(horizontal = Space.Screen.dp))
            // Every tab pads itself (U4 tabs, U5 History).
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (p != null) Crossfade(tab, animationSpec = if (animate) tween(180) else snap(), label = "projectTab") { t ->
                    when (t) {
                        ProjectLogic.TAB_FILES -> FilesTab(vm, p)
                        ProjectLogic.TAB_BATCHES -> BatchesTab(vm, p)
                        ProjectLogic.TAB_SCHEDULE -> ScheduleTab(vm, p)
                        else -> HistoryTab(vm, p, onRun)
                    }
                }
            }
        }
    }
    if (confirm && p != null) GlassDialog(
        onDismiss = { confirm = false }, title = ProjectLogic.deleteTitle(p.name), confirmText = "Delete",
        onConfirm = { confirm = false; vm.delete(p); onBack() },
    ) { Text(ProjectLogic.DELETE_BODY) }
}

/** Repo, branch and pending-batch count in one raised glass card (one TalkBack node). */
@Composable
private fun DetailHeader(p: ProjectEntity, pending: Int, modifier: Modifier = Modifier) = GlassCard(
    modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, level = GlassLevel.L2,
) {
    Text(ProjectLogic.repoLabel(p.repoUrl), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(
        "Branch ${p.branch} · ${ProjectLogic.detailSummary(pending, p.paused)}",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
