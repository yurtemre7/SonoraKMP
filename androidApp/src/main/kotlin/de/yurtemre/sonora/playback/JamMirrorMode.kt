package de.yurtemre.sonora.playback

import de.yurtemre.sonora.shared.model.QueueEntry
import de.yurtemre.sonora.shared.model.QueueState
import de.yurtemre.sonora.shared.model.RepeatMode
import de.yurtemre.sonora.shared.queue.currentEntry

/**
 * The playback service's Jam guest mode: while a mirror is loaded the guest plays at normal speed and pitch and with
 * skip silence off (both would move it away from the host's timeline); the user's settings come back on [end].
 */
class JamMirrorMode(
    private val setNormalSpeed: (Boolean) -> Unit,
    private val setSkipSilence: (Boolean) -> Unit,
) {
    var active = false
        private set

    /** The user's skip silence setting; applied only outside a mirror. */
    var userSkipSilence = false
        set(value) {
            field = value
            setSkipSilence(value && !active)
        }

    fun begin() {
        if (active) return
        active = true
        setNormalSpeed(true)
        setSkipSilence(false)
    }

    fun end() {
        if (!active) return
        active = false
        setNormalSpeed(false)
        setSkipSilence(userSkipSilence)
    }
}

/** How the service applies a Jam mirror queue. */
sealed interface MirrorEdit {
    /** The playing entry stays loaded (no gap); [play] starts it if it is paused. */
    data class Keep(val state: QueueState, val play: Boolean) : MirrorEdit

    /** A fresh load at [positionMs], playing or paused. */
    data class Load(val state: QueueState, val positionMs: Long, val play: Boolean) : MirrorEdit
}

/**
 * The mirror queue [ids] (current [index]) as an edit of [applied] (the queue the player has) given [latest] (the
 * newest request, possibly still being built). The playing entry is kept only when the player already has it and
 * nothing is pending; otherwise a pending build would be redone from "position 0, paused" and swallow the start.
 */
fun mirrorEdit(
    applied: QueueState,
    latest: QueueState,
    mirroring: Boolean,
    ids: List<Long>,
    index: Int,
    positionMs: Long,
    play: Boolean,
): MirrorEdit {
    val cur = applied.currentEntry
    val keep = mirroring && latest === applied && cur != null && ids.getOrNull(index) == cur.songId
    var uid = maxOf(latest.nextUid, applied.nextUid)
    val entries = ids.mapIndexed { i, id -> if (keep && i == index) cur!! else QueueEntry(uid++, id) }
    val state = QueueState(
        entries = entries, original = entries,
        currentIndex = if (entries.isEmpty()) -1 else index.coerceIn(0, entries.lastIndex),
        repeat = RepeatMode.Off, nextUid = uid,
    )
    return if (keep) MirrorEdit.Keep(state, play) else MirrorEdit.Load(state, positionMs, play)
}
