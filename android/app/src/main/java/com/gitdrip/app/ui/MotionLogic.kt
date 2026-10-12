package com.gitdrip.app.ui

/** Pure motion / appearance rules (U6). No Compose/Android imports so they run in plain JVM tests. */
object MotionLogic {
    const val PressScale = 0.98f
    const val PressMs = 90
    const val FadeMs = 260
    const val StaggerMs = 40
    const val StaggerMaxItems = 8

    /** Scale while a card/button is pressed; 1.0 when animations are off. */
    fun pressScale(pressed: Boolean, animate: Boolean): Float = if (pressed && animate) PressScale else 1f

    /** Entry delay of list item [index]: staggered for the first few rows only, 0 when animations are off or index is invalid. */
    fun enterDelayMs(index: Int, animate: Boolean): Int =
        if (!animate || index < 0) 0 else minOf(index, StaggerMaxItems) * StaggerMs

    /** Only the first rows animate in; later rows (scrolled into view) appear instantly so scrolling never lags. */
    fun shouldAnimateEntry(index: Int, animate: Boolean): Boolean = animate && index in 0 until StaggerMaxItems

    /** Settings hint under the "Solid surfaces" switch. */
    fun solidHint(on: Boolean): String =
        if (on) "Cards and panels use opaque colours: highest contrast, no see-through glass."
        else "Cards and panels are translucent glass over the background."

    /** Shimmer sweep position 0..1 (clamped); static mid value when animations are off. */
    fun shimmerPos(t: Float, animate: Boolean): Float = if (animate) t.coerceIn(0f, 1f) else 0.5f
}
