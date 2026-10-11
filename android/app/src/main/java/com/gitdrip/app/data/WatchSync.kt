package com.gitdrip.app.data

/** A batch the engine watcher appended to batches.json (engine id = rank). Pure: no Android/org.json. */
class WatchBatch(val id: Int, val message: String, val files: List<String>, val status: String, val commit: String)

/**
 * Batches to add to Room: watcher-origin only, engine id above what the app already ranks, ascending, ids unique,
 * must have a message and files (the engine never emits others; anything odd is ignored, never guessed).
 */
fun watchBatchesToImport(rankedCount: Int, states: List<WatchBatch>): List<WatchBatch> =
    states.filter { it.id > rankedCount && it.message.isNotBlank() && it.files.isNotEmpty() }
        .distinctBy { it.id }.sortedBy { it.id }

/** Paths of an imported watch batch that Room does not know yet (they become new files assigned to that batch). */
fun newWatchPaths(known: Set<String>, b: WatchBatch): List<String> = b.files.filter { it !in known }.distinct()

/** Contiguous only: a gap (id skipped) means the app's rank view and the engine disagree, so stop at the gap. */
fun contiguousFrom(rankedCount: Int, batches: List<WatchBatch>): List<WatchBatch> {
    var next = rankedCount + 1
    return batches.takeWhile { if (it.id == next) { next++; true } else false }
}
