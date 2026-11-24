package de.yurtemre.sonora.jam

import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.shared.jam.JamSong
import de.yurtemre.sonora.testing.FakePlayback
import de.yurtemre.sonora.testing.testSong
import de.yurtemre.sonora.ui.screens.jam.FakeJamControls
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JamPlaybackGuardTest {
    private val jam = FakeJamControls()
    private val guard = JamPlaybackGuard(jam)
    private val log = mutableListOf<String>()
    private val a = testSong(1, "A")
    private val b = testSong(2, "B")

    private fun guest(canAdd: Boolean = true, canControl: Boolean = false, playing: Boolean = true) {
        jam.state.value = JamUiState.Joined(
            "Host phone", listOf(JamQueueItem(JamSong("A", "Artist", "Album", 1), true)), 0, playing, canAdd, canControl,
            JamSyncState.InSync,
        )
    }

    // ---- the pending "play my own music" action ---------------------------------------------------------------------

    @Test fun outsideAJamOwnPlaybackRunsAtOnce() {
        assertTrue(guard.request(listOf(a), isAdd = false) { log += "run" })
        assertEquals(listOf("run"), log)
        assertNull(guard.pending.value)
    }

    @Test fun hostsAreUnaffected() {
        jam.state.value = JamUiState.Hosting("123456", 1, emptyList(), JamSettings())
        assertTrue(guard.request(listOf(a), isAdd = true) { log += "run" })
        assertEquals(listOf("run"), log)
        assertNull(guard.pending.value)
    }

    @Test fun aGuestsOwnPlaybackWaitsForAChoice() {
        guest()
        assertFalse(guard.request(listOf(a), isAdd = false) { log += "run" })
        assertTrue(log.isEmpty())
        assertEquals(1, guard.pending.value?.songs?.size)
    }

    @Test fun leavingRunsTheActionOnceAfterLeaving() {
        guest()
        guard.request(listOf(a), isAdd = false) { log += "run"; log += jam.calls.joinToString() }
        guard.leaveAndPlay()
        guard.leaveAndPlay()
        assertEquals(listOf("leave"), jam.calls)
        assertEquals(listOf("run", "leave"), log) // the Jam was left before the action ran
        assertNull(guard.pending.value)
    }

    @Test fun stayingDropsTheAction() {
        guest()
        guard.request(listOf(a), isAdd = false) { log += "run" }
        guard.stay()
        assertNull(guard.pending.value)
        assertTrue(log.isEmpty())
        assertTrue(jam.calls.isEmpty())
    }

    @Test fun addingBecomesAJamRequestWhenTheHostAllows() {
        guest(canAdd = true)
        guard.request(listOf(a, b), isAdd = true) { log += "run" }
        assertTrue(guard.canAddToJam(guard.pending.value!!))
        guard.addToJam()
        assertEquals(listOf(a, b), jam.added)
        assertTrue(log.isEmpty())
        assertTrue(jam.calls.isEmpty()) // still in the Jam
        assertNull(guard.pending.value)
    }

    @Test fun addingIsNotOfferedWhenTheHostDoesNotAllowIt() {
        guest(canAdd = false)
        guard.request(listOf(a), isAdd = true) { log += "run" }
        assertFalse(guard.canAddToJam(guard.pending.value!!))
        guard.addToJam()
        assertTrue(jam.added.isEmpty())
    }

    @Test fun playingIsNeverOfferedAsAJamAdd() {
        guest(canAdd = true)
        guard.request(listOf(a), isAdd = false) { log += "run" }
        assertFalse(guard.canAddToJam(guard.pending.value!!))
    }

    @Test fun aPendingActionIsDroppedWhenTheJamEnds() = runTest(UnconfinedTestDispatcher()) {
        val guard = JamPlaybackGuard(jam, backgroundScope)
        guest()
        guard.request(listOf(a), isAdd = false) { log += "run" }
        jam.state.value = JamUiState.Ended(JamEndReason.HostGone)
        assertNull(guard.pending.value) // no dialog about a Jam that is over ("playing along with .")
        assertTrue(log.isEmpty())
    }

    @Test fun ranNowTellsCallersWhetherToConfirm() {
        assertTrue(guarded.ranNow { addToQueue(listOf(a)) })
        assertEquals(listOf("addToQueue"), delegated)
        guest()
        assertFalse(guarded.ranNow { playNext(listOf(a)) }) // held for the dialog: no "Added…", no Undo
        assertEquals(listOf("addToQueue"), delegated)
        assertTrue(recorder.ranNow { addToQueue(listOf(b)) }) // a plain connection always runs
    }

    @Test fun addingToTheJamReportsIt() {
        guest(canAdd = true)
        guard.request(listOf(a), isAdd = true) { log += "run" }
        assertTrue(guard.addToJam())
        assertFalse(guard.addToJam()) // nothing pending any more
    }

    // ---- the guarded PlaybackConnection -----------------------------------------------------------------------------

    private val delegated = mutableListOf<String>()
    private val recorder: PlaybackConnection = run {
        val base = FakePlayback()
        Proxy.newProxyInstance(PlaybackConnection::class.java.classLoader, arrayOf(PlaybackConnection::class.java)) { _, m, args ->
            if (m.name.startsWith("get")) m.invoke(base, *(args ?: emptyArray())) else {
                delegated += m.name
                m.invoke(base, *(args ?: emptyArray()))
            }
        } as PlaybackConnection
    }
    private val guarded = JamGuardedPlayback(recorder, guard)

    @Test fun outsideAJamEverythingPassesThrough() {
        guarded.playSong(a, listOf(a))
        guarded.addToQueue(listOf(a))
        guarded.seekTo(5)
        guarded.skipToNext()
        guarded.toggleShuffle()
        assertEquals(listOf("playSong", "addToQueue", "seekTo", "skipToNext", "toggleShuffle"), delegated)
    }

    @Test fun aGuestsOwnPlayIsHeldAndRunsOnLeave() {
        guest()
        guarded.playSong(a, listOf(a, b))
        guarded.playShuffled(listOf(a, b))
        assertTrue(delegated.isEmpty())
        guard.leaveAndPlay() // the latest request wins
        assertEquals(listOf("playShuffled"), delegated)
    }

    @Test fun aGuestsAddsAreHeldAsAdds() {
        guest(canAdd = true)
        guarded.playNext(listOf(a))
        assertTrue(guard.canAddToJam(guard.pending.value!!))
        guarded.addToQueue(listOf(b))
        guard.addToJam()
        assertEquals(listOf(b), jam.added)
        assertTrue(delegated.isEmpty())
    }

    @Test fun aGuestsSeekShuffleRepeatAndQueueEditsAreIgnored() {
        guest()
        guarded.seekTo(5)
        guarded.seekBy(1_000)
        guarded.toggleShuffle()
        guarded.setShuffle(true)
        guarded.cycleRepeat()
        guarded.moveQueueItem(0, 1)
        guarded.removeFromQueue(3)
        guarded.insertQueueItem(1, 0)
        guarded.skipToQueueItem(4)
        assertTrue(delegated.isEmpty())
        guarded.setUserVolume(0.5f) // the guest's own volume stays its own
        guarded.closePlayer() // closing leaves the Jam (the controller sees the emptied queue)
        assertEquals(listOf("setUserVolume", "closePlayer"), delegated)
    }

    @Test fun aGuestsTransportBecomesRequestsOnlyWhenAllowed() {
        guest(canControl = false)
        guarded.skipToNext()
        guarded.togglePlayPause()
        assertTrue(jam.calls.isEmpty())
        guest(canControl = true, playing = true)
        guarded.skipToNext()
        guarded.skipToPrevious()
        guarded.togglePlayPause()
        guarded.play()
        assertEquals(listOf("next", "previous", "pause", "play"), jam.calls)
        assertTrue(delegated.isEmpty())
    }
}
