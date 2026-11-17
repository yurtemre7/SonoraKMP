package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagEditTest {
    private val song = Song(
        1, "Title", "Artist", "Album", 1000, "/m/1.mp3",
        trackNumber = 3, discNumber = 1, albumArtist = "AA", genre = "Rock", year = 1999,
    )

    @Test fun fromSongRoundTripsAndHidesUnknownPlaceholders() {
        val e = TagEdit.from(song)
        assertEquals("Title", e.title); assertEquals("1999", e.year); assertEquals("3", e.track)
        val unknown = TagEdit.from(song.copy(artist = "Unknown Artist", album = "Unknown Album", year = null))
        assertEquals("", unknown.artist); assertEquals("", unknown.album); assertEquals("", unknown.year)
    }

    @Test fun validation() {
        assertNull(TagEdit(title = "x").error())
        assertEquals(TagEdit.Error.EmptyTitle, TagEdit(title = "  ").error())
        assertEquals(TagEdit.Error.BadYear, TagEdit(title = "x", year = "abc").error())
        assertEquals(TagEdit.Error.BadYear, TagEdit(title = "x", year = "0").error())
        assertEquals(TagEdit.Error.BadTrack, TagEdit(title = "x", track = "-2").error())
        assertEquals(TagEdit.Error.BadDisc, TagEdit(title = "x", disc = "0").error())
        assertNull(TagEdit(title = "x", year = "", track = "", disc = "").error())
    }

    @Test fun normalizedTrimsAndParses() {
        val n = TagEdit(title = " T ", year = " 2001 ", track = " 7", lyrics = "\n a\nb \n").normalized()
        assertEquals("T", n.title); assertEquals(2001, n.yearOrNull()); assertEquals(7, n.trackOrNull())
        assertEquals("a\nb", n.lyrics)
    }

    @Test fun unknownPlaceholdersRoundTripAsUnchanged() {
        val s = song.copy(artist = "Unknown Artist", album = "Unknown Album")
        assertFalse(TagEdit.from(s).changesRow(s))
    }

    @Test fun changesRowDetectsEdits() {
        assertFalse(TagEdit.from(song).changesRow(song))
        assertTrue(TagEdit.from(song).copy(genre = "Pop").changesRow(song))
        assertTrue(TagEdit.from(song).copy(track = "").changesRow(song))
    }
}
