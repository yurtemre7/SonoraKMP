package de.yurtemre.sonora.shared.devicesync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed interface Frame {
    data class Hello(val value: de.yurtemre.sonora.shared.devicesync.Hello) : Frame
    /**
     * One piece of the deflated manifest JSON (see `ManifestTransfer`): part [index] of [total], each at most
     * [FrameCodec.MANIFEST_PART] bytes, sent in order right after pairing.
     */
    class ManifestPart(val index: Int, val total: Int, val bytes: ByteArray) : Frame {
        override fun equals(other: Any?) =
            other is ManifestPart && index == other.index && total == other.total && bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * (31 * index + total) + bytes.contentHashCode()
    }
    data class FetchFrame(val value: Fetch) : Frame
    data class FileStartFrame(val value: FileStart) : Frame
    class Chunk(val bytes: ByteArray) : Frame {
        override fun equals(other: Any?) = other is Chunk && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
    data class FileEndFrame(val value: FileEnd) : Frame
    data class Error(val value: ErrorFrame) : Frame
    data object Done : Frame
    data class FingerprintRequestFrame(val value: FingerprintRequest) : Frame
    data class FingerprintsFrame(val value: Fingerprints) : Frame
}

/**
 * Frame layout: type (1 byte) + length (4 bytes, big-endian, >= 0) + payload. A MANIFEST_PART payload is
 * index (4 bytes) + total (4 bytes) + at most [MANIFEST_PART] bytes of the deflated manifest JSON.
 */
object FrameCodec {
    const val MAX_CONTROL = 4 * 1024 * 1024
    const val CHUNK = 64 * 1024
    const val HEADER_SIZE = 5
    /** Largest piece of the deflated manifest in one MANIFEST_PART frame. */
    const val MANIFEST_PART = 1024 * 1024
    /** At most this many parts: the deflated manifest is capped at 64 MiB. */
    const val MAX_MANIFEST_PARTS = 64
    private const val PART_HEADER = 8

    private const val T_HELLO = 1
    private const val T_MANIFEST = 2
    private const val T_FETCH = 3
    private const val T_FILE_START = 4
    private const val T_CHUNK = 5
    private const val T_FILE_END = 6
    private const val T_ERROR = 7
    private const val T_DONE = 8
    private const val T_FP_REQUEST = 9
    private const val T_FINGERPRINTS = 10

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(frame: Frame): ByteArray {
        val (type, payload) = when (frame) {
            is Frame.Hello -> T_HELLO to json.encodeToString(Hello.serializer(), frame.value).encodeToByteArray()
            is Frame.ManifestPart -> T_MANIFEST to encodePart(frame)
            is Frame.FetchFrame -> T_FETCH to json.encodeToString(Fetch.serializer(), frame.value).encodeToByteArray()
            is Frame.FileStartFrame -> T_FILE_START to json.encodeToString(FileStart.serializer(), frame.value).encodeToByteArray()
            is Frame.Chunk -> T_CHUNK to frame.bytes
            is Frame.FileEndFrame -> T_FILE_END to json.encodeToString(FileEnd.serializer(), frame.value).encodeToByteArray()
            is Frame.Error -> T_ERROR to json.encodeToString(ErrorFrame.serializer(), frame.value).encodeToByteArray()
            Frame.Done -> T_DONE to ByteArray(0)
            is Frame.FingerprintRequestFrame ->
                T_FP_REQUEST to json.encodeToString(FingerprintRequest.serializer(), frame.value).encodeToByteArray()
            is Frame.FingerprintsFrame ->
                T_FINGERPRINTS to json.encodeToString(Fingerprints.serializer(), frame.value).encodeToByteArray()
        }
        checkLength(type, payload.size)
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = type.toByte()
        writeIntBE(out, 1, payload.size)
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /** Returns (type, length) from the first [HEADER_SIZE] bytes, validating both. */
    fun decodeHeader(bytes: ByteArray): Pair<Int, Int> {
        if (bytes.size < HEADER_SIZE) throw DeviceSyncProtocolException("Short frame header")
        val type = bytes[0].toInt() and 0xFF
        val length = readIntBE(bytes, 1)
        checkType(type)
        checkLength(type, length)
        return type to length
    }

    fun decodeBody(type: Int, payload: ByteArray): Frame {
        checkType(type)
        checkLength(type, payload.size)
        try {
            return when (type) {
                T_HELLO -> Frame.Hello(json.decodeFromString(Hello.serializer(), payload.decodeToString()))
                T_MANIFEST -> decodePart(payload)
                T_FETCH -> Frame.FetchFrame(json.decodeFromString(Fetch.serializer(), payload.decodeToString()))
                T_FILE_START -> Frame.FileStartFrame(json.decodeFromString(FileStart.serializer(), payload.decodeToString()))
                T_CHUNK -> Frame.Chunk(payload)
                T_FILE_END -> Frame.FileEndFrame(json.decodeFromString(FileEnd.serializer(), payload.decodeToString()))
                T_ERROR -> Frame.Error(json.decodeFromString(ErrorFrame.serializer(), payload.decodeToString()))
                T_DONE -> Frame.Done
                T_FP_REQUEST ->
                    Frame.FingerprintRequestFrame(json.decodeFromString(FingerprintRequest.serializer(), payload.decodeToString()))
                else ->
                    Frame.FingerprintsFrame(json.decodeFromString(Fingerprints.serializer(), payload.decodeToString()))
            }
        } catch (e: SerializationException) {
            throw DeviceSyncProtocolException("Malformed frame payload: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw DeviceSyncProtocolException("Malformed frame payload: ${e.message}")
        }
    }

    /** The manifest as UTF-8 JSON (what `ManifestTransfer` deflates and splits). */
    fun encodeManifest(manifest: Manifest): ByteArray =
        json.encodeToString(Manifest.serializer(), manifest).encodeToByteArray()

    fun decodeManifest(bytes: ByteArray): Manifest = try {
        json.decodeFromString(Manifest.serializer(), bytes.decodeToString())
    } catch (e: SerializationException) {
        throw DeviceSyncProtocolException("Malformed manifest: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw DeviceSyncProtocolException("Malformed manifest: ${e.message}")
    }

    private fun encodePart(part: Frame.ManifestPart): ByteArray {
        checkPart(part.index, part.total, part.bytes.size)
        val out = ByteArray(PART_HEADER + part.bytes.size)
        writeIntBE(out, 0, part.index)
        writeIntBE(out, 4, part.total)
        part.bytes.copyInto(out, PART_HEADER)
        return out
    }

    private fun decodePart(payload: ByteArray): Frame.ManifestPart {
        if (payload.size < PART_HEADER) throw DeviceSyncProtocolException("Short manifest part")
        val index = readIntBE(payload, 0)
        val total = readIntBE(payload, 4)
        checkPart(index, total, payload.size - PART_HEADER)
        return Frame.ManifestPart(index, total, payload.copyOfRange(PART_HEADER, payload.size))
    }

    private fun checkPart(index: Int, total: Int, size: Int) {
        if (total !in 1..MAX_MANIFEST_PARTS) throw DeviceSyncProtocolException("Bad manifest part count $total")
        if (index !in 0 until total) throw DeviceSyncProtocolException("Bad manifest part index $index of $total")
        if (size > MANIFEST_PART) throw DeviceSyncProtocolException("Manifest part too large: $size")
    }

    private fun checkType(type: Int) {
        if (type !in T_HELLO..T_FINGERPRINTS) throw DeviceSyncProtocolException("Unknown frame type $type")
    }

    private fun checkLength(type: Int, length: Int) {
        if (length < 0) throw DeviceSyncProtocolException("Negative frame length")
        val max = when (type) {
            T_CHUNK -> CHUNK
            T_MANIFEST -> PART_HEADER + MANIFEST_PART
            else -> MAX_CONTROL
        }
        if (length > max) throw DeviceSyncProtocolException("Frame type $type too large: $length > $max")
    }

    private fun writeIntBE(dst: ByteArray, offset: Int, v: Int) {
        dst[offset] = (v ushr 24).toByte()
        dst[offset + 1] = (v ushr 16).toByte()
        dst[offset + 2] = (v ushr 8).toByte()
        dst[offset + 3] = v.toByte()
    }

    private fun readIntBE(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 24) or
            ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl 8) or
            (src[offset + 3].toInt() and 0xFF)
}
