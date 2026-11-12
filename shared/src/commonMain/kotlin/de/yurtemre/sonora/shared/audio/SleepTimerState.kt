package de.yurtemre.sonora.shared.audio

/**
 * Pure sleep-timer reducer (PLAY-26, audit/playback §8.3). The Android driver feeds 1 s [SleepTimerEvent.Tick]s
 * and, in finish-song mode, 250 ms [SleepTimerEvent.FinishPoll]s, then performs the returned [SleepTimerEffect]s.
 */
data class SleepTimerState(
    /** Remaining countdown in seconds; null = no timer. 0 while finishing the song. */
    val remainingSec: Long? = null,
    val finishSong: Boolean = false,
    val fadeOutSec: Int = 0,
    val isFadingOut: Boolean = false,
    val isFinishingSong: Boolean = false,
    val targetSongId: Long? = null,
    val originalVolume: Double = 1.0,
) {
    val isActive: Boolean get() = remainingSec != null

    companion object {
        const val TICK_MS = 1000L
        const val FINISH_POLL_MS = 250L
        const val EXTEND_SEC = 60L
        val Idle = SleepTimerState()
    }
}

sealed interface SleepTimerEvent {
    /** Start (replaces any timer). [userVolume] = current user volume. */
    data class Start(val durationSec: Long, val finishSong: Boolean, val fadeOutSec: Int, val userVolume: Double) : SleepTimerEvent
    /** One-second tick; [playing]/[currentSongId] decide whether finish-song mode starts at expiry. */
    data class Tick(val playing: Boolean, val currentSongId: Long?, val userVolume: Double) : SleepTimerEvent
    /** Finish-song poll. [durationMs] = player duration ?: song duration. */
    data class FinishPoll(val playing: Boolean, val currentSongId: Long?, val positionMs: Long, val durationMs: Long) : SleepTimerEvent
    data object Stop : SleepTimerEvent
    /** Extend; when idle, starts a new timer of [extensionSec] with the given defaults. */
    data class Extend(val extensionSec: Long, val defaultFinishSong: Boolean, val defaultFadeOutSec: Int, val userVolume: Double) : SleepTimerEvent
}

sealed interface SleepTimerEffect {
    /** Set player volume immediately (bypasses transport fade). */
    data class ApplyVolume(val volume: Double) : SleepTimerEffect
    /** Pause without fade (song boundary / song changed). */
    data object PauseImmediate : SleepTimerEffect
    /** Finalize: faded pause, then restore the user volume. Queue is kept. */
    data object FinalizeStop : SleepTimerEffect
    data class UpdateNotification(val remainingSec: Long, val finishingSong: Boolean, val thenFinishSong: Boolean = false) : SleepTimerEffect
    data object CancelNotification : SleepTimerEffect
    /** Media-session custom-action state changed (active/inactive). */
    data class UpdateControls(val active: Boolean) : SleepTimerEffect
}

data class SleepTimerTransition(val state: SleepTimerState, val effects: List<SleepTimerEffect> = emptyList())

object SleepTimerReducer {
    fun reduce(state: SleepTimerState, event: SleepTimerEvent): SleepTimerTransition = when (event) {
        is SleepTimerEvent.Start -> start(state, event.durationSec, event.finishSong, event.fadeOutSec, event.userVolume)
        is SleepTimerEvent.Tick -> tick(state, event)
        is SleepTimerEvent.FinishPoll -> poll(state, event)
        SleepTimerEvent.Stop -> stop(state)
        is SleepTimerEvent.Extend -> extend(state, event)
    }

    private fun start(state: SleepTimerState, durationSec: Long, finishSong: Boolean, fade: Int, userVolume: Double): SleepTimerTransition {
        val stopped = stop(state)
        val s = SleepTimerState(
            remainingSec = durationSec,
            finishSong = finishSong,
            fadeOutSec = fade,
            originalVolume = userVolume,
        )
        return SleepTimerTransition(
            s,
            stopped.effects.filterIsInstance<SleepTimerEffect.ApplyVolume>() +
                listOf(SleepTimerEffect.UpdateControls(true), SleepTimerEffect.UpdateNotification(durationSec, false, finishSong)),
        )
    }

    private fun tick(state: SleepTimerState, e: SleepTimerEvent.Tick): SleepTimerTransition {
        val current = state.remainingSec ?: return SleepTimerTransition(state)
        if (state.isFinishingSong) return SleepTimerTransition(state)
        val remaining = current - 1
        if (remaining <= 0) {
            return if (state.finishSong && e.playing && e.currentSongId != null) {
                SleepTimerTransition(
                    state.copy(
                        remainingSec = 0, isFinishingSong = true, targetSongId = e.currentSongId,
                        originalVolume = e.userVolume, isFadingOut = false,
                    ),
                    listOf(SleepTimerEffect.ApplyVolume(e.userVolume), SleepTimerEffect.UpdateNotification(0, true)),
                )
            } else {
                finalize()
            }
        }
        var s = state.copy(remainingSec = remaining)
        val effects = mutableListOf<SleepTimerEffect>()
        if (!state.finishSong && state.fadeOutSec > 0 && remaining <= state.fadeOutSec) {
            if (!s.isFadingOut) s = s.copy(isFadingOut = true, originalVolume = e.userVolume)
            val fraction = remaining.toDouble() / state.fadeOutSec
            effects += SleepTimerEffect.ApplyVolume((s.originalVolume * fraction).coerceIn(0.0, 1.0))
        }
        effects += SleepTimerEffect.UpdateNotification(remaining, false, state.finishSong)
        return SleepTimerTransition(s, effects)
    }

    private fun poll(state: SleepTimerState, e: SleepTimerEvent.FinishPoll): SleepTimerTransition {
        if (!state.isFinishingSong || state.remainingSec == null) return SleepTimerTransition(state)
        if (!e.playing) return finalize()
        if (e.currentSongId == null || (state.targetSongId != null && e.currentSongId != state.targetSongId)) {
            return finalize(immediatePause = true)
        }
        val remainingMs = e.durationMs - e.positionMs
        if (remainingMs <= FINISH_BOUNDARY_MS || e.positionMs >= e.durationMs) return finalize(immediatePause = true)
        if (state.fadeOutSec > 0 && remainingMs / 1000 <= state.fadeOutSec) {
            val fraction = remainingMs.toDouble() / (state.fadeOutSec * 1000.0)
            return SleepTimerTransition(
                state.copy(isFadingOut = true),
                listOf(SleepTimerEffect.ApplyVolume((state.originalVolume * fraction).coerceIn(0.0, 1.0))),
            )
        }
        return SleepTimerTransition(state)
    }

    private const val FINISH_BOUNDARY_MS = 250L

    /** State is cleared up front, so a timer started while the finalize pause fade runs survives. */
    private fun finalize(immediatePause: Boolean = false): SleepTimerTransition = SleepTimerTransition(
        SleepTimerState.Idle,
        buildList {
            add(SleepTimerEffect.CancelNotification)
            if (immediatePause) add(SleepTimerEffect.PauseImmediate)
            add(SleepTimerEffect.FinalizeStop)
            add(SleepTimerEffect.UpdateControls(false))
        },
    )

    private fun stop(state: SleepTimerState): SleepTimerTransition = SleepTimerTransition(
        SleepTimerState.Idle,
        buildList {
            if (state.isFadingOut) add(SleepTimerEffect.ApplyVolume(state.originalVolume))
            add(SleepTimerEffect.UpdateControls(false))
            add(SleepTimerEffect.CancelNotification)
        },
    )

    private fun extend(state: SleepTimerState, e: SleepTimerEvent.Extend): SleepTimerTransition {
        val remaining = state.remainingSec
            ?: return start(state, e.extensionSec, e.defaultFinishSong, e.defaultFadeOutSec, e.userVolume)
        val restore = if (state.isFadingOut) listOf(SleepTimerEffect.ApplyVolume(state.originalVolume)) else emptyList()
        val restoredVolume = if (state.isFadingOut) state.originalVolume else e.userVolume
        val started = start(
            state.copy(isFadingOut = false, isFinishingSong = false, targetSongId = null),
            remaining + e.extensionSec, state.finishSong, state.fadeOutSec, restoredVolume,
        )
        return started.copy(effects = restore + started.effects)
    }
}
