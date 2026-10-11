package com.gitdrip.app.data

import android.content.Context
import androidx.room.withTransaction
import com.gitdrip.app.GitDripApp

/** The one copy of "turn a Termux result file into Room rows". Used by the ViewModel, the alarm receivers and the Scheduler. */
object RunRecorder {
    private fun db(c: Context) = (c.applicationContext as GitDripApp).db

    /** Engine batch id i == i-th batch (by seq) that has files; editing is frozen after the first run so this stays stable. */
    suspend fun ranked(db: AppDb, projectId: Long): List<BatchEntity> {
        val bd = db.batches()
        val used = bd.filesNow(projectId).mapNotNull { it.batchId }.toSet()
        return bd.batchesNow(projectId).filter { it.id in used || it.origin.isNotEmpty() }.sortedBy { it.seq }   // watcher batches always carry files engine-side
    }

    suspend fun applyResult(c: Context, req: String, r: BridgeResult) {
        val db = db(c); val ex = db.executions(); val bd = db.batches()
        val e = ex.byRequest(req) ?: return
        val rk = ranked(db, e.projectId)
        val next = if (r.state == "ERROR") "FAILED" else r.state
        db.withTransaction {
            r.batches.forEach { s ->
                rk.getOrNull(s.id - 1)?.let { b -> if (b.status != s.status) bd.setStatus(b.id, s.status, s.commit.ifEmpty { null }) }
            }
            ex.update(e.copy(
                state = next,
                taskId = r.taskId.ifEmpty { e.taskId }, commitHash = r.commit.ifEmpty { null }, reason = redact(r.reason).ifEmpty { null },
                batchId = e.batchId ?: r.batchId?.let { rk.getOrNull(it - 1)?.id },
                output = redact(r.output), filesChanged = r.filesChanged, attempt = r.attempt, nextRetryAt = r.nextRetryAt,
                finishedAt = if (isTerminal(r.state)) System.currentTimeMillis() else null,
            ))
        }
        if (isTerminal(r.state)) markSlot(db, req, next)
        notifyChange(c, e.id, e.projectId, e.state, next, r.reason, r.commit, r.filesChanged, if (isTerminal(r.state)) System.currentTimeMillis() else null)
    }

    /** An Auto Drip slot follows its run: SUCCESS -> DONE, FAILED -> FAILED, SKIPPED -> SKIPPED. */
    private suspend fun markSlot(db: AppDb, req: String, state: String) {
        val sd = db.autoSlots(); val slot = sd.byRequest(req) ?: return
        sd.update(slot.copy(state = when (state) { "SUCCESS" -> "DONE"; "FAILED" -> "FAILED"; else -> "SKIPPED" }))
    }

    /** P16b: one notification per real state transition (failures; successes only if enabled). */
    suspend fun notifyChange(c: Context, id: Long, projectId: Long, prev: String?, now: String, reason: String?, commit: String?, files: Int, finishedAt: Long?) {
        val name = db(c).projects().byIdNow(projectId)?.name ?: return
        noteFor(prev, now, name, reason, commit, files, Notify.successEnabled(c), finishedAt, System.currentTimeMillis())?.let { Notify.post(c, id, it) }
    }

    private suspend fun failStale(c: Context, e: ExecutionEntity, why: String) {
        val db = db(c); val now = System.currentTimeMillis()
        db.executions().update(e.copy(state = "FAILED", reason = why, finishedAt = now))
        markSlot(db, e.requestId, "FAILED")
        notifyChange(c, e.id, e.projectId, e.state, "FAILED", why, null, 0, now)
    }

    /** Applies finished result files and fails open rows older than [STALE_RUN_MS] that have none (S1 safety net). */
    suspend fun reconcileProject(c: Context, projectId: Long) {
        val ex = db(c).executions()
        ex.open(projectId).forEach { e ->
            val r = TermuxBridge.readResult(c, e.requestId)?.let(::parseResult)
            if (r != null) applyResult(c, e.requestId, r)
            else if (System.currentTimeMillis() - e.startedAt > STALE_RUN_MS) failStale(c, e, "no answer from Termux")
        }
    }

    /** One request: apply its result file if there is one. Returns true when the run is final (or already closed). */
    suspend fun reconcileRequest(c: Context, req: String): Boolean {
        val e = db(c).executions().byRequest(req) ?: return true
        if (e.state !in setOf("PENDING", "RUNNING")) return true
        val r = TermuxBridge.readResult(c, req)?.let(::parseResult) ?: return false
        applyResult(c, req, r)
        return r.isFinal
    }

    /** Last check alarm with no result: close the run so it neither blocks the next slot nor counts against the cap. */
    suspend fun failNoAnswer(c: Context, req: String) {
        val e = db(c).executions().byRequest(req) ?: return
        if (e.state in setOf("PENDING", "RUNNING")) failStale(c, e, "no answer from Termux (is Termux allowed to run in background?)")
    }
}
