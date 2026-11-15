package de.yurtemre.sonora.shared.devicesync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferPlannerTest {
    private fun audio(id: Int, path: String, size: Long = 1000, title: String = "T", dur: Long = 180_000) =
        ManifestFile(id, path, size, 0, FileKind.Audio, AudioMeta(title, "A", "B", dur))

    private fun song(id: Long, path: String?, title: String = "T", dur: Long = 180_000, size: Long? = 500, ext: String = path?.substringAfterLast('.') ?: "mp3") =
        ReceiverSong(id, path, title, "A", "B", dur, size, ext)

    private fun plan(files: List<ManifestFile>, existing: Map<String, Long> = emptyMap(), lib: List<ReceiverSong> = emptyList()) =
        TransferPlanner.plan(files, ReceiverIndex(existing, lib))

    @Test fun songsTheSenderLacksAreExtras() {
        val keep = song(1, "x/keep.mp3", title = "Keep")
        val extra = song(2, "x/extra.mp3", title = "Extra")
        val outside = song(3, null, title = "Elsewhere") // outside the destination: never an extra
        val p = plan(
            listOf(audio(1, "x/keep.mp3", size = 500, title = "Keep")),
            existing = mapOf("x/keep.mp3" to 500L, "x/extra.mp3" to 500L),
            lib = listOf(keep, extra, outside),
        )
        assertEquals(listOf(extra), p.extras)
    }

    @Test fun replacedSongsAndEmptySendersAreNeverExtras() {
        val old = song(7, "x/t.mp3", size = 400)
        assertTrue(plan(listOf(audio(1, "x/t.flac")), lib = listOf(old)).extras.isEmpty()) // Replace claims it
        // A sender without audio must not turn the whole receiver library into "extras".
        assertTrue(plan(emptyList(), lib = listOf(old)).extras.isEmpty())
    }

    @Test fun pathTheSenderWritesIsNotAnExtra() {
        // Same path, different tags and size: the new file lands there, so deleting "the old song" would hit it.
        val old = song(7, "x/t.mp3", title = "Old", size = 400)
        val p = plan(listOf(audio(1, "x/t.mp3", size = 1000, title = "New")), existing = mapOf("x/t.mp3" to 400L), lib = listOf(old))
        assertTrue(p.extras.isEmpty())
    }

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

    @Test fun pathLookupIgnoresCase() {
        // Receiver has Artist/…, sender artist/…: same files, nothing to copy (the destination is case-insensitive).
        val files = listOf(
            audio(1, "artist/Album/t.mp3", size = 1000, title = "Unmatched"),
            ManifestFile(2, "artist/cover.jpg", 20, 0, FileKind.Cover),
            ManifestFile(3, "artist/Album/T.LRC", 10, 0, FileKind.Lyrics),
        )
        val existing = mapOf("Artist/Album/t.mp3" to 1000L, "Artist/cover.jpg" to 20L, "Artist/Album/t.lrc" to 10L)
        val p = plan(files, existing = existing, lib = listOf(song(7, "Artist/Album/t.mp3", title = "Other")))
        assertEquals(listOf(Status.Present, Status.Present, Status.Present), p.items.map { it.status })
        assertTrue(p.defaultSelection().isEmpty())
    }

    @Test fun caseOnlyPathMatchClaimsTheSong() {
        // The song found by path is claimed, so tag matching cannot plan replacing it with another sender file.
        val files = listOf(audio(1, "artist/t.mp3", size = 1000, title = "Tags differ"), audio(2, "other/t.flac", size = 3000))
        val p = plan(files, existing = mapOf("Artist/t.mp3" to 1000L), lib = listOf(song(7, "Artist/t.mp3")))
        assertEquals(listOf(Status.Present, Status.New), p.items.map { it.status })
    }

    @Test fun sameSizeSidecarsAreFingerprinted() {
        val files = listOf(
            ManifestFile(1, "b/cover.jpg", 20, 0, FileKind.Cover),
            ManifestFile(2, "b/t.lrc", 10, 0, FileKind.Lyrics),
            ManifestFile(3, "b/u.lrc", 11, 0, FileKind.Lyrics),
        )
        val p = plan(files + audio(4, "b/t.mp3", size = 999, title = "New song"), existing = mapOf("a/folder.jpg" to 20L, "a/x.lrc" to 10L))
        assertEquals(listOf(true, true, false, false), p.items.map { it.needsFingerprint })
    }

    @Test fun sidecarsOfSongsPresentElsewhereAreNotCopied() {
        // The sender's layout differs: both songs are already here by tags, in another folder (or outside the
        // destination). Their lyrics and cover would only be orphans next to no audio.
        val files = listOf(
            audio(1, "Music/Album/t.mp3", title = "T"),
            audio(2, "Music/Album/u.mp3", title = "U"),
            ManifestFile(3, "Music/Album/t.lrc", 10, 0, FileKind.Lyrics),
            ManifestFile(4, "Music/Album/cover.jpg", 20, 0, FileKind.Cover),
        )
        val lib = listOf(song(7, "Album/t.mp3", title = "T"), song(8, null, title = "U"))
        val p = plan(files, lib = lib)
        assertEquals(listOf(Status.Present, Status.Present, Status.Present, Status.Present), p.items.map { it.status })
    }

    @Test fun sidecarsStayNewWhenTheirAudioIsCopiedOrInTheSameFolder() {
        val files = listOf(
            audio(1, "A/t.mp3", title = "T"),          // Present by tags in the same folder (another name)
            ManifestFile(2, "A/t.lrc", 10, 0, FileKind.Lyrics),
            audio(3, "B/u.flac", title = "U"),         // Replace: the new file lands in B/
            ManifestFile(4, "B/u.lrc", 10, 0, FileKind.Lyrics),
            audio(5, "C/v.mp3", title = "V"),          // Present elsewhere
            audio(6, "C/w.mp3", title = "W"),          // New
            ManifestFile(7, "C/cover.jpg", 20, 0, FileKind.Cover),
            ManifestFile(8, "C/v.lrc", 10, 0, FileKind.Lyrics),
        )
        val lib = listOf(
            song(10, "a/T song.mp3", title = "T"),
            song(11, "elsewhere/u.mp3", title = "U"),
            song(12, "elsewhere/v.mp3", title = "V"),
        )
        val p = plan(files, lib = lib)
        assertEquals(
            listOf(Status.Present, Status.New, Status.Replace, Status.New, Status.Present, Status.New, Status.New, Status.Present),
            p.items.map { it.status },
        )
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
        val p = plan(listOf(audio(2, "b.flac"), audio(1, "a.flac")), lib = listOf(song(9, "z.mp3"), song(8, "y.mp3")))
        assertEquals(8L, p.items.first { it.file.id == 1 }.replaces?.songId)
        assertEquals(Status.Replace, p.items.first { it.file.id == 2 }.status)
        assertEquals(9L, p.items.first { it.file.id == 2 }.replaces?.songId)
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

    @Test fun outsideDestinationSameFormatIsPresent() {
        val p = plan(listOf(audio(1, "a.mp3")), lib = listOf(song(7, null, ext = "mp3")))
        assertEquals(Status.Present, p.items.single().status)
    }

    @Test fun outsideDestinationOtherFormatIsReplace() {
        val s = song(7, null, ext = "mp3")
        val item = plan(listOf(audio(1, "a.flac")), lib = listOf(s)).items.single()
        assertEquals(Status.Replace, item.status)
        assertEquals(s, item.replaces)
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

    @Test fun emptySelectionFitsWithoutTheMargin() {
        // Nothing is written (data-only device sync), so the 200 MB margin does not apply.
        val p = plan(listOf(audio(1, "a.mp3", size = 1000), audio(2, "p.mp3", size = 50, title = "P")), existing = mapOf("p.mp3" to 50L))
        assertTrue(p.fits(emptySet(), 0))
        assertTrue(p.fits(setOf(2), 0), "only Present items selected: nothing to write")
        assertFalse(p.fits(setOf(1), 0))
    }
}
