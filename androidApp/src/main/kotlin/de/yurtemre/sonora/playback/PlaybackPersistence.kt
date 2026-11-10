package de.yurtemre.sonora.playback

import de.yurtemre.sonora.shared.settings.PlaybackStateStore
import de.yurtemre.sonora.shared.settings.SavedPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Last-session persistence (PLAY-24, audit/playback §11). The service calls the `on*` hooks; [snapshot] and
 * [positionMs] are read on [scope]'s thread (main). Restore is paused at the saved position.
 */
class PlaybackPersistence(
    private val store: PlaybackStateStore,
    private val scope: CoroutineScope,
    /** Current queue/song/shuffle snapshot, or null when nothing is loaded. */
    private val snapshot: () -> SavedPlaybackState?,
    private val positionMs: () -> Long,
    /** "Restore last played song" setting. */
    private val restoreEnabled: suspend () -> Boolean,
) {
    private var periodic: Job? = null

    fun onTrackChanged() = saveNow()
    fun onQueueEdited() = saveNow()

    fun onIsPlayingChanged(playing: Boolean) {
        if (playing) {
            if (periodic?.isActive == true) return
            periodic = scope.launch {
                while (isActive) {
                    delay(PERIODIC_MS)
                    val pos = positionMs()
                    store.savePosition(pos)
                }
            }
        } else {
            periodic?.cancel(); periodic = null
            saveNow() // pause trigger
        }
    }

    fun saveNow() {
        val s = snapshot() ?: return
        scope.launch { store.save(s) }
    }

    /** Saved state to load (paused, seek to [SavedPlaybackState.positionMs]); null if disabled or none saved. */
    suspend fun loadForRestore(): SavedPlaybackState? =
        if (restoreEnabled()) store.load()?.takeIf { it.queueIds.isNotEmpty() || it.songId > 0 } else null

    /** Close player / notification swipe-away / task removed. */
    fun clear() {
        periodic?.cancel(); periodic = null
        scope.launch { store.clear() }
    }

    companion object {
        const val PERIODIC_MS = 15_000L
    }
}
