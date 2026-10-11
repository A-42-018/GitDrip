package com.gitdrip.app.data

import java.time.LocalDate
import java.time.ZoneId
import java.util.Random

/** Pure Auto Drip logic (no Android): how a project is driven, how many slots to plan, and when (D3-D5). */
enum class DripMode { Fixed, Auto, Off }

/** D3: any enabled fixed time wins and Auto Drip is ignored; otherwise Auto Drip when switched on; otherwise nothing runs. */
fun dripMode(enabledSchedules: Int, autoDrip: Boolean): DripMode = when {
    enabledSchedules > 0 -> DripMode.Fixed
    autoDrip -> DripMode.Auto
    else -> DripMode.Off
}

/** D5: min(N, cap - used today, pending batches), never negative. */
fun slotsToPlan(perDay: Int, cap: Int, usedToday: Int, pendingBatches: Int): Int =
    minOf(perDay, cap - usedToday, pendingBatches).coerceAtLeast(0)

const val AUTO_MIN_GAP_MIN = 20       // between slots of one project
const val AUTO_GLOBAL_GAP_MIN = 5     // between any two slots of any projects
const val AUTO_LEAD_MIN = 5           // planning mid-day starts at now + 5 min
const val AUTO_MIN_WINDOW_MIN = 30    // smallest allowed window

private const val MIN_MS = 60_000L

/** Window rules shared by the UI and the planner: HH:MM both valid, start < end, at least [AUTO_MIN_WINDOW_MIN] minutes. */
fun autoWindowError(start: String, end: String): String? = when {
    !validTime(start) || !validTime(end) -> "Times must be HH:MM (24h)"
    start >= end -> "Start must be before end"
    minutesOf(end) - minutesOf(start) < AUTO_MIN_WINDOW_MIN -> "Window must be at least $AUTO_MIN_WINDOW_MIN minutes"
    else -> null
}

private fun minutesOf(hm: String) = hm.substring(0, 2).toInt() * 60 + hm.substring(3).toInt()

/**
 * D4: stratified random. The usable window (from max(window start, now + 5 min) to window end, in real instants so DST is
 * honoured) is split into [n] equal segments and one uniform random minute is picked in each; margins keep consecutive
 * slots of this project >= [minGapMin] apart (when segments are too short for that, slots sit at the segment centres:
 * even spacing, never overlapping). Slots within [AUTO_GLOBAL_GAP_MIN] of a [taken] slot (other projects) shift forward;
 * slots pushed past the window end are dropped. Returns sorted epoch ms; empty when the window has passed or n <= 0.
 */
fun planAutoSlots(
    day: LocalDate, start: String, end: String, n: Int, nowMs: Long, zone: ZoneId, rnd: Random,
    minGapMin: Int = AUTO_MIN_GAP_MIN, taken: List<Long> = emptyList(),
): List<Long> {
    if (n <= 0) return emptyList()
    val winStart = slotMs(day, start, zone); val winEnd = slotMs(day, end, zone)
    val from = (maxOf(winStart, nowMs + AUTO_LEAD_MIN * MIN_MS) + MIN_MS - 1) / MIN_MS * MIN_MS   // whole minutes, rounded up
    val spanMin = ((winEnd - from) / MIN_MS).toInt()
    if (spanMin < 1) return emptyList()
    val cnt = minOf(n, spanMin)
    val seg = spanMin / cnt
    val random = seg > minGapMin
    val m = minGapMin / 2; val m2 = minGapMin - m
    val base = (0 until cnt).map { i ->
        val offMin = if (!random) i * seg + seg / 2 else {
            val lo = i * seg + (if (i > 0) m2 else 0)
            val hi = (i + 1) * seg - 1 - (if (i < cnt - 1) m else 0)
            lo + rnd.nextInt(hi - lo + 1)
        }
        from + offMin * MIN_MS
    }
    val out = ArrayList<Long>()
    for (raw in base) {
        var t = raw
        repeat(100) {   // each pass moves past one clashing slot; bounded so it always terminates
            val clash = taken.firstOrNull { kotlin.math.abs(it - t) < AUTO_GLOBAL_GAP_MIN * MIN_MS }
            if (clash != null) t = clash + AUTO_GLOBAL_GAP_MIN * MIN_MS
        }
        if (out.isNotEmpty() && t <= out.last()) t = out.last() + MIN_MS   // shifts never make own slots overlap
        if (t < winEnd) out += t
    }
    return out.sorted()
}
