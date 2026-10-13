package com.gitdrip.app

import com.gitdrip.app.ui.NavBarLogic
import org.junit.Assert.*
import org.junit.Test

/** Floating bottom bar: Dashboard - Projects - Setup - Settings, shown on those four routes only. */
class NavBarLogicTest {
    @Test fun order() {
        assertEquals(listOf("dashboard", "projects", "setup", "settings"), NavBarLogic.ROUTES)
        assertEquals(NavBarLogic.ROUTES.size, NavBarLogic.LABELS.size)
        assertEquals(NavBarLogic.HOME, NavBarLogic.ROUTES[0])
    }
    @Test fun tabIndexAndVisibility() {
        assertEquals(0, NavBarLogic.tabIndex("dashboard")); assertEquals(3, NavBarLogic.tabIndex("settings"))
        assertEquals(-1, NavBarLogic.tabIndex("run/{id}")); assertEquals(-1, NavBarLogic.tabIndex(null))
        assertTrue(NavBarLogic.showsBar("projects"))
        listOf("run/{id}", "new", "project/{id}", null).forEach { assertFalse(NavBarLogic.showsBar(it)) }
    }
    @Test fun reselectDoesNothing() {
        assertFalse(NavBarLogic.shouldNavigate("setup", 2)); assertTrue(NavBarLogic.shouldNavigate("setup", 0))
        assertFalse(NavBarLogic.shouldNavigate("setup", 9)); assertTrue(NavBarLogic.shouldNavigate("run/{id}", 1))
    }
    @Test fun nearIsOneOnTheButtonAndFadesOut() {
        assertEquals(1f, NavBarLogic.near(2f, 2), 0.0001f); assertEquals(0.5f, NavBarLogic.near(1.5f, 2), 0.0001f)
        assertEquals(0f, NavBarLogic.near(0f, 3), 0.0001f)
    }
}
