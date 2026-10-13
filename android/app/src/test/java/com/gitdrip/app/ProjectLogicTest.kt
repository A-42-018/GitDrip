package com.gitdrip.app

import com.gitdrip.app.ui.ProjectLogic
import org.junit.Assert.*
import org.junit.Test

/** U3: project progress, labels and tab layout rules (pure JVM). */
class ProjectLogicTest {
    @Test fun progressCountsPushedAndNoChange() {
        val p = ProjectLogic.progress(listOf("SUCCESS", "nochange", "COMMITTED", "PENDING", "FAILED"))
        assertEquals(2, p.done); assertEquals(5, p.total); assertEquals(1, p.waiting); assertEquals(3, p.left)
        assertEquals(0.4, p.fraction.toDouble(), 0.0001)
        val e = ProjectLogic.progress(emptyList())
        assertEquals(0, e.total); assertEquals(0.0, e.fraction.toDouble(), 0.0); assertEquals(0, e.left)
    }

    @Test fun progressLabel() {
        assertEquals("No batches yet", ProjectLogic.progressLabel(ProjectLogic.progress(emptyList())))
        assertEquals("0 of 3 batches pushed", ProjectLogic.progressLabel(ProjectLogic.progress(listOf("PENDING", "PENDING", "PENDING"))))
        assertEquals("1 of 3 batches pushed · 1 waiting to push", ProjectLogic.progressLabel(ProjectLogic.progress(listOf("SUCCESS", "COMMITTED", "PENDING"))))
        assertEquals("All 2 batches pushed", ProjectLogic.progressLabel(ProjectLogic.progress(listOf("SUCCESS", "NOCHANGE"))))
        assertEquals("Batch pushed", ProjectLogic.progressLabel(ProjectLogic.progress(listOf("SUCCESS"))))
    }

    @Test fun repoLabel() {
        assertEquals("No repo set", ProjectLogic.repoLabel("")); assertEquals("No repo set", ProjectLogic.repoLabel("   "))
        assertEquals("A-42-018/alif", ProjectLogic.repoLabel("https://github.com/A-42-018/alif"))
        assertEquals("A-42-018/alif", ProjectLogic.repoLabel("https://github.com/A-42-018/alif.git"))
        assertEquals("A-42-018/alif", ProjectLogic.repoLabel("https://github.com/A-42-018/alif/"))
        assertEquals("o/my.repo", ProjectLogic.repoLabel("https://github.com/o/my.repo"))
        assertEquals("https://example.com/x/y", ProjectLogic.repoLabel("https://example.com/x/y"))
        assertEquals("A-42-018/alif · main", ProjectLogic.subtitle("https://github.com/A-42-018/alif", "main"))
        assertEquals("No repo set · dev", ProjectLogic.subtitle("", "dev"))
    }

    @Test fun detailSummary() {
        assertEquals("No batches pending", ProjectLogic.detailSummary(0, false))
        assertEquals("1 batch pending", ProjectLogic.detailSummary(1, false))
        assertEquals("4 batches pending", ProjectLogic.detailSummary(4, false))
        assertEquals("Paused · 4 batches pending", ProjectLogic.detailSummary(4, true))
        assertEquals("No projects", ProjectLogic.projectCount(0)); assertEquals("1 project", ProjectLogic.projectCount(1)); assertEquals("3 projects", ProjectLogic.projectCount(3))
    }

    @Test fun tabsAndColumns() {
        assertEquals(listOf("Files", "Batches", "Schedule", "History"), ProjectLogic.TAB_LABELS)
        assertEquals("Schedule", ProjectLogic.TAB_LABELS[ProjectLogic.TAB_SCHEDULE]); assertEquals("History", ProjectLogic.TAB_LABELS[ProjectLogic.TAB_HISTORY])
        assertEquals(4, ProjectLogic.tabColumns(1.0f, 412)); assertEquals(4, ProjectLogic.tabColumns(1.15f, 360))
        assertEquals(2, ProjectLogic.tabColumns(1.3f, 412)); assertEquals(2, ProjectLogic.tabColumns(1.0f, 320)); assertEquals(2, ProjectLogic.tabColumns(1.5f, 320))
    }

    @Test fun deleteCopy() {
        assertEquals("Delete 'alif'?", ProjectLogic.deleteTitle("alif"))
        assertTrue(ProjectLogic.DELETE_BODY.contains("Termux files are not touched"))
    }
}
