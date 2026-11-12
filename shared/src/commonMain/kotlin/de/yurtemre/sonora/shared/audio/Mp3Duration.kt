package de.yurtemre.sonora.shared.audio

import de.yurtemre.sonora.shared.audio.replaygain.ByteSource

/**
 * Exact MP3 duration. MediaStore and ExoPlayer extrapolate VBR files without a Xing/VBRI header from the
 * first frame's bitrate (a 3:00 track can read 0:29); this walks every MPEG audio frame instead, or uses
 * the Xing/Info/VBRI frame count when present. Never throws; null when the data is not MPEG audio.
 */
object Mp3Duration {
    private const val MIN_FRAMES = 4
    private const val RESYNC_LIMIT = 64 * 1024

    fun measureMs(source: ByteSource): Long? = try {
        measure(ChunkedReader(source))
    } catch (e: Exception) {
        null
    }

    private class Header(val version: Int, val layer: Int, val sampleRate: Int, val frameLength: Int, val samples: Int, val mono: Boolean)

    private val sampleRates = intArrayOf(44100, 48000, 32000)
    private val v1l1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val v1l2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val v1l3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val v2l1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val v2l23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /** version: 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5; layer: 1..3. */
    private fun parseHeader(b0: Int, b1: Int, b2: Int, b3: Int): Header? {
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val version = (b1 shr 3) and 3
        val layerBits = (b1 shr 1) and 3
        val brIdx = b2 shr 4
        val srIdx = (b2 shr 2) and 3
        if (version == 1 || layerBits == 0 || brIdx == 0 || brIdx == 15 || srIdx == 3) return null
        val layer = 4 - layerBits
        val pad = (b2 shr 1) and 1
        val sr = sampleRates[srIdx] shr (when (version) { 3 -> 0; 2 -> 1; else -> 2 })
        val kbps = when {
            version == 3 && layer == 1 -> v1l1
            version == 3 && layer == 2 -> v1l2
            version == 3 -> v1l3
            layer == 1 -> v2l1
            else -> v2l23
        }[brIdx]
        val br = kbps * 1000
        val (len, samples) = when {
            layer == 1 -> (12 * br / sr + pad) * 4 to 384
            layer == 2 -> 144 * br / sr + pad to 1152
            version == 3 -> 144 * br / sr + pad to 1152
            else -> 72 * br / sr + pad to 576
        }
        if (len < 21) return null
        return Header(version, layer, sr, len, samples, mono = (b3 shr 6) == 3)
    }

    private fun ChunkedReader.header(pos: Long): Header? {
        if (pos + 4 > end) return null
        return parseHeader(at(pos), at(pos + 1), at(pos + 2), at(pos + 3))
    }

    private fun Header.compatible(o: Header) = version == o.version && layer == o.layer && sampleRate == o.sampleRate

    /** Next header at or after [from] whose following frame is also a compatible header (or the end). */
    private fun ChunkedReader.sync(from: Long, ref: Header?): Long? {
        var p = from
        val limit = minOf(end - 4, from + RESYNC_LIMIT)
        while (p <= limit) {
            if (at(p) == 0xFF) {
                val h = header(p)
                if (h != null && (ref == null || h.compatible(ref))) {
                    val next = p + h.frameLength
                    val n = header(next)
                    if (next == end || (n != null && n.compatible(h))) return p
                }
            }
            p++
        }
        return null
    }

    private fun measure(r: ChunkedReader): Long? {
        var start = 0L
        // ID3v2 tags (possibly several), including the optional footer.
        while (start + 10 <= r.end && r.at(start) == 'I'.code && r.at(start + 1) == 'D'.code && r.at(start + 2) == '3'.code) {
            val size = (r.at(start + 6) shl 21) or (r.at(start + 7) shl 14) or (r.at(start + 8) shl 7) or r.at(start + 9)
            start += 10L + size + if ((r.at(start + 5) and 0x10) != 0) 10 else 0
        }
        trimTrailingTags(r)
        val first = r.sync(start, null) ?: return null
        val h0 = r.header(first)!!
        frameCountFromHeader(r, first, h0)?.let { frames ->
            if (frames > 0) return frames * h0.samples * 1000L / h0.sampleRate
        }
        var pos = first
        var samples = 0L
        var frames = 0
        while (pos + 4 <= r.end) {
            val h = r.header(pos)
            if (h != null && h.compatible(h0) && pos + h.frameLength <= r.end) {
                samples += h.samples; frames++; pos += h.frameLength
            } else {
                pos = r.sync(pos + 1, h0) ?: break
            }
        }
        if (frames < MIN_FRAMES) return null
        return samples * 1000L / h0.sampleRate
    }

    private fun trimTrailingTags(r: ChunkedReader) {
        if (r.end >= 128 && r.at(r.end - 128) == 'T'.code && r.at(r.end - 127) == 'A'.code && r.at(r.end - 126) == 'G'.code) r.end -= 128
        val f = r.end - 32
        if (f >= 0 && "APETAGEX".indices.all { r.at(f + it) == "APETAGEX"[it].code }) {
            val size = r.le32(f + 12)
            val hasHeader = (r.le32(f + 20) and 0x80000000L) != 0L
            val total = size + if (hasHeader) 32 else 0
            if (total in 32..r.end) r.end -= total
        }
    }

    /** Xing/Info or VBRI frame count in the first frame; null when absent. */
    private fun frameCountFromHeader(r: ChunkedReader, pos: Long, h: Header): Long? {
        if (h.layer == 3) {
            val side = if (h.version == 3) (if (h.mono) 17 else 32) else (if (h.mono) 9 else 17)
            val x = pos + 4 + side
            if (r.tag(x, "Xing") || r.tag(x, "Info")) {
                val flags = r.be32(x + 4)
                if ((flags and 1L) != 0L) return r.be32(x + 8)
            }
        }
        val v = pos + 36
        if (r.tag(v, "VBRI")) return r.be32(v + 14)
        return null
    }

    private fun ChunkedReader.tag(p: Long, s: String) = p + s.length <= end && s.indices.all { at(p + it) == s[it].code }
    private fun ChunkedReader.be32(p: Long): Long = (0..3).fold(0L) { acc, i -> (acc shl 8) or at(p + i).toLong() }
    private fun ChunkedReader.le32(p: Long): Long = (0..3).fold(0L) { acc, i -> acc or (at(p + i).toLong() shl (8 * i)) }

    /** Windowed reads over a [ByteSource]; [at] returns 0 outside the data. */
    private class ChunkedReader(private val src: ByteSource) {
        var end: Long = src.length
        private var bufStart = 0L
        private var buf = ByteArray(0)

        fun at(p: Long): Int {
            if (p < 0 || p >= src.length) return 0
            if (p < bufStart || p >= bufStart + buf.size) {
                bufStart = p
                val n = minOf(CHUNK.toLong(), src.length - p).toInt()
                buf = src.read(p, n) ?: ByteArray(0)
                if (buf.isEmpty()) return 0
            }
            return buf[(p - bufStart).toInt()].toInt() and 0xFF
        }

        companion object { const val CHUNK = 256 * 1024 }
    }
}
