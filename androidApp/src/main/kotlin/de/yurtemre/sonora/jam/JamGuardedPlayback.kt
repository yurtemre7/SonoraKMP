package de.yurtemre.sonora.jam

import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.model.Song

/**
 * The [PlaybackConnection] the UI gets ([JamController] keeps the raw one). While this phone is a Jam guest:
 * starting its own music (play, shuffle, play next, add to queue) waits for the [JamPlaybackGuard] choice;
 * seek, shuffle, repeat and queue edits are ignored (the host owns them); play, pause and skip become requests to
 * the host when it allows them. Volume, effects and closing the player work as always. Outside a Jam, and for a
 * host, every call goes straight through.
 */
class JamGuardedPlayback(
    private val delegate: PlaybackConnection,
    private val guard: JamPlaybackGuard,
) : PlaybackConnection by delegate {
    private val guest: Boolean get() = guard.guest() != null

    /** True while "play my own music" calls are held for the Jam dialog instead of running. */
    val holdsOwnPlayback: Boolean get() = guest

    override fun playSong(song: Song, list: List<Song>, playlistId: String?) {
        guard.request(list.ifEmpty { listOf(song) }, isAdd = false) { delegate.playSong(song, list, playlistId) }
    }

    override fun playShuffled(list: List<Song>, playlistId: String?) {
        guard.request(list, isAdd = false) { delegate.playShuffled(list, playlistId) }
    }

    override fun playNext(songs: List<Song>) {
        guard.request(songs, isAdd = true) { delegate.playNext(songs) }
    }

    override fun addToQueue(songs: List<Song>) {
        guard.request(songs, isAdd = true) { delegate.addToQueue(songs) }
    }

    override fun setShuffle(enabled: Boolean) { if (!guest) delegate.setShuffle(enabled) }
    override fun toggleShuffle() { if (!guest) delegate.toggleShuffle() }
    override fun cycleRepeat() { if (!guest) delegate.cycleRepeat() }
    override fun removeFromQueue(entryUid: Long) { if (!guest) delegate.removeFromQueue(entryUid) }
    override fun moveQueueItem(from: Int, to: Int) { if (!guest) delegate.moveQueueItem(from, to) }
    override fun insertQueueItem(songId: Long, logicalIndex: Int) { if (!guest) delegate.insertQueueItem(songId, logicalIndex) }
    override fun skipToQueueItem(entryUid: Long) { if (!guest) delegate.skipToQueueItem(entryUid) }
    override fun seekTo(positionMs: Long) { if (!guest) delegate.seekTo(positionMs) }
    override fun seekBy(deltaMs: Long) { if (!guest) delegate.seekBy(deltaMs) }

    override fun play() { guard.guest()?.let(guard.guestActions::play) ?: delegate.play() }
    override fun pause() { guard.guest()?.let(guard.guestActions::pause) ?: delegate.pause() }
    override fun togglePlayPause() { guard.guest()?.let(guard.guestActions::playPause) ?: delegate.togglePlayPause() }
    override fun skipToNext() { guard.guest()?.let(guard.guestActions::next) ?: delegate.skipToNext() }
    override fun skipToPrevious() { guard.guest()?.let(guard.guestActions::previous) ?: delegate.skipToPrevious() }
}

/**
 * Runs [block] (play next, add to queue, play…) on this connection and says whether it took effect now. False
 * while a Jam guest's call waits for the "Leave the Jam?" dialog: callers then show no "Added…" snackbar and no
 * Undo (there is nothing to undo yet).
 */
inline fun PlaybackConnection.ranNow(block: PlaybackConnection.() -> Unit): Boolean {
    val now = (this as? JamGuardedPlayback)?.holdsOwnPlayback != true
    block()
    return now
}
