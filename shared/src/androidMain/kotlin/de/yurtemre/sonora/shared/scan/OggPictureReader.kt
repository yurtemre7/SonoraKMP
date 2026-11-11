package de.yurtemre.sonora.shared.scan

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the cover picture (`METADATA_BLOCK_PICTURE`) from the comment header of an Ogg Opus / Vorbis
 * file. jaudiotagger and some platform MediaMetadataRetriever builds miss it for `.opus`. Never throws.
 */
object OggPictureReader {
    private const val MAX_HEADER = 32 * 1024 * 1024

    fun read(file: File): ByteArray? = try {
        file.inputStream().buffered().use { read(it) }
    } catch (_: Throwable) {
        null
    }

    fun read(input: InputStream): ByteArray? = try {
        commentPacket(DataInputStream(input))?.let(::pictureFromComments)
    } catch (_: Throwable) {
        null
    }

    /** Returns the second logical packet (the comment header) of the first stream. */
    private fun commentPacket(din: DataInputStream): ByteArray? {
        val packet = java.io.ByteArrayOutputStream()
        var packetIndex = 0
        while (true) {
            val header = ByteArray(27)
            try { din.readFully(header) } catch (_: EOFException) { return null }
            if (header[0] != 'O'.code.toByte() || header[1] != 'g'.code.toByte() ||
                header[2] != 'g'.code.toByte() || header[3] != 'S'.code.toByte()
            ) return null
            val segments = header[26].toInt() and 0xFF
            val lacing = ByteArray(segments).also { din.readFully(it) }
            for (l in lacing) {
                val len = l.toInt() and 0xFF
                val buf = ByteArray(len).also { din.readFully(it) }
                if (packetIndex == 1) packet.write(buf)
                if (packet.size() > MAX_HEADER) return null
                if (len < 255) {
                    if (packetIndex == 1) return packet.toByteArray()
                    packetIndex++
                }
            }
        }
    }

    private fun pictureFromComments(p: ByteArray): ByteArray? {
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val skip = when {
            p.size >= 8 && String(p, 0, 8, Charsets.US_ASCII) == "OpusTags" -> 8
            p.size >= 7 && p[0] == 3.toByte() && String(p, 1, 6, Charsets.US_ASCII) == "vorbis" -> 7
            else -> return null
        }
        bb.position(skip)
        val vendorLen = bb.int
        bb.position(bb.position() + vendorLen)
        val count = bb.int
        repeat(count) {
            val len = bb.int
            if (len < 0 || len > bb.remaining()) return null
            val start = bb.position()
            val eq = (start until start + len).firstOrNull { p[it] == '='.code.toByte() }
            if (eq != null && String(p, start, eq - start, Charsets.US_ASCII)
                    .equals("METADATA_BLOCK_PICTURE", ignoreCase = true)
            ) {
                val b64 = String(p, eq + 1, start + len - eq - 1, Charsets.US_ASCII)
                val block = java.util.Base64.getMimeDecoder().decode(b64)
                flacPicture(block)?.let { return it }
            }
            bb.position(start + len)
        }
        return null
    }

    private fun flacPicture(block: ByteArray): ByteArray? {
        val bb = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN)
        bb.int // picture type
        bb.position(bb.position() + bb.int) // mime
        bb.position(bb.position() + bb.int) // description
        bb.position(bb.position() + 16) // width, height, depth, colours
        val len = bb.int
        if (len <= 0 || len > bb.remaining()) return null
        return ByteArray(len).also { bb.get(it) }
    }
}
