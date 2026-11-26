package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JumpBackInTest {
    private fun snap(vararg e: Pair<Long, Long>) =
        StatsSnapshot(songs = e.associate { (id, t) -> id to SongStats(plays = 1, lastPlayedAtMs = t) })

    @Test fun distinctAlbumsNewestFirst() {
        val songs = listOf(
            song(1, album = "X", artist = "P"), song(2, album = "X", artist = "P"),
            song(3, album = "Y", artist = "Q"), song(4, album = "Z", artist = "R"),
        )
        val r = JumpBackIn.recentAlbums(songs, snap(1L to 100, 2L to 300, 3L to 200, 4L to 50))
        assertEquals(listOf("X", "Y", "Z"), r.map { it.albumName })
        assertEquals(listOf("P", "Q", "R"), r.map { it.albumArtist })
    }

    @Test fun skipsBlankAlbumsAndUnplayed() {
        val songs = listOf(song(1, album = " "), song(2, album = "Y"), song(3, album = "Z"))
        val r = JumpBackIn.recentAlbums(songs, snap(1L to 500, 2L to 10))
        assertEquals(listOf("Y"), r.map { it.albumName })
        assertTrue(JumpBackIn.recentAlbums(songs, StatsSnapshot()).isEmpty())
    }

    @Test fun respectsLimit() {
        val songs = (1L..5L).map { song(it, album = "A$it") }
        val r = JumpBackIn.recentAlbums(songs, snap(1L to 1, 2L to 2, 3L to 3, 4L to 4, 5L to 5), limit = 2)
        assertEquals(listOf("A5", "A4"), r.map { it.albumName })
    }

    @Test fun songsInTrackOrderAndCover() {
        val songs = listOf(song(1, album = "X", track = 3), song(2, album = "X", track = 1), song(3, album = "X", track = 2))
        val r = JumpBackIn.recentAlbums(songs, snap(1L to 9)).single()
        assertEquals(listOf(2L, 3L, 1L), r.songs.map { it.id })
        assertEquals(2L, r.coverSong.id)
    }
}
