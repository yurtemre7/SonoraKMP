package de.yurtemre.sonora.playback

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import de.yurtemre.sonora.shared.audio.ReplayGainMath
import de.yurtemre.sonora.shared.model.QueueState
import de.yurtemre.sonora.shared.model.RepeatMode
import de.yurtemre.sonora.shared.model.ReplayGainMode
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.queue.NavAction
import de.yurtemre.sonora.shared.queue.Navigation
import de.yurtemre.sonora.shared.queue.currentEntry
import de.yurtemre.sonora.shared.queue.next
import de.yurtemre.sonora.shared.queue.onTrackEnded
import de.yurtemre.sonora.shared.queue.previous
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bridges the shared [QueueState] with ExoPlayer's playlist through a sliding window (PLAY-02..08,
 * audit/playback §2.2): 10 behind / 30 ahead, append 20 when < 10 ahead, prepend 15 when < 5 behind,
 * never trimmed. Repeat-all loads the whole queue so ExoPlayer's REPEAT_MODE_ALL wraps gaplessly.
 * All calls on the player's application thread.
 */
@OptIn(UnstableApi::class)
class QueueController(
    private val player: SonoraForwardingPlayer,
    private val scope: CoroutineScope,
    /** Batch song lookup (repository); missing ids are skipped as unplayable. */
    private val songs: suspend (List<Long>) -> Map<Long, Song>,
    private val displayTitle: (Song) -> String = { it.title },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : QueueNavigator {

    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    /** Fired after the current index and ReplayGain are applied (stats, persistence, widget). */
    var onCurrentChanged: ((QueueState) -> Unit)? = null

    var replayGainMode: ReplayGainMode = ReplayGainMode.Off
        set(value) { field = value; applyReplayGain() }

    private var windowStart = 0
    private var windowEnd = 0
    private var editing = false
    private var token = 0L
    private val songCache = HashMap<Long, Song>()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) return
            syncCurrentFromPlayer(slide = !editing)
        }
    }

    init {
        player.navigator = this
        player.wrappedPlayer.addListener(listener) // added before the session's listener → runs first
    }

    fun release() = player.wrappedPlayer.removeListener(listener)

    /** Replaces the queue and loads a window around its current entry. */
    fun setQueue(newState: QueueState, startPositionMs: Long = 0, playWhenReady: Boolean = true): Job {
        val t = ++token
        return scope.launch {
            val idx = newState.currentIndex.coerceIn(0, (newState.entries.size - 1).coerceAtLeast(0))
            var start = (idx - PREPEND).coerceAtLeast(0)
            var end = (idx + APPEND).coerceAtMost(newState.entries.size)
            if (newState.repeat == RepeatMode.All) { start = 0; end = newState.entries.size }
            val items = buildItems(newState, start, end)
            if (t != token) return@launch // a newer request superseded this one
            _state.value = newState.copy(currentIndex = if (newState.entries.isEmpty()) -1 else idx)
            windowStart = start; windowEnd = end
            editing = true
            if (items.isEmpty()) player.wrappedPlayer.clearMediaItems()
            else player.wrappedPlayer.setMediaItems(items, idx - start, startPositionMs)
            player.wrappedPlayer.repeatMode = newState.repeat.toPlayer()
            player.wrappedPlayer.prepare()
            editing = false
            applyReplayGain()
            onCurrentChanged?.invoke(_state.value)
            if (playWhenReady && items.isNotEmpty()) player.play()
        }
    }

    /**
     * Mirrors an edit (play next, add, remove, move, shuffle, repeat) into the native playlist.
     * If the current entry is unchanged it stays loaded (no gap); everything around it is replaced.
     */
    fun applyEdit(newState: QueueState) {
        val old = _state.value
        val cur = newState.currentEntry
        if (cur == null || cur.uid != old.currentEntry?.uid || player.wrappedPlayer.mediaItemCount == 0) {
            setQueue(newState, if (cur?.uid == old.currentEntry?.uid) player.currentPosition else 0, player.playWhenReady)
            return
        }
        val t = ++token
        scope.launch {
            val idx = newState.currentIndex
            var start = (idx - PREPEND).coerceAtLeast(0)
            var end = (idx + APPEND).coerceAtMost(newState.entries.size)
            if (newState.repeat == RepeatMode.All) { start = 0; end = newState.entries.size }
            val before = buildItems(newState, start, idx)
            val after = buildItems(newState, idx + 1, end)
            if (t != token) return@launch
            val p = player.wrappedPlayer
            editing = true
            val native = p.currentMediaItemIndex
            p.removeMediaItems(native + 1, p.mediaItemCount)
            p.removeMediaItems(0, native)
            p.addMediaItems(0, before)
            p.addMediaItems(after)
            p.repeatMode = newState.repeat.toPlayer()
            editing = false
            windowStart = start; windowEnd = end
            _state.value = newState
            syncCurrentFromPlayer(slide = false) // a transition during the edit still updates item + gain
        }
    }

    override fun skipNext() = navigate(_state.value.next())
    override fun skipPrevious(positionMs: Long) = navigate(_state.value.previous(positionMs))

    /** Natural end with repeat off at the last item (ExoPlayer reached STATE_ENDED). */
    fun onEnded() = navigate(_state.value.onTrackEnded())

    private fun navigate(nav: Navigation?) {
        when (val a = nav?.action) {
            null, NavAction.Completed -> Unit
            NavAction.Restart -> player.wrappedPlayer.seekTo(0)
            is NavAction.MoveTo -> {
                if (a.index in windowStart until windowEnd) {
                    player.wrappedPlayer.seekTo(a.index - windowStart, 0)
                } else {
                    setQueue(nav.state, 0, player.playWhenReady)
                }
            }
        }
    }

    private fun syncCurrentFromPlayer(slide: Boolean) {
        val p = player.wrappedPlayer
        val native = p.currentMediaItemIndex
        val uid = p.currentMediaItem?.mediaId?.removePrefix(ID_PREFIX)?.toLongOrNull() ?: return
        val s = _state.value
        val global = (windowStart + native).takeIf { s.entries.getOrNull(it)?.uid == uid }
            ?: s.entries.indexOfFirst { it.uid == uid }.takeIf { it >= 0 } ?: return
        if (global != s.currentIndex) _state.value = s.copy(currentIndex = global)
        applyReplayGain() // before the session broadcasts the transition
        onCurrentChanged?.invoke(_state.value)
        if (slide) slideWindow(native)
    }

    private fun slideWindow(native: Int) {
        val s = _state.value
        val count = player.wrappedPlayer.mediaItemCount
        val ahead = count - 1 - native
        val total = s.entries.size
        if (ahead < SLIDE_AHEAD && windowEnd < total) {
            val from = windowEnd; val to = (windowEnd + APPEND_STEP).coerceAtMost(total)
            val t = token
            scope.launch {
                val items = buildItems(s, from, to)
                if (t != token || windowEnd != from) return@launch
                editing = true; player.wrappedPlayer.addMediaItems(items); editing = false
                windowEnd = to
            }
        }
        if (native < SLIDE_BEHIND && windowStart > 0) {
            val to = windowStart; val from = (windowStart - PREPEND_STEP).coerceAtLeast(0)
            val t = token
            scope.launch {
                val items = buildItems(s, from, to)
                if (t != token || windowStart != to) return@launch
                editing = true; player.wrappedPlayer.addMediaItems(0, items); editing = false
                windowStart = from
            }
        }
    }

    private fun applyReplayGain() {
        val song = _state.value.currentEntry?.let { songCache[it.songId] }
        player.volumes.replayGain = ReplayGainMath.multiplier(song?.replayGain, replayGainMode)
    }

    /** Builds items for logical range [from, to); every entry gets an item (missing songs → placeholder). */
    private suspend fun buildItems(s: QueueState, from: Int, to: Int): List<MediaItem> {
        if (from >= to) return emptyList()
        val slice = s.entries.subList(from, to)
        val missing = slice.map { it.songId }.filter { it !in songCache }.distinct()
        if (missing.isNotEmpty()) songCache.putAll(songs(missing))
        return withContext(io) {
            slice.map { e -> songCache[e.songId]?.let { toMediaItem(e.uid, it, s.playlistContext) } ?: MediaItem.Builder().setMediaId("$ID_PREFIX${e.uid}").build() }
        }
    }

    /** Cached song for a song id (loaded by the last window build). */
    fun cachedSong(songId: Long): Song? = songCache[songId]

    private fun toMediaItem(uid: Long, song: Song, playlistId: String?): MediaItem {
        // Folder-scan songs also have positive ids, so only fall back to MediaStore when the file is unreadable.
        val file = File(song.filePath)
        val uri = if (file.canRead() || song.id <= 0) Uri.fromFile(file)
        else ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
        val art = song.artworkPath?.let(::File)?.takeIf { it.isFile }
        val extras = Bundle().apply {
            playlistId?.let { putString(ListenStatsRecorder.EXTRA_PLAYLIST_ID, it) }
            song.replayGain?.let { rg ->
                rg.trackGain?.let { putDouble("rg_track_gain", it) }
                rg.albumGain?.let { putDouble("rg_album_gain", it) }
                rg.trackPeak?.let { putDouble("rg_track_peak", it) }
                rg.albumPeak?.let { putDouble("rg_album_peak", it) }
            }
        }
        val meta = MediaMetadata.Builder()
            .setTitle(displayTitle(song))
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setDurationMs(song.durationMs)
            .setTrackNumber(song.trackNumber)
            .setGenre(song.genre)
            .setRecordingYear(song.year)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(extras)
            .apply {
                // URI only: Media3's bitmap loader fetches on demand (bytes for a whole repeat-all queue would blow memory).
                if (art != null) setArtworkUri(Uri.fromFile(art))
            }
            .build()
        return MediaItem.Builder().setMediaId("$ID_PREFIX$uid").setUri(uri).setMediaMetadata(meta).build()
    }

    private fun RepeatMode.toPlayer() = when (this) {
        RepeatMode.Off -> Player.REPEAT_MODE_OFF
        RepeatMode.All -> Player.REPEAT_MODE_ALL
        RepeatMode.One -> Player.REPEAT_MODE_ONE
    }

    companion object {
        const val ID_PREFIX = "q:"
        const val PREPEND = 10
        const val APPEND = 30
        const val SLIDE_AHEAD = 10
        const val SLIDE_BEHIND = 5
        const val APPEND_STEP = 20
        const val PREPEND_STEP = 15
    }
}
