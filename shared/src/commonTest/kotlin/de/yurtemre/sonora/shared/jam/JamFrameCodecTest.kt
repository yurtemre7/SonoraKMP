package de.yurtemre.sonora.shared.jam

import de.yurtemre.sonora.shared.devicesync.DeviceSyncProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JamFrameCodecTest {
    private val song = JamSong("Título ☃", "Artist", "Album", 200_000)
    private val state = JamState(listOf(song, song.copy(title = "B")), 1, true, 12_345, 99L, true, false)

    private fun roundTrip(f: JamFrame): JamFrame {
        val bytes = JamFrameCodec.encode(f)
        val (type, len) = JamFrameCodec.decodeHeader(bytes.copyOfRange(0, JamFrameCodec.HEADER_SIZE))
        assertEquals(bytes.size - JamFrameCodec.HEADER_SIZE, len)
        return JamFrameCodec.decode(type, bytes.copyOfRange(JamFrameCodec.HEADER_SIZE, bytes.size))
    }

    @Test fun roundTripsEveryFrame() {
        listOf<JamFrame>(
            JamHello(JAM_PROTOCOL_VERSION, "Emre"), JamStateFrame(state), JamAddRequest(song),
            JamControl(JamAction.Previous), Ping(1, 2), Pong(1, 2, 3), JamEnd("bye"),
            JamWelcome("guest-1", "00112233445566778899aabbccddeeff"),
        ).forEach { assertEquals(it, roundTrip(it)) }
        JamAction.entries.forEach { assertEquals(JamControl(it), roundTrip(JamControl(it))) }
    }

    @Test fun welcomeDoesNotPrintTheReconnectKey() {
        val key = "00112233445566778899aabbccddeeff"
        kotlin.test.assertFalse(key in JamWelcome("guest-1", key).toString())
    }

    @Test fun unknownTypeThrows() {
        assertFailsWith<DeviceSyncProtocolException> { JamFrameCodec.decode(0, ByteArray(0)) }
        assertFailsWith<DeviceSyncProtocolException> { JamFrameCodec.decode(99, ByteArray(0)) }
        assertFailsWith<DeviceSyncProtocolException> { JamFrameCodec.decodeHeader(byteArrayOf(99, 0, 0, 0, 1)) }
    }

    @Test fun oversizedThrows() {
        assertFailsWith<DeviceSyncProtocolException> {
            JamFrameCodec.decode(1, ByteArray(JamFrameCodec.MAX_CONTROL + 1))
        }
        assertFailsWith<DeviceSyncProtocolException> {
            JamFrameCodec.decodeHeader(byteArrayOf(1, 0x7F, 0, 0, 0))
        }
        assertFailsWith<DeviceSyncProtocolException> {
            JamFrameCodec.decodeHeader(byteArrayOf(1, -1, -1, -1, -1))
        }
        assertFailsWith<DeviceSyncProtocolException> {
            JamFrameCodec.encode(JamEnd("x".repeat(JamFrameCodec.MAX_CONTROL)))
        }
    }

    @Test fun malformedPayloadsThrowProtocolException() {
        val good = JamFrameCodec.encode(JamStateFrame(state))
        val body = good.copyOfRange(JamFrameCodec.HEADER_SIZE, good.size)
        assertFailsWith<DeviceSyncProtocolException> { JamFrameCodec.decode(2, body.copyOf(body.size / 2)) }
        assertFailsWith<DeviceSyncProtocolException> { JamFrameCodec.decode(2, ByteArray(0)) }
        assertFailsWith<DeviceSyncProtocolException> { JamFrameCodec.decode(2, byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte())) }
        assertFailsWith<DeviceSyncProtocolException> { JamFrameCodec.decode(1, "{\"protocol\":\"x\"}".encodeToByteArray()) }
        assertFailsWith<DeviceSyncProtocolException> { JamFrameCodec.decode(4, "{\"action\":\"Explode\"}".encodeToByteArray()) }
        assertFailsWith<DeviceSyncProtocolException> { JamFrameCodec.decodeHeader(byteArrayOf(1, 0)) }
    }
}
