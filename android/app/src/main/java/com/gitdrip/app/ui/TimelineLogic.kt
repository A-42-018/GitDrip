package com.gitdrip.app.ui

/** Marker kinds of the Auto Drip day timeline (pure; the composable maps each to a token colour + shape, never colour alone). */
enum class MarkerKind { Done, Sent, Planned, Next, Skipped, Failed }

class Marker(val fraction: Float, val kind: MarkerKind, val atMs: Long)

/** Pure maths + text for [SlotTimeline]: where slots sit on the active window and which one is "next". */
object TimelineLogic {
    /** Position of [t] on the window as 0..1 (clamped); 0 for an empty window. */
    fun fraction(t: Long, start: Long, end: Long): Float = if (end <= start) 0f else ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f)

    /** "Now" marker position, or null outside the window. */
    fun nowFraction(now: Long, start: Long, end: Long): Float? = if (end <= start || now < start || now > end) null else fraction(now, start, end)

    fun baseKind(state: String): MarkerKind = when (state.uppercase()) {
        "DONE", "SUCCESS" -> MarkerKind.Done
        "SENT", "RUNNING" -> MarkerKind.Sent
        "FAILED" -> MarkerKind.Failed
        "SKIPPED" -> MarkerKind.Skipped
        else -> MarkerKind.Planned
    }

    /** [slots] = (atMs, state). The earliest PLANNED slot that is not already in the past becomes Next (falls back to the earliest PLANNED). */
    fun markers(slots: List<Pair<Long, String>>, start: Long, end: Long, nowMs: Long): List<Marker> {
        val sorted = slots.sortedBy { it.first }
        val planned = sorted.filter { baseKind(it.second) == MarkerKind.Planned }
        val next = (planned.firstOrNull { it.first >= nowMs } ?: planned.firstOrNull())?.first
        return sorted.map { (at, st) ->
            val k = baseKind(st)
            Marker(fraction(at, start, end), if (k == MarkerKind.Planned && at == next) MarkerKind.Next else k, at)
        }
    }

    /** One-line TalkBack / caption summary: "3 of 10 done · next 14:37". */
    fun summary(slots: List<Pair<Long, String>>, nowMs: Long, fmt: (Long) -> String): String {
        if (slots.isEmpty()) return "No slots planned today"
        val done = slots.count { baseKind(it.second) == MarkerKind.Done }
        val next = markers(slots, 0, 1, nowMs).firstOrNull { it.kind == MarkerKind.Next }
        return "$done of ${slots.size} done" + (next?.let { " · next ${fmt(it.atMs)}" } ?: "")
    }

    fun kindLabel(k: MarkerKind): String = when (k) {
        MarkerKind.Done -> "Done"; MarkerKind.Sent -> "Sent"; MarkerKind.Planned -> "Planned"
        MarkerKind.Next -> "Next"; MarkerKind.Skipped -> "Skipped"; MarkerKind.Failed -> "Failed"
    }
}
