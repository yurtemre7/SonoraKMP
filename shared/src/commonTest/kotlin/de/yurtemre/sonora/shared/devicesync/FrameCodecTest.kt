package de.yurtemre.sonora.shared.devicesync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FrameCodecTest {
    private val bundle = SyncBundle(
        backupJson = "{\"a\":1}",
        stats = SyncStats(
            songs = mapOf("a/b.mp3" to SongCounters(plays = 2, lastPlayedAtMs = 5L)),
            weekday = mapOf(1 to 3),
            playlistPlays = mapOf("p" to 1),
            completeListens = 4,
            firstPlayedPath = "a/b.mp3",
            firstPlayedDate = "2026-01-01",
            recentlyPlayed = mapOf("a/b.mp3" to 9L),
        ),
    )

    private fun roundTrip(frame: Frame): Frame {
        val bytes = FrameCodec.encode(frame)
        val (type, length) = FrameCodec.decodeHeader(bytes.copyOfRange(0, FrameCodec.HEADER_SIZE))
        assertEquals(bytes.size - FrameCodec.HEADER_SIZE, length)
        return FrameCodec.decodeBody(type, bytes.copyOfRange(FrameCodec.HEADER_SIZE, bytes.size))
    }

    @Test fun controlFramesRoundTrip() {
        val frames = listOf<Frame>(
            Frame.Hello(Hello(1, "id", "Phone")),
            Frame.ManifestPart(1, 3, byteArrayOf(1, 2, 3)),
            Frame.FetchFrame(Fetch(listOf(1, 2))),
            Frame.FileStartFrame(FileStart(3, 123456789012L)),
            Frame.FileEndFrame(FileEnd(3, "abcd")),
            Frame.Error(ErrorFrame("code", "msg")),
            Frame.FingerprintRequestFrame(FingerprintRequest(listOf(4, 5))),
            Frame.FingerprintsFrame(Fingerprints(mapOf(4 to "ff", 5 to "00"))),
        )
        for (f in frames) assertEquals(f, roundTrip(f))
    }

    @Test fun doneAndChunkRoundTrip() {
        assertEquals(Frame.Done, roundTrip(Frame.Done))
        val data = byteArrayOf(0, 1, 2, -1, 127)
        val c = assertIs<Frame.Chunk>(roundTrip(Frame.Chunk(data)))
        assertContentEquals(data, c.bytes)
    }

    @Test fun headerIsTypeAndBigEndianLength() {
        val bytes = FrameCodec.encode(Frame.Chunk(ByteArray(0x0102)))
        assertContentEquals(byteArrayOf(5, 0, 0, 1, 2), bytes.copyOfRange(0, 5))
    }

    @Test fun rejectsUnknownType() {
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeHeader(byteArrayOf(0, 0, 0, 0, 0)) }
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeHeader(byteArrayOf(11, 0, 0, 0, 0)) }
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeBody(99, ByteArray(0)) }
    }

    @Test fun rejectsNegativeLength() {
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeHeader(byteArrayOf(1, -1, -1, -1, -1)) }
    }

    @Test fun rejectsOversizedControl() {
        val n = FrameCodec.MAX_CONTROL + 1
        val h = byteArrayOf(1, (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeHeader(h) }
        val ok = FrameCodec.MAX_CONTROL
        val h2 = byteArrayOf(1, (ok ushr 24).toByte(), (ok ushr 16).toByte(), (ok ushr 8).toByte(), ok.toByte())
        assertEquals(1 to ok, FrameCodec.decodeHeader(h2))
    }

    @Test fun manifestPartLimits() {
        val full = Frame.ManifestPart(0, FrameCodec.MAX_MANIFEST_PARTS, ByteArray(FrameCodec.MANIFEST_PART))
        assertEquals(full, roundTrip(full))
        // At most 1 MiB of data per part, 1..64 parts, index within total.
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.encode(Frame.ManifestPart(0, 1, ByteArray(FrameCodec.MANIFEST_PART + 1))) }
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.encode(Frame.ManifestPart(0, 0, ByteArray(1))) }
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.encode(Frame.ManifestPart(0, FrameCodec.MAX_MANIFEST_PARTS + 1, ByteArray(1))) }
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.encode(Frame.ManifestPart(2, 2, ByteArray(1))) }
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.encode(Frame.ManifestPart(-1, 2, ByteArray(1))) }
        val n = 8 + FrameCodec.MANIFEST_PART + 1
        assertFailsWith<DeviceSyncProtocolException> {
            FrameCodec.decodeHeader(byteArrayOf(2, (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        }
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeBody(2, ByteArray(7)) }
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeBody(2, byteArrayOf(0, 0, 0, 3, 0, 0, 0, 2)) }
    }

    @Test fun manifestJsonRoundTrips() {
        val manifest = Manifest(
            1, "id", "Phone", bundle,
            listOf(
                ManifestFile(1, "a/b.mp3", 10L, 20L, FileKind.Audio, AudioMeta("t", "ar", "al", 99L)),
                ManifestFile(2, "a/b.lrc", 1L, 2L, FileKind.Lyrics),
            ),
        )
        assertEquals(manifest, FrameCodec.decodeManifest(FrameCodec.encodeManifest(manifest)))
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeManifest("{".encodeToByteArray()) }
    }

    @Test fun chunkBoundary() {
        val ok = FrameCodec.encode(Frame.Chunk(ByteArray(FrameCodec.CHUNK)))
        assertEquals(5 to FrameCodec.CHUNK, FrameCodec.decodeHeader(ok.copyOfRange(0, 5)))
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.encode(Frame.Chunk(ByteArray(FrameCodec.CHUNK + 1))) }
        val n = FrameCodec.CHUNK + 1
        val h = byteArrayOf(5, 0, (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeHeader(h) }
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeBody(5, ByteArray(n)) }
    }

    @Test fun rejectsMalformedJsonAndShortHeader() {
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeBody(1, "not json".encodeToByteArray()) }
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeBody(3, "{}".encodeToByteArray()) }
        assertFailsWith<DeviceSyncProtocolException> { FrameCodec.decodeHeader(byteArrayOf(1, 0)) }
    }

    @Test fun ignoresUnknownKeys() {
        val f = FrameCodec.decodeBody(3, """{"ids":[1],"extra":true}""".encodeToByteArray())
        assertEquals(Frame.FetchFrame(Fetch(listOf(1))), f)
    }
}
