package de.yurtemre.sonora.devicesync

import de.yurtemre.sonora.shared.devicesync.FileKind
import de.yurtemre.sonora.shared.devicesync.ManifestFile
import de.yurtemre.sonora.shared.devicesync.PlanItem
import de.yurtemre.sonora.shared.devicesync.Status
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FingerprintsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun item(id: Int, size: Long, needs: Boolean = true, status: Status = Status.New) = PlanItem(
        ManifestFile(id, "A/$id.mp3", size, 0, FileKind.Audio), status, needsFingerprint = needs,
    )

    @Test fun batchedSplitsIntoChunksOfAtMostLimit() {
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), batched(listOf(1, 2, 3, 4, 5), 2))
        assertEquals(emptyList(), batched(emptyList<Int>(), 200))
        assertEquals(3, batched((1..401).toList(), 200).size)
        assertFailsWith<IllegalArgumentException> { batched(listOf(1), 0) }
    }

    @Test fun fetchSenderFingerprintsBatchesAndMerges() = runBlocking {
        val calls = ArrayList<List<Int>>()
        val out = fetchSenderFingerprints((1..450).toList()) { ids -> calls += ids; ids.associateWith { "fp$it" } }
        assertEquals(listOf(200, 200, 50), calls.map { it.size })
        assertEquals(450, out.size)
        assertEquals("fp449", out[449])
    }

    @Test fun resolveAmbiguousTurnsEqualFingerprintIntoPresent() {
        val items = listOf(item(1, 10), item(2, 10), item(3, 10, needs = false), item(4, 10), item(5, 10, status = Status.Present, needs = false))
        val out = resolveAmbiguous(
            items,
            senderFps = mapOf(1 to "aa", 2 to "bb", 3 to "aa"),
            receiverFps = mapOf("Other/x.mp3" to "aa"),
        )
        assertEquals(Status.Present, out[0].status)
        assertEquals(false, out[0].needsFingerprint)
        assertEquals(Status.New, out[1].status)
        assertEquals(true, out[1].needsFingerprint)
        assertEquals(items[2], out[2])
        assertEquals(items[3], out[3]) // sender fingerprint missing
        assertEquals(items[4], out[4])
    }

    @Test fun candidatePathsAreSameSizeFilesOnly() {
        val items = listOf(item(1, 10), item(2, 20, needs = false), item(3, 30))
        val existing = mapOf("a.mp3" to 10L, "b.mp3" to 20L, "c.mp3" to 30L, "d.mp3" to 40L)
        assertEquals(setOf("a.mp3", "c.mp3"), fingerprintCandidates(items, existing).toSet())
    }

    @Test fun cacheKeyedByPathSizeMtimeAndPersists() {
        val file = tmp.newFile("fp.json")
        file.delete()
        val cache = FingerprintCache(file)
        var computed = 0
        assertEquals("h1", cache.getOrCompute("/a", 5, 7) { computed++; "h1" })
        assertEquals("h1", cache.getOrCompute("/a", 5, 7) { computed++; "zz" })
        assertEquals("h2", cache.getOrCompute("/a", 5, 8) { computed++; "h2" }) // mtime changed
        assertEquals(2, computed)
        cache.save()
        val again = FingerprintCache(file)
        assertEquals("h1", again.get("/a", 5, 7))
        assertEquals("h2", again.get("/a", 5, 8))
        assertNull(again.get("/a", 6, 7))
    }

    @Test fun cacheCapDropsOldestAndPruneRemovesStale() {
        val cache = FingerprintCache(tmp.newFile("c.json"), maxEntries = 3)
        for (i in 1..5) cache.put("/f$i", 1, 1, "h$i")
        cache.save()
        assertNull(cache.get("/f1", 1, 1))
        assertNull(cache.get("/f2", 1, 1))
        assertEquals("h5", cache.get("/f5", 1, 1))
        cache.prune(setOf("/f4"))
        assertNull(cache.get("/f3", 1, 1))
        assertEquals("h4", cache.get("/f4", 1, 1))
    }

    @Test fun corruptCacheFileIsIgnored() {
        val f = tmp.newFile("bad.json").also { it.writeText("{not json") }
        assertNull(FingerprintCache(f).get("/a", 1, 1))
    }
}
