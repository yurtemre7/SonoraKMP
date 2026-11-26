package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.JamAction
import de.yurtemre.sonora.shared.jam.JamAddManyRequest
import de.yurtemre.sonora.shared.jam.MAX_JAM_QUEUE
import de.yurtemre.sonora.shared.jam.JamControl
import de.yurtemre.sonora.shared.jam.JamEnd
import de.yurtemre.sonora.shared.jam.JamError
import de.yurtemre.sonora.shared.jam.JamServer
import de.yurtemre.sonora.shared.jam.JamStateFrame
import de.yurtemre.sonora.testing.testSong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
/** Guest side: mirroring, sync rules, stats and failure handling. */
class JamControllerTest : JamControllerTestBase() {
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
        assertEquals(1, jam.requestAdd(listOf(c)))
        assertEquals(listOf(JamControl(JamAction.Next), JamControl(JamAction.Pause), JamAddManyRequest(listOf(c.jam()))), link.sent)
    }

    @Test fun manySongsGoOutInBatchesUpToTheJamQueueCap() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        runCurrent()
        val sixty = (1L..60L).map { testSong(1_000 + it, "S$it") }
        assertEquals(60, jam.requestAdd(sixty))
        assertEquals(listOf(sixty.map { it.jam() }), link.sent.filterIsInstance<JamAddManyRequest>().map { it.songs })
        link.sent.clear()

        val many = (1L..200L).map { testSong(2_000 + it, "M$it") }
        assertEquals(200, jam.requestAdd(many))
        assertEquals(listOf(100, 100), link.sent.filterIsInstance<JamAddManyRequest>().map { it.songs.size })
        link.sent.clear()

        // The host's queue is nearly full: only what fits is sent, and the count says so.
        val big = (1L..(MAX_JAM_QUEUE - 10L)).map { testSong(5_000 + it, "Q$it") }
        link.deliver(JamStateFrame(state(*big.toTypedArray())))
        runCurrent()
        assertEquals(10, jam.requestAdd(many))
        assertEquals(many.take(10).map { it.jam() }, link.sent.filterIsInstance<JamAddManyRequest>().single().songs)
    }

    @Test fun aRefusedAddFrameIsNotCountedAsSent() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b)))
        runCurrent()
        link.accepts = 1 // the outbox takes one more frame, then refuses
        val many = (1L..250L).map { testSong(2_000 + it, "M$it") }
        assertEquals(100, jam.requestAdd(many))
        assertFalse("never closed for a full outbox", link.closed)
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

    @Test fun aHostSeekIsFollowedAtOnceMidSong() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, positionMs = 0)))
        advanceTimeBy(30_000)
        link.deliver(JamStateFrame(state(a, b, positionMs = 30_000))) // an ordinary frame: nothing to do
        runCurrent()
        assertTrue(playback.seeks.isEmpty())
        advanceTimeBy(1_000)
        // The host jumped to 90 s (a seekbar drag or a lyric tap): even a small or large jump is followed now.
        link.deliver(JamStateFrame(state(a, b, positionMs = 90_000, seekCount = 1)))
        runCurrent()
        assertEquals(listOf(90_000L), playback.seeks)
        assertTrue(playback.player.value.isPlaying)
        // The same count again (a keepalive) is no new seek.
        advanceTimeBy(2_000)
        link.deliver(JamStateFrame(state(a, b, positionMs = 92_000, seekCount = 1)))
        runCurrent()
        assertEquals(listOf(90_000L), playback.seeks)
        assertNoEffects()
    }

    @Test fun aSeekWhilePausedIsFollowedToo() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, playing = false, positionMs = 10_000)))
        runCurrent()
        link.deliver(JamStateFrame(state(a, b, playing = false, positionMs = 50_000, seekCount = 1)))
        runCurrent()
        assertEquals(listOf(50_000L), playback.seeks)
    }

    @Test fun joiningToAnAlreadySeekedJamIsNotASeek() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, positionMs = 0, seekCount = 7)))
        runCurrent()
        assertTrue(playback.seeks.isEmpty())
    }

    @Test fun theGuestPlaysAtTheHostsSpeed() = runTest {
        val jam = controller()
        val link = joined(jam)
        link.deliver(JamStateFrame(state(a, b, positionMs = 0, speed = 1.25f, pitch = 1.25f)))
        runCurrent()
        assertEquals(1.25f to 1.25f, playback.mirrorRates.last())
        link.deliver(JamStateFrame(state(a, b, positionMs = 0, speed = 1f, pitch = 1f)))
        runCurrent()
        assertEquals(1f to 1f, playback.mirrorRates.last())
        assertNoEffects() // the user's own effect settings are never touched
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

    @Test fun nothingStartsBeforeTheClockOffsetIsKnown() = runTest {
        val jam = controller()
        advanceTimeBy(10_000)
        val link = joined(jam, FakeLink(offsetMs = null))
        // Host clock (another phone's uptime) is an hour ahead: planning on the raw clock would wait an hour.
        link.deliver(JamStateFrame(state(a, b, positionMs = 0, hostTimeMs = testScheduler.currentTime + 3_600_000)))
        advanceTimeBy(500)
        assertTrue(playback.mirrorLoads.isEmpty())
        assertEquals(JamSyncState.Syncing, (jam.state.value as JamUiState.Joined).sync)

        val now = testScheduler.currentTime
        link.clock.addSample(sentMs = now, hostMs = now + 3_600_000, receivedMs = now) // the first pong
        advanceTimeBy(JamGuestSession.CLOCK_POLL_MS + 1)
        assertEquals(1, playback.mirrorLoads.size)
        assertTrue(playback.player.value.isPlaying)
        assertEquals(JamSyncState.InSync, (jam.state.value as JamUiState.Joined).sync)
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
