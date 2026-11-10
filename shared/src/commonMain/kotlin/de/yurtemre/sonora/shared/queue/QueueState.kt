package de.yurtemre.sonora.shared.queue

import de.yurtemre.sonora.shared.model.QueueEntry
import de.yurtemre.sonora.shared.model.QueueState

/** Currently selected entry, or null when nothing is loaded. */
val QueueState.currentEntry: QueueEntry? get() = entries.getOrNull(currentIndex)

val QueueState.isEmpty: Boolean get() = entries.isEmpty()

val QueueState.isLast: Boolean get() = currentIndex == entries.lastIndex

/** Allocates fresh entries for [songIds] using [QueueState.nextUid]; returns entries and the next free uid. */
internal fun QueueState.allocate(songIds: List<Long>): Pair<List<QueueEntry>, Long> {
    var uid = nextUid
    val created = songIds.map { QueueEntry(uid++, it) }
    return created to uid
}
