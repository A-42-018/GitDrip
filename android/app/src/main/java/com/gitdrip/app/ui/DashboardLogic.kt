package com.gitdrip.app.ui

/** Pure text/layout rules for the Dashboard and navigation (U2). No Compose/Android imports so they run in plain JVM tests. */
object DashboardLogic {
    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..22 -> "Good evening"
        else -> "Good night"
    }

    fun heroSummary(today: Int, streak: Int, cap: Int? = null): String {
        val parts = mutableListOf(
            if (cap != null) "$today/$cap commits today" else when (today) { 0 -> "No commits yet today"; 1 -> "1 commit today"; else -> "$today commits today" },
        )
        if (streak > 0) parts += if (streak == 1) "1-day streak" else "$streak-day streak"
        return parts.joinToString(" · ")
    }

    fun nextRunLabel(next: Pair<String, Long>?, fmt: (Long) -> String): String =
        next?.let { "Next run: ${it.first} · ${fmt(it.second)}" } ?: "No upcoming runs (add a schedule)"

    /** Lines for the "Needs attention" card; empty list = card hidden. */
    fun attentionLines(failed: Int, retrying: Int): List<String> = buildList {
        if (failed > 0) add(if (failed == 1) "1 run failed today" else "$failed runs failed today")
        if (retrying > 0) add(if (retrying == 1) "1 run waiting to retry in Termux" else "$retrying runs waiting to retry in Termux")
    }

    /** 4 icon tiles in one row only when they fit; large fonts or narrow screens get a 2x2 grid so labels never clip. */
    fun quickActionColumns(fontScale: Float, widthDp: Int): Int = if (fontScale > 1.15f || widthDp < 340) 2 else 4

    fun runSummary(project: String?, time: String, commit: String?, files: Int, reason: String?, state: String): String = listOfNotNull(
        project, time, commit?.take(7)?.takeIf { it.isNotBlank() },
        if (files > 0) (if (files == 1) "1 file" else "$files files") else null,
        reason?.takeIf { state != "SUCCESS" && it.isNotBlank() }?.take(60),
    ).joinToString(" · ")

    /** System "Remove animations" (ANIMATOR_DURATION_SCALE = 0) turns navigation transitions off. */
    fun animationsEnabled(durationScale: Float): Boolean = durationScale > 0f
}
