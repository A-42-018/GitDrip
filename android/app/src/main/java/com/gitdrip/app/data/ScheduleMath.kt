package com.gitdrip.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Pure schedule logic (no Android). days = bitmask, bit0 = Mon … bit6 = Sun. */
const val ALL_DAYS = 127
const val MISSED_GRACE_MIN = 10L

/** Daily-cap bounds (D1). The app stores the value; `config-set max_commits_per_day` keeps the engine in step. */
object Limits {
    const val DEFAULT_CAP = 10
    const val MIN_CAP = 1
    const val MAX_CAP = 10
    fun clampCap(n: Int) = n.coerceIn(MIN_CAP, MAX_CAP)
}

/** Open runs older than this are treated as stale: not counted against the cap, failed by reconcile. */
const val STALE_RUN_MS = 15 * 60_000L
private val TIME_RE = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

fun validTime(s: String) = TIME_RE.matches(s)
fun validZone(z: String) = z.isEmpty() || z in ZoneId.getAvailableZoneIds()
/** "" = follow the device zone (also after a timezone change). */
fun zoneOf(z: String): ZoneId = if (z.isEmpty()) ZoneId.systemDefault() else ZoneId.of(z)

fun dayAllowed(days: Int, d: LocalDate) = (days shr (d.dayOfWeek.value - 1)) and 1 == 1
fun dayKey(nowMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()

fun slotMs(d: LocalDate, time: String, zone: ZoneId): Long =
    ZonedDateTime.of(d, LocalTime.parse(time), zone).toInstant().toEpochMilli()   // DST gap shifts forward

/** Next fire strictly after [nowMs], or null when no day is selected. */
fun nextFire(nowMs: Long, time: String, zone: ZoneId, days: Int): Long? {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    for (i in 0L..8L) {
        val d = today.plusDays(i)
        if (!dayAllowed(days, d)) continue
        val s = slotMs(d, time, zone)
        if (s > nowMs) return s
    }
    return null
}

/** Today's slot passed by more than the grace period and it has not fired today (boot / time-change catch-up). */
fun isMissed(nowMs: Long, time: String, zone: ZoneId, days: Int, lastFireDay: String, graceMin: Long = MISSED_GRACE_MIN): Boolean {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return dayAllowed(days, today) && lastFireDay != today.toString() &&
        nowMs > slotMs(today, time, zone) + graceMin * 60_000
}

fun daysLabel(days: Int): String {
    if (days == ALL_DAYS) return "Every day"
    val n = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return n.filterIndexed { i, _ -> (days shr i) and 1 == 1 }.joinToString(" ").ifEmpty { "No days" }
}

// ---- FX2: cap + fire decisions (pure) ----
fun capReached(used: Int, cap: Int) = used >= cap

/** D2: manual pushes are never blocked by the cap, but past it the first tap only warns; a second tap within [windowMs] goes through. */
const val CAP_CONFIRM_MS = 30_000L
fun capWarning(used: Int, cap: Int) = "Daily limit reached ($used/$cap). Tap Run again to push anyway"
fun manualNeedsConfirm(used: Int, cap: Int, lastWarnMs: Long?, nowMs: Long): Boolean =
    capReached(used, cap) && (lastWarnMs == null || nowMs - lastWarnMs > CAP_CONFIRM_MS)

/** Next retry after [nowMs] in [stepMin] steps, or null once it would pass slot + [limitMin]. */
fun retryAt(slotMs: Long, nowMs: Long, stepMin: Long = 10, limitMin: Long = 60): Long? {
    val next = nowMs + stepMin * 60_000
    return if (next <= slotMs + limitMin * 60_000) next else null
}

/** Everything `fire()` / `dispatch()` knows before sending. Defaults suit Auto Drip (no per-day slot bookkeeping). */
class FireState(
    val paused: Boolean, val used: Int, val cap: Int, val openRuns: Int, val hasBatches: Boolean,
    val dayAllowed: Boolean = true, val slotFired: Boolean = false,
)

sealed class Decision {
    object Run : Decision()
    /** [permanent] = consume today's slot; otherwise the caller retries (previous run still open). */
    data class Skip(val reason: String, val permanent: Boolean) : Decision()
}

fun decide(s: FireState, cap: Int = s.cap): Decision = when {
    s.slotFired -> Decision.Skip("already ran today's slot", true)
    !s.dayAllowed -> Decision.Skip("not an enabled day", true)
    s.paused -> Decision.Skip("project paused", true)
    capReached(s.used, cap) -> Decision.Skip("daily cap (${s.used}/$cap) reached", true)
    !s.hasBatches -> Decision.Skip("no batches", true)
    s.openRuns > 0 -> Decision.Skip("previous run still open", false)
    else -> Decision.Run
}
