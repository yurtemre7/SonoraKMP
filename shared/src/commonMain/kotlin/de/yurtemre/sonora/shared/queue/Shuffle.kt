package de.yurtemre.sonora.shared.queue

import de.yurtemre.sonora.shared.model.QueueState
import de.yurtemre.sonora.shared.model.RepeatMode
import kotlin.random.Random

/** PLAY-03: history + current stay; only upcoming entries are shuffled. No-op without a current entry. */
fun QueueState.shuffleOn(random: Random = Random): QueueState {
    if (currentEntry == null || isShuffled) return this
    val head = entries.subList(0, currentIndex + 1)
    val upcoming = entries.subList(currentIndex + 1, entries.size).shuffled(random)
    return copy(entries = head + upcoming, isShuffled = true)
}

/** PLAY-03: restore original order, current located by entry uid (keeps queue if not found). */
fun QueueState.shuffleOff(): QueueState {
    if (!isShuffled) return this
    val cur = currentEntry ?: return copy(entries = original, isShuffled = false)
    val idx = original.indexOfFirst { it.uid == cur.uid }
    if (idx < 0) return copy(isShuffled = false)
    return copy(entries = original, currentIndex = idx, isShuffled = false)
}

fun QueueState.toggleShuffle(random: Random = Random): QueueState =
    if (isShuffled) shuffleOff() else shuffleOn(random)

/** PLAY-03: shuffled copy of [songIds] starting at its first entry; original = unshuffled list. */
fun QueueState.quickShuffle(songIds: List<Long>, random: Random = Random, playlistContext: String? = null): QueueState {
    val loaded = load(songIds, playlistContext = playlistContext)
    if (loaded.entries.isEmpty()) return loaded
    return loaded.copy(entries = loaded.entries.shuffled(random), currentIndex = 0, isShuffled = true)
}

/** PLAY-04: off → all → one → off. */
fun RepeatMode.next(): RepeatMode = when (this) {
    RepeatMode.Off -> RepeatMode.All
    RepeatMode.All -> RepeatMode.One
    RepeatMode.One -> RepeatMode.Off
}

fun QueueState.cycleRepeat(): QueueState = copy(repeat = repeat.next())
