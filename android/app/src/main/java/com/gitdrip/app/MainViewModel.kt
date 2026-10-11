package com.gitdrip.app

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.room.withTransaction
import com.gitdrip.app.data.*
import com.gitdrip.app.ui.TabLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as GitDripApp).db
    private fun <T> kotlinx.coroutines.flow.Flow<T>.hot(init: T) =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)

    private val dayStart: Long get() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val projects = db.projects().all().hot(emptyList())
    val activeSchedules = db.stats().activeSchedules().hot(0)
    /** Re-emits the day start at every local midnight so "failed today" does not go stale (F4). */
    private val dayTick = flow { while (true) { emit(dayStart); delay(Calendar.getInstance().let { c -> c.add(Calendar.DAY_OF_YEAR, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 1); c.timeInMillis } - System.currentTimeMillis()) } }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val failedToday = dayTick.flatMapLatest { db.stats().runs("FAILED", it) }.hot(0)

    fun project(id: Long) = db.projects().byId(id)
    fun pending(id: Long) = db.stats().pendingBatches(id)

    /** Returns an error message or null on success. */
    suspend fun create(name: String, repo: String, branch: String): String? {
        val n = name.trim(); val r = repo.trim(); val b = branch.trim().ifEmpty { "main" }
        validateProject(n, r, b)?.let { return it }
        return try {
            db.projects().insert(ProjectEntity(name = n, repoUrl = r, branch = b)); null
        } catch (e: SQLiteConstraintException) { "A project named '$n' already exists" }
    }

    fun delete(p: ProjectEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.schedules().forProjectNow(p.id).forEach { Scheduler.cancel(ctx, it.id) }   // alarms are not FK-cascaded
            Scheduler.cancelAuto(ctx, p.id)
            db.projects().delete(p)
        }
    }

    // ---- P9: import + batch manager ----
    private val bd = db.batches()
    val status = MutableStateFlow<String?>(null)
    fun files(id: Long) = bd.files(id)
    fun batches(id: Long) = bd.batches(id)
    val sharedAccess get() = Importer.sharedAccess(ctx)

    private fun work(block: suspend () -> String?) {
        viewModelScope.launch(Dispatchers.IO) {
            status.value = try { block() } catch (e: Exception) { "Failed: ${e.message}" }
        }
    }

    private suspend fun requireEditable(p: Long) {
        check(bd.started(p) == 0) { "Batches already started; editing refused" }
        check(ex.open(p).isEmpty()) { "A run is in progress; try again in a moment" }
    }

    fun importFrom(p: ProjectEntity, kind: Importer.Kind, uris: List<Uri>) = work {
        status.value = "Importing…"
        requireEditable(p.id)
        val r = Importer.run(getApplication(), p.name, kind, uris)
        db.withTransaction {
            bd.clearBatches(p.id); bd.clearFiles(p.id)
            bd.insertFiles(r.files.map { FileEntity(projectId = p.id, path = it.path, size = it.size, sha256 = it.sha256) })
        }
        "Imported ${r.files.size} files" +
            if (r.excluded.isEmpty()) "" else "; excluded ${r.excluded.size} secret file(s): ${r.excluded.take(3).joinToString()}"
    }

    fun autoBatches(p: ProjectEntity) = work {
        requireEditable(p.id)
        val files = bd.filesNow(p.id)
        check(files.isNotEmpty()) { "Import files first" }
        val sug = suggestBatches(files.map { FileEntry(it.path, it.size, it.sha256) })
        db.withTransaction {
            bd.clearBatches(p.id)
            sug.forEachIndexed { i, b ->
                val id = bd.insertBatch(BatchEntity(projectId = p.id, seq = i + 1, message = b.message))
                bd.assignPaths(p.id, b.paths, id)
            }
        }
        "Created ${sug.size} batches"
    }

    fun addBatch(p: ProjectEntity, message: String) = work {
        requireEditable(p.id)
        val m = message.trim(); check(m.isNotEmpty()) { "Message required" }
        bd.insertBatch(BatchEntity(projectId = p.id, seq = bd.maxSeq(p.id) + 1, message = m)); null
    }

    fun assign(p: ProjectEntity, file: Long, batch: Long?) = work { requireEditable(p.id); bd.assign(file, batch); null }
    fun rename(p: ProjectEntity, id: Long, message: String) = work {
        requireEditable(p.id); check(message.isNotBlank()) { "Message required" }; bd.rename(id, message.trim()); null
    }
    fun deleteBatch(p: ProjectEntity, id: Long) = work { requireEditable(p.id); bd.deleteBatch(id); null }

    /** Moves a batch up (-1) or down (+1) and rewrites seq 1..n. */
    fun moveBatch(p: ProjectEntity, id: Long, delta: Int) = work {
        requireEditable(p.id)
        val list = bd.batchesNow(p.id)
        val i = list.indexOfFirst { it.id == id }
        db.withTransaction { list.moved(i, delta).forEachIndexed { n, b -> bd.setSeq(b.id, n + 1) } }
        null
    }

    // ---- P10: Termux bridge ----
    private val ex = db.executions()
    val bridge = MutableStateFlow<String?>(null)
    val doctor = MutableStateFlow<List<Pair<String, Boolean>>?>(null)
    val doctorMsg = MutableStateFlow<String?>(null)
    fun recentRuns(id: Long) = ex.recent(id)
    private val ctx get() = getApplication<Application>()

    private fun bridgeReady(): String? = when {
        !TermuxBridge.installed(ctx) -> "Termux is not installed (use the F-Droid / GitHub build)"
        !TermuxBridge.permitted(ctx) -> "Grant Termux 'Run commands' permission on the Setup screen"
        !Importer.sharedAccess(ctx) -> "Grant All-files access so Termux can read GitDrip files (Setup screen)"
        else -> null
    }

    /** Polls results/<req>.json until a final state (or timeout). Calls [onUpdate] for every new version. */
    private suspend fun poll(req: String, timeoutMs: Long, onUpdate: suspend (String, BridgeResult) -> Unit): Pair<String, BridgeResult>? {
        val end = System.currentTimeMillis() + timeoutMs; var last: String? = null
        while (System.currentTimeMillis() < end) {
            val raw = TermuxBridge.readResult(ctx, req)
            if (raw != null && raw != last) {
                last = raw
                parseResult(raw)?.let { onUpdate(raw, it); if (it.isFinal) return raw to it }
            }
            delay(1000)
        }
        return null
    }

    private fun summary(r: BridgeResult) = when (r.state) {
        "SUCCESS" -> if (r.prUrl.isNotEmpty()) "Pull request #${r.pr ?: "?"} ready: ${r.prUrl}" else "Committed + pushed ${r.commit.take(7)} (${r.filesChanged} files)"
        "SKIPPED" -> "Skipped: ${r.reason.ifEmpty { "nothing to do" }}"
        "PENDING_RETRY" -> "Offline/transient error: Termux will retry (${r.reason})"
        "PENDING" -> r.reason.ifEmpty { "Waiting" }
        else -> "${r.state}: ${r.reason}"
    }

    /** Runs [batch] (or the next pending one when null) through Termux: writes plan.json, sends the intent, polls the result file. */
    fun runBatch(p: ProjectEntity, batch: BatchEntity?, pr: PrMode? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            running.value = true   // drives the glow ring on the Run button
            bridge.value = try { runInner(p, batch, pr) } catch (e: Exception) { "Failed: ${e.message}" } finally { running.value = false }
        }
    }
    val running = MutableStateFlow(false)

    private val capWarnedAt = HashMap<Long, Long>()

    private suspend fun runInner(p: ProjectEntity, batch: BatchEntity?, pr: PrMode?): String {
        bridgeReady()?.let { return it }
        if (p.paused) return "Project is paused"
        if (ex.open(p.id).isNotEmpty()) return "A run is already in progress"
        val used = Scheduler.usedToday(ctx); val cap = CapPrefs.get(ctx); val nowMs = System.currentTimeMillis()
        if (manualNeedsConfirm(used, cap, capWarnedAt[p.id], nowMs)) { capWarnedAt[p.id] = nowMs; return capWarning(used, cap) }
        val files = bd.filesNow(p.id)
        val plan = bd.batchesNow(p.id).map { b -> PlanBatch(b.seq, b.message, files.filter { it.batchId == b.id }.map { it.path }) }
        val rk = plan.filter { it.files.isNotEmpty() }.sortedBy { it.seq }
        if (rk.isEmpty()) return "Nothing to run: create batches and assign files first"
        val target = if (batch == null) null else rk.indexOfFirst { it.seq == batch.seq }.let { if (it < 0) return "That batch has no files" else it + 1 }
        TermuxBridge.writePlan(ctx, p.name, buildPlan(p.name, p.repoUrl, p.branch, plan))
        val req = newRequestId()
        ex.insert(ExecutionEntity(projectId = p.id, batchId = batch?.id, taskId = "", requestId = req, state = "PENDING", startedAt = System.currentTimeMillis()))
        val args = runArgs(req, p.name, target, pr)
        TermuxBridge.send(ctx, args)?.let { err ->
            ex.byRequest(req)?.let { ex.update(it.copy(state = "FAILED", reason = err, finishedAt = System.currentTimeMillis())) }
            return err
        }
        bridge.value = "Sent to Termux…"
        val done = poll(req, 300_000) { _, r -> RunRecorder.applyResult(ctx, req, r); bridge.value = summary(r) }
        return done?.let { summary(it.second) } ?: "No answer from Termux yet. Check Setup (allow-external-apps), or refresh later."
    }

    // ---- FX3: daily limit (app preference, mirrored to the engine over the bridge) ----
    val dailyCap = MutableStateFlow(CapPrefs.get(getApplication<Application>()))
    val capMsg = MutableStateFlow<String?>(null)

    fun setDailyCap(n: Int) {
        val v = Limits.clampCap(n); CapPrefs.set(ctx, v); dailyCap.value = v
        pushCapToEngine(v)
    }

    /** `bridge config-set max_commits_per_day N`; also re-sent from Setup "Test connection" so a reinstalled engine gets the value. */
    fun pushCapToEngine(n: Int = dailyCap.value, quiet: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = bridgeReady() ?: run {
                val req = newRequestId()
                TermuxBridge.send(ctx, listOf("bridge", req, "config-set", "max_commits_per_day", n.toString()))
                    ?: poll(req, 20_000) { _, _ -> }.let { done ->
                        TermuxBridge.resultFile(ctx, req).delete()
                        done?.second?.let { r -> if (r.state == "OK") "Engine limit set to $n/day" else "Engine refused: ${redact(r.reason)}" }
                            ?: "No answer from Termux (needs engine 0.23.0); the app limit is saved"
                    }
            }
            if (!quiet || msg.startsWith("Engine refused")) capMsg.value = msg
        }
    }

    // ---- P7.9: watch dir (one scan pass / cron tick mode over the bridge; result file is not a task, nothing goes into Room) ----
    val watchMsg = MutableStateFlow<String?>(null)
    val watchBusy = MutableStateFlow(false)

    /** [commit] = also push one queued batch; [tick] = also change the cron tick mode. Needs the watch dir set in Termux first. */
    fun watchScan(p: ProjectEntity, commit: Boolean, tick: WatchTick? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (watchBusy.value) return@launch
            watchBusy.value = true
            try {
                watchMsg.value = bridgeReady() ?: run {
                    if (p.paused) return@run "Project is paused"
                    val req = newRequestId()
                    TermuxBridge.send(ctx, watchArgs(req, p.name, commit, tick))
                        ?: poll(req, if (commit) 120_000 else 60_000) { _, _ -> }.let { done ->
                            TermuxBridge.resultFile(ctx, req).delete()
                            done?.second?.let { TabLogic.watchSummary(it.state, redact(it.reason)) } ?: "No answer from Termux (needs engine 0.20.0)"
                        }
                }
            } finally { watchBusy.value = false }
        }
    }

    /** Picks up results that arrived while the app was closed (open executions only). */
    fun reconcile(p: ProjectEntity) { viewModelScope.launch(Dispatchers.IO) { RunRecorder.reconcileProject(ctx, p.id) } }

    /** Setup screen: asks the engine for its dependency report. */
    fun checkEngine() {
        viewModelScope.launch(Dispatchers.IO) {
            doctor.value = null
            bridgeReady()?.let { doctorMsg.value = it; return@launch }
            val req = newRequestId(); doctorMsg.value = "Asking Termux…"
            TermuxBridge.send(ctx, listOf("bridge", req, "doctor"))?.let { doctorMsg.value = it; return@launch }
            val done = poll(req, 20_000) { _, _ -> }
            val list = done?.let { parseDoctor(it.first) }
            doctor.value = list
            if (list != null) pushCapToEngine(quiet = true)
            doctorMsg.value = if (list == null) "No answer. Run the setup command in Termux first (engine, allow-external-apps, storage)." else null
        }
    }

    // ---- P13: GitHub (through the Termux bridge: the token never leaves Termux) ----
    val ghAccount = MutableStateFlow<GhAccount?>(null)
    val ghRepos = MutableStateFlow<List<GhRepo>?>(null)
    val ghBranches = MutableStateFlow<GhBranches?>(null)
    val ghMsg = MutableStateFlow<String?>(null)
    val ghBusy = MutableStateFlow(false)

    /** Sends `bridge <req> <args>`, waits for results/<req>.json, returns the parsed data or sets [ghMsg]. */
    private suspend fun <T> ghCall(vararg args: String, parse: (String) -> GhResult<T>?): T? {
        ghMsg.value = null; ghBusy.value = true
        try {
            bridgeReady()?.let { ghMsg.value = it; return null }
            val req = newRequestId()
            TermuxBridge.send(ctx, listOf("bridge", req) + args)?.let { ghMsg.value = it; return null }
            val raw = poll(req, 30_000) { _, _ -> }?.first
            TermuxBridge.resultFile(ctx, req).delete()
            val r = raw?.let(parse)
            if (r == null) { ghMsg.value = "No answer from Termux. Update the engine to 0.14.0 and check the Setup screen."; return null }
            if (r.data == null) ghMsg.value = r.error + " · " + ghHint(r.errorClass)
            return r.data
        } finally { ghBusy.value = false }
    }

    fun ghValidate() { viewModelScope.launch(Dispatchers.IO) { ghAccount.value = ghCall("gh-validate", parse = ::parseAccount) } }
    fun ghLoadRepos() { viewModelScope.launch(Dispatchers.IO) { ghRepos.value = ghCall("gh-repos", parse = ::parseRepos) } }
    fun ghLoadBranches(fullName: String) {
        ghBranches.value = null
        viewModelScope.launch(Dispatchers.IO) { ghBranches.value = ghCall("gh-branches", fullName, parse = ::parseBranches) }
    }

    // ---- P14: token handoff (Keystore vault -> one-time encrypted file + key via Intent -> Termux credentials) ----
    val tokMsg = MutableStateFlow<String?>(null)
    val tokBusy = MutableStateFlow(false)
    val vaultHas = MutableStateFlow(Vault.has(ctx))

    /** Encrypts [token], hands it to Termux, waits for the engine's OK, then verifies it with GET /user. Never logs/stores it unless [remember]. */
    fun sendToken(user: String, token: String, remember: Boolean) {
        val t = token.trim(); val u = user.trim()
        when {
            !Handoff.validUser(u) -> { tokMsg.value = "GitHub user name looks invalid"; return }
            !Handoff.validToken(t) -> { tokMsg.value = "Token format not recognised (ghp_/gho_/ghu_/ghs_/ghr_ or github_pat_)"; return }
        }
        viewModelScope.launch(Dispatchers.IO) {
            tokMsg.value = null; tokBusy.value = true
            val req = newRequestId(); val f = Handoff.handoffFile(ctx, req)
            try {
                bridgeReady()?.let { tokMsg.value = it; return@launch }
                if (remember) try { Vault.save(ctx, t); vaultHas.value = true } catch (e: Exception) { tokMsg.value = "Could not save to Keystore (continuing without)" }
                val key = Handoff.newKeyHex()
                f.parentFile?.mkdirs(); f.writeBytes(Handoff.encrypt(t, key))
                TermuxBridge.send(ctx, listOf("bridge", req, "auth-handoff", u, key))?.let { tokMsg.value = it; return@launch }
                val r = poll(req, 30_000) { _, _ -> }?.second
                tokMsg.value = when {
                    r == null -> "No answer from Termux. Update the engine to 0.14.0 and install openssl-tool (Setup screen)."
                    r.state == "OK" -> "Token stored in Termux (chmod 600)."
                    else -> "Termux refused the token: " + redact(r.reason)
                }
                if (r?.state == "OK") ghAccount.value = ghCall("gh-validate", parse = ::parseAccount)
            } finally {
                f.delete(); TermuxBridge.resultFile(ctx, req).delete(); tokBusy.value = false   // engine also deletes the .enc
            }
        }
    }

    fun resendSavedToken(user: String) {
        val t = Vault.load(ctx)
        if (t == null) { tokMsg.value = "Saved token unreadable. Enter it again."; return }
        sendToken(user, t, remember = false)
    }

    fun forgetSavedToken() { Vault.clear(ctx); vaultHas.value = false; tokMsg.value = "Saved token removed from this device." }

    // ---- P11: schedules ----
    private val sd = db.schedules()
    val schedMsg = MutableStateFlow<String?>(null)
    fun schedules(id: Long) = sd.forProject(id)

    fun saveSchedule(p: ProjectEntity, old: ScheduleEntity?, time: String, zone: String, days: Int, policy: String) {
        viewModelScope.launch(Dispatchers.IO) {
            schedMsg.value = when {
                !validTime(time) -> "Invalid time"
                !validZone(zone) -> "Invalid zone"
                days == 0 || days !in 1..ALL_DAYS -> "Pick at least one day"
                policy !in setOf("skip", "run-now") -> "Invalid policy"
                old == null && sd.forProjectNow(p.id).any { it.time == time } -> "Schedule at $time already exists"
                else -> null
            }
            if (schedMsg.value != null) return@launch
            // moving a slot or changing its days gives it a fresh day: it may fire again today (S5)
            val s = old?.copy(time = time, zone = zone, days = days, policy = policy, lastFireDay = if (old.time != time || old.days != days) "" else old.lastFireDay)
                ?: ScheduleEntity(projectId = p.id, time = time, zone = zone, days = days, policy = policy)
            val id = if (old == null) sd.insert(s) else { sd.update(s); s.id }
            Scheduler.arm(ctx, s.copy(id = id))
            Scheduler.syncAuto(ctx)   // fixed times <-> Auto Drip mode may have changed
        }
    }

    fun setEnabled(s: ScheduleEntity, on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { val n = s.copy(enabled = on, lastFireDay = if (on) "" else s.lastFireDay); sd.update(n); Scheduler.arm(ctx, n); Scheduler.syncAuto(ctx) }
    }

    fun deleteSchedule(s: ScheduleEntity) {
        viewModelScope.launch(Dispatchers.IO) { Scheduler.cancel(ctx, s.id); sd.delete(s); Scheduler.syncAuto(ctx) }
    }
    // ---- FX4/FX5: Auto Drip settings ----
    val autoMsg = MutableStateFlow<String?>(null)

    /** Saves the project's Auto Drip settings and re-plans. Returns nothing; errors land in [autoMsg]. */
    fun saveAuto(p: ProjectEntity, on: Boolean, perDay: Int, start: String, end: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val err = autoWindowError(start, end)
            if (err != null) { autoMsg.value = err; return@launch }
            autoMsg.value = null
            val n = perDay.coerceIn(1, Limits.clampCap(CapPrefs.get(ctx)))
            val windowChanged = p.autoStart != start || p.autoEnd != end || p.autoPerDay != n
            db.projects().setAuto(p.id, on, n, start, end)
            if (windowChanged || !on) db.autoSlots().deletePlanned(p.id)   // new window / count -> fresh plan for the rest of today
            Scheduler.syncAuto(ctx)
        }
    }

    fun rerollAuto(p: ProjectEntity) { viewModelScope.launch(Dispatchers.IO) { Scheduler.reroll(ctx, p.id) } }
    fun autoSlotsToday(id: Long) = db.autoSlots().forDay(id, java.time.LocalDate.now().toString())
    // ---- P12: history, run detail, dashboard stats, sync from Termux ----
    private val zone: ZoneId get() = ZoneId.systemDefault()
    val syncing = MutableStateFlow(false)
    val syncMsg = MutableStateFlow<String?>(null)
    val latestRuns = ex.latest(8).hot(emptyList())
    val retrying = ex.retrying().hot(0)
    /** Commits are counted from successful pushes (SUCCESS + commit hash). Recomputed on every DB change, not at midnight. */
    val stats = ex.commitTimes(System.currentTimeMillis() - 400L * 86_400_000)
        .map { ts -> commitStats(ts.map { dayKey(it, zone) }, LocalDate.now(zone)) }.hot(CommitStats(0, 0, 0))
    /** Earliest upcoming alarm across enabled schedules of unpaused projects: (project name, epoch ms). */
    val nextRun = combine(sd.enabledFlow(), projects, db.autoSlots().plannedFlow()) { ss, ps, slots ->
        val names = ps.filter { !it.paused }.associate { it.id to it.name }
        val now = System.currentTimeMillis()
        val fixed = ss.mapNotNull { s -> names[s.projectId]?.let { n -> nextFire(now, s.time, zoneOf(s.zone), s.days)?.let { n to it } } }
        val auto = slots.filter { it.atMs > now }.mapNotNull { sl -> names[sl.projectId]?.let { TabLogic.autoName(it) to sl.atMs } }
        (fixed + auto).minByOrNull { it.second }
    }.hot(null)

    fun execution(id: Long) = ex.byId(id)
    suspend fun batchById(id: Long) = bd.batchById(id)

    /** Pulls tasks from Termux (`bridge history`) and merges them into Room. [p] = null syncs every project. */
    fun sync(p: ProjectEntity? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!syncing.compareAndSet(false, true)) return@launch
            try {
                bridgeReady()?.let { syncMsg.value = it; return@launch }
                TermuxBridge.pruneResults(ctx)
                var n = 0
                for (pr in p?.let { listOf(it) } ?: db.projects().allNow()) {
                    RunRecorder.reconcileProject(ctx, pr.id)
                    n += syncOne(pr) ?: run { syncMsg.value = "No answer from Termux for '${pr.name}' (engine updated? jq installed?)"; return@launch }
                }
                syncMsg.value = if (n == 0) "Up to date" else "Synced: $n run(s) updated"
            } catch (e: Exception) { syncMsg.value = "Sync failed: ${e.message}" } finally { syncing.value = false }
        }
    }

    private suspend fun syncOne(p: ProjectEntity): Int? {
        val req = newRequestId()
        TermuxBridge.send(ctx, listOf("bridge", req, "history", p.name))?.let { error(it) }
        val raw = poll(req, 20_000) { _, _ -> }?.first
        TermuxBridge.resultFile(ctx, req).delete()
        return raw?.let(::parseHistory)?.let { mergeHistory(p, it) }
    }

    /** P7.10: batches `gitdrip watch` appended to the engine's batches.json become Room batches (origin "watch"), so the Batches tab and rank mapping match the engine. */
    private suspend fun importWatchBatches(p: ProjectEntity, h: HistoryResult): Int {
        val rk = RunRecorder.ranked(db, p.id)
        val add = contiguousFrom(rk.size, watchBatchesToImport(rk.size, h.batches.filter { it.origin == "watch" }.map { WatchBatch(it.id, it.message, it.files, it.status, it.commit) }))
        if (add.isEmpty()) return 0
        val known = bd.filesNow(p.id).map { it.path }.toMutableSet()
        var seq = bd.maxSeq(p.id)
        for (w in add) {
            seq += 1
            val id = bd.insertBatch(BatchEntity(projectId = p.id, seq = seq, message = w.message, status = w.status.ifEmpty { "PENDING" }, commitHash = w.commit.ifEmpty { null }, origin = "watch"))
            val fresh = newWatchPaths(known, w)
            if (fresh.isNotEmpty()) { bd.insertFiles(fresh.map { FileEntity(projectId = p.id, path = it, size = 0, sha256 = "", batchId = id) }); known += fresh }
        }
        return add.size
    }

    private suspend fun mergeHistory(p: ProjectEntity, h: HistoryResult): Int {
        var changed = 0
        val notes = mutableListOf<Triple<Long, String?, ExecutionEntity>>()   // (row id, previous state, new row)
        db.withTransaction {
            changed += importWatchBatches(p, h)
            val rk = RunRecorder.ranked(db, p.id)
            h.batches.forEach { s ->
                rk.getOrNull(s.id - 1)?.let { b -> if (b.status != s.status || b.commitHash.orEmpty() != s.commit) bd.setStatus(b.id, s.status, s.commit.ifEmpty { null }) }
            }
            for (t in h.tasks) {
                val created = isoMs(t.createdAt)
                val old = ex.byTask(t.id) ?: ex.unbound(p.id).firstOrNull { created != null && kotlin.math.abs(it.startedAt - created) < 120_000 }
                val started = old?.startedAt ?: isoMs(t.startedAt) ?: created ?: System.currentTimeMillis()
                val row = (old ?: ExecutionEntity(projectId = p.id, taskId = t.id, state = t.state, startedAt = started)).copy(
                    taskId = t.id, state = t.state, commitHash = t.commit.ifEmpty { null }, reason = redact(t.reason).ifEmpty { null },
                    batchId = rk.getOrNull((t.batchId ?: 0) - 1)?.id ?: old?.batchId,
                    output = redact(t.output), filesChanged = t.filesChanged, attempt = t.attempt, errorClass = t.errorClass, nextRetryAt = t.nextRetryAt,
                    finishedAt = isoMs(t.finishedAt) ?: if (isTerminal(t.state)) old?.finishedAt ?: started else null,
                )
                if (old == null) { val nid = ex.insert(row); changed++; notes += Triple(nid, null, row) }
                else if (row != old) { ex.update(row); changed++; notes += Triple(old.id, old.state, row) }
            }
        }
        notes.take(3).forEach { (id, prev, row) -> RunRecorder.notifyChange(ctx, id, p.id, prev, row.state, row.reason, row.commitHash, row.filesChanged, row.finishedAt) }
        return changed
    }

    // ---- P16b: settings, backup / restore (engine does the work; files live in <GitDrip>/backups) ----
    val backups = MutableStateFlow<List<BackupFile>>(emptyList())
    val backupMsg = MutableStateFlow<String?>(null)
    val backupBusy = MutableStateFlow(false)

    fun refreshBackups() { viewModelScope.launch(Dispatchers.IO) { backups.value = listBackups(java.io.File(Importer.baseDir(ctx), "backups")) } }

    /** Sends `bridge <req> <args>`, waits for the OK/ERROR result, shows the engine's one-line reason. */
    private fun backupCall(label: String, vararg args: String) {
        if (backupBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            backupBusy.value = true; backupMsg.value = null
            try {
                bridgeReady()?.let { backupMsg.value = it; return@launch }
                val req = newRequestId()
                TermuxBridge.send(ctx, listOf("bridge", req) + args)?.let { backupMsg.value = it; return@launch }
                val r = poll(req, 120_000) { _, _ -> }?.second
                TermuxBridge.resultFile(ctx, req).delete()
                backupMsg.value = when {
                    r == null -> "$label: no answer from Termux (update the engine to 0.16.1 and check Setup)."
                    r.state == "OK" -> "$label done. ${redact(r.reason)}"
                    else -> "$label failed: ${redact(r.reason)}"
                }
            } finally { backupBusy.value = false; backups.value = listBackups(java.io.File(Importer.baseDir(ctx), "backups")) }
        }
    }

    fun backupNow(full: Boolean) = backupCall("Backup", *(if (full) arrayOf("backup", "--full") else arrayOf("backup")))
    fun restoreBackup(name: String, force: Boolean) {
        if (!validBackupName(name, ".tar.gz")) { backupMsg.value = "Invalid backup name"; return }
        backupCall("Restore", *(if (force) arrayOf("restore", name, "--force") else arrayOf("restore", name)))
    }
    fun exportConfig() = backupCall("Config export", "cfg-export")
    fun importConfig(name: String) {
        if (!validBackupName(name, ".json")) { backupMsg.value = "Invalid config name"; return }
        backupCall("Config import", "cfg-import", name)
    }
}
