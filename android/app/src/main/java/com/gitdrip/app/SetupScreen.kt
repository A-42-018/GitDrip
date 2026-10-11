package com.gitdrip.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitdrip.app.data.Importer
import com.gitdrip.app.data.Scheduler
import com.gitdrip.app.data.TermuxBridge
import com.gitdrip.app.ui.*

@Composable
fun SetupScreen(vm: MainViewModel, onBack: (() -> Unit)? = null) { // onBack == null: top-level tab, no back arrow
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val installed = remember(tick) { TermuxBridge.installed(ctx) }
    val permitted = remember(tick) { TermuxBridge.permitted(ctx) }
    val files = remember(tick) { Importer.sharedAccess(ctx) }
    val doctor by vm.doctor.collectAsStateWithLifecycle()
    val msg by vm.doctorMsg.collectAsStateWithLifecycle()
    GlassScaffold(topBar = { GlassTopBar("Termux setup", onBack) }) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = Space.Screen.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.M.dp),
        ) {
            Text(ScreenLogic.setupSummary(listOf(installed, permitted, files).count { it }, 3),
                Modifier.asHeading(), style = MaterialTheme.typography.titleMedium)
            CheckCard("Termux installed", installed, "Install Termux from F-Droid or GitHub (the Play Store build is outdated).")
            CheckCard("Run-commands permission", permitted, "Lets GitDrip start the engine in Termux.") {
                GlassButton("Grant", { askPerm.launch(TermuxBridge.PERM) }, style = GlassButtonStyle.Secondary)
            }
            CheckCard("All-files access", files, "Termux can only read files in /storage/emulated/0/GitDrip.") {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R)
                    GlassButton("Grant storage", { askStorage.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) }, style = GlassButtonStyle.Secondary)
                else GlassButton("Open settings", {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }, style = GlassButtonStyle.Secondary)
            }
            val exact = remember(tick) { Scheduler.canExact(ctx) }
            val battery = remember(tick) { (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }
            CheckCard("Exact alarms", exact, "Needed so runs start at the planned minute. Android 12-13 asks in Settings > Alarms & reminders.") {
                GlassButton("Allow", {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                }, style = GlassButtonStyle.Secondary)
            }
            CheckCard("Battery: unrestricted", battery, TabLogic.oemHint(android.os.Build.MANUFACTURER) ?: "Disable battery optimization for GitDrip and Termux so alarms and runs are not killed.") {
                GlassButton("Battery", { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, style = GlassButtonStyle.Secondary)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                    SectionTitle("One-time setup in Termux")
                    Text("1. Copy the extracted gitdrip/ engine folder to your phone's Download folder.\n2. Paste this in Termux:", style = MaterialTheme.typography.bodySmall)
                    CodePanel(TermuxBridge.SETUP_COMMAND, "Copy command")
                    Text("3. Store your GitHub token in Termux (never in the app): echo YOUR_TOKEN | gitdrip auth set --user YOUR_NAME", style = MaterialTheme.typography.bodySmall)
                }
            }
            GlassButton("Test connection", { vm.checkEngine() }, Modifier.fillMaxWidth())
            msg?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
            doctor?.forEach { (label, ok) -> CheckCard(label, ok, "Missing or not ready in Termux") }
            GitHubAccountCard(vm)
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "Contributions: commits count on GitHub only with a verified/noreply author email, on the default branch of a non-fork repo. " +
                        "For private repos enable Profile → Contribution settings → \"Private contributions\". " +
                        "Also disable battery optimization for Termux. " + TabLogic.PRIVACY_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(Space.L.dp))
        }
    }
}
