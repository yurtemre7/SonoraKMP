package de.yurtemre.sonora.shared.queue

import de.yurtemre.sonora.shared.model.QueueState

/**
 * PLAY-02: replace the queue with [songIds], starting at the first occurrence of [startSongId] (else 0).
 * Shuffle is reset; repeat mode is kept (not part of a load).
 */
fun QueueState.load(songIds: List<Long>, startSongId: Long? = null, playlistContext: String? = null): QueueState {
    val (created, uid) = allocate(songIds)
    val start = if (created.isEmpty()) -1 else created.indexOfFirst { it.songId == startSongId }.coerceAtLeast(0)
    return copy(
        entries = created, original = created, currentIndex = start, isShuffled = false,
        playlistContext = playlistContext, nextUid = uid,
    )
}

/**
 * PLAY-05, FIFO (departs from Flutter's LIFO): consecutive "play next" calls queue up in the order
 * they were added, after the current entry. The anchor resets once the current entry changes.
 */
fun QueueState.playNext(songIds: List<Long>): QueueState {
    if (songIds.isEmpty()) return this
    val (created, uid) = allocate(songIds)
    val cur = currentEntry
    val anchor = playNextAnchorUid?.takeIf { cur != null && playNextForUid == cur.uid }
    val anchorIdx = anchor?.let { a -> entries.indexOfFirst { it.uid == a } }?.takeIf { it > currentIndex }
    val at = anchorIdx?.plus(1) ?: (currentIndex + 1).coerceIn(0, entries.size)
    val newEntries = entries.toMutableList().apply { addAll(at, created) }
    val origAnchor = anchor?.let { a -> original.indexOfFirst { it.uid == a } }?.takeIf { it >= 0 }
    val origAt = origAnchor?.plus(1)
        ?: cur?.let { c -> original.indexOfFirst { it.uid == c.uid } }?.takeIf { it >= 0 }?.plus(1) ?: original.size
    val newOriginal = original.toMutableList().apply { addAll(origAt, created) }
    val newIndex = if (currentIndex < 0) 0 else currentIndex
    return copy(
        entries = newEntries, original = newOriginal, currentIndex = newIndex, nextUid = uid,
        playNextAnchorUid = created.last().uid, playNextForUid = newEntries[newIndex].uid,
    )
}

/** PLAY-05: append to both the play order and the original order. */
fun QueueState.add(songIds: List<Long>): QueueState {
    if (songIds.isEmpty()) return this
    val (created, uid) = allocate(songIds)
    return copy(
        entries = entries + created, original = original + created,
        currentIndex = if (currentIndex < 0) 0 else currentIndex, nextUid = uid,
    )
}

/**
 * PLAY-06 / FIX D-03: remove exactly the entry with [uid] (duplicates of the same song stay).
 * Removing the current entry keeps the index (the following item becomes current), clamped to the end.
 */
fun QueueState.removeByUid(uid: Long): QueueState {
    val idx = entries.indexOfFirst { it.uid == uid }
    if (idx < 0) return copy(original = original.filterNot { it.uid == uid })
    val newEntries = entries.toMutableList().apply { removeAt(idx) }
    val newIndex = when {
        newEntries.isEmpty() -> -1
        idx < currentIndex -> currentIndex - 1
        else -> currentIndex.coerceAtMost(newEntries.lastIndex)
    }
    return copy(entries = newEntries, original = original.filterNot { it.uid == uid }, currentIndex = newIndex)
}

/** PLAY-06: move [from] → [to]; current index follows the current entry; original unchanged (parity). */
fun QueueState.move(from: Int, to: Int): QueueState {
    if (from !in entries.indices || to !in entries.indices || from == to) return this
    val list = entries.toMutableList()
    list.add(to, list.removeAt(from))
    val cur = currentIndex
    val newIndex = when {
        from == cur -> to
        from < cur && to >= cur -> cur - 1
        from > cur && to <= cur -> cur + 1
        else -> cur
    }
    return copy(entries = list, currentIndex = newIndex)
}

/** PLAY-07: jump to [index]; out-of-range is a no-op. */
fun QueueState.skipTo(index: Int): QueueState =
    if (index in entries.indices) copy(currentIndex = index) else this

/** Clears the queue (uids keep increasing so stale references never collide). */
fun QueueState.cleared(): QueueState =
    copy(entries = emptyList(), original = emptyList(), currentIndex = -1, isShuffled = false, playlistContext = null)
