package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.AlbumSort
import de.yurtemre.sonora.shared.model.ArtistSort
import de.yurtemre.sonora.shared.model.SongActivityView
import de.yurtemre.sonora.shared.model.SongSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SortSearchTest {
    private val songs = listOf(
        song(1, "Bohemian Rhapsody", "Queen", "A Night at the Opera", 354_000, modified = 10),
        song(2, "Hotel California", "Eagles", "Hotel California", 391_000, modified = 30),
        song(3, "Don't Stop Me Now", "Queen", "Jazz", 209_000, modified = 20),
    )
    private val index = LibrarySearchIndex(songs)

    @Test fun songSortsAndDirections() {
        assertEquals(listOf(1L, 3L, 2L), index.getSortedSongs(SongSort.Title, true).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), index.getSortedSongs(SongSort.Title, false).map { it.id })
        assertEquals(listOf(2L, 1L, 3L), index.getSortedSongs(SongSort.Artist, true).map { it.id })
        assertEquals(listOf(3L, 1L, 2L), index.getSortedSongs(SongSort.Duration, true).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), index.getSortedSongs(SongSort.Recent, true).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), index.getSortedSongs(SongSort.Recent, false).map { it.id })
    }

    @Test fun playsUsesActivityViewHook() {
        var seen: SongActivityView? = null
        LibrarySorter.sortSongs(songs, SongSort.Plays, false) { l, v -> seen = v; l }
        assertEquals(SongActivityView.MostPlayed, seen)
        assertEquals(false, LibrarySorter.directionOnSortChange(SongSort.Title, SongSort.Plays, true))
        assertEquals(true, LibrarySorter.directionOnSortChange(SongSort.Plays, SongSort.Title, false))
    }

    @Test fun songSearchIsAndSubstring() {
        assertEquals(listOf(1L), index.searchSongs("bohem").map { it.id })
        assertEquals(2, index.searchSongs("queen").size)
        assertEquals(listOf(1L), index.searchSongs("opera").map { it.id })
        assertEquals(listOf(2L), index.searchSongs("eagles calif").map { it.id })
        assertTrue(index.searchSongs("zzz").isEmpty())
    }

    @Test fun albumAndArtistSearchAndSort() {
        val albums = AlbumGrouper.build(songs)
        assertEquals(listOf("Jazz", "A Night at the Opera"), index.searchAlbums(albums, "queen", AlbumSort.Recent, true).map { it.name })
        val artists = ArtistGrouper.build(songs, albums)
        assertEquals(listOf("Queen", "Eagles"), index.searchArtists(artists, "", ArtistSort.Songs, false).map { it.name })
        assertEquals(listOf("Eagles"), index.searchArtists(artists, "eag").map { it.name })
    }

    @Test fun scrollSections() {
        assertEquals(ScrollSection.Letter('#'), ScrollSection.letterOf("1979"))
        assertEquals(ScrollSection.Letter('Q'), ScrollSection.forSong(songs[0], SongSort.Artist))
        assertEquals(ScrollSection.Minutes(5), ScrollSection.forSong(songs[0], SongSort.Duration))
    }
}
