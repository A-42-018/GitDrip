package com.gitdrip.app.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.gitdrip.app.AlarmReceiver
import com.gitdrip.app.GitDripApp
import com.gitdrip.app.ui.TabLogic
import java.time.LocalDate
import java.time.ZoneId

/** Result of [Scheduler.dispatch]. [permanent] = consume the slot; [failure] = worth an alert (FAILED row + notification) when given up. */
sealed class Dispatch {
    data class Dispatched(val req: String) : Dispatch()
    data class Skipped(val reason: String, val permanent: Boolean, val failure: Boolean = false) : Dispatch()
}

/** AlarmManager glue (P11). One exact alarm per enabled schedule; the receiver re-arms the next one after each fire. */
object Scheduler {
    const val ACTION_FIRE = "com.gitdrip.app.FIRE"
    const val ACTION_RETRY = "com.gitdrip.app.RETRY"
    const val ACTION_CHECK = "com.gitdrip.app.CHECK"
    const val ACTION_AUTO = "com.gitdrip.app.AUTO"
    const val ACTION_PLAN = "com.gitdrip.app.PLAN"
    const val EXTRA_SLOT = "slotId"
    private const val AUTO_BASE = 300_000       // + projectId: one auto alarm per project (always its earliest PLANNED slot)
    private const val PLAN_CODE = 399_999
    const val EXTRA_ID = "scheduleId"
    const val EXTRA_REQ = "req"
    const val EXTRA_STEP = "step"
    const val EXTRA_T0 = "t0"
    private const val RETRY_BASE = 200_000

    /** Check alarms after a dispatch: minutes after the send (S1). */
    private val CHECK_MIN = longArrayOf(2, 5, 10, 20)

    private fun pi(c: Context, action: String, code: Int, flags: Int = 0, extras: (Intent) -> Unit = {}): PendingIntent? =
        PendingIntent.getBroadcast(
            c, code, Intent(c, AlarmReceiver::class.java).setAction(action).also(extras),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    fun canExact(c: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(c).canScheduleExactAlarms()

    /** Exact when allowed, otherwise an inexact idle-safe alarm. */
    private fun setAt(c: Context, at: Long, p: PendingIntent) {
        if (canExact(c)) am(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        else am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
    }

    private fun slotPi(c: Context, id: Long, flags: Int = 0) = pi(c, ACTION_FIRE, id.toInt(), flags) { it.putExtra(EXTRA_ID, id) }
    private fun retryPi(c: Context, id: Long, flags: Int = 0) = pi(c, ACTION_RETRY, RETRY_BASE + id.toInt(), flags) { it.putExtra(EXTRA_ID, id) }

    fun cancel(c: Context, id: Long) {
        slotPi(c, id, PendingIntent.FLAG_NO_CREATE)?.let { am(c).cancel(it); it.cancel() }
        retryPi(c, id, PendingIntent.FLAG_NO_CREATE)?.let { am(c).cancel(it); it.cancel() }
    }

    /** Arms (or re-arms) the next occurrence. */
    fun arm(c: Context, s: ScheduleEntity, nowMs: Long = System.currentTimeMillis()) {
        cancel(c, s.id)
        if (!s.enabled) return
        val at = nextFire(nowMs, s.time, zoneOf(s.zone), s.days) ?: return
        setAt(c, at, slotPi(c, s.id, PendingIntent.FLAG_UPDATE_CURRENT)!!)
    }

    private fun armRetry(c: Context, id: Long, at: Long) = setAt(c, at, retryPi(c, id, PendingIntent.FLAG_UPDATE_CURRENT)!!)

    suspend fun armAll(c: Context) {
        val db = (c.applicationContext as GitDripApp).db
        db.schedules().enabled().forEach { arm(c, it) }
    }

    // ---- S1: close scheduled runs in the background ----
    private fun checkPi(c: Context, req: String, flags: Int = 0, extras: (Intent) -> Unit = {}) =
        pi(c, ACTION_CHECK, req.hashCode(), flags, extras)

    private fun armCheck(c: Context, req: String, step: Int, t0: Long) {
        val at = t0 + CHECK_MIN[step] * 60_000
        setAt(c, maxOf(at, System.currentTimeMillis() + 5_000), checkPi(c, req, PendingIntent.FLAG_UPDATE_CURRENT) {
            it.putExtra(EXTRA_REQ, req).putExtra(EXTRA_STEP, step).putExtra(EXTRA_T0, t0)
        }!!)
    }

    /** Check alarm: apply the result if it is there; otherwise arm the next check, or fail the run on the last one. */
    suspend fun check(c: Context, req: String, step: Int, t0: Long) {
        if (RunRecorder.reconcileRequest(c, req)) return
        if (step + 1 < CHECK_MIN.size) armCheck(c, req, step + 1, t0) else RunRecorder.failNoAnswer(c, req)
    }

    private fun dayStartMs(zone: ZoneId = ZoneId.systemDefault()) = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Cap usage across all projects today (D2). */
    suspend fun usedToday(c: Context, now: Long = System.currentTimeMillis()): Int =
        (c.applicationContext as GitDripApp).db.executions().usedToday(dayStartMs(), now - STALE_RUN_MS)

    /**
     * Single send path for scheduled and Auto Drip runs: reconcile, decide, write plan.json, send, arm check alarms.
     * Does NOT write History rows or consume slots; callers do that from the returned [Dispatch].
     */
    suspend fun dispatch(c: Context, p: ProjectEntity, slotFired: Boolean = false, dayOk: Boolean = true): Dispatch {
        val db = (c.applicationContext as GitDripApp).db; val ex = db.executions(); val now = System.currentTimeMillis()
        RunRecorder.reconcileProject(c, p.id)
        val bd = db.batches(); val files = bd.filesNow(p.id)
        val plan = bd.batchesNow(p.id).map { b -> PlanBatch(b.seq, b.message, files.filter { it.batchId == b.id }.map { it.path }) }
        val cap = CapPrefs.get(c)
        val runnable = bd.batchesNow(p.id).any { b -> TabLogic.canRun(b.status) && (b.origin.isNotEmpty() || files.any { it.batchId == b.id }) }
        val d = decide(FireState(p.paused, usedToday(c, now), cap, ex.open(p.id).size, runnable, dayOk, slotFired))
        if (d is Decision.Skip) return Dispatch.Skipped(d.reason, d.permanent)
        if (!TermuxBridge.installed(c)) return Dispatch.Skipped("Termux not installed", true, true)
        if (!TermuxBridge.permitted(c)) return Dispatch.Skipped("Termux run-command permission missing", true, true)
        if (!Importer.sharedAccess(c)) return Dispatch.Skipped("All-files access missing", true, true)
        TermuxBridge.writePlan(c, p.name, buildPlan(p.name, p.repoUrl, p.branch, plan))
        val req = newRequestId()
        val rowId = ex.insert(ExecutionEntity(projectId = p.id, taskId = "", requestId = req, state = "PENDING", startedAt = now))
        TermuxBridge.send(c, listOf("bridge", req, "run", p.name, "--sync"))?.let { err ->
            ex.deleteById(rowId)   // never reached the engine: no run happened, the caller retries or reports it
            return Dispatch.Skipped(err, false, true)
        }
        armCheck(c, req, 0, now)
        return Dispatch.Dispatched(req)
    }

    /**
     * Alarm / catch-up entry point (also the retry alarm). Always re-arms the next occurrence in `finally`.
     * Permanent skips and real dispatches consume today's slot; temporary blocks (run still open, Termux unreachable)
     * keep it and retry every 10 min until slot + 60 min (S4).
     */
    suspend fun fire(c: Context, id: Long, catchUp: Boolean = false) {
        val db = (c.applicationContext as GitDripApp).db
        val s = db.schedules().byId(id) ?: run { cancel(c, id); return }
        try {
            if (!s.enabled) return
            val now = System.currentTimeMillis()
            val zone = zoneOf(s.zone); val today = dayKey(now, zone)
            val ex = db.executions()
            val p = db.projects().byIdNow(s.projectId) ?: return
            suspend fun row(state: String, why: String) = ex.insert(ExecutionEntity(projectId = p.id, taskId = "", state = state, reason = "${s.time}: $why", startedAt = now, finishedAt = now))
            // every alarm that reaches the app leaves a History row, so "it never ran" can be told apart from "it ran and was skipped"
            when (val r = dispatch(c, p, slotFired = s.lastFireDay == today, dayOk = dayAllowed(s.days, LocalDate.parse(today)))) {
                is Dispatch.Dispatched -> db.schedules().markFired(s.id, today)
                is Dispatch.Skipped -> {
                    val slot = slotMs(LocalDate.parse(today), s.time, zone)
                    val retry = if (r.permanent) null else retryAt(slot, now)
                    when {
                        r.permanent && r.failure -> { db.schedules().markFired(s.id, today); notifyFailed(c, p, row("FAILED", r.reason), r.reason) }
                        r.permanent -> { if (s.lastFireDay != today) db.schedules().markFired(s.id, today); row("SKIPPED", r.reason) }
                        retry != null -> {
                            row("SKIPPED", "${r.reason}, will retry at ${java.time.Instant.ofEpochMilli(retry).atZone(zone).toLocalTime().withSecond(0).withNano(0)}")
                            armRetry(c, id, retry)
                        }
                        r.failure -> { db.schedules().markFired(s.id, today); notifyFailed(c, p, row("FAILED", "gave up after 60 min: ${r.reason}"), r.reason) }
                        else -> { db.schedules().markFired(s.id, today); row("SKIPPED", "gave up after 60 min (${r.reason})") }
                    }
                }
            }
        } finally {
            db.schedules().byId(id)?.let { arm(c, it) }
        }
    }

    private fun notifyFailed(c: Context, p: ProjectEntity, rowId: Long, why: String) {
        val now = System.currentTimeMillis()
        noteFor(null, "FAILED", p.name, why, null, 0, false, now, now)?.let { Notify.post(c, rowId, it) }
    }

    /** An exception inside an alarm must not vanish: it becomes a FAILED row in the project's History. */
    suspend fun recordError(c: Context, id: Long, e: Throwable) {
        val db = (c.applicationContext as GitDripApp).db
        val s = db.schedules().byId(id) ?: return; val now = System.currentTimeMillis()
        db.executions().insert(ExecutionEntity(projectId = s.projectId, taskId = "", state = "FAILED", reason = "${s.time}: alarm error: ${e.javaClass.simpleName} ${e.message ?: ""}".take(300), startedAt = now, finishedAt = now))
    }

    /** Boot / time / timezone change / app update: close stale runs, re-arm everything and apply run-now to slots missed today. */
    suspend fun restore(c: Context) {
        val db = (c.applicationContext as GitDripApp).db; val now = System.currentTimeMillis()
        db.projects().allNow().forEach { RunRecorder.reconcileProject(c, it.id) }
        for (s in db.schedules().enabled()) {
            if (s.policy == "run-now" && isMissed(now, s.time, zoneOf(s.zone), s.days, s.lastFireDay)) fire(c, s.id, catchUp = true)
            else arm(c, s, now)
        }
        syncAuto(c, dropOverdue = true)
    }

    // ---- FX4: Auto Drip (random daily times) ----
    private fun autoPi(c: Context, projectId: Long, flags: Int = 0, slotId: Long = 0) =
        pi(c, ACTION_AUTO, AUTO_BASE + projectId.toInt(), flags) { it.putExtra(EXTRA_SLOT, slotId) }

    fun cancelAuto(c: Context, projectId: Long) {
        autoPi(c, projectId, PendingIntent.FLAG_NO_CREATE)?.let { am(c).cancel(it); it.cancel() }
    }

    /** One alarm per project: its earliest PLANNED slot (or none). */
    suspend fun armAuto(c: Context, projectId: Long) {
        val next = (c.applicationContext as GitDripApp).db.autoSlots().nextPlanned(projectId)
        if (next == null) { cancelAuto(c, projectId); return }
        setAt(c, maxOf(next.atMs, System.currentTimeMillis() + 2_000), autoPi(c, projectId, PendingIntent.FLAG_UPDATE_CURRENT, next.id)!!)
    }

    /** Exact alarm at 00:05 local tomorrow: plans the new day, then re-arms itself. */
    fun armPlanner(c: Context) {
        val zone = ZoneId.systemDefault()
        val at = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 5 * 60_000
        setAt(c, at, pi(c, ACTION_PLAN, PLAN_CODE, PendingIntent.FLAG_UPDATE_CURRENT)!!)
    }

    /** Runnable batches of a project (engine rank list that can still go out). */
    private suspend fun pendingCount(db: AppDb, p: ProjectEntity): Int =
        RunRecorder.ranked(db, p.id).count { TabLogic.canRun(it.status) }

    /**
     * Brings Auto Drip in line with reality; safe to call repeatedly (app open, boot, toggles, 00:05 planner, schedule edits):
     * - mode Fixed / Off: cancel the alarm, PLANNED slots become SKIPPED "fixed times set" / are dropped;
     * - mode Auto: no batches left -> remaining PLANNED slots SKIPPED "no batches left"; otherwise plan what is missing today;
     * - [dropOverdue] (boot / time change / app open): PLANNED slots already in the past are dropped and only the rest of the window is re-planned.
     */
    suspend fun syncAuto(c: Context, dropOverdue: Boolean = false, now: Long = System.currentTimeMillis()) {
        val db = (c.applicationContext as GitDripApp).db; val slots = db.autoSlots()
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone).toString()
        slots.pruneBefore(LocalDate.now(zone).minusDays(7).toString())
        if (dropOverdue) slots.deleteOverdue(now - 3 * 60_000)
        for (p in db.projects().allNow()) {
            val fixed = db.schedules().forProjectNow(p.id).count { it.enabled }
            when (dripMode(fixed, p.autoDrip)) {
                DripMode.Fixed -> { slots.skipPlanned(p.id, "fixed times set"); cancelAuto(c, p.id); continue }
                DripMode.Off -> { slots.deletePlanned(p.id); cancelAuto(c, p.id); continue }
                DripMode.Auto -> {}
            }
            if (p.paused) { slots.skipPlanned(p.id, "project paused"); cancelAuto(c, p.id); continue }
            val pending = pendingCount(db, p)
            if (pending == 0) { slots.skipPlanned(p.id, "no batches left"); cancelAuto(c, p.id); continue }
            if (slots.plannedCount(p.id, today) == 0) {
                val n = slotsToPlan(p.autoPerDay - slots.consumed(p.id, today), CapPrefs.get(c), usedToday(c, now) + slots.plannedOthers(today, p.id), pending)
                val times = planAutoSlots(LocalDate.parse(today), p.autoStart, p.autoEnd, n, now, zone, java.security.SecureRandom(), taken = slots.plannedTimes(today))
                slots.insertAll(times.map { AutoSlotEntity(projectId = p.id, day = today, atMs = it) })
            }
            armAuto(c, p.id)
        }
        armPlanner(c)
    }

    /** User asked for new times: only future PLANNED slots are replaced (never ones already sent). */
    suspend fun reroll(c: Context, projectId: Long) {
        val db = (c.applicationContext as GitDripApp).db
        db.autoSlots().deletePlanned(projectId)
        syncAuto(c)
    }

    /** `ACTION_AUTO`: sends one batch for the slot, then arms the project's next PLANNED slot. */
    suspend fun fireAuto(c: Context, slotId: Long) {
        val db = (c.applicationContext as GitDripApp).db; val slots = db.autoSlots()
        val slot = slots.byId(slotId) ?: return
        try {
            if (slot.state != "PLANNED") return    // stale alarm (re-rolled, skipped or already handled)
            val p = db.projects().byIdNow(slot.projectId) ?: return
            val now = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
            val label = "auto " + java.time.Instant.ofEpochMilli(slot.atMs).atZone(zone).toLocalTime().withSecond(0).withNano(0)
            val fixed = db.schedules().forProjectNow(p.id).count { it.enabled }
            if (dripMode(fixed, p.autoDrip) != DripMode.Auto) { slots.update(slot.copy(state = "SKIPPED", reason = "mode changed")); return }
            suspend fun row(state: String, why: String) = db.executions().insert(ExecutionEntity(projectId = p.id, taskId = "", state = state, reason = "$label: $why", startedAt = now, finishedAt = now))
            when (val r = dispatch(c, p)) {
                is Dispatch.Dispatched -> slots.update(slot.copy(state = "SENT", requestId = r.req, reason = ""))
                is Dispatch.Skipped -> {
                    val windowEnd = slotMs(LocalDate.parse(slot.day), p.autoEnd, zone)
                    val retry = if (r.permanent) null else retryAt(slot.atMs, now).takeIf { it != null && it < windowEnd }
                    when {
                        retry != null -> { slots.update(slot.copy(atMs = retry, reason = r.reason)); row("SKIPPED", "${r.reason}, will retry") }
                        r.failure -> { slots.update(slot.copy(state = "FAILED", reason = r.reason)); notifyFailed(c, p, row("FAILED", r.reason), r.reason) }
                        else -> { slots.update(slot.copy(state = "SKIPPED", reason = r.reason)); row("SKIPPED", r.reason) }
                    }
                }
            }
        } finally {
            armAuto(c, slot.projectId)
        }
    }
}
