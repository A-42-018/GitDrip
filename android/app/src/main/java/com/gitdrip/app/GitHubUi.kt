@file:OptIn(ExperimentalLayoutApi::class)

package com.gitdrip.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitdrip.app.data.GhRepo
import com.gitdrip.app.data.filterRepos
import com.gitdrip.app.ui.*

/** Setup screen: asks the engine (Termux) who the stored token belongs to. The token itself never reaches the app. */
@Composable
fun GitHubAccountCard(vm: MainViewModel) {
    val acc by vm.ghAccount.collectAsStateWithLifecycle()
    val msg by vm.ghMsg.collectAsStateWithLifecycle()
    val busy by vm.ghBusy.collectAsStateWithLifecycle()
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
            SectionTitle("GitHub account")
            Text("The token stays in Termux. GitDrip only asks the engine whom it belongs to.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            acc?.let {
                StateBadge("SUCCESS")
                Text(ScreenLogic.accountLine(it.login, it.tokenKind), style = MaterialTheme.typography.titleSmall)
                Text("Commit author email: ${it.noreplyEmail}", style = MaterialTheme.typography.bodySmall)
                if (!it.userMatches) Text("Stored user name differs from the token owner. Re-run: echo TOKEN | gitdrip auth set --user ${it.login}",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            msg?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            GlassButton(if (busy) "Checking…" else "Check token", { vm.ghValidate() }, enabled = !busy, style = GlassButtonStyle.Secondary)
        }
    }
    GlassCard(Modifier.fillMaxWidth()) { TokenSection(vm) }
}

private fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }

/** Blocks screenshots / recents thumbnails only while [enabled] (token field non-empty). */
@Composable
private fun SecureWindow(enabled: Boolean) {
    val w = LocalContext.current.findActivity()?.window
    DisposableEffect(w, enabled) {
        if (enabled) w?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Paste a token once: encrypted file + one-time key -> Termux credentials. Optional Keystore copy for re-sending after a Termux reinstall. */
@Composable
private fun TokenSection(vm: MainViewModel) {
    val msg by vm.tokMsg.collectAsStateWithLifecycle()
    val busy by vm.tokBusy.collectAsStateWithLifecycle()
    val saved by vm.vaultHas.collectAsStateWithLifecycle()
    var user by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    SecureWindow(enabled = token.isNotEmpty())
    var keep by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
        SectionTitle("Send token to Termux")
        Text("Encrypted before it leaves the app; Termux stores it chmod 600. Fine-grained token: Contents read/write on the target repo only.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassTextField(user, { user = it.trim() }, "GitHub user name")
        GlassTextField(token, { token = it.trim() }, "Token", visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false))
        GlassSwitchRow("Keep an encrypted copy on this device (Keystore)", keep, { keep = it })
        msg?.let {
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
                color = if (ScreenLogic.tokenMsgOk(it)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S.dp), verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
            GlassButton(if (busy) "Sending…" else "Send", { vm.sendToken(user, token, keep); token = "" }, enabled = !busy && user.isNotEmpty() && token.isNotEmpty())
            if (saved) {
                GlassButton("Re-send saved", { vm.resendSavedToken(user) }, enabled = !busy && user.isNotEmpty(), style = GlassButtonStyle.Secondary)
                GlassButton("Forget saved", { vm.forgetSavedToken() }, enabled = !busy, style = GlassButtonStyle.Text)
            }
        }
    }
}

/** Two steps: pick a repo from the account, then a branch. Calls [onPick] with the https URL and branch. */
@Composable
fun RepoPickerDialog(vm: MainViewModel, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val repos by vm.ghRepos.collectAsStateWithLifecycle()
    val br by vm.ghBranches.collectAsStateWithLifecycle()
    val msg by vm.ghMsg.collectAsStateWithLifecycle()
    val busy by vm.ghBusy.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<GhRepo?>(null) }
    LaunchedEffect(Unit) { vm.ghLoadRepos() }
    GlassDialog(onDismiss, picked?.fullName ?: "Your GitHub repositories", "Close", onDismiss, dismissText = null) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
            if (busy) GlassProgress("Loading repositories")
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val p = picked
            if (p == null) {
                GlassTextField(q, { q = it }, "Search")
                GlassSwitchRow("Show forks / read-only", all, { all = it })
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                    items(filterRepos(repos.orEmpty(), q, !all)) { r ->
                        GlassCard(Modifier.fillMaxWidth(), onClick = { picked = r; vm.ghLoadBranches(r.fullName) }, onClickLabel = "Choose ${r.fullName}") {
                            Text(r.fullName, style = MaterialTheme.typography.titleSmall)
                            Text(ScreenLogic.repoMeta(r.isPrivate, r.fork, r.archived, r.push), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                Text("Only the default branch counts toward your graph. Private repo? Enable \"Private contributions\" on GitHub.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                    items(br?.names.orEmpty()) { n ->
                        GlassButton(ScreenLogic.branchLabel(n, br?.defaultBranch), { onPick(p.url, n) }, Modifier.fillMaxWidth(), style = GlassButtonStyle.Secondary)
                    }
                }
                GlassButton("Back", { picked = null }, style = GlassButtonStyle.Text)
            }
        }
    }
}
