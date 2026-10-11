@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gitdrip.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitdrip.app.data.*
import com.gitdrip.app.ui.*

@Composable
fun ScheduleTab(vm: MainViewModel, p: ProjectEntity) {
    val ctx = LocalContext.current
    val list by vm.schedules(p.id).collectAsStateWithLifecycle(emptyList())
    val msg by vm.schedMsg.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ScheduleEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val fixedEnabled = list.count { it.enabled }
    val mode = dripMode(fixedEnabled, p.autoDrip)
    val cap by vm.dailyCap.collectAsStateWithLifecycle()
    var exact by remember { mutableStateOf(Scheduler.canExact(ctx)) }
    LaunchedEffect(list) { exact = Scheduler.canExact(ctx) }
    val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.Screen.dp, end = Space.Screen.dp, top = Space.S.dp, bottom = Space.Xl.dp),
        verticalArrangement = Arrangement.spacedBy(Space.S.dp),
    ) {
        if (!exact) item {
            NoticeCard(TabLogic.EXACT_ALARM_NOTICE, actionLabel = "Allow exact alarms") {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S.dp), verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                GlassButton("Add time", { adding = true }, icon = Icons.Default.Add)
                GlassButton("Battery", { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, style = GlassButtonStyle.Secondary)
            }
        }
        item { ModeCard(mode, fixedEnabled) }
        item { AutoDripCard(vm, p, mode, cap) }
        item { Text(TabLogic.scheduleHelp(CapPrefs.get(ctx)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        msg?.let { m ->
            item {
                Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }, horizontalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(m, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (list.isEmpty()) item { EmptyState("No schedules", "Each slot runs the next pending batch.") }
        items(list, key = { it.id }) { s ->
            val at = nextFire(System.currentTimeMillis(), s.time, zoneOf(s.zone), s.days)
            GlassCard(Modifier.fillMaxWidth()) {
                // Only the text area is the edit button, so the Switch / Delete below stay separate TalkBack nodes.
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(onClickLabel = "Edit schedule ${s.time}", role = Role.Button) { editing = s },
                    verticalArrangement = Arrangement.spacedBy(Space.Xs.dp),
                ) {
                    Text(TabLogic.scheduleTitle(s.time, daysLabel(s.days)), style = MaterialTheme.typography.titleMedium)
                    Text(TabLogic.scheduleSubtitle(s.zone, s.policy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(TabLogic.nextLine(s.enabled, at?.let { fmt.format(it) }), style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    LabeledSwitch("Schedule ${s.time} enabled", s.enabled, { vm.setEnabled(s, it) }, Modifier.weight(1f))
                    GlassIconButton(Icons.Default.Delete, "Delete schedule ${s.time}", { vm.deleteSchedule(s) })
                }
            }
        }
    }
    if (adding || editing != null) ScheduleDialog(editing, onDismiss = { adding = false; editing = null }) { time, zone, days, policy ->
        vm.saveSchedule(p, editing, time, zone, days, policy); adding = false; editing = null
    }
}

/** Read-only chip: which mode drives this project and why. */
@Composable
private fun ModeCard(mode: DripMode, fixedEnabled: Int) = GlassCard(Modifier.fillMaxWidth()) {
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.Xs.dp)) {
        Text("Mode: ${TabLogic.modeChip(mode)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
        Text(TabLogic.modeWhy(mode, fixedEnabled), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** FX5: Auto Drip switch, commits/day stepper, active window, and today's planned slots with Re-roll. */
@Composable
private fun AutoDripCard(vm: MainViewModel, p: ProjectEntity, mode: DripMode, cap: Int) {
    val fixed = mode == DripMode.Fixed
    var perDay by remember(p.id, p.autoPerDay) { mutableIntStateOf(TabLogic.clampPerDay(p.autoPerDay, cap)) }
    var start by remember(p.id, p.autoStart) { mutableStateOf(p.autoStart) }
    var end by remember(p.id, p.autoEnd) { mutableStateOf(p.autoEnd) }
    val winErr = autoWindowError(start, end)
    val dirty = perDay != p.autoPerDay || start != p.autoStart || end != p.autoEnd
    val autoMsg by vm.autoMsg.collectAsStateWithLifecycle()
    val slots by vm.autoSlotsToday(p.id).collectAsStateWithLifecycle(emptyList())
    val timeFmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    val zone = java.time.ZoneId.systemDefault()
    val windowStartMs = remember(p.autoStart) { slotMs(java.time.LocalDate.now(zone), p.autoStart, zone) }
    val windowEndMs = remember(p.autoEnd) { slotMs(java.time.LocalDate.now(zone), p.autoEnd, zone) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); nowMs = System.currentTimeMillis() } }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
            SectionTitle(TabLogic.AUTO_TITLE)
            if (fixed) Text(TabLogic.AUTO_PAUSED, style = MaterialTheme.typography.bodyMedium)
            LabeledSwitch(TabLogic.AUTO_SWITCH, p.autoDrip, { on -> vm.saveAuto(p, on, perDay, if (winErr == null) start else p.autoStart, if (winErr == null) end else p.autoEnd) }, enabled = !fixed)
            if (!fixed) {
                Text(TabLogic.AUTO_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M.dp)) {
                    GlassIconButton(Icons.Default.KeyboardArrowDown, "Fewer commits per day", { perDay = TabLogic.clampPerDay(perDay - 1, cap) }, enabled = perDay > 1)
                    Text(TabLogic.perDayLabel(perDay), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    GlassIconButton(Icons.Default.KeyboardArrowUp, "More commits per day", { perDay = TabLogic.clampPerDay(perDay + 1, cap) }, enabled = perDay < cap)
                }
                TabLogic.perDayNote(p.autoPerDay, cap)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                    GlassTextField(start, { start = it.trim() }, "From HH:MM", Modifier.weight(1f), keyboardOptions = AsciiKeys)
                    GlassTextField(end, { end = it.trim() }, "Until HH:MM", Modifier.weight(1f), keyboardOptions = AsciiKeys)
                }
                (winErr ?: autoMsg)?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (dirty) GlassButton("Save Auto Drip", { vm.saveAuto(p, p.autoDrip, perDay, start, end) }, enabled = winErr == null, style = GlassButtonStyle.Secondary)
            }
            if (mode == DripMode.Auto || slots.isNotEmpty()) {
                SectionTitle(TabLogic.PLAN_TITLE)
                SlotTimeline(slots.map { it.atMs to it.state }, windowStartMs, windowEndMs, nowMs, { timeFmt.format(it) })
                if (slots.isEmpty()) Text(TabLogic.PLAN_EMPTY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                slots.forEach { s ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                        Text(TabLogic.slotLine(timeFmt.format(s.atMs), s.state, s.reason), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        StateBadge(TabLogic.slotState(s.state))
                    }
                }
                if (mode == DripMode.Auto && TabLogic.hasPlanned(slots.map { it.state })) {
                    GlassButton(TabLogic.REROLL, { vm.rerollAuto(p) }, style = GlassButtonStyle.Secondary)
                    Text(TabLogic.REROLL_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val AsciiKeys = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false, keyboardType = KeyboardType.Ascii)

@Composable
private fun ScheduleDialog(s: ScheduleEntity?, onDismiss: () -> Unit, onSave: (String, String, Int, String) -> Unit) {
    var time by remember(s) { mutableStateOf(s?.time ?: "09:00") }
    var zone by remember(s) { mutableStateOf(s?.zone ?: "") }
    var days by remember(s) { mutableIntStateOf(s?.days ?: ALL_DAYS) }
    var policy by remember(s) { mutableStateOf(s?.policy ?: TabLogic.POLICY_SKIP) }
    val err = TabLogic.scheduleError(validTime(time), validZone(zone.trim()), days)
    GlassDialog(
        onDismiss = onDismiss, title = if (s == null) "New schedule" else "Edit schedule", confirmText = "Save",
        onConfirm = { onSave(time, zone.trim(), days, policy) }, confirmEnabled = err == null,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
            GlassTextField(time, { time = it.trim() }, "Time HH:MM (24h)", keyboardOptions = AsciiKeys)
            GlassTextField(zone, { zone = it }, "Time zone (empty = device)", keyboardOptions = AsciiKeys)
            Text("Days", style = MaterialTheme.typography.labelMedium, modifier = Modifier.asHeading())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S.dp), verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                TabLogic.DAY_NAMES.forEachIndexed { i, n -> GlassChip(n, TabLogic.dayOn(days, i), { days = TabLogic.toggleDay(days, i) }) }
            }
            Text("If a slot is missed", style = MaterialTheme.typography.labelMedium, modifier = Modifier.asHeading())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S.dp), verticalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                TabLogic.POLICIES.forEach { o -> GlassChip(TabLogic.policyLabel(o), policy == o, { policy = o }) }
            }
            err?.let {
                Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }, horizontalArrangement = Arrangement.spacedBy(Space.S.dp)) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
