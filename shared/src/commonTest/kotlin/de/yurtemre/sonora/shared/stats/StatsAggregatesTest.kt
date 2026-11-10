package de.yurtemre.sonora.shared.stats

import de.yurtemre.sonora.shared.model.*
import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import kotlin.test.*

class StatsAggregatesTest {
    private fun song(id: Long, title: String, artist: String = "A") = Song(id, title, artist, "Al", 1000, "/$id")
    private val s1 = song(1, "b"); private val s2 = song(2, "a"); private val s3 = song(3, "c"); private val s4 = song(4, "a", "Z")
    private val lib = listOf(s1, s2, s3, s4)
    private val snap = StatsSnapshot(
        songs = linkedMapOf(
            1L to SongStats(plays = 3, skips = 1, lastPlayedAtMs = 100),
            2L to SongStats(plays = 5, restarts = 2, lastPlayedAtMs = 300),
            3L to SongStats(plays = 3, lastPlayedAtMs = 300),
            99L to SongStats(plays = 9),
        ),
        weekdayCounts = mapOf(3 to 4, 1 to 4, 0 to 2),
        playlistPlays = linkedMapOf("gone" to 9, "p1" to 1, "p2" to 2),
        firstPlayedSongId = 2,
    )

    @Test fun totalsAndTops() {
        val agg = StatsAggregates
        assertEquals(1, agg.totalSkips(snap)); assertEquals(2, agg.totalRestarts(snap))
        assertEquals(4, agg.uniqueSongsPlayed(snap)); assertEquals(3, agg.playlistsWithPlays(snap))
        assertEquals(listOf(2L, 1L, 3L), agg.topSongs(snap, lib).map { it.item.id })
        assertEquals(listOf(2L), agg.topSongs(snap, lib, 1).map { it.item.id })
        val pls = listOf(Playlist("p1", "One"), Playlist("p2", "Two"))
        assertEquals(listOf("p2", "p1"), agg.topPlaylists(snap, pls).map { it.item.id })
        assertEquals(s2, agg.firstPlayedSong(snap, lib))
        val albums = listOf(AlbumGroup("X", "A", listOf(s1)), AlbumGroup("Y", "A", listOf(s2, s3)), AlbumGroup("Z", "Z", listOf(s4)))
        assertEquals(listOf("Y" to 8, "X" to 3), agg.topAlbums(snap, albums).map { it.item.name to it.plays })
        assertEquals(2, agg.albumListenCount(snap, albums))
        val artists = listOf(ArtistGroup("A", listOf(s1, s2, s3), emptyList(), null), ArtistGroup("Z", listOf(s4), emptyList(), null))
        assertEquals(1, agg.artistListenCount(snap, artists))
        assertEquals(11, agg.topArtists(snap, artists).single().plays)
    }

    @Test fun mostActiveDay() {
        assertEquals(1, StatsAggregates.mostActiveDay(snap))
        assertNull(StatsAggregates.mostActiveDay(StatsSnapshot()))
    }

    @Test fun activityViews() {
        val a = StatsAggregates
        assertEquals(lib, a.applyActivityView(snap, lib, SongActivityView.All))
        assertEquals(listOf(2L, 3L, 1L), a.applyActivityView(snap, lib, SongActivityView.RecentlyPlayed).map { it.id })
        assertEquals(listOf(2L, 1L, 3L, 4L), a.applyActivityView(snap, lib, SongActivityView.MostPlayed).map { it.id })
        assertEquals(listOf(4L, 1L, 3L, 2L), a.applyActivityView(snap, lib, SongActivityView.LeastPlayed).map { it.id })
        assertEquals(listOf(2L), a.recentSongs(snap, lib, 1).map { it.id })
    }
}
