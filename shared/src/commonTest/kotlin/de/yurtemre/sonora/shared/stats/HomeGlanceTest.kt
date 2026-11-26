package de.yurtemre.sonora.shared.stats

import de.yurtemre.sonora.shared.library.song
import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeGlanceTest {
    private fun snap(vararg p: Pair<Long, Int>) = StatsSnapshot(songs = p.associate { (id, n) -> id to SongStats(plays = n) })

    @Test fun emptyLibrary() {
        val g = HomeGlance.from(StatsSnapshot(), emptyList())
        assertEquals(HomeGlance(0, 0, 0L, null, null, 0), g)
    }

    @Test fun favoriteIsMostPlayed() {
        val songs = listOf(song(1), song(2), song(3).copy(isFavorite = true))
        val g = HomeGlance.from(snap(1L to 2, 2L to 7), songs)
        assertEquals(2L, g.favorite?.id)
        assertEquals(7, g.favoritePlays)
        assertEquals(9, g.plays)
        assertEquals(3, g.songCount)
    }

    @Test fun fallsBackToIsFavoriteWhenNothingPlayed() {
        val songs = listOf(song(1), song(2).copy(isFavorite = true), song(3).copy(isFavorite = true))
        val g = HomeGlance.from(StatsSnapshot(), songs)
        assertEquals(2L, g.favorite?.id)
        assertEquals(0, g.favoritePlays)
        assertNull(g.topArtist)
    }

    @Test fun noFavoriteWhenNothingPlayedOrFavorited() {
        assertNull(HomeGlance.from(StatsSnapshot(), listOf(song(1))).favorite)
    }

    @Test fun topArtistByRawArtistString() {
        val songs = listOf(
            song(1, artist = "X"), song(2, artist = "X"),
            song(3, artist = "Y"), song(4, artist = "X feat. Z"),
        )
        val g = HomeGlance.from(snap(1L to 1, 2L to 2, 3L to 4, 4L to 9), songs)
        assertEquals("X feat. Z", g.topArtist)
        val g2 = HomeGlance.from(snap(1L to 2, 2L to 2, 3L to 4), songs)
        assertEquals("X", g2.topArtist) // 4 vs 4 tie -> alphabetical
    }

    @Test fun listenedIsPlaysTimesDuration() {
        val songs = listOf(song(1, durationMs = 1000), song(2, durationMs = 60_000))
        val g = HomeGlance.from(snap(1L to 3, 2L to 2, 99L to 5), songs)
        assertEquals(3 * 1000L + 2 * 60_000L, g.listenedMs)
        assertEquals(5, g.plays) // songs missing from the library are ignored
    }
}
