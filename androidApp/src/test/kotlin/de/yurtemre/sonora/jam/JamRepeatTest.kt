package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.JamRepeat
import de.yurtemre.sonora.shared.jam.JamStateFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host's repeat mode on a guest: Repeat One loops in the guest's own player, never a reload per loop. */
@OptIn(ExperimentalCoroutinesApi::class)
class JamRepeatTest : JamControllerTestBase() {

    @Test fun repeatOneLoadsOnlyTheCurrentSongLooping() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, c, repeat = JamRepeat.One)))
        runCurrent()
        val load = playback.mirrorLoads.last()
        assertEquals("nothing after the song: the player cannot roll on into b", listOf(a.id), load.songIds)
        assertTrue("the guest's player loops it", load.repeatOne)
    }

    @Test fun hostRepeatOneLoopNeverReloadsOrCutsTheGuest() = runTest {
        val jam = controller()
        val link = joined(jam)
        val start = testScheduler.currentTime
        link.deliver(JamStateFrame(state(a, b, repeat = JamRepeat.One)))
        runCurrent()
        val loads = playback.mirrorLoads.size
        repeat(3) { loop ->
            // Both players loop by themselves: the host's seek check then sends "same song, position ~0".
            advanceTimeBy(a.durationMs)
            playback.position = 400 // the guest looped too, a moment ago
            val loopStart = start + (loop + 1) * a.durationMs
            link.deliver(JamStateFrame(state(a, b, positionMs = 0, hostTimeMs = loopStart - 100, repeat = JamRepeat.One)))
            runCurrent()
        }
        // A guest a little behind (not looped yet when the frame comes) is never reloaded either.
        advanceTimeBy(a.durationMs)
        playback.position = a.durationMs - 500
        link.deliver(JamStateFrame(state(a, b, positionMs = 200, hostTimeMs = testScheduler.currentTime, repeat = JamRepeat.One)))
        runCurrent()
        assertEquals("never a reload per loop", loads, playback.mirrorLoads.size)
        assertTrue("never a seek while playing", playback.seeks.isEmpty())
        assertTrue(playback.player.value.isPlaying)
        assertNoEffects()
    }

    @Test fun repeatTurnedOffKeepsTheSongAndBringsTheNextOnesBack() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, c, repeat = JamRepeat.One)))
        runCurrent()
        advanceTimeBy(10_000)
        link.deliver(JamStateFrame(state(a, b, c, positionMs = 10_000, repeat = JamRepeat.Off)))
        runCurrent()
        val load = playback.mirrorLoads.last()
        assertEquals(listOf(a.id, b.id, c.id), load.songIds)
        assertEquals(false, load.repeatOne)
        assertEquals("the playing song stays (no gap, no seek)", a, playback.player.value.currentSong)
        assertTrue(playback.seeks.isEmpty())
    }

    @Test fun repeatAllWrapLetsTheLastSongFinish() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, index = 1, repeat = JamRepeat.All)))
        runCurrent()
        advanceTimeBy(b.durationMs - 5_000)
        val loads = playback.mirrorLoads.size
        // The host wrapped to the first song while this phone still has 5 s of b left: b finishes, uncut.
        link.deliver(JamStateFrame(state(a, b, index = 0, positionMs = 0, repeat = JamRepeat.All)))
        runCurrent()
        assertEquals(b, playback.player.value.currentSong)
        assertTrue(playback.mirrorLoads.drop(loads).all { it.songIds.first() == b.id })
        playback.finishSong()
        runCurrent()
        assertEquals(a, playback.player.value.currentSong)
        assertTrue(playback.player.value.isPlaying)
    }
}
