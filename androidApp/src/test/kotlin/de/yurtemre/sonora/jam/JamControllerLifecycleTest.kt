package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.shared.jam.JamStateFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Leaving at awkward moments: closing the player mid-Jam, leaving while a join or host start is in flight. */
@OptIn(ExperimentalCoroutinesApi::class)
class JamControllerLifecycleTest : JamControllerTestBase() {

    @Test fun closingThePlayerMidJamLeavesTheJam() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        advanceTimeBy(1_000)
        playback.closePlayer() // notification "Close" or the app shell
        runCurrent()
        assertTrue(link.closed)
        assertEquals(JamUiState.Idle, jam.state.value)
        // Another frame (already in flight) must not load the mirror again.
        link.deliver(JamStateFrame(state(a, b, index = 1)))
        runCurrent()
        assertEquals(1, playback.mirrorLoads.size)
    }

    @Test fun leaveDuringAJoinCancelsIt() = runTest {
        val jam = controller()
        val gate = CompletableDeferred<Unit>()
        connector.joinGate = gate
        val link = FakeLink(offsetMs = 0)
        connector.links.addLast(link)
        backgroundScope.launch { jam.join("10.0.0.2", 41_234, "123456", "Host phone") }
        runCurrent()
        assertEquals(JamUiState.Joining, jam.state.value)
        jam.leave()
        gate.complete(Unit)
        runCurrent()
        assertEquals(JamUiState.Idle, jam.state.value)
        assertTrue("a late connection is closed", link.closed)
        link.deliver(JamStateFrame(state(a, b)))
        runCurrent()
        assertTrue(playback.mirrorLoads.isEmpty())
    }

    @Test fun hostingWhileAJoinIsInFlightWins() = runTest {
        val jam = controller()
        val gate = CompletableDeferred<Unit>()
        connector.joinGate = gate
        val link = FakeLink(offsetMs = 0)
        connector.links.addLast(link)
        backgroundScope.launch { jam.join("10.0.0.2", 41_234, "123456", "Host phone") }
        runCurrent()
        jam.host(JamSettings())
        gate.complete(Unit)
        runCurrent()
        assertTrue(jam.state.value is JamUiState.Hosting)
        assertTrue(link.closed)
    }

    @Test fun leaveDuringAHostStartStopsTheServer() = runTest {
        val jam = controller()
        val gate = CompletableDeferred<Unit>()
        transport.startGate = gate
        backgroundScope.launch { runCatching { jam.host(JamSettings()) } }
        runCurrent()
        jam.leave()
        gate.complete(Unit)
        runCurrent()
        assertEquals(JamUiState.Idle, jam.state.value)
        assertTrue(transport.stopped)
    }
}
