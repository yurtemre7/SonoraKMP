package de.yurtemre.sonora.devicesync

import de.yurtemre.sonora.shared.model.Song
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReceiverIndexBuilderTest {
    private fun song(path: String, size: Long? = 99) = Song(
        id = 7, title = "T", artist = "A", album = "B", durationMs = 1234, filePath = path, fileSize = size,
    )

    @Test fun relPathIsRelativeToRootWithSlashPrefixCheck() {
        assertEquals("A/a.mp3", ReceiverIndexBuilder.relativeTo("/m/Music", "/m/Music/A/a.mp3"))
        assertEquals("A/a.mp3", ReceiverIndexBuilder.relativeTo("/m/Music/", "/m/Music/A/a.mp3"))
        assertNull(ReceiverIndexBuilder.relativeTo("/m/Music", "/m/MusicOther/a.mp3"))
        assertNull(ReceiverIndexBuilder.relativeTo("/m/Music", "/m/Music"))
        assertNull(ReceiverIndexBuilder.relativeTo("/m/Music", "/x/a.mp3"))
    }

    @Test fun songMapsToReceiverSongWithLowercaseExtension() {
        val s = ReceiverIndexBuilder.toReceiverSong(song("/m/Music/A/a.FLAC"), "/m/Music")
        assertEquals("A/a.FLAC", s.relPath)
        assertEquals("flac", s.extension)
        assertEquals(99L, s.size)
        assertEquals(7L, s.songId)
        assertEquals(1234L, s.durationMs)
        val outside = ReceiverIndexBuilder.toReceiverSong(song("/x/a.mp3", size = null), "/m/Music")
        assertNull(outside.relPath)
        assertNull(outside.size)
        assertEquals("mp3", outside.extension)
    }
}
