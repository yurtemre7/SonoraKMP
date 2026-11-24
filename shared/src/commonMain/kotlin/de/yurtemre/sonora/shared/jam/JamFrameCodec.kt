package de.yurtemre.sonora.shared.jam

import de.yurtemre.sonora.shared.devicesync.DeviceSyncProtocolException
import de.yurtemre.sonora.shared.devicesync.FrameCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Jam frame layout: type (1 byte) + length (4 bytes, big-endian, >= 0) + JSON payload; own type numbers. */
object JamFrameCodec {
    const val HEADER_SIZE = FrameCodec.HEADER_SIZE
    const val MAX_CONTROL = FrameCodec.MAX_CONTROL

    private const val T_HELLO = 1
    private const val T_STATE = 2
    private const val T_ADD = 3
    private const val T_CONTROL = 4
    private const val T_PING = 5
    private const val T_PONG = 6
    private const val T_END = 7
    private const val T_WELCOME = 8
    private const val T_ADD_MANY = 9

    @Serializable private class AddP(val song: JamSong)
    @Serializable private class AddManyP(val songs: List<JamSong>)
    @Serializable private class ControlP(val action: JamAction)
    @Serializable private class PingP(val id: Long, val sentMs: Long)
    @Serializable private class PongP(val id: Long, val sentMs: Long, val hostMs: Long)
    @Serializable private class EndP(val reason: String)
    @Serializable private class WelcomeP(val guestId: String, val reconnectKey: String)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(frame: JamFrame): ByteArray {
        val (type, text) = when (frame) {
            is JamHello -> T_HELLO to json.encodeToString(JamHelloPayload.serializer(), JamHelloPayload(frame.protocol, frame.name))
            is JamStateFrame -> T_STATE to json.encodeToString(JamState.serializer(), frame.state)
            is JamAddRequest -> T_ADD to json.encodeToString(AddP.serializer(), AddP(frame.song))
            is JamControl -> T_CONTROL to json.encodeToString(ControlP.serializer(), ControlP(frame.action))
            is Ping -> T_PING to json.encodeToString(PingP.serializer(), PingP(frame.id, frame.sentMs))
            is Pong -> T_PONG to json.encodeToString(PongP.serializer(), PongP(frame.id, frame.sentMs, frame.hostMs))
            is JamEnd -> T_END to json.encodeToString(EndP.serializer(), EndP(frame.reason))
            is JamWelcome ->
                T_WELCOME to json.encodeToString(WelcomeP.serializer(), WelcomeP(frame.guestId, frame.reconnectKey))
            is JamAddManyRequest -> T_ADD_MANY to json.encodeToString(AddManyP.serializer(), AddManyP(frame.songs))
        }
        val payload = text.encodeToByteArray()
        checkLength(payload.size)
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = type.toByte()
        val n = payload.size
        out[1] = (n ushr 24).toByte(); out[2] = (n ushr 16).toByte(); out[3] = (n ushr 8).toByte(); out[4] = n.toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /** Returns (type, length) from the first [HEADER_SIZE] bytes, validating both. */
    fun decodeHeader(bytes: ByteArray): Pair<Int, Int> {
        if (bytes.size < HEADER_SIZE) throw DeviceSyncProtocolException("Short jam frame header")
        val type = bytes[0].toInt() and 0xFF
        val length = ((bytes[1].toInt() and 0xFF) shl 24) or ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
        checkType(type)
        checkLength(length)
        return type to length
    }

    fun decode(type: Int, payload: ByteArray): JamFrame {
        checkType(type)
        checkLength(payload.size)
        // Strict UTF-8 decoding throws a platform-specific exception, so catch broadly here.
        val text = try {
            payload.decodeToString(throwOnInvalidSequence = true)
        } catch (e: Exception) {
            throw DeviceSyncProtocolException("Jam payload is not valid UTF-8")
        }
        try {
            return when (type) {
                T_HELLO -> json.decodeFromString(JamHelloPayload.serializer(), text).let { JamHello(it.protocol, it.name) }
                T_STATE -> JamStateFrame(json.decodeFromString(JamState.serializer(), text))
                T_ADD -> JamAddRequest(json.decodeFromString(AddP.serializer(), text).song)
                T_CONTROL -> JamControl(json.decodeFromString(ControlP.serializer(), text).action)
                T_PING -> json.decodeFromString(PingP.serializer(), text).let { Ping(it.id, it.sentMs) }
                T_PONG -> json.decodeFromString(PongP.serializer(), text).let { Pong(it.id, it.sentMs, it.hostMs) }
                T_END -> JamEnd(json.decodeFromString(EndP.serializer(), text).reason)
                T_ADD_MANY -> json.decodeFromString(AddManyP.serializer(), text).songs.let { songs ->
                    if (songs.size > MAX_ADD_BATCH) throw DeviceSyncProtocolException("Too many songs in one jam add: ${songs.size}")
                    JamAddManyRequest(songs)
                }
                else -> json.decodeFromString(WelcomeP.serializer(), text).let { JamWelcome(it.guestId, it.reconnectKey) }
            }
        } catch (e: SerializationException) {
            throw DeviceSyncProtocolException("Malformed jam payload: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw DeviceSyncProtocolException("Malformed jam payload: ${e.message}")
        }
    }

    private fun checkType(type: Int) {
        if (type !in T_HELLO..T_ADD_MANY) throw DeviceSyncProtocolException("Unknown jam frame type $type")
    }

    private fun checkLength(length: Int) {
        if (length < 0) throw DeviceSyncProtocolException("Negative jam frame length")
        if (length > MAX_CONTROL) throw DeviceSyncProtocolException("Jam frame too large: $length > $MAX_CONTROL")
    }
}
