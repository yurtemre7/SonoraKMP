package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class LibrarySearchIndexTest {
    @Test fun albumSearchMatchesAllTermsAcrossNameAndArtist() {
        val albums = listOf(
            AlbumGroup("Abbey Road", "The Beatles", emptyList()),
            AlbumGroup("Abbey Sessions", "Someone Else", emptyList()),
        )
        val got = LibrarySearchIndex().searchAlbums(albums, "abbey beatles")
        assertEquals(listOf("Abbey Road"), got.map { it.name })
        assertEquals(2, LibrarySearchIndex().searchAlbums(albums, "  abbey ").size)
    }

    @Test fun playlistFilterIgnoresCaseAccentsAndKana() {
        val lists = listOf(Playlist("1", "Café Mix"), Playlist("2", "ロック"), Playlist("3", "Gym"))
        assertEquals(listOf("1"), LibrarySearchIndex.filterPlaylists(lists, "cafe").map { it.id })
        assertEquals(listOf("2"), LibrarySearchIndex.filterPlaylists(lists, "ろっく").map { it.id })
        assertEquals(3, LibrarySearchIndex.filterPlaylists(lists, " ").size)
    }

    private fun song(id: Long, title: String, artist: String = "X", album: String = "Y") =
        Song(id = id, title = title, artist = artist, album = album, durationMs = 1000, filePath = "/m/$id.mp3")

    @Test fun songsAreRankedByRelevanceBeforeSort() {
        val idx = LibrarySearchIndex(listOf(
            song(1, "A Love Story"), song(2, "Glove"), song(3, "Love"), song(4, "Love Me Do"),
        ))
        assertEquals(listOf(3L, 4L, 1L, 2L), idx.searchSongs("love").map { it.id })
    }

    @Test fun titleOutranksArtistMatch() {
        val idx = LibrarySearchIndex(listOf(song(1, "Zzz", artist = "Queen"), song(2, "Queen of Hearts")))
        assertEquals(listOf(2L, 1L), idx.searchSongs("queen").map { it.id })
    }

    @Test fun punctuationIsIgnored() {
        val idx = LibrarySearchIndex(listOf(song(1, "Thunderstruck", artist = "AC/DC"), song(2, "Other")))
        assertEquals(listOf(1L), idx.searchSongs("acdc").map { it.id })
        assertEquals(listOf(1L), idx.searchSongs("ac/dc").map { it.id })
    }

    @Test fun artistSearchMatchesAllTerms() {
        val artists = listOf(
            ArtistGroup("Tyler, The Creator", emptyList(), emptyList(), null),
            ArtistGroup("The Beatles", emptyList(), emptyList(), null),
        )
        assertEquals(listOf("Tyler, The Creator"), LibrarySearchIndex().searchArtists(artists, "creator tyler").map { it.name })
    }

    @Test fun punctuationOnlyTermsAreOptional() {
        val idx = LibrarySearchIndex(listOf(song(1, "Love Story")))
        assertEquals(listOf(1L), idx.searchSongs("love - story").map { it.id })
    }

    @Test fun punctuationInsensitiveWholeFieldOutranksSubstring() {
        val idx = LibrarySearchIndex(listOf(song(1, "Xacdc"), song(2, "AC/DC")))
        assertEquals(listOf(2L, 1L), idx.searchSongs("acdc").map { it.id })
        assertEquals(listOf(2L), idx.searchSongs("ac/dc").map { it.id })
    }
}
