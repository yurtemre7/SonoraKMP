package de.yurtemre.sonora.shared.jam

import de.yurtemre.sonora.shared.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JamSessionTest {
    private fun jam(n: Int) = JamSong("T$n", "A", "B", 100_000)
    private fun song(n: Int) = Song(id = n.toLong(), title = "T$n", artist = "A", album = "B", durationMs = 100_000, filePath = "/m/$n.mp3")
    private fun host(settings: JamSettings = JamSettings()) = JamHostState.initial(settings)
    private fun JamHostState.join(vararg ids: String) = ids.fold(this) { s, id -> s.apply(JamEvent.GuestJoined(id, "n$id")) }

    @Test fun eighthGuestRefused() {
        var s = host().join("1", "2", "3", "4", "5", "6", "7")
        assertEquals(7, s.guests.size)
        assertEquals(JamError.Full, s.canJoin("8"))
        s = s.apply(JamEvent.GuestJoined("8", "x"))
        assertEquals(7, s.guests.size)
        assertFalse("8" in s.guests)
    }

    @Test fun duplicateJoinReplacesAndNeverCountsTwice() {
        var s = host().join("1", "2", "3", "4", "5", "6", "7")
        assertNull(s.canJoin("3"))
        s = s.apply(JamEvent.GuestJoined("3", "renamed"))
        assertEquals(7, s.guests.size)
        assertEquals("renamed", s.guests["3"]?.name)
    }

    @Test fun leftThenRejoinFreesSlot() {
        var s = host().join("1", "2", "3", "4", "5", "6", "7")
        s = s.apply(JamEvent.GuestLeft("1"))
        assertFalse("1" in s.guests)
        assertNull(s.canJoin("8"))
        s = s.apply(JamEvent.GuestJoined("8", "x")).apply(JamEvent.GuestLeft("8")).apply(JamEvent.GuestJoined("1", "back"))
        assertEquals("back", s.guests["1"]?.name)
        assertEquals(7, s.guests.size)
        assertEquals(s, s.apply(JamEvent.GuestLeft("nobody")))
    }

    @Test fun addRequestHonoursSetting() {
        val on = host().join("1").apply(JamEvent.AddRequest("1", jam(1)))
        assertEquals(listOf(jam(1)), on.state.queue)
        val off = host(JamSettings(guestsCanAdd = false)).join("1")
        assertEquals(off, off.apply(JamEvent.AddRequest("1", jam(1))))
        assertEquals(on.state.queue, on.apply(JamEvent.AddRequest("ghost", jam(2))).state.queue)
    }

    @Test fun addManyRequestIsOneRequestWithTheSamePermissionRules() {
        val songs = (1..60).map(::jam)
        val on = host().join("1").apply(JamEvent.AddManyRequest("1", songs))
        assertEquals(songs, on.state.queue)
        val off = host(JamSettings(guestsCanAdd = false)).join("1")
        assertEquals(off, off.apply(JamEvent.AddManyRequest("1", songs)))
        assertEquals(on, on.apply(JamEvent.AddManyRequest("ghost", songs)))
    }

    @Test fun guestAddsStopAtTheJamQueueCap() {
        val nearlyFull = host().apply(JamEvent.HostAction(HostAction.SetQueue((1..MAX_JAM_QUEUE - 30).map(::jam), 0))).join("1")
        val added = nearlyFull.apply(JamEvent.AddManyRequest("1", (1..100).map { jam(1000 + it) }))
        assertEquals(MAX_JAM_QUEUE, added.state.queue.size)
        assertEquals((1..30).map { jam(1000 + it) }, added.state.queue.takeLast(30)) // the first ones that fit
        assertEquals(added, added.apply(JamEvent.AddManyRequest("1", listOf(jam(5000)))))
        assertEquals(added, added.apply(JamEvent.AddRequest("1", jam(5000))))
    }

    @Test fun addFramesSplitLargeAddsIntoBatches() {
        val frames = jamAddRequests((1..250).map(::jam))
        assertEquals(listOf(MAX_ADD_BATCH, MAX_ADD_BATCH, 50), frames.map { it.songs.size })
        assertEquals((1..250).map(::jam), frames.flatMap { it.songs })
        assertTrue(jamAddRequests(emptyList()).isEmpty())
    }

    @Test fun controlHonoursSetting() {
        val q = host().apply(JamEvent.HostAction(HostAction.SetQueue(listOf(jam(1), jam(2)), 0))).join("1")
        assertEquals(q, q.apply(JamEvent.Control("1", JamAction.Play, 10)))
        val c = q.apply(JamEvent.HostAction(HostAction.SetSettings(JamSettings(guestsCanControl = true))))
        assertTrue(c.state.guestsCanControl)
        assertTrue(c.apply(JamEvent.Control("1", JamAction.Play, 10)).state.playing)
        assertEquals(1, c.apply(JamEvent.Control("1", JamAction.Next, 10)).state.index)
        assertEquals(c, c.apply(JamEvent.Control("ghost", JamAction.Play, 10)))
    }

    @Test fun settingsFlipUpdatesStateFlags() {
        val s = host().apply(JamEvent.HostAction(HostAction.SetSettings(JamSettings(guestsCanAdd = false, guestsCanControl = true))))
        assertFalse(s.state.guestsCanAdd)
        assertTrue(s.state.guestsCanControl)
        assertEquals(JamSettings(false, true), s.settings)
        assertFalse(host(JamSettings(guestsCanAdd = false)).state.guestsCanAdd)
    }

    @Test fun nextPreviousBoundsAndNoWrap() {
        var s = host().apply(JamEvent.HostAction(HostAction.SetQueue(listOf(jam(1), jam(2)), 0)))
        assertEquals(s, s.apply(JamEvent.HostAction(HostAction.Previous(5))))
        s = s.apply(JamEvent.HostAction(HostAction.Next(5)))
        assertEquals(1, s.state.index)
        assertEquals(0L, s.state.positionMs)
        assertEquals(5L, s.state.hostTimeMs)
        assertEquals(s, s.apply(JamEvent.HostAction(HostAction.Next(6))))
        val e = host()
        assertEquals(0, e.apply(JamEvent.HostAction(HostAction.Next(1))).state.index)
    }

    @Test fun setQueueClampsIndex() {
        val q = listOf(jam(1), jam(2))
        assertEquals(1, host().apply(JamEvent.HostAction(HostAction.SetQueue(q, 9))).state.index)
        assertEquals(0, host().apply(JamEvent.HostAction(HostAction.SetQueue(q, -3))).state.index)
        assertEquals(0, host().apply(JamEvent.HostAction(HostAction.SetQueue(emptyList(), 4))).state.index)
    }

    @Test fun playPauseCarryHostTimestamps() {
        val p = host().apply(JamEvent.HostAction(HostAction.Play(1_000, 50)))
        assertTrue(p.state.playing)
        assertEquals(1_000L, p.state.positionMs)
        assertEquals(50L, p.state.hostTimeMs)
        val q = p.apply(JamEvent.HostAction(HostAction.Pause(3_000, 90)))
        assertFalse(q.state.playing)
        assertEquals(3_000L, q.state.positionMs)
        assertEquals(90L, q.state.hostTimeMs)
        val a = host().apply(JamEvent.HostAction(HostAction.AddSong(jam(1))))
        assertEquals(listOf(jam(1)), a.state.queue)
    }

    // ---- guest side ----

    private fun state(queue: List<JamSong>, index: Int = 0, playing: Boolean = true, pos: Long = 10_000, hostTime: Long = 1_000_000) =
        JamState(queue, index, playing, pos, hostTime, true, false)

    private fun clockWithOffset(offset: Long) = ClockSync().also { it.addSample(0, offset, 0) }

    @Test fun missingSongSkips() {
        val plan = planPlayback(state(listOf(jam(1), jam(2)), index = 1), listOf(song(1)), ClockSync(), 0, null)
        assertEquals(GuestPlan.Skip(1), plan)
    }

    @Test fun matchedSongPlansLocalStart() {
        val clock = clockWithOffset(500)
        val plan = planPlayback(state(listOf(jam(1)), hostTime = 1_000_000), listOf(song(1)), clock, 900_000, null) as GuestPlan.Play
        assertEquals(1L, plan.song.id)
        assertEquals(999_500L, plan.startAtLocalMs)
        assertEquals(10_000L, plan.positionMs)
        assertFalse(plan.seekOnNextBoundary)
    }

    @Test fun lateJoinerStartsAtExtrapolatedPosition() {
        val plan = planPlayback(state(listOf(jam(1)), hostTime = 1_000_000), listOf(song(1)), ClockSync(), 1_004_000, null) as GuestPlan.Play
        assertEquals(14_000L, plan.positionMs)
    }

    @Test fun driftBoundary() {
        val lib = listOf(song(1))
        val s = state(listOf(jam(1)), pos = 10_000, hostTime = 1_000_000)
        // expected position at now = 1_000_000 + 0 is 10_000
        fun seek(actual: Long) = (planPlayback(s, lib, ClockSync(), 1_000_000, actual) as GuestPlan.Play).seekOnNextBoundary
        assertFalse(seek(10_000 + 1_999))
        assertFalse(seek(10_000 + 2_000))
        assertTrue(seek(10_000 + 2_001))
        assertFalse(seek(10_000 - 1_999))
        assertFalse(seek(10_000 - 2_000))
        assertTrue(seek(10_000 - 2_001))
        assertTrue(seek(10_000 + 2_100))
    }

    @Test fun pausedStateDoesNotExtrapolate() {
        val s = state(listOf(jam(1)), playing = false, pos = 10_000, hostTime = 1_000_000)
        val plan = planPlayback(s, listOf(song(1)), ClockSync(), 1_050_000, 10_000) as GuestPlan.Play
        assertEquals(10_000L, plan.positionMs)
        assertFalse(plan.seekOnNextBoundary)
    }

    @Test fun nextPlayableIndexOverMixedQueue() {
        val s = state(listOf(jam(1), jam(2), jam(3), jam(4)))
        val lib = listOf(song(1), song(4))
        assertEquals(0, nextPlayableIndex(s, lib, 0))
        assertEquals(3, nextPlayableIndex(s, lib, 1))
        assertEquals(3, nextPlayableIndex(s, lib, 3))
        assertNull(nextPlayableIndex(s, lib, 4))
        assertNull(nextPlayableIndex(s, emptyList(), 0))
        assertEquals(0, nextPlayableIndex(s, lib, -2))
    }
}
