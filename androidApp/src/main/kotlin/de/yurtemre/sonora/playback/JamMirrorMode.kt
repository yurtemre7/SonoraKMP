package de.yurtemre.sonora.playback

import de.yurtemre.sonora.shared.model.QueueEntry
import de.yurtemre.sonora.shared.model.QueueState
import de.yurtemre.sonora.shared.model.RepeatMode
import de.yurtemre.sonora.shared.queue.currentEntry

/**
 * The playback service's Jam guest mode: while a mirror is loaded nothing is persisted ([PlaybackPersistence] keeps
 * the guest's own queue), and the guest plays at the host's speed and pitch (normal until told) with skip silence
 * off (the host's skipped silence cannot be followed); the user's settings come back on [end].
 */
class JamMirrorMode(
    private val persistence: PlaybackPersistence,
    /** Plays at the given speed and pitch while mirroring; (null, null) gives the user's own back. */
    private val applyRate: (Float?, Float?) -> Unit,
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

    /** The host's playback rate; applied while mirroring, kept for the first load too. */
    private var speed = 1f
    private var pitch = 1f

    fun setRate(speed: Float, pitch: Float) {
        this.speed = speed
        this.pitch = pitch
        if (active) applyRate(speed, pitch)
    }

    /** Runs [load]; the first time, the own queue is saved before it and the Jam audio settings apply after it. */
    fun begin(load: () -> Unit) {
        if (active) {
            load()
            return
        }
        persistence.beginMirror()
        active = true
        load()
        applyRate(speed, pitch)
        setSkipSilence(false)
    }

    /**
     * Ends the mirror. [stopPlayback] runs first, still in mirror mode: ExoPlayer reports the pause from inside the
     * pause call, and a save then would store the Jam queue over the guest's own one.
     */
    fun end(stopPlayback: () -> Unit) {
        if (!active) return
        stopPlayback()
        active = false
        persistence.endMirror()
        speed = 1f
        pitch = 1f
        applyRate(null, null)
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
 * Repeat is off, or one when [repeatOne] (the host repeats its song: the guest loops it itself, never a reload).
 */
fun mirrorEdit(
    applied: QueueState,
    latest: QueueState,
    mirroring: Boolean,
    ids: List<Long>,
    index: Int,
    positionMs: Long,
    play: Boolean,
    repeatOne: Boolean = false,
): MirrorEdit {
    val cur = applied.currentEntry
    val keep = mirroring && latest === applied && cur != null && ids.getOrNull(index) == cur.songId
    var uid = maxOf(latest.nextUid, applied.nextUid)
    val entries = ids.mapIndexed { i, id -> if (keep && i == index) cur!! else QueueEntry(uid++, id) }
    val state = QueueState(
        entries = entries, original = entries,
        currentIndex = if (entries.isEmpty()) -1 else index.coerceIn(0, entries.lastIndex),
        repeat = if (repeatOne) RepeatMode.One else RepeatMode.Off, nextUid = uid,
    )
    return if (keep) MirrorEdit.Keep(state, play) else MirrorEdit.Load(state, positionMs, play)
}
