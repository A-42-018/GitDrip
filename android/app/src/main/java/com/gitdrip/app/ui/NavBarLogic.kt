package com.gitdrip.app.ui

/** Pure rules for the floating bottom bar (no Compose/Android imports so they run in plain JVM tests). */
object NavBarLogic {
    /** Top-level destinations, in bar order. Route names are the same ones the NavHost has always used. */
    val ROUTES: List<String> = listOf("dashboard", "projects", "setup", "settings")
    val LABELS: List<String> = listOf("Dashboard", "Projects", "Setup", "Settings")
    const val HOME = "dashboard"

    /** Index of [route] in the bar, or -1 when the route is not a top-level destination. */
    fun tabIndex(route: String?): Int = ROUTES.indexOf(route)

    /** The bar is shown on the four top-level screens only (not on run / new project / project detail). */
    fun showsBar(route: String?): Boolean = tabIndex(route) >= 0

    /** Tapping the tab that is already selected does nothing. */
    fun shouldNavigate(currentRoute: String?, targetIndex: Int): Boolean = targetIndex in ROUTES.indices && tabIndex(currentRoute) != targetIndex

    /** 0..1: how close the raised button is to tab [i] (1 = on it). */
    fun near(pos: Float, i: Int): Float = maxOf(0f, 1f - Math.abs(pos - i))
}
