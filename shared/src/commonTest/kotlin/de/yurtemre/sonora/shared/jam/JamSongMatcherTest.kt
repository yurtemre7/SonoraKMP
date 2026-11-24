package de.yurtemre.sonora.shared.jam

import de.yurtemre.sonora.shared.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JamSongMatcherTest {
    private fun song(id: Long, title: String = "Hello", artist: String = "Adele", album: String = "25", dur: Long = 200_000, path: String = "/m/$id.mp3") =
        Song(id = id, title = title, artist = artist, album = album, durationMs = dur, filePath = path)

    private val jam = JamSong("Hello", "Adele", "25", 200_000)

    @Test fun matchesWithinTolerance() {
        assertEquals(1L, JamSongMatcher.match(jam, listOf(song(1, dur = 201_999)))?.id)
        assertNull(JamSongMatcher.match(jam, listOf(song(1, dur = 202_001))))
    }

    @Test fun missingReturnsNull() {
        assertNull(JamSongMatcher.match(jam, emptyList()))
        assertNull(JamSongMatcher.match(jam, listOf(song(1, title = "Other"), song(2, artist = "X"))))
    }

    @Test fun caseAndUnicodeInsensitive() {
        val j = JamSong("  ÄÖÜ Straße ", "ÉMILIE", "", 1000)
        assertEquals(5L, JamSongMatcher.match(j, listOf(song(5, title = "äöü straße", artist = "émilie", dur = 1000)))?.id)
    }

    @Test fun tieBreakPrefersAlbumThenDurationThenPath() {
        val lib = listOf(
            song(1, album = "Other", dur = 200_000),
            song(2, album = "25", dur = 201_000, path = "/b"),
            song(3, album = "25", dur = 200_500, path = "/z"),
            song(4, album = "25", dur = 200_500, path = "/a"),
        )
        assertEquals(4L, JamSongMatcher.match(jam, lib)?.id)
        assertEquals(4L, JamSongMatcher.match(jam, lib.reversed())?.id)
        assertEquals(1L, JamSongMatcher.match(jam, listOf(song(1, album = "Other"), song(2, album = "Else", dur = 201_000)))?.id)
    }
}
