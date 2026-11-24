package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.JamAction
import de.yurtemre.sonora.shared.jam.JamAddRequest
import de.yurtemre.sonora.shared.jam.JamControl
import de.yurtemre.sonora.shared.jam.JamEnd
import de.yurtemre.sonora.shared.jam.JamError
import de.yurtemre.sonora.shared.jam.JamEvent
import de.yurtemre.sonora.shared.jam.JamServer
import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.shared.jam.JamState
import de.yurtemre.sonora.shared.jam.JamStateFrame
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.testing.testSong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JamControllerTest {
    private val a = testSong(1, "A")
    private val b = testSong(2, "B")
    private val c = testSong(3, "C")
    /** Only the host has this one. */
    private val missing = testSong(99, "Missing")

    private val transport = FakeHostTransport()
    private val connector = FakeConnector()
    private val skips = mutableListOf<Long>()
    private val logs = mutableListOf<String>()
    private var speakerDelay = 0
    private var codes = 0
    private lateinit var playback: RecordingPlayback

    private fun TestScope.controller(library: List<Song> = listOf(a, b, c)): JamController {
        val now = { testScheduler.currentTime }
        playback = RecordingPlayback(now)
        return JamController(
            backgroundScope, playback,
            JamEnvironment(
                hostTransport = { transport },
                connector = connector,
                library = { library },
                nowMs = now,
                speakerDelayMs = { speakerDelay },
                recordSkip = { skips += it },
                deviceName = { "Guest phone" },
                newCode = { (123_456 + codes++).toString() },
                debugLog = { logs += it },
            ),
        )
    }

    private fun TestScope.state(
        vararg songs: Song,
        index: Int = 0,
        playing: Boolean = true,
        positionMs: Long = 0,
        hostTimeMs: Long = testScheduler.currentTime,
        canControl: Boolean = false,
    ) = JamState(songs.map { it.jam() }, index, playing, positionMs, hostTimeMs, guestsCanAdd = true, guestsCanControl = canControl)

    /** Joins with a fresh link; returns it. */
    private suspend fun TestScope.joined(jam: JamController, link: FakeLink = FakeLink(offsetMs = 0)): FakeLink {
        connector.links.addLast(link)
        jam.join("10.0.0.2", 41_234, "123456", "Host phone")
        runCurrent()
        return link
    }

    private fun assertNoEffects() =
        assertTrue("speed/pitch/effects touched: ${playback.effectCalls}", playback.effectCalls.isEmpty())

    // ---- host ----------------------------------------------------------------------------------------------------

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

    // ---- guest ---------------------------------------------------------------------------------------------------

    @Test fun guestMirrorsTheHostQueueAndSavesItsPreviousQueue() = runTest {
        val jam = controller(library = listOf(a, b, c))
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, missing, b, c, index = 0, playing = true)))
        runCurrent()

        assertEquals("previous queue saved before the mirror", listOf("queueSnapshot", "setMirrorQueue"), playback.calls.take(2))
        // Only the songs this phone has, and only up to the next missing one (the guest never runs ahead of the host).
        assertEquals(MirrorLoad(listOf(1), 0, 0, true), playback.mirrorLoads.single())
        val ui = jam.state.value as JamUiState.Joined
        assertEquals("Host phone", ui.hostName)
        assertEquals(listOf(true, false, true, true), ui.queue.map { it.available })
        assertTrue(ui.playing)
        assertNoEffects()
    }

    @Test fun leavingRestoresThePreviousQueue() = runTest {
        val jam = controller()
        val previous = playback.snapshot
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        runCurrent()
        jam.leave()
        runCurrent()
        assertTrue(link.closed)
        assertEquals(listOf(previous), playback.restored)
        assertEquals(JamUiState.Idle, jam.state.value)
        assertEquals(1, playback.snapshotCalls)
    }

    @Test fun hostDrivenSkipIsNotASkipOnTheGuest() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, c, index = 0)))
        advanceTimeBy(10_000)
        link.deliver(JamStateFrame(state(a, b, c, index = 1)))
        runCurrent()
        assertEquals(listOf(2L, 3L), playback.mirrorLoads.last().songIds)
        assertEquals(b, playback.player.value.currentSong)
        assertTrue(skips.isEmpty())
        assertFalse("skipToNext" in playback.calls)
    }

    @Test fun guestsOwnSkipGoesToTheHostAndCountsAsASkip() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, index = 0, canControl = false)))
        runCurrent()
        jam.requestNext()
        assertTrue("not allowed: nothing sent", link.sent.isEmpty())
        assertTrue(skips.isEmpty())

        link.deliver(JamStateFrame(state(a, b, index = 0, canControl = true)))
        runCurrent()
        jam.requestNext()
        runCurrent()
        assertEquals(listOf(JamControl(JamAction.Next)), link.sent)
        assertEquals(listOf(a.id), skips)
        assertFalse("never a local skip", "skipToNext" in playback.calls)

        // The host applies it: following that frame records nothing more.
        link.deliver(JamStateFrame(state(a, b, index = 1, canControl = true)))
        runCurrent()
        assertEquals(listOf(a.id), skips)

        jam.requestPause()
        jam.requestAdd(c)
        assertEquals(listOf(JamControl(JamAction.Next), JamControl(JamAction.Pause), JamAddRequest(c.jam())), link.sent)
    }

    @Test fun missingSongIsSkippedWithoutAnyStatsCall() = runTest {
        val jam = controller(library = listOf(a, c))
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, missing, c, index = 1, playing = true)))
        runCurrent()

        assertTrue("the missing song never reaches the player", playback.mirrorLoads.none { missing.id in it.songIds })
        // The next song this phone has is cued, paused at its start, ready for when the host gets there.
        assertEquals(MirrorLoad(listOf(c.id), 0, 0, false), playback.mirrorLoads.single())
        assertFalse(playback.player.value.isPlaying)
        assertTrue(skips.isEmpty())
        assertFalse("skipToNext" in playback.calls)
        val ui = jam.state.value as JamUiState.Joined
        assertEquals(1, ui.index)
        assertFalse(ui.queue[1].available)

        advanceTimeBy(180_000)
        link.deliver(JamStateFrame(state(a, missing, c, index = 2, playing = true)))
        runCurrent()
        assertTrue(playback.player.value.isPlaying)
        assertEquals(c, playback.player.value.currentSong)
        assertTrue(skips.isEmpty())
        assertNoEffects()
    }

    @Test fun driftIsCorrectedOnlyAtThePauseNeverMidSong() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, positionMs = 0)))
        advanceTimeBy(60_000)
        // The host says it is 3 s further than this phone: more than 2 s of drift.
        link.deliver(JamStateFrame(state(a, b, c, positionMs = 63_000)))
        runCurrent()
        assertTrue("never a seek while playing", playback.seeks.isEmpty())
        assertTrue(playback.player.value.isPlaying)

        advanceTimeBy(1_000)
        link.deliver(JamStateFrame(state(a, b, c, playing = false, positionMs = 64_000)))
        runCurrent()
        assertFalse(playback.player.value.isPlaying)
        assertEquals("one seek, at the pause", listOf(64_000L), playback.seeks)

        advanceTimeBy(5_000)
        link.deliver(JamStateFrame(state(a, b, c, playing = true, positionMs = 64_000)))
        runCurrent()
        assertTrue(playback.player.value.isPlaying)
        assertEquals(listOf(64_000L), playback.seeks)
        assertNoEffects()
    }

    @Test fun smallDriftIsLeftAlone() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, positionMs = 0)))
        advanceTimeBy(60_000)
        link.deliver(JamStateFrame(state(a, b, playing = false, positionMs = 61_500)))
        runCurrent()
        assertTrue(playback.seeks.isEmpty())
    }

    @Test fun lateJoinerStartsAtTheExtrapolatedPositionOnce() = runTest {
        val jam = controller()
        advanceTimeBy(20_000)
        val link = joined(jam)
        // The host started this song 10 s ago at 5 s: the guest starts at once at 15 s, not 25 s.
        link.deliver(JamStateFrame(state(a, b, positionMs = 5_000, hostTimeMs = testScheduler.currentTime - 10_000)))
        runCurrent()
        assertEquals(MirrorLoad(listOf(1, 2), 0, 15_000, true), playback.mirrorLoads.single())
        advanceTimeBy(5_000)
        assertFalse("no second start", "play" in playback.calls)
    }

    @Test fun futureStartWaitsForTheClockInsteadOfSpeedingUp() = runTest {
        val jam = controller()
        advanceTimeBy(10_000)
        // The host's clock runs 1 s ahead of this phone's.
        val link = joined(jam, FakeLink(offsetMs = 1_000))
        val startLocal = testScheduler.currentTime + 300
        link.deliver(JamStateFrame(state(a, b, positionMs = 0, hostTimeMs = startLocal + 1_000)))
        runCurrent()
        assertEquals(MirrorLoad(listOf(1, 2), 0, 0, false), playback.mirrorLoads.single())
        advanceTimeBy(299)
        assertFalse(playback.player.value.isPlaying)
        advanceTimeBy(2)
        assertEquals(listOf(startLocal), playback.playAt)
        assertTrue(logs.any { it.contains("start gap") })
        assertNoEffects()
    }

    @Test fun speakerDelayShiftsTheStartAndIsClamped() = runTest {
        speakerDelay = 200
        val jam = controller()
        advanceTimeBy(10_000)
        val link = joined(jam)
        val anchor = testScheduler.currentTime + 100
        link.deliver(JamStateFrame(state(a, positionMs = 0, hostTimeMs = anchor)))
        advanceTimeBy(1_000)
        assertEquals(listOf(anchor + 200), playback.playAt)

        // Too large: clamped to 500 ms.
        speakerDelay = 900
        val anchor2 = testScheduler.currentTime + 100
        link.deliver(JamStateFrame(state(b, positionMs = 0, hostTimeMs = anchor2)))
        advanceTimeBy(1_000)
        assertEquals(anchor2 + 500, playback.playAt.last())

        // Already past even with the delay: start at once, the delay behind the host.
        speakerDelay = 200
        link.deliver(JamStateFrame(state(c, positionMs = 1_000, hostTimeMs = testScheduler.currentTime - 2_000)))
        runCurrent()
        assertEquals(MirrorLoad(listOf(3), 0, 2_800, true), playback.mirrorLoads.last())
    }

    @Test fun whenTheHostDropsTheGuestFinishesTheSongThenEnds() = runTest {
        val jam = controller()
        val previous = playback.snapshot
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, c)))
        advanceTimeBy(30_000)
        link.drop()
        runCurrent()
        assertEquals(JamSyncState.Reconnecting, (jam.state.value as JamUiState.Joined).sync)
        // Only the current song is left in the player: it plays on and nothing follows it.
        assertEquals(MirrorLoad(listOf(1), 0, playback.mirrorLoads.last().positionMs, true), playback.mirrorLoads.last())
        assertTrue(playback.player.value.isPlaying)

        advanceTimeBy(JamServer.RECONNECT_WINDOW_MS + 1)
        assertEquals(JamSyncState.Ending, (jam.state.value as JamUiState.Joined).sync)
        assertTrue(playback.restored.isEmpty())
        assertTrue(playback.player.value.isPlaying)

        playback.finishSong()
        runCurrent()
        assertEquals(JamUiState.Ended(JamEndReason.HostGone), jam.state.value)
        assertEquals(listOf(previous), playback.restored)
        assertTrue(connector.reconnectAttempts > 1)
    }

    @Test fun hostEndingTheJamLetsTheCurrentSongFinish() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        advanceTimeBy(1_000)
        link.deliver(JamEnd(JamServer.REASON_HOST_ENDED))
        runCurrent()
        assertEquals(JamSyncState.Ending, (jam.state.value as JamUiState.Joined).sync)
        assertEquals(0, connector.reconnectAttempts)
        playback.finishSong()
        runCurrent()
        assertEquals(JamUiState.Ended(JamEndReason.HostEnded), jam.state.value)
        assertEquals(1, playback.restored.size)
    }

    @Test fun droppedGuestReconnectsWithinTheWindow() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        runCurrent()
        val again = FakeLink(offsetMs = 0)
        connector.reconnects.addLast(JamError.HostGone) // first try fails (Wi-Fi still coming back)
        connector.reconnects.addLast(again)
        link.drop()
        advanceTimeBy(5_000)
        assertEquals(2, connector.reconnectAttempts)
        assertEquals(JamSyncState.InSync, (jam.state.value as JamUiState.Joined).sync)

        again.deliver(JamStateFrame(state(a, b, index = 1)))
        runCurrent()
        assertEquals(b, playback.player.value.currentSong)
        assertTrue(playback.restored.isEmpty())
        assertEquals("previous queue saved once", 1, playback.snapshotCalls)
    }

    @Test fun refusedReconnectEndsAfterTheSong() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        runCurrent()
        connector.reconnects.addLast(JamError.ReconnectRefused)
        link.drop()
        runCurrent()
        assertEquals(1, connector.reconnectAttempts)
        assertEquals(JamSyncState.Ending, (jam.state.value as JamUiState.Joined).sync)
        playback.finishSong()
        runCurrent()
        assertEquals(JamUiState.Ended(JamEndReason.HostGone), jam.state.value)
    }

    @Test fun networkChangeEndsCleanlyWithRejoin() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        runCurrent()
        jam.onNetworkLost()
        runCurrent()
        assertTrue(link.closed)
        playback.finishSong()
        runCurrent()
        assertEquals(
            JamUiState.Ended(JamEndReason.NetworkChanged, JamRejoin("10.0.0.2", 41_234, "Host phone")),
            jam.state.value,
        )
        assertEquals(0, connector.reconnectAttempts)
    }

    @Test fun joinErrorsAreTyped() = runTest {
        val jam = controller()
        connector.joinError = JamError.WrongCode
        jam.join("10.0.0.2", 41_234, "000000", "Host phone")
        assertEquals(JamUiState.Error(JamError.WrongCode), jam.state.value)
        assertTrue("nothing touched", playback.calls.isEmpty())
    }
}
