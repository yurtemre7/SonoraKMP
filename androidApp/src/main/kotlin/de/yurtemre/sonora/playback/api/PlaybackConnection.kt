package de.yurtemre.sonora.playback.api

import de.yurtemre.sonora.shared.model.EqPreset
import de.yurtemre.sonora.shared.model.MfxPreset
import de.yurtemre.sonora.shared.model.QueueState
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
    /** Undo of a remove: insert [songId] at play-order position [logicalIndex]. */
    fun insertQueueItem(songId: Long, logicalIndex: Int)
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
    fun setCustomPitch(pitch: Float)
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
    /** [durationMs] 0 with [finishSong] = stop after the current song; both = count down, then finish that song. */
    fun startSleepTimer(durationMs: Long, finishSong: Boolean, fadeSeconds: Int)
    /** Stop at the end of the [count]th song, counting the current one (see `SleepTimerEvent.StartTracks`). */
    fun startSleepTimerTracks(count: Int, fadeSeconds: Int)
    /** Extends the running timer: by [deltaMs] for a countdown, by one song in track-count mode. */
    fun extendSleepTimer(deltaMs: Long)
    fun cancelSleepTimer()

    // Jam (synchronized playback over the LAN)
    /** The player's position right now in ms (main thread); 0 when nothing is loaded. */
    fun currentPositionMs(): Long

    /** The loaded queue and position, kept by a Jam guest to bring back afterwards; null when nothing is loaded. */
    fun queueSnapshot(): QueueSnapshot?

    /**
     * Jam guest: plays [songs] (only songs this phone has) from [index] at [positionMs], or paused when
     * [playWhenReady] is false. While mirroring, nothing is persisted (the saved session stays the guest's own
     * queue), repeat is off, playback runs at normal speed and pitch, and local skips are ignored. When the song at
     * [index] is the one already playing it keeps playing without a gap; [positionMs] is then ignored.
     */
    fun setMirrorQueue(songs: List<Song>, index: Int, positionMs: Long, playWhenReady: Boolean)

    /** Ends the Jam mirror: [snapshot] comes back paused (null leaves an empty queue) and persistence resumes. */
    fun endMirror(snapshot: QueueSnapshot?)
}

/** A queue with its position, as [PlaybackConnection.queueSnapshot] returns it. */
data class QueueSnapshot(val queue: QueueState, val positionMs: Long)
