package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.JamStateFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guest sync rules around song boundaries, drift and a player that reports changes late. */
@OptIn(ExperimentalCoroutinesApi::class)
class JamControllerSyncTest : JamControllerTestBase() {

    @Test fun twoQuickFramesDoNotSwallowTheStart() = runTest {
        val jam = controller()
        playback.deferPlayer = true
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        runCurrent()
        // A settings change right after (or the join's sendTo after a broadcast): the player has not reported yet.
        link.deliver(JamStateFrame(state(a, b, canControl = true)))
        runCurrent()
        playback.catchUp()
        runCurrent()
        assertEquals("loaded once, never reloaded", 1, playback.mirrorLoads.size)
        assertTrue(playback.player.value.isPlaying)
        assertEquals(a, playback.player.value.currentSong)
    }

    @Test fun twoQuickFramesKeepAFutureStart() = runTest {
        val jam = controller()
        playback.deferPlayer = true
        advanceTimeBy(1_000)
        val link = joined(jam)
        val at = testScheduler.currentTime + 300
        link.deliver(JamStateFrame(state(a, b, hostTimeMs = at)))
        runCurrent()
        link.deliver(JamStateFrame(state(a, b, hostTimeMs = at, canControl = true)))
        runCurrent()
        playback.catchUp()
        advanceTimeBy(400)
        playback.catchUp()
        assertEquals(1, playback.mirrorLoads.size)
        assertEquals(listOf(at), playback.playAt)
        assertTrue(playback.player.value.isPlaying)
    }

    @Test fun guestAheadOfTheHostNeverStartsTheNextSongFirst() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, c)))
        advanceTimeBy(100_000)
        // The host seeked back: it is 60 s behind this phone.
        link.deliver(JamStateFrame(state(a, b, c, positionMs = 40_000)))
        runCurrent()
        assertTrue("never a seek mid-song", playback.seeks.isEmpty())
        assertTrue(playback.player.value.isPlaying)
        assertEquals("only the current song is left", listOf(1L), playback.mirrorLoads.last().songIds)

        // A queue change meanwhile must not bring the following songs back.
        link.deliver(JamStateFrame(state(a, b, c, b, positionMs = 41_000)))
        runCurrent()
        assertEquals(listOf(1L), playback.mirrorLoads.last().songIds)

        advanceTimeBy(80_000)
        playback.finishSong() // our copy of A ends 60 s before the host's
        runCurrent()
        assertFalse(playback.player.value.isPlaying)
        assertEquals(a, playback.player.value.currentSong)

        advanceTimeBy(60_000)
        link.deliver(JamStateFrame(state(a, b, c, b, index = 1)))
        runCurrent()
        assertEquals(MirrorLoad(listOf(2, 3, 2), 0, 0, true), playback.mirrorLoads.last())
        assertTrue(playback.player.value.isPlaying)
    }

    @Test fun trailingDriftIsCorrectedAtTheSongChange() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, c)))
        advanceTimeBy(100_000)
        link.deliver(JamStateFrame(state(a, b, c, positionMs = 103_000))) // this phone trails by 3 s
        runCurrent()
        assertEquals(listOf(1L), playback.mirrorLoads.last().songIds)
        val loads = playback.mirrorLoads.size

        advanceTimeBy(77_000) // the host's A ends; ours has 3 s left
        link.deliver(JamStateFrame(state(a, b, c, index = 1)))
        runCurrent()
        assertEquals("our song is not cut", loads, playback.mirrorLoads.size)
        assertTrue(playback.player.value.isPlaying)

        advanceTimeBy(3_000)
        playback.finishSong()
        runCurrent()
        assertEquals(MirrorLoad(listOf(2, 3), 0, 3_000, true), playback.mirrorLoads.last())
        assertTrue(playback.seeks.isEmpty())
    }

    @Test fun inSyncSongChangeIsGapless() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        advanceTimeBy(179_700)
        link.deliver(JamStateFrame(state(a, b, index = 1)))
        runCurrent()
        assertEquals("the player rolls on by itself", 1, playback.mirrorLoads.size)
        assertTrue(playback.player.value.isPlaying)
    }

    @Test fun hostPausePausesAGuestThatIsStillBuffering() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        advanceTimeBy(5_000)
        playback.player.value = playback.player.value.copy(isPlaying = false, isBuffering = true)
        link.deliver(JamStateFrame(state(a, b, playing = false, positionMs = 5_000)))
        runCurrent()
        assertTrue("pause" in playback.calls)
    }

    @Test fun hostPauseNearTheEndOfASongThatIsNotTheLastPausesTheGuest() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        advanceTimeBy(178_500)
        link.deliver(JamStateFrame(state(a, b, playing = false, positionMs = 178_500)))
        runCurrent()
        assertTrue("pause" in playback.calls)
        assertFalse(playback.player.value.isPlaying)
    }

    @Test fun hostStopAtTheEndOfTheQueueLetsTheLastSongEnd() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, index = 1)))
        advanceTimeBy(179_000)
        link.deliver(JamStateFrame(state(a, b, index = 1, playing = false, positionMs = 180_000)))
        runCurrent()
        assertFalse("pause" in playback.calls)
    }

    @Test fun hostSkipWhileTheGuestHasSecondsLeftNeverRollsIntoTheNextSongFromZero() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, c)))
        advanceTimeBy(175_000) // 5 s left on this phone; the host skips now
        link.deliver(JamStateFrame(state(a, b, c, index = 1)))
        runCurrent()
        assertEquals("our song ends, then stops: nothing rolls on from 0", listOf(1L), playback.mirrorLoads.last().songIds)
        assertTrue(playback.player.value.isPlaying)
        advanceTimeBy(5_000)
        playback.finishSong()
        runCurrent()
        assertEquals(MirrorLoad(listOf(2, 3), 0, 5_000, true), playback.mirrorLoads.last())
    }

    @Test fun noCutBeforeThePlayerReportedItsPosition() = runTest {
        val jam = controller()
        playback.deferPlayer = true
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        runCurrent()
        link.drop() // the player has not reported the load yet: its position may still be the guest's own song
        runCurrent()
        assertEquals(1, playback.mirrorLoads.size)
    }

    @Test fun theGuestsOwnSameSongIsNotMistakenForTheMirror() = runTest {
        val jam = controller()
        // The guest was listening to A itself, 90 s in.
        playback.player.value = playback.player.value.copy(currentSong = a, currentEntryUid = 5, isPlaying = true, durationMs = a.durationMs)
        playback.position = 90_000
        playback.deferPlayer = true
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        runCurrent()
        link.deliver(JamStateFrame(state(a, b, canControl = true)))
        runCurrent()
        assertEquals("no drift cut from the guest's own position", 1, playback.mirrorLoads.size)
        assertTrue(playback.seeks.isEmpty())
    }

    @Test fun afterAReconnectTheFollowingSongsComeBack() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        advanceTimeBy(10_000)
        val again = FakeLink(offsetMs = 0)
        connector.reconnects.addLast(again)
        link.drop()
        runCurrent()
        assertEquals(listOf(1L), playback.mirrorLoads.last().songIds)
        again.deliver(JamStateFrame(state(a, b, positionMs = 10_000)))
        runCurrent()
        assertEquals("in sync again: B follows A gaplessly", listOf(1L, 2L), playback.mirrorLoads.last().songIds)
    }

    @Test fun keepaliveFramesChangeNothing() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        runCurrent()
        val calls = playback.calls.size
        repeat(5) {
            advanceTimeBy(JamHostSession.KEEPALIVE_MS)
            link.deliver(JamStateFrame(state(a, b, positionMs = testScheduler.currentTime)))
            runCurrent()
        }
        assertEquals("no reload, seek, play or pause", calls, playback.calls.size)
        assertTrue(playback.player.value.isPlaying)
    }
}
