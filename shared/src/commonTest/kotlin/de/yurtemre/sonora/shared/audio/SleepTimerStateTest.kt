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

    @Test
    fun trackCountStopsAtTheEndOfTheNthSong() {
        fun tick(id: Long?, playing: Boolean = true) = SleepTimerEvent.Tick(playing, id, 1.0)
        // 3 songs: song 1 is the first. Nothing happens until the 3rd song becomes current.
        var s = SleepTimerState.Idle.on(SleepTimerEvent.StartTracks(3, fadeOutSec = 4, userVolume = 1.0, currentSongId = 1L)).state
        assertTrue(s.isActive); assertEquals(3, s.tracksRemaining)
        s = s.on(tick(1L)).state
        assertEquals(3, s.tracksRemaining)
        s = s.on(tick(2L)).state
        assertEquals(2, s.tracksRemaining); assertTrue(!s.isFinishingSong)
        s = s.on(tick(3L)).state
        // On the last song it hands over to finish-song mode, keeping the fade.
        assertTrue(s.isFinishingSong); assertEquals(3L, s.targetSongId); assertEquals(null, s.tracksRemaining); assertEquals(4, s.fadeOutSec)
        val end = s.on(SleepTimerEvent.FinishPoll(true, 3L, positionMs = 99_900, durationMs = 100_000))
        assertEquals(SleepTimerState.Idle, end.state)
        assertTrue(SleepTimerEffect.FinalizeStop in end.effects)
    }

    @Test
    fun trackCountOfOneFinishesTheCurrentSongAndPausedTimersWaitForPlayback() {
        fun tick(id: Long?, playing: Boolean = true) = SleepTimerEvent.Tick(playing, id, 1.0)
        var s = SleepTimerState.Idle.on(SleepTimerEvent.StartTracks(1, 0, 1.0, currentSongId = 5L)).state
        s = s.on(tick(5L, playing = false)).state
        assertEquals(1, s.tracksRemaining) // paused: keeps waiting
        s = s.on(tick(5L)).state
        assertTrue(s.isFinishingSong); assertEquals(5L, s.targetSongId)
        // Nothing playing when started: the first song seen becomes song 1 without counting a change.
        var n = SleepTimerState.Idle.on(SleepTimerEvent.StartTracks(2, 0, 1.0, currentSongId = null)).state
        n = n.on(tick(9L)).state
        assertEquals(2, n.tracksRemaining)
    }

    @Test
    fun trackCountSkippingPastTheLastSongPausesAndExtendAddsASong() {
        fun tick(id: Long?, playing: Boolean = true) = SleepTimerEvent.Tick(playing, id, 1.0)
        var s = SleepTimerState.Idle.on(SleepTimerEvent.StartTracks(2, 0, 1.0, currentSongId = 1L)).state
        s = s.on(SleepTimerEvent.Extend(60, false, 0, 1.0)).state
        assertEquals(3, s.tracksRemaining)
        // Paused on the last song, then skipped on: pause immediately.
        var p = SleepTimerState.Idle.on(SleepTimerEvent.StartTracks(1, 0, 1.0, currentSongId = 1L)).state
        val t = p.on(tick(2L, playing = false))
        assertEquals(SleepTimerState.Idle, t.state)
        assertTrue(SleepTimerEffect.PauseImmediate in t.effects)
        assertEquals(SleepTimerState.Idle, s.on(SleepTimerEvent.Stop).state)
    }
}
