package de.yurtemre.sonora.devicesync

import de.yurtemre.sonora.shared.devicesync.FileKind
import de.yurtemre.sonora.shared.model.Song
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenderManifestBuilderTest {
    private fun song(path: String, size: Long? = null, title: String = "T") = Song(
        id = 1, title = title, artist = "A", album = "B", durationMs = 1000, filePath = path,
        fileSize = size, lastModifiedMs = if (size != null) 5L else null,
    )

    private fun walked(path: String, size: Long = 10, mod: Long = 1) = WalkedFile(path, size, mod)

    @Test fun commonRootIsLongestCommonParentDirectory() {
        assertEquals(
            "/storage/emulated/0/Music",
            SenderManifestBuilder.commonRoot(listOf("/storage/emulated/0/Music/A/a.mp3", "/storage/emulated/0/Music/B/b.mp3")),
        )
        assertEquals("/m/A", SenderManifestBuilder.commonRoot(listOf("/m/A/a.mp3", "/m/A/b.mp3")))
        assertEquals("/m/A", SenderManifestBuilder.commonRoot(listOf("/m/A/a.mp3")))
        assertEquals("/", SenderManifestBuilder.commonRoot(listOf("/storage/x/a.mp3", "/other/b.mp3")))
        assertNull(SenderManifestBuilder.commonRoot(emptyList()))
    }

    @Test fun includesSongsSidecarsAndCoversWithSortedStableIds() {
        val root = "/m"
        val plan = SenderManifestBuilder.plan(
            root,
            listOf(song("/m/B/b.mp3", title = "bt"), song("/m/A/a.flac")),
            listOf(
                walked("/m/B/b.mp3", 20), walked("/m/A/a.flac", 30),
                walked("/m/A/a.lrc"), walked("/m/B/b.txt"), walked("/m/A/Cover.JPG"),
                walked("/m/A/notes.txt"), walked("/m/A/random.jpg"), walked("/m/A/doc.pdf"),
                walked("/m/Other/c.mp3"),
            ),
        )
        assertEquals(
            listOf("A/Cover.JPG", "A/a.flac", "A/a.lrc", "B/b.mp3", "B/b.txt"),
            plan.files.map { it.relPath },
        )
        assertEquals(listOf(0, 1, 2, 3, 4), plan.files.map { it.id })
        assertEquals(FileKind.Cover, plan.files[0].kind)
        assertEquals(FileKind.Audio, plan.files[1].kind)
        assertEquals(FileKind.Lyrics, plan.files[2].kind)
        assertEquals(30L, plan.files[1].size)
        assertEquals("bt", plan.files[3].audio?.title)
        assertNull(plan.files[2].audio)
        assertEquals("/m/A/a.flac", plan.absPathById[1])
    }

    @Test fun skipsSongsOutsideRootAndUnsafeOrUnknownFiles() {
        val plan = SenderManifestBuilder.plan(
            "/m",
            listOf(song("/m/ok.mp3"), song("/elsewhere/x.mp3"), song("/m/a:b.mp3"), song("/mother/y.mp3")),
            listOf(walked("/m/ok.mp3"), walked("/elsewhere/x.mp3"), walked("/m/a:b.mp3"), walked("/mother/y.mp3")),
        )
        assertEquals(listOf("ok.mp3"), plan.files.map { it.relPath })
    }

    @Test fun songNotWalkedUsesSongSizeOrIsSkipped() {
        val plan = SenderManifestBuilder.plan(
            "/m",
            listOf(song("/m/known.mp3", size = 77), song("/m/unknown.mp3"), song("/m/zero.mp3", size = 0)),
            emptyList(),
        )
        assertEquals(listOf("known.mp3"), plan.files.map { it.relPath })
        assertEquals(77L, plan.files[0].size)
    }

    @Test fun lyricsNeedSiblingIncludedAudioInSameDirectory() {
        val plan = SenderManifestBuilder.plan(
            "/m",
            listOf(song("/m/A/a.mp3")),
            listOf(walked("/m/A/a.mp3"), walked("/m/A/a.lrc"), walked("/m/B/a.lrc"), walked("/m/A/b.lrc")),
        )
        assertEquals(listOf("A/a.lrc", "A/a.mp3"), plan.files.map { it.relPath })
    }

    @Test fun rootWithTrailingSlashAndSlashRootWork() {
        val a = SenderManifestBuilder.plan("/m/", listOf(song("/m/a.mp3")), listOf(walked("/m/a.mp3")))
        assertEquals(listOf("a.mp3"), a.files.map { it.relPath })
        val b = SenderManifestBuilder.plan("/", listOf(song("/m/a.mp3")), listOf(walked("/m/a.mp3")))
        assertEquals(listOf("m/a.mp3"), b.files.map { it.relPath })
    }

    @Test fun emptyInputGivesEmptyPlan() {
        assertTrue(SenderManifestBuilder.plan("/m", emptyList(), emptyList()).files.isEmpty())
    }

    @Test fun coversNeedAnIncludedAudioFileInTheSameDirectory() {
        val plan = SenderManifestBuilder.plan(
            "/m",
            listOf(song("/m/A/a.mp3")),
            listOf(walked("/m/A/a.mp3"), walked("/m/A/cover.jpg"), walked("/m/DCIM/cover.jpg"), walked("/m/cover.png")),
        )
        assertEquals(listOf("A/a.mp3", "A/cover.jpg"), plan.files.map { it.relPath })
    }

    @Test fun lyricsBaseNameWithDotsMatches() {
        val plan = SenderManifestBuilder.plan(
            "/m", listOf(song("/m/a.b.mp3")), listOf(walked("/m/a.b.mp3"), walked("/m/a.b.lrc"), walked("/m/a.lrc")),
        )
        assertEquals(listOf("a.b.lrc", "a.b.mp3"), plan.files.map { it.relPath })
    }
}
