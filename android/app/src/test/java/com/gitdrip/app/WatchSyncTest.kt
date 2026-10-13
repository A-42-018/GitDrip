package com.gitdrip.app

import com.gitdrip.app.data.WatchBatch
import com.gitdrip.app.data.contiguousFrom
import com.gitdrip.app.data.newWatchPaths
import com.gitdrip.app.data.watchBatchesToImport
import com.gitdrip.app.ui.TabLogic
import org.junit.Assert.*
import org.junit.Test

/** P7.10: which engine batches the app imports (pure JVM). */
class WatchSyncTest {
    private fun wb(id: Int, msg: String = "feat: add x module", files: List<String> = listOf("x/a.kt")) = WatchBatch(id, msg, files, "PENDING", "")

    @Test fun importsOnlyIdsAboveRankedAscending() {
        val r = watchBatchesToImport(2, listOf(wb(4), wb(1), wb(3), wb(2)))
        assertEquals(listOf(3, 4), r.map { it.id })
    }

    @Test fun dropsOddBatchesAndDuplicates() {
        val r = watchBatchesToImport(0, listOf(wb(1), wb(1), wb(2, msg = " "), wb(3, files = emptyList())))
        assertEquals(listOf(1), r.map { it.id })
    }

    @Test fun stopsAtAGap() {
        val r = contiguousFrom(2, watchBatchesToImport(2, listOf(wb(3), wb(5), wb(6))))
        assertEquals(listOf(3), r.map { it.id })
        assertTrue(contiguousFrom(2, watchBatchesToImport(2, listOf(wb(4)))).isEmpty())
    }

    @Test fun newPathsSkipKnownAndRepeats() {
        val b = wb(1, files = listOf("a.kt", "b.kt", "a.kt", "c/d.kt"))
        assertEquals(listOf("b.kt", "c/d.kt"), newWatchPaths(setOf("a.kt"), b))
    }

    @Test fun titleMarksWatcherBatches() {
        assertEquals("#3 feat: x", TabLogic.batchTitle(3, "feat: x"))
        assertEquals("#3 feat: x (from watcher)", TabLogic.batchTitle(3, "feat: x", "watch"))
    }
}
