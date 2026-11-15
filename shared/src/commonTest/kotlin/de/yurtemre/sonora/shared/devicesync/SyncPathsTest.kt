package de.yurtemre.sonora.shared.devicesync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncPathsTest {
    @Test fun acceptsPlainRelativePaths() {
        assertEquals("Artist/Album/01 Song.mp3", SyncPaths.sanitize("Artist/Album/01 Song.mp3"))
        assertEquals("song.flac", SyncPaths.sanitize("song.flac"))
    }

    @Test fun convertsBackslashes() {
        assertEquals("a/b/c.mp3", SyncPaths.sanitize("a\\b\\c.mp3"))
    }

    @Test fun rejectsTraversalAndAbsolute() {
        listOf("../x", "a/../../x", "a/..", "./a", "a/./b", "/abs", "\\abs", "C:x", "C:\\x", "a//b", "a/b/", "", "..", ".")
            .forEach { assertNull(SyncPaths.sanitize(it), "should reject '$it'") }
    }

    @Test fun keepsUnicodeNames() {
        assertEquals("音楽/アルバム/曲 ♪.mp3", SyncPaths.sanitize("音楽/アルバム/曲 ♪.mp3"))
        assertEquals("Björk/Homogénic/é.flac", SyncPaths.sanitize("Björk/Homogénic/é.flac"))
    }

    @Test fun segmentLengthLimitIsInUtf8Bytes() {
        assertEquals("a".repeat(255), SyncPaths.sanitize("a".repeat(255)))
        assertNull(SyncPaths.sanitize("a".repeat(256)))
        // 86 * 3 bytes = 258 bytes but only 86 chars
        assertNull(SyncPaths.sanitize("あ".repeat(86)))
        assertEquals("あ".repeat(85), SyncPaths.sanitize("あ".repeat(85)))
    }

    @Test fun depthLimit() {
        val ok = List(16) { "d" }.joinToString("/")
        val tooDeep = List(17) { "d" }.joinToString("/")
        assertEquals(ok, SyncPaths.sanitize(ok))
        assertNull(SyncPaths.sanitize(tooDeep))
    }

    @Test fun totalLengthLimit() {
        val seg = "a".repeat(200)
        val tooLong = List(6) { seg }.joinToString("/") // 1205 chars
        assertNull(SyncPaths.sanitize(tooLong))
        assertEquals(1004, SyncPaths.sanitize(List(5) { seg }.joinToString("/"))?.length)
    }

    @Test fun rejectsReservedAndControlChars() {
        listOf("a<b.mp3", "a>b", "a:b", "a\"b", "a|b", "a?b", "a*b", "a\u0000b", "a\nb", "a\u001Fb", "a\u007Fb", "a\tb")
            .forEach { assertNull(SyncPaths.sanitize(it), "should reject ${it.map { c -> c.code }}") }
    }

    @Test fun trimsTrailingDotsAndSpacesPerSegment() {
        assertEquals("a/b/c.mp3", SyncPaths.sanitize("a. /b  /c.mp3"))
        assertEquals("song", SyncPaths.sanitize("song..."))
        assertNull(SyncPaths.sanitize("a/.../b"))
        assertNull(SyncPaths.sanitize("a/   /b"))
    }

    @Test fun kindsForAllowedTypes() {
        listOf("mp3", "m4a", "mp4", "aac", "flac", "ogg", "opus", "wav", "wma", "amr", "3gp", "ts", "mkv", "mid", "midi")
            .forEach {
                assertEquals(FileKind.Audio, SyncPaths.kindOf("A/song.$it"), it)
                assertEquals(FileKind.Audio, SyncPaths.kindOf("A/SONG.${it.uppercase()}"), it)
            }
        assertEquals(FileKind.Lyrics, SyncPaths.kindOf("A/song.lrc"))
        assertEquals(FileKind.Lyrics, SyncPaths.kindOf("A/song.TXT"))
        listOf("cover.jpg", "cover.png", "cover.webp", "Cover.jpg", "FOLDER.JPG", "folder.jpg", "COVER.PNG")
            .forEach { assertEquals(FileKind.Cover, SyncPaths.kindOf("A/B/$it"), it) }
    }

    @Test fun kindsForDisallowedTypes() {
        listOf("A/app.apk", "A/run.exe", "A/x.sh", "A/index.html", "A/noext", "A/photo.jpg", "A/cover.gif", "A/cover.jpeg", "A/folder.png")
            .forEach { assertNull(SyncPaths.kindOf(it), it) }
    }

    @Test fun pathHelpers() {
        assertEquals("mp3", SyncPaths.extension("a/b/Song.MP3"))
        assertEquals("", SyncPaths.extension("a/b/Song"))
        assertEquals("", SyncPaths.extension("a.b/Song"))
        assertEquals("Song.MP3", SyncPaths.baseName("a/b/Song.MP3"))
        assertEquals("Song", SyncPaths.baseName("Song"))
        assertEquals("a/b", SyncPaths.parent("a/b/Song.mp3"))
        assertEquals("", SyncPaths.parent("Song.mp3"))
    }

    @Test fun collisionNames() {
        assertEquals("Song (2).mp3", SyncPaths.collisionName("Song.mp3", 2))
        assertEquals("Song (3).mp3", SyncPaths.collisionName("Song.mp3", 3))
        assertEquals("A/B/Song (2).mp3", SyncPaths.collisionName("A/B/Song.mp3", 2))
        assertEquals("A.b/Song (2)", SyncPaths.collisionName("A.b/Song", 2))
        assertEquals("a.b (4).flac", SyncPaths.collisionName("a.b.flac", 4))
        assertEquals("Song.mp3", SyncPaths.collisionName("Song.mp3", 1))
    }
}
