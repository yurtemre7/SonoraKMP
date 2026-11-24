package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.JamStateFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A guest's phone paused itself (headphones unplugged, audio focus lost for good): the host's frames must never
 * start it again (music out of the speaker); only the user, or the phone itself once the route is safe, resumes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JamSystemPauseTest : JamControllerTestBase() {
    private fun JamController.joinedState() = state.value as JamUiState.Joined

    private suspend fun TestScope.playingGuest(): Pair<JamController, FakeLink> {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        runCurrent()
        assertTrue(playback.player.value.isPlaying)
        return jam to link
    }

    @Test fun unpluggedGuestStaysPausedThroughTheHostsKeepalive() = runTest {
        val (jam, link) = playingGuest()
        advanceTimeBy(10_000)
        playback.systemPause()
        runCurrent()
        assertTrue(jam.joinedState().pausedBySystem)
        val plays = playback.playAt.size
        advanceTimeBy(30_000)
        link.deliver(JamStateFrame(state(a, b, positionMs = 40_000))) // keepalive: the host still plays
        runCurrent()
        link.deliver(JamStateFrame(state(a, b, positionMs = 100_000))) // the host seeked
        runCurrent()
        assertFalse(playback.player.value.isPlaying)
        assertEquals("never started again by the host", plays, playback.playAt.size)
        assertTrue(jam.joinedState().pausedBySystem)
    }

    @Test fun theUsersPlayRejoinsAndClearsTheFlag() = runTest {
        val (jam, link) = playingGuest()
        advanceTimeBy(10_000)
        playback.systemPause()
        runCurrent()
        advanceTimeBy(20_000)
        link.deliver(JamStateFrame(state(a, b, positionMs = 30_000))) // keepalive while paused
        runCurrent()
        // Allowed without host control: it only resumes this phone's own output.
        JamGuestActions(jam).play(jam.joinedState())
        runCurrent()
        assertTrue(playback.player.value.isPlaying)
        assertFalse(jam.joinedState().pausedBySystem)
        assertTrue("no request to the host", link.sent.isEmpty())
        // Back where the host is now (a seek while still paused, before the start), never a jump while playing.
        assertEquals(30_000L, playback.seeks.last())
    }

    @Test fun aNewSongAfterASystemPauseDoesNotStartLoud() = runTest {
        val (jam, link) = playingGuest()
        playback.systemPause() // e.g. audio focus lost for good
        runCurrent()
        val plays = playback.playAt.size
        link.deliver(JamStateFrame(state(a, b, index = 1)))
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(b, playback.player.value.currentSong) // cued, ready
        assertFalse(playback.player.value.isPlaying)
        assertEquals(plays, playback.playAt.size)
        assertTrue(jam.joinedState().pausedBySystem)
    }

    @Test fun thePhoneResumingByItselfClearsTheFlag() = runTest {
        val (jam, _) = playingGuest()
        playback.systemPause()
        runCurrent()
        playback.resumeBySystem() // headphones back, "resume on connect"
        runCurrent()
        assertFalse(jam.joinedState().pausedBySystem)
    }

    @Test fun anOrdinaryPauseIsNotASystemPause() = runTest {
        val (jam, _) = playingGuest()
        playback.pause()
        runCurrent()
        assertFalse(jam.joinedState().pausedBySystem)
    }
}
