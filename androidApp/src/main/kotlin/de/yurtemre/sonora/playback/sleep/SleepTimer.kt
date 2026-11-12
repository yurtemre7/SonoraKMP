package de.yurtemre.sonora.playback.sleep

import de.yurtemre.sonora.playback.api.SleepTimerUiState
import de.yurtemre.sonora.shared.audio.SleepTimerEffect
import de.yurtemre.sonora.shared.audio.SleepTimerEvent
import de.yurtemre.sonora.shared.audio.SleepTimerReducer
import de.yurtemre.sonora.shared.audio.SleepTimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Player-side hooks the sleep timer needs; implemented by the playback service. Called on [scope]'s thread (main). */
interface SleepTimerHost {
    val isPlaying: Boolean
    val currentSongId: Long?
    val positionMs: Long
    /** Player duration, falling back to the song duration. */
    val durationMs: Long
    /** Current user volume (0..1). */
    val userVolume: Double
    fun applyVolume(volume: Double)
    fun pauseImmediate()
    /** Faded pause, then restore the user volume; queue kept. */
    fun finalizeStop()
    fun updateControls(active: Boolean)
}

/**
 * Sleep-timer driver (PLAY-26/27, P4-05): feeds 1 s ticks and 250 ms finish-song polls into [SleepTimerReducer]
 * and performs the effects.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val host: SleepTimerHost,
    private val notifier: SleepTimerNotifier,
) {
    private var state = SleepTimerState.Idle
    private var totalMs = 0L
    private var tickJob: Job? = null
    private var pollJob: Job? = null
    private val _ui = MutableStateFlow<SleepTimerUiState>(SleepTimerUiState.Inactive)
    val uiState: StateFlow<SleepTimerUiState> = _ui.asStateFlow()

    val isActive: Boolean get() = state.isActive

    fun start(durationSec: Long, finishSong: Boolean, fadeOutSec: Int) {
        totalMs = durationSec * 1000
        dispatch(SleepTimerEvent.Start(durationSec, finishSong, fadeOutSec, host.userVolume))
        // "Finish the song" alone: enter finishing mode right away instead of after the first tick.
        if (durationSec <= 0 && finishSong) dispatch(SleepTimerEvent.Tick(host.isPlaying, host.currentSongId, host.userVolume))
    }

    fun stop() = dispatch(SleepTimerEvent.Stop)

    /** "+1 min" by default; when idle starts a new timer with the given defaults. */
    fun extend(
        extensionSec: Long = SleepTimerState.EXTEND_SEC,
        defaultFinishSong: Boolean = false,
        defaultFadeOutSec: Int = 0,
    ) {
        val wasActive = state.isActive
        if (!wasActive) totalMs = extensionSec * 1000 else totalMs += extensionSec * 1000
        dispatch(SleepTimerEvent.Extend(extensionSec, defaultFinishSong, defaultFadeOutSec, host.userVolume))
    }

    fun release() {
        tickJob?.cancel(); pollJob?.cancel()
        notifier.cancel()
    }

    private fun dispatch(event: SleepTimerEvent) {
        val t = SleepTimerReducer.reduce(state, event)
        state = t.state
        t.effects.forEach(::perform)
        syncJobs()
        publish()
    }

    private fun perform(effect: SleepTimerEffect) {
        when (effect) {
            is SleepTimerEffect.ApplyVolume -> host.applyVolume(effect.volume)
            SleepTimerEffect.PauseImmediate -> host.pauseImmediate()
            SleepTimerEffect.FinalizeStop -> host.finalizeStop()
            is SleepTimerEffect.UpdateNotification -> notifier.show(effect.remainingSec, effect.finishingSong)
            SleepTimerEffect.CancelNotification -> notifier.cancel()
            is SleepTimerEffect.UpdateControls -> host.updateControls(effect.active)
        }
    }

    private fun syncJobs() {
        val ticking = state.isActive && !state.isFinishingSong
        if (ticking && tickJob?.isActive != true) {
            tickJob = scope.launch {
                while (isActive) {
                    delay(SleepTimerState.TICK_MS)
                    dispatch(SleepTimerEvent.Tick(host.isPlaying, host.currentSongId, host.userVolume))
                }
            }
        } else if (!ticking) {
            tickJob?.cancel(); tickJob = null
        }
        if (state.isFinishingSong && pollJob?.isActive != true) {
            pollJob = scope.launch {
                while (isActive) {
                    delay(SleepTimerState.FINISH_POLL_MS)
                    dispatch(
                        SleepTimerEvent.FinishPoll(host.isPlaying, host.currentSongId, host.positionMs, host.durationMs),
                    )
                }
            }
        } else if (!state.isFinishingSong) {
            pollJob?.cancel(); pollJob = null
        }
    }

    private fun publish() {
        val remaining = state.remainingSec
        _ui.value = when {
            remaining == null -> SleepTimerUiState.Inactive
            state.isFinishingSong -> SleepTimerUiState.FinishSong(state.fadeOutSec, state.isFadingOut)
            else -> SleepTimerUiState.Countdown(
                remainingMs = remaining * 1000,
                totalMs = totalMs.coerceAtLeast(remaining * 1000),
                fadeSeconds = state.fadeOutSec,
                isFading = state.isFadingOut,
                finishSong = state.finishSong,
            )
        }
    }
}
