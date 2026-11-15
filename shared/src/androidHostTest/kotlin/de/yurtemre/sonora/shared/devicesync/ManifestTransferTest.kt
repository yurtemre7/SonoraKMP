package de.yurtemre.sonora.shared.devicesync

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestTransferTest {
    private val manifest = Manifest(
        1, "id", "Phone", SyncBundle("{}", SyncStats(completeListens = 3)),
        (1..300).map { ManifestFile(it, "Artist/Album/${Random(it).nextLong()}.mp3", it.toLong(), 0L, FileKind.Audio) },
    )

    private fun assemble(parts: List<Frame.ManifestPart>, assembler: ManifestTransfer.Assembler = ManifestTransfer.Assembler()): Manifest? {
        var result: Manifest? = null
        parts.forEach { result = assembler.add(it) }
        return result
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(bytes)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    @Test
    fun splitsIntoOrderedPartsAndReassembles() {
        val parts = ManifestTransfer.split(manifest, partSize = 1_000)
        assertTrue(parts.size > 3)
        parts.forEachIndexed { i, p ->
            assertEquals(i, p.index)
            assertEquals(parts.size, p.total)
            assertTrue(p.bytes.size <= 1_000)
        }
        val assembler = ManifestTransfer.Assembler()
        parts.dropLast(1).forEach { assertNull(assembler.add(it)) }
        assertEquals(manifest, assembler.add(parts.last()))
    }

    @Test
    fun outOfOrderDuplicateOrChangedTotalIsRejected() {
        val parts = ManifestTransfer.split(manifest, partSize = 1_000)
        assertFailsWith<DeviceSyncProtocolException> { assemble(listOf(parts[1])) }
        assertFailsWith<DeviceSyncProtocolException> { assemble(listOf(parts[0], parts[0])) }
        assertFailsWith<DeviceSyncProtocolException> {
            assemble(listOf(parts[0], Frame.ManifestPart(1, parts.size + 1, parts[1].bytes)))
        }
    }

    @Test
    fun malformedDataIsRejected() {
        // Not zlib.
        assertFailsWith<DeviceSyncProtocolException> { assemble(listOf(Frame.ManifestPart(0, 1, byteArrayOf(1, 2, 3, 4)))) }
        // Truncated zlib stream.
        val whole = ManifestTransfer.split(manifest).single().bytes
        assertFailsWith<DeviceSyncProtocolException> { assemble(listOf(Frame.ManifestPart(0, 1, whole.copyOf(whole.size / 2)))) }
        // Trailing bytes after the stream.
        assertFailsWith<DeviceSyncProtocolException> { assemble(listOf(Frame.ManifestPart(0, 1, whole + 0))) }
        // Valid zlib, not a manifest.
        assertFailsWith<DeviceSyncProtocolException> { assemble(listOf(Frame.ManifestPart(0, 1, deflate("[1]".encodeToByteArray())))) }
    }

    @Test
    fun compressedSizeIsCapped() {
        assertEquals(64 * 1024 * 1024, ManifestTransfer.MAX_COMPRESSED)
        val parts = ManifestTransfer.split(manifest, partSize = 1_000)
        val capped = ManifestTransfer.Assembler(maxCompressed = 2_500)
        assertFailsWith<DeviceSyncProtocolException> { assemble(parts, capped) }
    }

    @Test
    fun inflationStopsAtTheCap() {
        assertEquals(256 * 1024 * 1024, ManifestTransfer.MAX_INFLATED)
        // A zip bomb: 64 MiB of zeros deflate to about 64 KiB.
        val bomb = deflate(ByteArray(64 * 1024 * 1024))
        assertTrue(bomb.size < FrameCodec.MANIFEST_PART)
        val capped = ManifestTransfer.Assembler(maxInflated = 1024 * 1024)
        val e = assertFailsWith<DeviceSyncProtocolException> { capped.add(Frame.ManifestPart(0, 1, bomb)) }
        assertEquals("Manifest too large", e.message)
    }

    @Test
    fun senderRefusesAManifestOverTheCaps() {
        assertFailsWith<ManifestTooLargeException> { ManifestTransfer.split(manifest, maxCompressed = 100) }
        assertFailsWith<ManifestTooLargeException> { ManifestTransfer.split(manifest, maxInflated = 1_000) }
        // More than 64 parts.
        assertFailsWith<ManifestTooLargeException> { ManifestTransfer.split(manifest, partSize = 10) }
    }
}
