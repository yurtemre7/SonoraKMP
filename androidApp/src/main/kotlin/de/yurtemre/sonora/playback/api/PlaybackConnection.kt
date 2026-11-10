package de.yurtemre.sonora.playback.api

import de.yurtemre.sonora.shared.model.EqPreset
import de.yurtemre.sonora.shared.model.MfxPreset
import de.yurtemre.sonora.shared.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing playback contract (P1-10). Implemented by a MediaController-backed
 * `PlaybackConnectionImpl` (P4-12). Every PLAY-* user action has a command here.
 */
interface PlaybackConnection {
    val player: StateFlow<PlayerUiState>
    val queue: StateFlow<QueueUiState>
    val sleepTimer: StateFlow<SleepTimerUiState>
    val mfx: StateFlow<MfxUiState>

    /** 200 ms ticker while playing and subscribed (PLAY-25). */
    val positionMs: Flow<Long>

    // PLAY-02 load & play
    fun playSong(song: Song, list: List<Song>, playlistId: String? = null)
    fun playShuffled(list: List<Song>, playlistId: String? = null)

    // PLAY-03/04
    fun setShuffle(enabled: Boolean)
    fun toggleShuffle()
    fun cycleRepeat()

    // PLAY-05 play next / add to queue
    fun playNext(songs: List<Song>)
    fun addToQueue(songs: List<Song>)

    // PLAY-06 remove / reorder
    fun removeFromQueue(entryUid: Long)
    fun moveQueueItem(from: Int, to: Int)
    fun skipToQueueItem(entryUid: Long)

    // PLAY-07/09 transport
    fun play()
    fun pause()
    fun togglePlayPause()
    fun skipToNext()
    fun skipToPrevious()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)

    // PLAY-11 user volume
    fun setUserVolume(volume: Float)

    // PLAY-12..16 effects
    fun setCustomSpeed(speed: Float)
    fun setMfxPreset(preset: MfxPreset)
    fun setBassBoost(enabled: Boolean)
    fun setWarmth(enabled: Boolean)
    fun setLoFi(enabled: Boolean)
    fun setCustomEqEnabled(enabled: Boolean)
    fun setCustomEqBandGain(band: Int, gain: Float)
    fun applyEqPreset(preset: EqPreset)
    fun resetAllEffects()

    // PLAY-21 close player (+ undo for mini-player swipe)
    fun closePlayer()
    fun undoClosePlayer()

    // PLAY-26 sleep timer
    fun startSleepTimer(durationMs: Long, fadeSeconds: Int)
    fun startSleepTimerFinishSong(fadeSeconds: Int)
    fun extendSleepTimer(deltaMs: Long)
    fun cancelSleepTimer()
}
