@file:OptIn(ExperimentalLayoutApi::class)

package com.gitdrip.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import com.gitdrip.app.data.BackupFile
import com.gitdrip.app.data.Limits
import com.gitdrip.app.data.Notify
import com.gitdrip.app.data.humanSize
import com.gitdrip.app.ui.*

/** P16b: notifications + backup / restore / config import-export. The engine (Termux) does the work; files live in GitDrip/backups. U5: grouped glass sections. */
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: (() -> Unit)? = null) { // onBack == null: top-level tab, no back arrow
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++; vm.refreshBackups() }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val granted = remember(tick) { Notify.granted(ctx) }
    var okNotes by remember(tick) { mutableStateOf(Notify.successEnabled(ctx)) }
    var full by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<BackupFile?>(null) }
    val files by vm.backups.collectAsStateWithLifecycle()
    val msg by vm.backupMsg.collectAsStateWithLifecycle()
    val busy by vm.backupBusy.collectAsStateWithLifecycle()

    GlassScaffold(topBar = { GlassTopBar("Settings & backup", onBack) }) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = Space.Screen.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.M.dp),
        ) {
            val solid = LocalSolidSurfaces.current
            val setSolid = LocalSetSolid.current
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                    SectionTitle("Appearance")
                    GlassSwitchRow("Solid surfaces", solid, setSolid)
                    Text(MotionLogic.solidHint(solid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val cap by vm.dailyCap.collectAsStateWithLifecycle()
            val capMsg by vm.capMsg.collectAsStateWithLifecycle()
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                    SectionTitle("Daily limit")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M.dp)) {
                        GlassIconButton(Icons.Default.KeyboardArrowDown, "Lower daily limit", { vm.setDailyCap(cap - 1) }, enabled = cap > Limits.MIN_CAP)
                        Text("$cap", Modifier.semantics { contentDescription = "Daily limit $cap commits" }, style = MaterialTheme.typography.headlineSmall)
                        GlassIconButton(Icons.Default.KeyboardArrowUp, "Raise daily limit", { vm.setDailyCap(cap + 1) }, enabled = cap < Limits.MAX_CAP)
                        Text("commits per day", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(TabLogic.CAP_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    capMsg?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                    SectionTitle("Notifications")
                    if (!granted) {
                        Text("Allow notifications to hear about failed runs while the app is closed.", style = MaterialTheme.typography.bodySmall)
                        GlassButton("Allow notifications", { if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) })
                    } else Text("Failed runs always notify. Messages never contain tokens.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GlassSwitchRow("Also notify when a batch is pushed", okNotes, { okNotes = it; Notify.setSuccess(ctx, it) })
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                    SectionTitle("Backup")
                    Text("Saved to GitDrip/backups (last 10 kept). Contains projects, batches, schedules, task history and config. Never your GitHub token.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GlassSwitchRow("Include repo and source folders (bigger)", full, { full = it })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S.dp), verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                        GlassButton("Back up now", { vm.backupNow(full) }, enabled = !busy)
                        GlassButton("Export config", { vm.exportConfig() }, enabled = !busy, style = GlassButtonStyle.Secondary)
                    }
                    if (busy) GlassProgress("Working")
                    msg?.let {
                        Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
                            color = if (ScreenLogic.backupMsgError(it)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }

            SectionTitle("Saved files")
            if (files.isEmpty()) EmptyState("No backups yet", "Use Back up now or Export config above.")
            files.forEach { f ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(ScreenLogic.backupTitle(f.isConfig, f.whenLabel), style = MaterialTheme.typography.titleSmall)
                            Text(humanSize(f.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GlassButton(ScreenLogic.backupAction(f.isConfig), { target = f }, enabled = !busy, style = GlassButtonStyle.Secondary,
                            modifier = Modifier.semantics { contentDescription = ScreenLogic.backupActionDescription(f.isConfig, f.whenLabel) })
                    }
                }
            }
            Text(
                "After a restore: set your token again (Termux setup > Send token), run `gitdrip schedule install` if you use Termux cron, " +
                    "and re-create app projects with the same names to see their history again. The app's own database is not part of the backup; run Sync to rebuild it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.L.dp))
        }
    }

    target?.let { f ->
        var force by remember(f) { mutableStateOf(false) }
        GlassDialog(
            onDismiss = { target = null }, title = if (f.isConfig) "Apply this config?" else "Restore this backup?",
            confirmText = ScreenLogic.backupAction(f.isConfig),
            onConfirm = { target = null; if (f.isConfig) vm.importConfig(f.name) else vm.restoreBackup(f.name, force) },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                Text(if (f.isConfig) "Only known settings with matching types are applied." else "Projects that already exist are refused unless you allow overwriting. Local repo and source folders are kept.")
                if (!f.isConfig) GlassSwitchRow("Overwrite existing projects", force, { force = it })
            }
        }
    }
}
