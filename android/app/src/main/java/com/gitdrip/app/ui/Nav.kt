package com.gitdrip.app.ui

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gitdrip.app.MainViewModel
import com.gitdrip.app.NewProject
import com.gitdrip.app.ProjectDetail
import com.gitdrip.app.ProjectList
import com.gitdrip.app.RunDetailScreen
import com.gitdrip.app.SettingsScreen
import com.gitdrip.app.SetupScreen
import com.gitdrip.app.ui.screens.DashboardScreen

private const val ENTER_MS = 280
private const val EXIT_MS = 200

/** Route table + 200-300 ms fade/slide transitions (off when the system animation scale is 0). Route names are unchanged since U1. */
@Composable
fun GitDripNav(vm: MainViewModel = viewModel()) {
    val nav = rememberNavController()
    val resolver = LocalContext.current.contentResolver
    val animate = remember { DashboardLogic.animationsEnabled(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)) }
    val enter = if (animate) fadeIn(tween(ENTER_MS)) + slideInHorizontally(tween(ENTER_MS)) { it / 12 } else EnterTransition.None
    val exit = if (animate) fadeOut(tween(EXIT_MS)) else ExitTransition.None
    val popEnter = if (animate) fadeIn(tween(ENTER_MS)) + slideInHorizontally(tween(ENTER_MS)) { -it / 12 } else EnterTransition.None
    val popExit = if (animate) fadeOut(tween(EXIT_MS)) + slideOutHorizontally(tween(EXIT_MS)) { it / 12 } else ExitTransition.None
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val showBar = NavBarLogic.showsBar(route)
    val tabs = remember {
        listOf(
            NavTab(NavBarLogic.LABELS[0], Icons.Default.Home), NavTab(NavBarLogic.LABELS[1], Icons.AutoMirrored.Filled.List),
            NavTab(NavBarLogic.LABELS[2], Icons.Default.Build), NavTab(NavBarLogic.LABELS[3], Icons.Default.Settings),
        )
    }
    // Bottom bar tabs: always back to the Dashboard first, so Back from any tab returns to the Dashboard.
    // Decided at click time from the live back stack (never from a captured `route`, which can be stale inside a function reference).
    val goTab: (Int) -> Unit = { i ->
        val live = nav.currentBackStackEntry?.destination?.route
        if (NavBarLogic.shouldNavigate(live, i)) {
            val target = NavBarLogic.ROUTES[i]
            if (target == NavBarLogic.HOME) {
                // Dashboard is the start destination: pop everything above it; if it is not on the stack, navigate to it fresh.
                if (!nav.popBackStack(NavBarLogic.HOME, inclusive = false)) {
                    nav.navigate(NavBarLogic.HOME) { popUpTo(nav.graph.id) { inclusive = true }; launchSingleTop = true }
                }
            } else {
                nav.navigate(target) { popUpTo(NavBarLogic.HOME) { inclusive = false }; launchSingleTop = true }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
    NavHost(
        nav, "dashboard",
        modifier = Modifier.weight(1f).then(if (showBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier), // the bar handles the gesture-bar inset
        enterTransition = { enter }, exitTransition = { exit }, popEnterTransition = { popEnter }, popExitTransition = { popExit },
    ) {
        composable("dashboard") {
            DashboardScreen(vm, onProjects = { goTab(1) }, onRun = { nav.navigate("run/$it") })
        }
        composable("run/{id}", listOf(navArgument("id") { type = NavType.LongType })) { e ->
            RunDetailScreen(vm, e.arguments!!.getLong("id")) { nav.popBackStack() }
        }
        composable("setup") { SetupScreen(vm) }
        composable("settings") { SettingsScreen(vm) }
        composable("projects") {
            ProjectList(vm, onNew = { nav.navigate("new") }, onOpen = { nav.navigate("project/$it") })
        }
        composable("new") { NewProject(vm) { nav.popBackStack() } }
        composable("project/{id}", listOf(navArgument("id") { type = NavType.LongType })) { e ->
            ProjectDetail(vm, e.arguments!!.getLong("id"), { nav.navigate("run/$it") }) { nav.popBackStack() }
        }
    }
    if (showBar) FloatNavBar(tabs, NavBarLogic.tabIndex(route), goTab)
    }
}
