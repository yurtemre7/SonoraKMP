package de.yurtemre.sonora.shared.devicesync

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** The sender's manifest does not fit the wire caps ([ManifestTransfer.MAX_COMPRESSED] / [ManifestTransfer.MAX_INFLATED]). */
class ManifestTooLargeException : Exception(DeviceSyncServer.ERROR_LIBRARY_TOO_LARGE)

/**
 * How the manifest travels after pairing (C1 of the final review): its JSON ([FrameCodec.encodeManifest]) is
 * deflated (zlib) and split into [Frame.ManifestPart] frames of at most [FrameCodec.MANIFEST_PART] bytes, sent in
 * order. The receiver reassembles them with [Assembler], which caps the compressed size at [MAX_COMPRESSED] and
 * inflates in a bounded loop that stops at [MAX_INFLATED] (a zip bomb cannot exhaust memory).
 */
object ManifestTransfer {
    /** = [FrameCodec.MANIFEST_PART] × [FrameCodec.MAX_MANIFEST_PARTS]. */
    const val MAX_COMPRESSED = FrameCodec.MANIFEST_PART * FrameCodec.MAX_MANIFEST_PARTS
    const val MAX_INFLATED = 256 * 1024 * 1024

    private const val BUFFER = 64 * 1024

    /** The frames for [manifest]; [ManifestTooLargeException] when it exceeds the caps. */
    fun split(
        manifest: Manifest,
        partSize: Int = FrameCodec.MANIFEST_PART,
        maxCompressed: Int = MAX_COMPRESSED,
        maxInflated: Int = MAX_INFLATED,
    ): List<Frame.ManifestPart> {
        require(partSize in 1..FrameCodec.MANIFEST_PART)
        val json = FrameCodec.encodeManifest(manifest)
        if (json.size > maxInflated) throw ManifestTooLargeException()
        val compressed = deflate(json, maxCompressed)
        val total = (compressed.size + partSize - 1) / partSize
        if (total > FrameCodec.MAX_MANIFEST_PARTS) throw ManifestTooLargeException()
        return List(maxOf(total, 1)) { i ->
            Frame.ManifestPart(i, maxOf(total, 1), compressed.copyOfRange(i * partSize, minOf(compressed.size, (i + 1) * partSize)))
        }
    }

    private fun deflate(bytes: ByteArray, maxCompressed: Int): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                out.write(buffer, 0, n)
                if (out.size() > maxCompressed) throw ManifestTooLargeException()
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /**
     * Collects the parts of one manifest: [add] returns null until the last part, then the manifest. Any part out
     * of order, a changed total, more than [maxCompressed] bytes, a stream that is not valid zlib, does not end
     * with the last part or inflates past [maxInflated], or JSON that is not a manifest →
     * [DeviceSyncProtocolException].
     */
    class Assembler(
        private val maxCompressed: Int = MAX_COMPRESSED,
        private val maxInflated: Int = MAX_INFLATED,
    ) {
        private val compressed = ByteArrayOutputStream()
        private var next = 0
        private var total = -1

        fun add(part: Frame.ManifestPart): Manifest? {
            if (total == -1) total = part.total
            if (part.total != total) throw DeviceSyncProtocolException("Manifest part count changed")
            if (part.index != next) throw DeviceSyncProtocolException("Manifest part ${part.index} out of order")
            if (compressed.size().toLong() + part.bytes.size > maxCompressed) {
                throw DeviceSyncProtocolException("Manifest too large")
            }
            compressed.write(part.bytes)
            next++
            return if (next == total) FrameCodec.decodeManifest(inflate(compressed.toByteArray())) else null
        }

        private fun inflate(bytes: ByteArray): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(bytes)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER)
                while (!inflater.finished()) {
                    val n = try {
                        inflater.inflate(buffer)
                    } catch (e: DataFormatException) {
                        throw DeviceSyncProtocolException("Manifest is not valid zlib data")
                    }
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                        throw DeviceSyncProtocolException("Manifest data is truncated")
                    }
                    if (out.size().toLong() + n > maxInflated) throw DeviceSyncProtocolException("Manifest too large")
                    out.write(buffer, 0, n)
                }
                if (inflater.remaining > 0) throw DeviceSyncProtocolException("Trailing manifest data")
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
    }
}
