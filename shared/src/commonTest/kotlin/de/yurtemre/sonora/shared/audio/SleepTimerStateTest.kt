package de.yurtemre.sonora.shared.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SleepTimerStateTest {
    private fun SleepTimerState.on(e: SleepTimerEvent) = SleepTimerReducer.reduce(this, e)

    @Test
    fun durationPlusFinishSongFinishOnlyAndExtend() {
        val tick = SleepTimerEvent.Tick(playing = true, currentSongId = 7L, userVolume = 1.0)
        // 2 s + finish song: counts down, then keeps playing song 7 to its end (fading over the last 5 s).
        var s = SleepTimerState.Idle.on(SleepTimerEvent.Start(2, finishSong = true, fadeOutSec = 5, userVolume = 1.0)).state
        s = s.on(tick).state
        assertEquals(1L, s.remainingSec)
        s = s.on(tick).state
        assertTrue(s.isFinishingSong); assertEquals(7L, s.targetSongId)
        val fading = s.on(SleepTimerEvent.FinishPoll(true, 7L, positionMs = 97_500, durationMs = 100_000))
        assertEquals(SleepTimerEffect.ApplyVolume(0.5), fading.effects.single())
        val end = fading.state.on(SleepTimerEvent.FinishPoll(true, 7L, positionMs = 99_900, durationMs = 100_000))
        assertEquals(SleepTimerState.Idle, end.state)
        assertTrue(SleepTimerEffect.FinalizeStop in end.effects)

        // Finish song only: the first tick enters FinishingSong; a song change stops.
        var f = SleepTimerState.Idle.on(SleepTimerEvent.Start(0, finishSong = true, fadeOutSec = 0, userVolume = 1.0)).state
        f = f.on(tick).state
        assertTrue(f.isFinishingSong)
        assertEquals(SleepTimerState.Idle, f.on(SleepTimerEvent.FinishPoll(true, 8L, 0, 100_000)).state)

        // Extend while finishing: back to a 60 s countdown that still finishes the song.
        val ext = f.on(SleepTimerEvent.Extend(60, false, 0, 1.0)).state
        assertEquals(60L, ext.remainingSec); assertTrue(!ext.isFinishingSong && ext.finishSong)
    }
}
