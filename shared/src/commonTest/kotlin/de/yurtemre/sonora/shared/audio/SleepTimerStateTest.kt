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

    private fun changed(entry: Long?, song: Long? = entry, playing: Boolean = true) =
        SleepTimerEvent.EntryChanged(entry, song, playing, 1.0)

    @Test
    fun trackCountStopsAtTheEndOfTheNthSong() {
        // 3 songs: entry 1 is the first. Nothing happens until the 3rd song becomes current.
        var s = SleepTimerState.Idle.on(SleepTimerEvent.StartTracks(3, fadeOutSec = 4, userVolume = 1.0, currentEntryId = 1L)).state
        assertTrue(s.isActive); assertEquals(3, s.tracksRemaining)
        s = s.on(changed(1L)).state
        assertEquals(3, s.tracksRemaining)
        s = s.on(changed(2L)).state
        assertEquals(2, s.tracksRemaining); assertTrue(!s.isFinishingSong)
        s = s.on(changed(3L)).state
        // On the last song it hands over to finish-song mode, keeping the fade.
        assertTrue(s.isFinishingSong); assertEquals(3L, s.targetSongId); assertEquals(null, s.tracksRemaining); assertEquals(4, s.fadeOutSec)
        val end = s.on(SleepTimerEvent.FinishPoll(true, 3L, positionMs = 99_900, durationMs = 100_000))
        assertEquals(SleepTimerState.Idle, end.state)
        assertTrue(SleepTimerEffect.FinalizeStop in end.effects)
    }

    @Test
    fun trackCountCountsEveryChangeEvenWhenSkippingFast() {
        // Two songs, on A: skipping to B and straight on to C must stop (B was the last allowed song).
        var s = SleepTimerState.Idle.on(SleepTimerEvent.StartTracks(2, 0, 1.0, currentEntryId = 1L)).state
        s = s.on(changed(2L, playing = false)).state // B, paused for the moment: hand-over waits
        assertEquals(1, s.tracksRemaining)
        val t = s.on(changed(3L))
        assertEquals(SleepTimerState.Idle, t.state)
        assertTrue(SleepTimerEffect.PauseImmediate in t.effects)
        // Skipping back to a song already played still counts as a change.
        var r = SleepTimerState.Idle.on(SleepTimerEvent.StartTracks(3, 0, 1.0, currentEntryId = 1L)).state
        r = r.on(changed(2L)).state
        r = r.on(changed(1L)).state
        assertTrue(r.isFinishingSong)
    }

    @Test
    fun trackCountOfOneFinishesTheCurrentSongAndPausedTimersWaitForPlayback() {
        fun tick(id: Long?, playing: Boolean = true) = SleepTimerEvent.Tick(playing, id, 1.0)
        var s = SleepTimerState.Idle.on(SleepTimerEvent.StartTracks(1, 0, 1.0, currentEntryId = 5L)).state
        s = s.on(tick(5L, playing = false)).state
        assertEquals(1, s.tracksRemaining) // paused: keeps waiting
        s = s.on(tick(5L)).state
        assertTrue(s.isFinishingSong); assertEquals(5L, s.targetSongId)
        // Nothing playing when started: the first entry seen becomes song 1 without counting a change.
        var n = SleepTimerState.Idle.on(SleepTimerEvent.StartTracks(2, 0, 1.0, currentEntryId = null)).state
        n = n.on(changed(9L)).state
        assertEquals(2, n.tracksRemaining)
        assertEquals(n, n.on(changed(null)).state) // queue emptied: ignored
        // Entry changes do nothing without a track-count timer.
        assertEquals(SleepTimerState.Idle, SleepTimerState.Idle.on(changed(1L)).state)
    }

    @Test
    fun trackCountExtendAddsASongAndReplacingACountdownCancelsItsNotification() {
        var s = SleepTimerState.Idle.on(SleepTimerEvent.StartTracks(2, 0, 1.0, currentEntryId = 1L)).state
        s = s.on(SleepTimerEvent.Extend(60, false, 0, 1.0)).state
        assertEquals(3, s.tracksRemaining)
        assertEquals(SleepTimerState.Idle, s.on(SleepTimerEvent.Stop).state)

        val countdown = SleepTimerState.Idle.on(SleepTimerEvent.Start(600, false, 0, 1.0)).state
        val replaced = countdown.on(SleepTimerEvent.StartTracks(3, 0, 1.0, currentEntryId = 1L))
        assertTrue(SleepTimerEffect.CancelNotification in replaced.effects)
        assertTrue(SleepTimerEffect.UpdateControls(true) in replaced.effects)
    }

    @Test
    fun movingPastTheLastSongPausesEvenWhenTheNextEntryHoldsTheSameSong() {
        var s = SleepTimerState.Idle.on(SleepTimerEvent.StartTracks(2, 0, 1.0, currentEntryId = 1L)).state
        s = s.on(changed(entry = 2L, song = 7L)).state
        assertTrue(s.isFinishingSong); assertEquals(2L, s.lastEntryId)
        // Same entry again (e.g. a re-emitted state): nothing happens.
        assertEquals(s, s.on(changed(entry = 2L, song = 7L)).state)
        // Entry 3 holds the same song 7: the poll alone would accept it, the entry check must not.
        val t = s.on(changed(entry = 3L, song = 7L))
        assertEquals(SleepTimerState.Idle, t.state)
        assertTrue(SleepTimerEffect.PauseImmediate in t.effects)
    }
}
