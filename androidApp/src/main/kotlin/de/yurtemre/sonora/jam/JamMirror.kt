package de.yurtemre.sonora.jam

import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.playback.api.QueueSnapshot
import de.yurtemre.sonora.shared.jam.GuestPlan
import de.yurtemre.sonora.shared.jam.JamSong
import de.yurtemre.sonora.shared.jam.JamSongMatcher
import de.yurtemre.sonora.shared.jam.JamState
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.model.Song

/**
 * The guest's player while it mirrors a Jam. The first [load] keeps the guest's own queue (index, position, shuffle
 * and repeat) in memory; [restore] brings it back. The mirror itself is never persisted (the playback service stops
 * saving while it is loaded), so an app killed mid-Jam comes back with the guest's own queue.
 */
internal class JamMirror(private val playback: PlaybackConnection) {
    private var previous: QueueSnapshot? = null

    var active = false
        private set

    /** The songs in the player, in order (only songs this phone has, never past a missing one). */
    var run: List<Song> = emptyList()
        private set

    fun load(songs: List<Song>, index: Int, positionMs: Long, play: Boolean) {
        if (!active) {
            previous = playback.queueSnapshot()
            active = true
        }
        run = songs
        playback.setMirrorQueue(songs, index, positionMs, play)
    }

    fun restore() {
        if (!active) return
        active = false
        run = emptyList()
        playback.endMirror(previous)
        previous = null
    }
}

/** Jam song → local song, cached until the library list changes (the matcher normalises every library song). */
internal class JamLibrary(private val library: () -> List<Song>) {
    private var source: List<Song>? = null
    private val cache = HashMap<JamSong, Song?>()

    fun songs(): List<Song> {
        val current = library()
        if (current !== source) {
            source = current
            cache.clear()
        }
        return current
    }

    fun match(song: JamSong): Song? {
        val all = songs()
        return cache.getOrPut(song) { JamSongMatcher.match(song, all) }
    }

    /**
     * The songs from jam index [from] on that this phone has, up to (not including) the first one it lacks: the
     * player stops there by itself, so the guest never plays a song before the host gets to it.
     */
    fun runFrom(state: JamState, from: Int): List<Song> =
        state.queue.drop(from.coerceAtLeast(0)).asSequence().map(::match).takeWhile { it != null }.filterNotNull().toList()
}

/** When ([atLocalMs], null = at once) and where ([positionMs]) a guest starts a song. */
internal data class JamStart(val atLocalMs: Long?, val positionMs: Long)

/**
 * Applies the speaker delay ([speakerDelayMs], clamped to [AppSettings.JAM_SPEAKER_DELAY_RANGE_MS]) to [plan]. A
 * start in the future waits for the clock (never a faster start). A start already past begins at once where the
 * host is now: [GuestPlan.Play.positionMs] already runs on from the anchor to now, so that elapsed time is taken
 * out once and only the time since the (delayed) start is added back, never both.
 */
internal fun jamStart(plan: GuestPlan.Play, nowMs: Long, speakerDelayMs: Int): JamStart {
    val delay = speakerDelayMs.coerceIn(AppSettings.JAM_SPEAKER_DELAY_RANGE_MS).toLong()
    val at = plan.startAtLocalMs + delay
    val anchored = plan.positionMs - (nowMs - plan.startAtLocalMs).coerceAtLeast(0)
    return if (at > nowMs) JamStart(at, anchored) else JamStart(null, (anchored + (nowMs - at)).coerceAtLeast(0))
}

fun Song.toJamSong() = JamSong(title, artist, album, durationMs)
