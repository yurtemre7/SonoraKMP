package de.yurtemre.sonora.shared.audio

import de.yurtemre.sonora.shared.audio.SleepTimerEffect.ApplyVolume
import de.yurtemre.sonora.shared.audio.SleepTimerEffect.FinalizeStop
import de.yurtemre.sonora.shared.audio.SleepTimerEffect.PauseImmediate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepTimerStateTest {
    private fun run(s: SleepTimerState, e: SleepTimerEvent) = SleepTimerReducer.reduce(s, e)
    private fun tick(s: SleepTimerState, vol: Double = 0.8) = run(s, SleepTimerEvent.Tick(true, 1L, vol))

    @Test fun extendFivePlusTwo() {
        val s = run(SleepTimerState.Idle, SleepTimerEvent.Start(300, false, 10, 1.0)).state
        val e = run(s, SleepTimerEvent.Extend(120, false, 10, 1.0)).state
        assertEquals(420L, e.remainingSec)
        assertEquals(60L, run(SleepTimerState.Idle, SleepTimerEvent.Extend(60, true, 10, 1.0)).state.remainingSec)
    }

    @Test fun stopRestoresUserVolumeNotMidFade() {
        var s = run(SleepTimerState.Idle, SleepTimerEvent.Start(5, false, 10, 0.8)).state
        val t = tick(s)
        assertEquals(0.32, t.effects.filterIsInstance<ApplyVolume>().single().volume, 1e-9)
        s = tick(t.state, vol = 0.32).state // user volume reported mid-fade is ignored
        val stop = run(s, SleepTimerEvent.Stop)
        assertEquals(ApplyVolume(0.8), stop.effects.first())
        assertFalse(stop.state.isActive)
    }

    @Test fun standardTimerFinalizesWithoutClearingQueue() {
        var s = run(SleepTimerState.Idle, SleepTimerEvent.Start(2, false, 0, 1.0)).state
        s = tick(s).state
        val end = tick(s)
        assertTrue(FinalizeStop in end.effects)
        assertFalse(PauseImmediate in end.effects)
        assertFalse(end.state.isActive)
    }

    @Test fun newTimerDuringFinalizeSurvives() {
        val end = tick(run(SleepTimerState.Idle, SleepTimerEvent.Start(1, false, 0, 1.0)).state)
        // state was already cleared by finalize; a new start on top of it is untouched
        val fresh = run(end.state, SleepTimerEvent.Start(600, false, 10, 1.0)).state
        assertEquals(600L, fresh.remainingSec)
        assertEquals(599L, tick(fresh).state.remainingSec)
    }

    @Test fun finishSongNoCountdownFade() {
        var s = run(SleepTimerState.Idle, SleepTimerEvent.Start(5, true, 10, 0.8)).state
        repeat(4) { val t = tick(s); assertTrue(t.effects.none { it is ApplyVolume }); s = t.state }
        val t = tick(s)
        assertTrue(t.state.isFinishingSong)
        assertEquals(ApplyVolume(0.8), t.effects.first())
        s = t.state
        val fade = run(s, SleepTimerEvent.FinishPoll(true, 1L, 175_000, 180_000))
        assertEquals(ApplyVolume(0.8 * 0.5), fade.effects.single())
        val boundary = run(fade.state, SleepTimerEvent.FinishPoll(true, 1L, 179_800, 180_000))
        assertTrue(PauseImmediate in boundary.effects && FinalizeStop in boundary.effects)
        val changed = run(s, SleepTimerEvent.FinishPoll(true, 2L, 0, 1000))
        assertTrue(PauseImmediate in changed.effects)
        assertFalse(PauseImmediate in run(s, SleepTimerEvent.FinishPoll(false, 1L, 0, 1000)).effects)
    }

    @Test fun stopClearsFinishingFlag() {
        val s = tick(run(SleepTimerState.Idle, SleepTimerEvent.Start(1, true, 0, 1.0)).state).state
        assertTrue(s.isFinishingSong)
        assertFalse(run(s, SleepTimerEvent.Stop).state.isFinishingSong)
        assertFalse(run(s, SleepTimerEvent.Extend(60, true, 0, 1.0)).state.isFinishingSong)
    }
}
