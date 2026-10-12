package com.gitdrip.app.ui

/** Pure text/layout rules for the Projects screens (U3). No Compose/Android imports so they run in plain JVM tests. */
object ProjectLogic {
    /** Batches that no longer need work: SUCCESS (pushed) or NOCHANGE (nothing to push). [waiting] = committed locally, push still to do. */
    data class Progress(val done: Int, val total: Int, val waiting: Int) {
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
        val left: Int get() = (total - done).coerceAtLeast(0)
    }

    fun progress(statuses: List<String>): Progress {
        val s = statuses.map { it.uppercase() }
        return Progress(
            done = s.count { it == "SUCCESS" || it == "NOCHANGE" },
            total = s.size,
            waiting = s.count { it == "COMMITTED" },
        )
    }

    fun progressLabel(p: Progress): String = when {
        p.total == 0 -> "No batches yet"
        p.done == p.total -> if (p.total == 1) "Batch pushed" else "All ${p.total} batches pushed"
        else -> "${p.done} of ${p.total} batches pushed" + if (p.waiting > 0) " · ${p.waiting} waiting to push" else ""
    }

    /** `https://github.com/owner/repo(.git)(/)` -> `owner/repo`; anything else is shown as typed; blank -> "No repo set". */
    fun repoLabel(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return "No repo set"
        val m = Regex("^https://github\\.com/([^/\\s]+/[^/\\s]+?)(?:\\.git)?/?$").find(u)
        return m?.groupValues?.get(1) ?: u
    }

    fun subtitle(url: String, branch: String): String = "${repoLabel(url)} · $branch"

    fun detailSummary(pending: Int, paused: Boolean): String {
        val left = when (pending) { 0 -> "No batches pending"; 1 -> "1 batch pending"; else -> "$pending batches pending" }
        return if (paused) "Paused · $left" else left
    }

    fun projectCount(n: Int): String = when (n) { 0 -> "No projects"; 1 -> "1 project"; else -> "$n projects" }

    const val TAB_FILES = 0
    const val TAB_BATCHES = 1
    const val TAB_SCHEDULE = 2
    const val TAB_HISTORY = 3
    val TAB_LABELS: List<String> = listOf("Files", "Batches", "Schedule", "History")

    /** 4 tabs in one row only when they fit; large fonts or narrow screens get a 2x2 grid so labels never clip. */
    fun tabColumns(fontScale: Float, widthDp: Int): Int = if (fontScale > 1.15f || widthDp < 340) 2 else 4

    fun deleteTitle(name: String) = "Delete '$name'?"
    const val DELETE_BODY = "Removes the project and its batches, schedules and history from the app. Termux files are not touched."

    const val NAME_HINT = "Lowercase letters, digits, dot, dash or underscore. Used as the Termux folder name."
}
