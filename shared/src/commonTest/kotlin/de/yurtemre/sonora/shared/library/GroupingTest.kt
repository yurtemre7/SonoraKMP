package de.yurtemre.sonora.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupingTest {
    @Test fun parsesIndividualArtists() {
        assertEquals(listOf("Drake", "Future", "Young Thug"), ArtistParser.parse("Drake feat. Future & Young Thug"))
        assertEquals(listOf("AC/DC"), ArtistParser.parse("AC/DC"))
        assertEquals(listOf("Simon&Garfunkel"), ArtistParser.parse("Simon&Garfunkel"))
        assertEquals(listOf("Simon", "Garfunkel"), ArtistParser.parse("Simon & Garfunkel"))
        assertEquals(listOf("A", "B", "C"), ArtistParser.parse("A\\B;C"))
        assertEquals(listOf("Earth", "Wind", "Fire"), ArtistParser.parse("Earth, Wind & Fire"))
        assertEquals(listOf("Unknown Artist"), ArtistParser.parse("  "))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A x (B) & a"))
    }

    @Test fun commonArtistBecomesAlbumArtist() {
        val albums = AlbumGrouper.build(listOf(
            song(1, artist = "Ufo361, Capital Bra, Sonu Lal", album = "808"),
            song(2, artist = "Ufo361, Gzuz, RAF Camora", album = "808"),
        ))
        assertEquals(1, albums.size)
        assertEquals("Ufo361", albums[0].artist)
    }

    @Test fun sameNameDifferentArtistsSplits() {
        val albums = AlbumGrouper.build(listOf(
            song(1, artist = "Queen", album = "Greatest Hits"),
            song(2, artist = "Guns N Roses", album = "Greatest Hits"),
        ))
        assertEquals(listOf("Queen", "Guns N Roses"), albums.map { it.artist })
    }

    @Test fun unknownArtistKeepsAlbumAndAlbumArtistGroups() {
        val albums = AlbumGrouper.build(listOf(song(1, artist = "", album = "Some Album")))
        assertEquals("Some Album", albums.single().name)
        val va = AlbumGrouper.build(listOf(
            song(1, artist = "X", album = "Hits").copy(albumArtist = "Various"),
            song(2, artist = "Y", album = "Hits").copy(albumArtist = "Various"),
        ))
        assertEquals("Various", va.single().artist)
        assertEquals(Pair(2, 5), TrackNumbers.split(2005)); assertEquals(Pair(null, 7), TrackNumbers.split(7))
    }

    @Test fun tracksSortedByDiscThenTrack() {
        val album = AlbumGrouper.build(listOf(
            song(1, track = 2, disc = 2), song(2, track = 3), song(3, track = 1, disc = 1),
        )).single()
        assertEquals(listOf(3L, 2L, 1L), album.songs.map { it.id })
    }

    @Test fun artistGroupsIncludeEachIndividualArtist() {
        val songs = listOf(song(1, artist = "A & B", album = "X"), song(2, artist = "b", album = "Y"))
        val artists = ArtistGrouper.build(songs, AlbumGrouper.build(songs), mapOf("b" to "/b.jpg"))
        assertEquals(listOf("A", "B"), artists.map { it.name })
        assertEquals(2, artists[1].songs.size)
        assertEquals(listOf("X", "Y"), artists[1].albums.map { it.name })
        assertEquals("/b.jpg", artists[1].localImagePath)
        assertEquals("X", AlbumGrouper.find("x ", "B", songs).name)
        assertEquals(0, ArtistGrouper.find("nobody", songs).songs.size)
    }
}
