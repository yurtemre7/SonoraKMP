package de.yurtemre.sonora.devicesync

import de.yurtemre.sonora.shared.devicesync.SyncPaths
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafSyncFileSinkTest {
    @Test fun firstCandidateIsThePathItself() {
        assertEquals("A/Song.mp3", SyncNames.candidate("A/Song.mp3", 1))
    }

    @Test fun collisionCandidatesUseCollisionName() {
        assertEquals("A/Song (2).mp3", SyncNames.candidate("A/Song.mp3", 2))
        assertEquals("Song (10)", SyncNames.candidate("Song", 10))
        assertEquals(SyncPaths.collisionName("A/B/x.flac", 3), SyncNames.candidate("A/B/x.flac", 3))
    }

    @Test fun longNamesTruncateTheBaseNameAndKeepSuffixAndExtension() {
        val rel = "D/" + "\u00e9".repeat(125) + ".mp3" // 250 + 4 = 254 bytes: valid, but " (2)" would overflow
        assertEquals(rel, SyncNames.candidate(rel, 1))
        assertTrue(SyncPaths.collisionName(rel, 2).let { SyncPaths.sanitize(it) == null })
        val c = SyncNames.candidate(rel, 2)!!
        assertEquals(c, SyncPaths.sanitize(c))
        val name = SyncPaths.baseName(c)
        assertTrue(name.encodeToByteArray().size <= 255)
        assertEquals("\u00e9".repeat(123) + " (2).mp3", name)
    }

    @Test fun truncationNeverSplitsSurrogatePairs() {
        val rel = "x/" + "\uD83D\uDE00".repeat(62) + ".mp3" // 248 + 4 bytes, still valid
        assertEquals(rel, SyncNames.candidate(rel, 1))
        val name = SyncPaths.baseName(SyncNames.candidate(rel, 12)!!)
        assertTrue(name.encodeToByteArray().size <= 255)
        val stem = name.removeSuffix(" (12).mp3")
        assertEquals(61, stem.codePointCount(0, stem.length))
        assertEquals("\uD83D\uDE00".repeat(61), stem)
    }

    @Test fun unsafePathsYieldNull() {
        assertNull(SyncNames.candidate("../x.mp3", 2))
        assertNull(SyncNames.candidate("", 2))
    }

    @Test fun partNameKeepsSuffixAndFitsIn255Bytes() {
        assertEquals("Song.mp3.sonora-part", SyncNames.partName("Song.mp3"))
        val long = "a".repeat(250) + ".mp3"
        val part = SyncNames.partName(long)
        assertTrue(part.endsWith(".sonora-part"))
        assertTrue(part.encodeToByteArray().size <= 255)
    }
}
