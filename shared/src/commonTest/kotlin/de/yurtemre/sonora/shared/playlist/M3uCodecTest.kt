package de.yurtemre.sonora.shared.playlist

import de.yurtemre.sonora.shared.model.Song
import kotlin.test.*

class M3uCodecTest {
    private fun song(id: Long, path: String, d: Long = 61_999) = Song(id, "T$id", "A$id", "Al", d, path)

    @Test fun exportFormat() {
        assertEquals(
            "#EXTM3U\n#EXTINF:61,A1 - T1\n/m/a.mp3\n#EXTINF:0,A2 - T2\n/m/b.mp3\n",
            M3uCodec.encode(listOf(song(1, "/m/a.mp3"), song(2, "/m/b.mp3", 999))),
        )
    }
    @Test fun decodeSkipsCommentsAndBlanks() {
        assertEquals(listOf("/x/a.mp3", "b.mp3"), M3uCodec.decodePaths("#EXTM3U\n\n#EXTINF:1,x\n /x/a.mp3 \r\nb.mp3\n"))
    }
    @Test fun matching() {
        val lib = listOf(song(1, "/m/ba.mp3"), song(2, "/m/A.mp3"), song(3, "/n/a.mp3"), song(4, "/m/my song.mp3"))
        val got = M3uCodec.match(listOf("/n/a.mp3", "C:\\x\\a.MP3", "/other/a.mp3", "zz/My%20Song.mp3", "/nope.mp3", "/n/a.mp3"), lib)
        assertEquals(listOf(3L, 2L, 2L, 4L, 3L), got.map { it.id })
        assertTrue(M3uCodec.match(listOf("a.mp3"), listOf(song(1, "/m/ba.mp3"))).isEmpty())
    }
    @Test fun names() {
        assertEquals("a_b.m3u", M3uCodec.exportFileName("a/b"))
        assertEquals("_________", M3uCodec.sanitizeFileName("\\/:*?\"<>|"))
        assertEquals("Mix", M3uCodec.playlistNameFromFile("/d/Mix.m3u8"))
        assertEquals("Mix.M3U", M3uCodec.playlistNameFromFile("Mix.M3U"))
        assertTrue(M3uCodec.isM3uFile("x.M3U8")); assertFalse(M3uCodec.isM3uFile("x.pls"))
    }
}
