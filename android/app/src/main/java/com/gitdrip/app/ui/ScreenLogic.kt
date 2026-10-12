package com.gitdrip.app.ui

/** Pure text/rule helpers for History, Run detail, GitHub, Setup and Settings (U5). No Compose/Android imports so they run in plain JVM tests. */
object ScreenLogic {
    /** Run detail rows: blank values are dropped (same behaviour as the old Field()). */
    fun detailRows(rows: List<Pair<String, String>>): List<Pair<String, String>> = rows.filter { it.second.isNotBlank() }

    private val RETRYABLE = setOf("FAILED", "PENDING_RETRY")
    fun canRetry(runState: String, batchStatus: String): Boolean = runState.uppercase() in RETRYABLE && !batchStatus.equals("SUCCESS", true)
    fun retryLabel(runState: String): String = if (runState.equals("FAILED", true)) "Retry this batch" else "Retry now"

    fun historyEmpty(): String = "No runs yet. Use Run on the Batches tab or add a schedule."
    fun runsCount(n: Int): String = if (n == 1) "1 run" else "$n runs"

    /** Setup check: text status next to the icon so state is never colour/glyph only. */
    fun checkStatus(ok: Boolean): String = if (ok) "Ready" else "Needs attention"
    fun setupSummary(ok: Int, total: Int): String = if (total > 0 && ok == total) "All $total checks passed" else "$ok of $total checks ready"

    fun repoMeta(isPrivate: Boolean, fork: Boolean, archived: Boolean, push: Boolean): String = listOfNotNull(
        if (isPrivate) "private" else "public", if (fork) "fork: commits won't count" else null,
        if (archived) "archived" else null, if (!push) "no push access" else null,
    ).joinToString(" · ")

    fun branchLabel(name: String, defaultBranch: String?): String = if (name == defaultBranch) "$name (default)" else name

    fun accountLine(login: String, kind: String): String = "$login · $kind token"

    /** Success texts the ViewModel produces for the token card; anything else is an error. */
    fun tokenMsgOk(msg: String): Boolean = msg.startsWith("Token stored") || msg.startsWith("Saved token removed")
    fun backupMsgError(msg: String): Boolean = msg.contains("failed") || msg.contains("no answer")

    fun backupKind(isConfig: Boolean): String = if (isConfig) "Config" else "Backup"
    fun backupTitle(isConfig: Boolean, whenLabel: String): String = "${backupKind(isConfig)} · $whenLabel"
    fun backupAction(isConfig: Boolean): String = if (isConfig) "Apply" else "Restore"
    fun backupActionDescription(isConfig: Boolean, whenLabel: String): String = "${backupAction(isConfig)} ${backupKind(isConfig).lowercase()} from $whenLabel"
}
