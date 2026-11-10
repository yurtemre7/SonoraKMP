package de.yurtemre.sonora.shared.queue

import de.yurtemre.sonora.shared.model.QueueState
import de.yurtemre.sonora.shared.model.RepeatMode

/** What the player must do after a navigation request. */
sealed interface NavAction {
    /** Seek to 0 on the current entry. */
    data object Restart : NavAction
    /** Switch to logical [index] at position 0. */
    data class MoveTo(val index: Int) : NavAction
    /** End of queue reached with repeat off (auto-advance only). */
    data object Completed : NavAction
}

data class Navigation(
    val state: QueueState,
    val action: NavAction,
    /** UI-originated next: skip stat when not on the last item (PLAY-07). */
    val recordSkip: Boolean = false,
    /** UI-originated previous with position > 3 s: restart stat. */
    val recordRestart: Boolean = false,
)

const val RESTART_THRESHOLD_MS: Long = 3_000

private fun QueueState.moveTo(i: Int) = Navigation(copy(currentIndex = i), NavAction.MoveTo(i))

/** PLAY-04/07: user "next". Repeat one restarts; repeat all wraps to logical 0 (FIX D-12); off at end restarts. */
fun QueueState.next(): Navigation? {
    if (currentEntry == null) return null
    val skip = !isLast
    val nav = when {
        repeat == RepeatMode.One -> Navigation(this, NavAction.Restart)
        !isLast -> moveTo(currentIndex + 1)
        repeat == RepeatMode.All -> moveTo(0)
        else -> Navigation(this, NavAction.Restart)
    }
    return nav.copy(recordSkip = skip)
}

/** PLAY-04/07: user "previous"; position strictly > 3 s restarts. */
fun QueueState.previous(positionMs: Long): Navigation? {
    if (currentEntry == null) return null
    if (positionMs > RESTART_THRESHOLD_MS) return Navigation(this, NavAction.Restart, recordRestart = true)
    return when {
        repeat == RepeatMode.One -> Navigation(this, NavAction.Restart)
        currentIndex > 0 -> moveTo(currentIndex - 1)
        repeat == RepeatMode.All -> moveTo(entries.lastIndex)
        else -> Navigation(this, NavAction.Restart)
    }
}

/** Natural end of the current track. */
fun QueueState.onTrackEnded(): Navigation? {
    if (currentEntry == null) return null
    return when {
        repeat == RepeatMode.One -> Navigation(this, NavAction.Restart)
        !isLast -> moveTo(currentIndex + 1)
        repeat == RepeatMode.All -> moveTo(0)
        else -> Navigation(this, NavAction.Completed)
    }
}

/** PLAY-07: a seek from > 3 s to < 3 s counts as a restart. */
fun isRestartSeek(fromMs: Long, toMs: Long): Boolean = toMs < RESTART_THRESHOLD_MS && fromMs > RESTART_THRESHOLD_MS
