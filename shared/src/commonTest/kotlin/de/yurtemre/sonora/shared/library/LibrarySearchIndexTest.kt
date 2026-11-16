package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.Playlist
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
}
