package de.yurtemre.sonora.shared.devicesync

import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsDeltaTest {
    private val ids = mapOf("/m/a.mp3" to 1L, "/m/b.mp3" to 2L)

    private fun stats(
        plays: Int,
        complete: Int = plays,
        last: Long? = 100L,
        path: String = "/m/a.mp3",
    ) = SyncStats(
        songs = mapOf(path to SongCounters(plays = plays, skips = 1, restarts = 2, lastPlayedAtMs = last)),
        weekday = mapOf(3 to plays),
        playlistPlays = mapOf("pl" to plays),
        completeListens = complete,
        shuffleSessions = 1,
        firstPlayedPath = path,
        firstPlayedDate = "2026-01-05",
        recentlyPlayed = last?.let { mapOf(path to it) } ?: emptyMap(),
    )

    @Test fun differenceSubtractsPerCounterAndKeepsNewKeys() {
        val base = stats(plays = 5)
        val now = stats(plays = 8).copy(
            songs = stats(plays = 8).songs + ("/m/b.mp3" to SongCounters(plays = 2, lastPlayedAtMs = 7L)),
            playlistPlays = mapOf("pl" to 8, "new" to 4),
        )
        val d = StatsDelta.difference(now, base)
        assertEquals(3, d.songs.getValue("/m/a.mp3").plays)
        assertEquals(0, d.songs.getValue("/m/a.mp3").skips)
        assertEquals(2, d.songs.getValue("/m/b.mp3").plays)
        assertEquals(mapOf(3 to 3), d.weekday)
        assertEquals(mapOf("pl" to 3, "new" to 4), d.playlistPlays)
        assertEquals(3, d.completeListens)
        assertEquals(0, d.shuffleSessions)
    }

    @Test fun differenceNullBaselineIsFullNow() {
        val now = stats(plays = 4)
        val d = StatsDelta.difference(now, null)
        assertEquals(4, d.songs.getValue("/m/a.mp3").plays)
        assertEquals(4, d.completeListens)
    }

    @Test fun noNewListensYieldsEmptySongs() {
        val s = stats(plays = 5)
        val d = StatsDelta.difference(s, s)
        assertTrue(d.songs.isEmpty())
        assertEquals(0, d.completeListens)
    }

    @Test fun changedLastPlayedKeepsSongWithZeroCounters() {
        val d = StatsDelta.difference(stats(plays = 5, last = 200L), stats(plays = 5, last = 100L))
        assertEquals(200L, d.songs.getValue("/m/a.mp3").lastPlayedAtMs)
        assertEquals(0, d.songs.getValue("/m/a.mp3").plays)
    }

    @Test fun senderResetDoesNotSubtract() {
        val d = StatsDelta.difference(stats(plays = 2, complete = 2), stats(plays = 50, complete = 50))
        assertEquals(2, d.songs.getValue("/m/a.mp3").plays)
        assertEquals(2, d.completeListens)
        assertEquals(mapOf(3 to 2), d.weekday)
    }

    @Test fun neverNegative() {
        val d = StatsDelta.difference(stats(plays = 5, complete = 9), stats(plays = 7, complete = 9))
        assertTrue(d.songs.isEmpty())
        assertTrue(d.weekday.isEmpty())
        assertEquals(0, d.completeListens)
    }

    @Test fun passThroughFirstPlayedAndRecent() {
        val d = StatsDelta.difference(stats(plays = 5), stats(plays = 5))
        assertEquals("/m/a.mp3", d.firstPlayedPath)
        assertEquals("2026-01-05", d.firstPlayedDate)
        assertEquals(mapOf("/m/a.mp3" to 100L), d.recentlyPlayed)
    }

    @Test fun nextBaselineAdvancesOnlyImportedSongs() {
        val previous = SyncStats(songs = mapOf("b" to SongCounters(plays = 1)), completeListens = 1)
        val now = SyncStats(
            songs = mapOf("a" to SongCounters(plays = 5), "b" to SongCounters(plays = 3), "c" to SongCounters(plays = 2)),
            completeListens = 10,
            recentlyPlayed = mapOf("a" to 1L, "c" to 2L),
        )
        val next = StatsDelta.nextBaseline(now, previous, imported = setOf("a"))
        assertEquals(mapOf("a" to SongCounters(plays = 5), "b" to SongCounters(plays = 1)), next.songs)
        assertEquals(mapOf("a" to 1L), next.recentlyPlayed)
        assertEquals(10, next.completeListens)
        // "c" matches later: its plays are then added in full, once.
        assertEquals(2, StatsDelta.difference(now, next).songs.getValue("c").plays)
        assertEquals(2, StatsDelta.difference(now, next).songs.getValue("b").plays)
        assertTrue("a" !in StatsDelta.difference(now, next).songs)
    }

    @Test fun nextBaselineAfterSenderResetDropsOldEntries() {
        val previous = SyncStats(songs = mapOf("b" to SongCounters(plays = 9)), completeListens = 50)
        val now = SyncStats(songs = mapOf("b" to SongCounters(plays = 2)), completeListens = 3)
        assertEquals(emptyMap(), StatsDelta.nextBaseline(now, previous, imported = emptySet()).songs)
    }

    @Test fun toSnapshotDeltaDropsUnknownPathsAndFoldsRecent() {
        val delta = SyncStats(
            songs = mapOf(
                "/m/a.mp3" to SongCounters(plays = 3, lastPlayedAtMs = 10L),
                "/x/unknown.mp3" to SongCounters(plays = 9),
            ),
            weekday = mapOf(1 to 2),
            playlistPlays = mapOf("pl" to 1),
            completeListens = 3,
            shuffleSessions = 1,
            firstPlayedPath = "/x/unknown.mp3",
            firstPlayedDate = "2026-01-01",
            recentlyPlayed = mapOf("/m/a.mp3" to 50L, "/m/b.mp3" to 60L, "/x/unknown.mp3" to 70L),
        )
        val snap = StatsDelta.toSnapshotDelta(delta, ids)
        assertEquals(setOf(1L, 2L), snap.songs.keys)
        assertEquals(3, snap.songs.getValue(1L).plays)
        assertEquals(50L, snap.songs.getValue(1L).lastPlayedAtMs)
        assertEquals(60L, snap.songs.getValue(2L).lastPlayedAtMs)
        assertEquals(mapOf(1 to 2), snap.weekdayCounts)
        assertEquals(3, snap.completeListens)
        assertNull(snap.firstPlayedSongId)
        assertNull(snap.firstPlayedDate)
    }

    @Test fun toSnapshotDeltaMapsKnownFirstPlayed() {
        val snap = StatsDelta.toSnapshotDelta(stats(plays = 1), ids)
        assertEquals(1L, snap.firstPlayedSongId)
        assertEquals("2026-01-05", snap.firstPlayedDate)
    }

    @Test fun mergeSumsCountersAndTakesMaxLastPlayed() {
        val receiver = StatsSnapshot(
            songs = mapOf(1L to SongStats(plays = 2, skips = 1, restarts = 0, lastPlayedAtMs = 500L)),
            weekdayCounts = mapOf(3 to 1),
            playlistPlays = mapOf("pl" to 1),
            completeListens = 2,
            shuffleSessions = 1,
        )
        val delta = StatsSnapshot(
            songs = mapOf(
                1L to SongStats(plays = 3, lastPlayedAtMs = 400L),
                2L to SongStats(plays = 1, lastPlayedAtMs = null),
            ),
            weekdayCounts = mapOf(3 to 2, 4 to 1),
            playlistPlays = mapOf("pl" to 2),
            completeListens = 3,
            shuffleSessions = 2,
        )
        val m = StatsDelta.merge(receiver, delta)
        assertEquals(SongStats(plays = 5, skips = 1, restarts = 0, lastPlayedAtMs = 500L), m.songs.getValue(1L))
        assertEquals(SongStats(plays = 1), m.songs.getValue(2L))
        assertEquals(mapOf(3 to 3, 4 to 1), m.weekdayCounts)
        assertEquals(mapOf("pl" to 3), m.playlistPlays)
        assertEquals(5, m.completeListens)
        assertEquals(3, m.shuffleSessions)
    }

    @Test fun mergeLastPlayedNullSafe() {
        val a = StatsSnapshot(songs = mapOf(1L to SongStats(lastPlayedAtMs = null)))
        val b = StatsSnapshot(songs = mapOf(1L to SongStats(lastPlayedAtMs = 9L)))
        assertEquals(9L, StatsDelta.merge(a, b).songs.getValue(1L).lastPlayedAtMs)
        assertEquals(9L, StatsDelta.merge(b, a).songs.getValue(1L).lastPlayedAtMs)
    }

    @Test fun mergeFirstPlayedRules() {
        fun snap(id: Long?, date: String?) = StatsSnapshot(firstPlayedSongId = id, firstPlayedDate = date)
        // earlier delta wins
        assertEquals(snap(2, "2025-12-31"), pick(snap(1, "2026-01-01"), snap(2, "2025-12-31")))
        // later delta loses
        assertEquals(snap(1, "2026-01-01"), pick(snap(1, "2026-01-01"), snap(2, "2026-02-01")))
        // tie: receiver wins
        assertEquals(snap(1, "2026-01-01"), pick(snap(1, "2026-01-01"), snap(2, "2026-01-01")))
        // delta null: receiver wins
        assertEquals(snap(1, "2026-01-01"), pick(snap(1, "2026-01-01"), snap(null, null)))
        // receiver null: delta wins
        assertEquals(snap(2, "2026-01-01"), pick(snap(null, null), snap(2, "2026-01-01")))
    }

    private fun pick(r: StatsSnapshot, d: StatsSnapshot): StatsSnapshot {
        val m = StatsDelta.merge(r, d)
        return StatsSnapshot(firstPlayedSongId = m.firstPlayedSongId, firstPlayedDate = m.firstPlayedDate)
    }

    @Test fun fromSnapshotMapsPathsAndSkipsUnknown() {
        val snap = StatsSnapshot(
            songs = mapOf(
                1L to SongStats(plays = 2, lastPlayedAtMs = 30L),
                2L to SongStats(plays = 1),
                3L to SongStats(plays = 9, lastPlayedAtMs = 5L),
            ),
            weekdayCounts = mapOf(0 to 4),
            playlistPlays = mapOf("pl" to 1),
            completeListens = 3,
            shuffleSessions = 1,
            firstPlayedSongId = 1L,
            firstPlayedDate = "2026-03-03",
        )
        val paths = mapOf(1L to "/m/a.mp3", 2L to "/m/b.mp3")
        val s = SyncStats.fromSnapshot(snap) { paths[it] }
        assertEquals(setOf("/m/a.mp3", "/m/b.mp3"), s.songs.keys)
        assertEquals(mapOf("/m/a.mp3" to 30L), s.recentlyPlayed)
        assertEquals("/m/a.mp3", s.firstPlayedPath)
        assertEquals("2026-03-03", s.firstPlayedDate)
        assertEquals(mapOf(0 to 4), s.weekday)
        assertEquals(3, s.completeListens)
    }

    // Spec acceptance (DSYNC-06 / D-71).
    @Test fun syncingTwiceWithNoNewListensAddsNothing() {
        val sender = stats(plays = 5)
        var receiver = StatsSnapshot()
        var baseline: SyncStats? = null
        repeat(2) {
            receiver = StatsDelta.merge(receiver, StatsDelta.toSnapshotDelta(StatsDelta.difference(sender, baseline), ids))
            baseline = sender
        }
        assertEquals(5, receiver.songs.getValue(1L).plays)
        assertEquals(5, receiver.completeListens)
        assertEquals(mapOf(3 to 5), receiver.weekdayCounts)
    }

    @Test fun threeNewPlaysAddExactlyThree() {
        val before = stats(plays = 5)
        val after = stats(plays = 8, last = 300L)
        val receiver = StatsSnapshot(songs = mapOf(1L to SongStats(plays = 10)), completeListens = 10)
        val m = StatsDelta.merge(receiver, StatsDelta.toSnapshotDelta(StatsDelta.difference(after, before), ids))
        assertEquals(13, m.songs.getValue(1L).plays)
        assertEquals(13, m.completeListens)
        assertEquals(300L, m.songs.getValue(1L).lastPlayedAtMs)
    }

    @Test fun senderResetAddsFullNowOnce() {
        val receiver = StatsSnapshot(songs = mapOf(1L to SongStats(plays = 50)), completeListens = 50)
        val reset = stats(plays = 2, complete = 2)
        val m = StatsDelta.merge(receiver, StatsDelta.toSnapshotDelta(StatsDelta.difference(reset, stats(plays = 50, complete = 50)), ids))
        assertEquals(52, m.songs.getValue(1L).plays)
        assertEquals(52, m.completeListens)
    }
}
