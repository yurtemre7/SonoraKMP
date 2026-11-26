package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecommendationsTest {
    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun snap(vararg e: Pair<Long, SongStats>) = StatsSnapshot(songs = mapOf(*e))
    private fun played(plays: Int, ago: Long = 2 * day) = SongStats(plays = plays, lastPlayedAtMs = now - ago)
    private fun ids(l: List<de.yurtemre.sonora.shared.model.Song>) = l.map { it.id }

    @Test fun ranksByLikedScore() {
        val songs = listOf(song(1, artist = "X", album = "X1"), song(2, artist = "Y", album = "Y1"), song(3, artist = "Z", album = "Z1"))
        val r = Recommendations.recommended(songs, snap(1L to played(3), 2L to played(9), 3L to played(5)), now)
        assertEquals(listOf(2L, 3L, 1L), ids(r))
    }

    @Test fun boostsUnplayedSongsSharingArtistWithTopSongs() {
        val songs = listOf(
            song(1, artist = "Fav", album = "F1"),
            song(2, artist = "Fav", album = "F2"),
            song(3, artist = "Other", album = "O1"),
        )
        val r = Recommendations.recommended(songs, snap(1L to played(10)), now)
        assertEquals(listOf(1L, 2L), ids(r))
    }

    @Test fun fallsBackToRecentlyAddedWithoutHistory() {
        val songs = listOf(song(1, modified = 10), song(2, modified = 30), song(3, modified = 20))
        assertEquals(listOf(2L, 3L, 1L), ids(Recommendations.recommended(songs, StatsSnapshot(), now)))
    }

    @Test fun excludesSongsPlayedInsideWindow() {
        val songs = listOf(song(1), song(2))
        val recent = played(10, ago = Recommendations.RECENT_EXCLUDE_MS - 1)
        val r = Recommendations.recommended(songs, snap(1L to recent, 2L to played(2)), now)
        assertEquals(listOf(2L), ids(r))
        val edge = played(10, ago = Recommendations.RECENT_EXCLUDE_MS + 1)
        assertTrue(1L in ids(Recommendations.recommended(songs, snap(1L to edge, 2L to played(2)), now)))
    }

    @Test fun deterministicTieBreakByTitleThenId() {
        val songs = listOf(song(3, title = "b"), song(2, title = "a"), song(1, title = "a"))
        val st = snap(1L to played(4), 2L to played(4), 3L to played(4))
        val r = ids(Recommendations.recommended(songs, st, now))
        assertEquals(listOf(1L, 2L, 3L), r)
        assertEquals(r, ids(Recommendations.recommended(songs.reversed(), st, now)))
    }

    @Test fun respectsLimitAndHasNoDuplicates() {
        val songs = (1L..40L).map { song(it, artist = "A$it", album = "B$it") } + song(5)
        val st = StatsSnapshot(songs = (1L..40L).associateWith { played(3) })
        val r = Recommendations.recommended(songs, st, now, limit = 7)
        assertEquals(7, r.size)
        assertEquals(r.size, r.map { it.id }.toSet().size)
        assertEquals(Recommendations.DEFAULT_LIMIT, Recommendations.recommended(songs, st, now).size)
        assertTrue(Recommendations.recommended(songs, st, now, limit = 0).isEmpty())
    }
}
