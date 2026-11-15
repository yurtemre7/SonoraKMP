package de.yurtemre.sonora.shared.devicesync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferPlannerTest {
    private fun audio(id: Int, path: String, size: Long = 1000, title: String = "T", dur: Long = 180_000) =
        ManifestFile(id, path, size, 0, FileKind.Audio, AudioMeta(title, "A", "B", dur))

    private fun song(id: Long, path: String?, title: String = "T", dur: Long = 180_000, size: Long? = 500) =
        ReceiverSong(id, path, title, "A", "B", dur, size)

    private fun plan(files: List<ManifestFile>, existing: Map<String, Long> = emptyMap(), lib: List<ReceiverSong> = emptyList()) =
        TransferPlanner.plan(files, ReceiverIndex(existing, lib))

    @Test fun mp3ToFlacIsReplace() {
        val s = song(7, "x/t.mp3", size = 400)
        val item = plan(listOf(audio(1, "x/t.flac")), lib = listOf(s)).items.single()
        assertEquals(Status.Replace, item.status)
        assertEquals(s, item.replaces)
    }

    @Test fun sameSongSameFormatIsPresent() {
        val item = plan(listOf(audio(1, "other/Song.MP3", dur = 181_500)), lib = listOf(song(7, "x/t.mp3"))).items.single()
        assertEquals(Status.Present, item.status)
    }

    @Test fun renamedSameSizeNeedsFingerprint() {
        val item = plan(listOf(audio(1, "new.mp3", size = 1000)), existing = mapOf("old.mp3" to 1000L)).items.single()
        assertEquals(Status.New, item.status)
        assertTrue(item.needsFingerprint)
    }

    @Test fun newWithoutSizeCollisionNoFingerprint() {
        val item = plan(listOf(audio(1, "new.mp3", size = 1000)), existing = mapOf("old.mp3" to 999L)).items.single()
        assertFalse(item.needsFingerprint)
    }

    @Test fun samePathDifferentSizeFlagged() {
        val item = plan(listOf(audio(1, "a.mp3", size = 1000)), existing = mapOf("a.mp3" to 5L, "b.mp3" to 1000L)).items.single()
        assertEquals(Status.New, item.status)
        assertTrue(item.needsFingerprint)
    }

    @Test fun lyricsAndCoverPresentByPath() {
        val files = listOf(
            ManifestFile(1, "a/t.lrc", 10, 0, FileKind.Lyrics),
            ManifestFile(2, "a/cover.jpg", 20, 0, FileKind.Cover),
            ManifestFile(3, "a/u.lrc", 10, 0, FileKind.Lyrics),
        )
        val p = plan(files, existing = mapOf("a/t.lrc" to 10L, "a/cover.jpg" to 20L))
        assertEquals(listOf(Status.Present, Status.Present, Status.New), p.items.map { it.status })
    }

    @Test fun unsafeAndMismatchedRejected() {
        val bad = ManifestFile(1, "../evil.mp3", 1, 0, FileKind.Audio)
        val badType = ManifestFile(2, "a/run.exe", 1, 0, FileKind.Audio)
        val mismatch = ManifestFile(3, "a/t.mp3", 1, 0, FileKind.Lyrics)
        val p = plan(listOf(bad, badType, mismatch, audio(4, "ok.mp3")))
        assertEquals(listOf(bad, badType, mismatch), p.rejected)
        assertEquals(listOf(4), p.items.map { it.file.id })
    }

    @Test fun songClaimedOnceLowestIdWins() {
        val p = plan(listOf(audio(2, "b.mp3"), audio(1, "a.mp3")), lib = listOf(song(9, "z.mp3"), song(8, "y.mp3")))
        assertEquals(Status.Present, p.items.first { it.file.id == 1 }.status)
        // ids 1,2 each claim one of two identical songs; none left for a third
        val p2 = plan(listOf(audio(1, "a.mp3"), audio(2, "b.mp3")), lib = listOf(song(9, "z.mp3")))
        assertEquals(Status.Present, p2.items.first { it.file.id == 1 }.status)
        assertEquals(Status.New, p2.items.first { it.file.id == 2 }.status)
    }

    @Test fun tiesPickLowestSongIdAndClosestDuration() {
        val p = plan(listOf(audio(1, "a.flac")), lib = listOf(song(9, "z.mp3"), song(8, "y.mp3")))
        assertEquals(8L, p.items.single().replaces?.songId)
        val q = plan(listOf(audio(1, "a.flac", dur = 180_000)), lib = listOf(song(1, "z.mp3", dur = 181_000), song(2, "y.mp3", dur = 180_200)))
        assertEquals(2L, q.items.single().replaces?.songId)
    }

    @Test fun pathPresentSongNotClaimedByTags() {
        val files = listOf(audio(1, "a.mp3", size = 500), audio(2, "b.flac"))
        val p = plan(files, existing = mapOf("a.mp3" to 500L), lib = listOf(song(7, "a.mp3")))
        assertEquals(Status.Present, p.items[0].status)
        assertEquals(Status.New, p.items[1].status)
    }

    @Test fun outsideDestinationSongNotMatched() {
        val p = plan(listOf(audio(1, "a.mp3")), lib = listOf(song(7, null)))
        assertEquals(Status.New, p.items.single().status)
    }

    @Test fun toleranceExceededIsNew() {
        val p = plan(listOf(audio(1, "a.mp3", dur = 183_000)), lib = listOf(song(7, "a.mp3")))
        assertEquals(Status.New, p.items.single().status)
    }

    @Test fun spaceMathAndGrouping() {
        val p = plan(
            listOf(audio(1, "a.flac", size = 1000), audio(2, "n.mp3", size = 300, title = "N"), audio(3, "p.mp3", size = 50, title = "P"),
                ManifestFile(4, "a/t.lrc", 7, 0, FileKind.Lyrics)),
            existing = mapOf("p.mp3" to 50L),
            lib = listOf(song(7, "a.mp3", size = 400), song(8, "p.mp3", title = "P", size = 50)),
        )
        assertEquals(setOf(1, 2, 4), p.defaultSelection())
        assertEquals(1307L, p.bytesNeeded(setOf(1, 2, 3, 4)))
        assertEquals(400L, p.bytesFreedAfter(setOf(1, 2, 3, 4)))
        assertEquals(0L, p.bytesFreedAfter(setOf(2)))
        val margin = 209_715_200L
        assertTrue(p.fits(setOf(1), 1000 + margin))
        assertFalse(p.fits(setOf(1), 999 + margin))
        assertEquals(setOf(FileKind.Audio, FileKind.Lyrics), p.groupsByKind().keys)
        assertEquals(AudioFormatStat(2, 350), p.audioFormats()["mp3"])
        assertEquals(AudioFormatStat(1, 1000), p.audioFormats()["flac"])
        assertNull(p.audioFormats()["ogg"])
    }
}
