package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.JamAction
import de.yurtemre.sonora.shared.jam.JamEvent
import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.shared.jam.JamStateFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JamControllerHostTest : JamControllerTestBase() {
    @Test fun hostQueueChangeBroadcastsOneStateFrame() = runTest {
        val jam = controller()
        val session = jam.host(JamSettings())
        assertEquals("123456", session.code)
        assertEquals(41_234, session.port)
        advanceTimeBy(1_000) // the initial (empty) state goes out first
        transport.broadcasts.clear()

        playback.hostQueue(listOf(a, b), index = 0, playing = false)
        playback.hostQueue(listOf(a, b, c), index = 1, playing = false)
        playback.hostQueue(listOf(a, b, c), index = 1, playing = true, positionMs = 2_000) // one user action, three updates
        advanceTimeBy(JamController.HOST_DEBOUNCE_MS + 1)

        val frames = transport.broadcasts.filterIsInstance<JamStateFrame>()
        assertEquals(1, frames.size)
        val s = frames.single().state
        assertEquals(listOf(a, b, c).map { it.jam() }, s.queue)
        assertEquals(1, s.index)
        assertTrue(s.playing)
        // Anchored when the debounce settles: host time and the position at that moment.
        assertEquals(1_000 + JamController.HOST_DEBOUNCE_MS, s.hostTimeMs)
        assertEquals(2_000 + JamController.HOST_DEBOUNCE_MS, s.positionMs)
        assertTrue(jam.state.value is JamUiState.Hosting)

        playback.hostQueue(listOf(a, b, c), index = 1, playing = true, positionMs = playback.position) // nothing changed
        advanceTimeBy(JamController.HOST_DEBOUNCE_MS + 1)
        assertEquals(1, transport.broadcasts.size)
        assertNoEffects()
    }

    @Test fun joiningGuestGetsTheCurrentStateOnly() = runTest {
        val jam = controller()
        jam.host(JamSettings())
        playback.hostQueue(listOf(a, b), index = 1, playing = true, positionMs = 5_000)
        advanceTimeBy(JamController.HOST_DEBOUNCE_MS + 1)
        transport.broadcasts.clear()

        transport.eventChannel.trySend(JamEvent.GuestJoined("g1", "Ann"))
        runCurrent()
        val (to, frame) = transport.sent.single()
        assertEquals("g1", to)
        assertEquals(1, (frame as JamStateFrame).state.index)
        assertEquals(5_000L + JamController.HOST_DEBOUNCE_MS + 1, frame.state.positionMs)
        assertTrue(transport.broadcasts.none { it is JamStateFrame })
        assertEquals(listOf(JamGuestUi("g1", "Ann")), (jam.state.value as JamUiState.Hosting).guests)

        // A reconnect is a join of the same id: it gets the state again.
        transport.eventChannel.trySend(JamEvent.GuestLeft("g1"))
        transport.eventChannel.trySend(JamEvent.GuestJoined("g1", "Ann"))
        runCurrent()
        assertEquals(2, transport.sent.size)
    }

    @Test fun remoteControlsFollowTheHostsPermissions() = runTest {
        val jam = controller(library = listOf(a, b, c))
        jam.host(JamSettings(guestsCanAdd = true, guestsCanControl = false))
        playback.hostQueue(listOf(a, b), index = 0, playing = true)
        transport.eventChannel.trySend(JamEvent.GuestJoined("g1", "Ann"))
        runCurrent()

        transport.eventChannel.trySend(JamEvent.Control("g1", JamAction.Next, 0))
        transport.eventChannel.trySend(JamEvent.AddRequest("g1", c.jam()))
        transport.eventChannel.trySend(JamEvent.AddRequest("g1", missing.jam())) // the host lacks it: nothing to add
        runCurrent()
        assertTrue(playback.calls.none { it.startsWith("skipTo") })
        assertEquals(listOf("addToQueue:3"), playback.calls.filter { it.startsWith("addToQueue") })

        jam.setGuestsCanControl(true)
        jam.setGuestsCanAdd(false)
        runCurrent()
        assertTrue(transport.broadcasts.filterIsInstance<JamStateFrame>().last().state.guestsCanControl)
        transport.eventChannel.trySend(JamEvent.Control("g1", JamAction.Next, 0))
        transport.eventChannel.trySend(JamEvent.Control("g1", JamAction.Pause, 0))
        transport.eventChannel.trySend(JamEvent.AddRequest("g1", c.jam()))
        transport.eventChannel.trySend(JamEvent.Control("nobody", JamAction.Next, 0))
        runCurrent()
        // Jumps to the next entry: never recorded as the host's own skip.
        assertEquals(listOf("skipToQueueItem:1"), playback.calls.filter { it.startsWith("skipTo") })
        assertEquals(1, playback.calls.count { it == "pause" })
        assertEquals(1, playback.calls.count { it.startsWith("addToQueue") })
    }

    @Test fun hostControlsKickNewCodeAndEnd() = runTest {
        val jam = controller()
        jam.host(JamSettings())
        transport.eventChannel.trySend(JamEvent.GuestJoined("g1", "Ann"))
        runCurrent()
        jam.kick("g1")
        jam.newCode()
        assertEquals(listOf("g1"), transport.kicked)
        assertEquals(2, transport.codes.size)
        jam.endJam()
        runCurrent()
        assertTrue(transport.stopped)
        assertEquals(JamUiState.Idle, jam.state.value)
        assertTrue("the host's own queue is untouched", playback.mirrorLoads.isEmpty() && playback.restored.isEmpty())
    }
}
