package de.yurtemre.sonora.shared.audio.replaygain

import de.yurtemre.sonora.shared.model.ReplayGainInfo
import kotlin.math.abs

/**
 * Reads ReplayGain tags without decoding audio (1:1 port of Flutter `replay_gain_reader.dart`,
 * audit/data §9.1). Supports ID3v2.2–2.4, FLAC, Ogg Vorbis/Opus (R128) and MP4. Never throws.
 */
object ReplayGainReader {
    private const val MAX_FRAMES = 4096
    private const val MAX_ATOMS = 512
    private const val MAX_COMMENTS = 1024
    private const val MAX_TEXT_FRAME_BYTES = 4096
    private const val MAX_COMMENT_BYTES = 1024
    private const val MAX_UNSYNC_TAG_BYTES = 1 shl 20

    /** Opens and reads a source; any failure to open or read the storage yields [ReplayGainReadResult.Failed]. */
    fun tryRead(open: () -> ByteSource): ReplayGainReadResult {
        val source = try {
            open()
        } catch (_: Throwable) {
            return ReplayGainReadResult.Failed
        }
        return tryRead(source)
    }

    fun tryRead(source: ByteSource): ReplayGainReadResult = try {
        ReplayGainReadResult(read(source))
    } catch (_: ByteSourceException) {
        ReplayGainReadResult.Failed
    } catch (_: Throwable) {
        // Malformed tag data: readable, just no usable tags.
        ReplayGainReadResult(null)
    }

    fun readBytes(bytes: ByteArray): ReplayGainInfo? = try {
        read(ByteArraySource(bytes))
    } catch (_: Throwable) {
        null
    }

    private val gainRegex = Regex("""^\s*([+-]?\d+(?:[.,]\d+)?)""")

    /** Parses "-7.43 dB" into dB, or null (|gain| > 60 rejected). */
    fun parseGain(value: String): Double? {
        val m = gainRegex.find(value) ?: return null
        val gain = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        if (!gain.isFinite() || abs(gain) > 60) return null
        return gain
    }

    /** Parses a linear peak in (0, 10], or null. */
    fun parsePeak(value: String): Double? {
        val peak = value.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!peak.isFinite() || peak <= 0 || peak > 10) return null
        return peak
    }

    private fun read(source: ByteSource): ReplayGainInfo? {
        val magic = source.read(0, 12) ?: return null
        if (matches(magic, 0, "ID3")) {
            val tags = Tags()
            val tagEnd = readId3v2(source, tags)
            tags.result?.let { return it }
            if (tagEnd != null) {
                val next = source.read(tagEnd, 4)
                if (next != null && matches(next, 0, "fLaC")) return readFlac(source, tagEnd + 4)
            }
            return null
        }
        if (matches(magic, 0, "fLaC")) return readFlac(source, 4)
        if (matches(magic, 0, "OggS")) return readOgg(source)
        if (matches(magic, 4, "ftyp")) return readMp4(source)
        return null
    }

    // ---- ID3v2 ----

    private fun readId3v2(source: ByteSource, tags: Tags): Long? {
        val header = source.read(0, 10) ?: return null
        val major = u8(header, 3)
        val flags = u8(header, 5)
        val tagSize = syncsafe(header, 6) ?: return null
        if (major < 2 || major > 4) return null
        var tagEnd = 10L + tagSize
        if (major == 4 && flags and 0x10 != 0) tagEnd += 10
        val tagUnsync = flags and 0x80 != 0
        val hasExt = flags and 0x40 != 0

        if (tagUnsync && major < 4) {
            val length = tagSize.coerceIn(0, MAX_UNSYNC_TAG_BYTES)
            val raw = source.read(10, length)
                ?: source.read(10, (source.length - 10).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                ?: return tagEnd
            val decoded = ByteArraySource(removeUnsync(raw))
            readId3Frames(decoded, 0, decoded.length, major, hasExt, false, tags)
            return tagEnd
        }
        readId3Frames(source, 10, 10L + tagSize, major, hasExt, tagUnsync, tags)
        return tagEnd
    }

    private val frameIdRegex = Regex("^[A-Z0-9]+$")

    private fun readId3Frames(
        source: ByteSource, start: Long, end: Long, major: Int,
        hasExt: Boolean, unsyncAll: Boolean, tags: Tags,
    ) {
        var pos = start
        if (hasExt && major >= 3) {
            val ext = source.read(pos, 4) ?: return
            if (major == 4) {
                val size = syncsafe(ext, 0)
                if (size == null || size < 6) return
                pos += size
            } else {
                pos += 4 + uint32(ext, 0)
            }
        }
        val idLength = if (major == 2) 3 else 4
        val headerLength = if (major == 2) 6 else 10
        var frames = 0
        while (pos + headerLength <= end && frames++ < MAX_FRAMES) {
            val header = source.read(pos, headerLength) ?: return
            if (header[0].toInt() == 0) return
            val id = latin1(header, 0, idLength)
            if (!frameIdRegex.matches(id)) return
            val size: Long
            var fmt = 0
            when (major) {
                2 -> size = (u8(header, 3) shl 16 or (u8(header, 4) shl 8) or u8(header, 5)).toLong()
                4 -> { size = syncsafe(header, 4)?.toLong() ?: uint32(header, 4); fmt = u8(header, 9) }
                else -> { size = uint32(header, 4); fmt = u8(header, 9) }
            }
            val dataStart = pos + headerLength
            pos = dataStart + size
            if (size <= 0 || pos > end) return
            if (id != "TXXX" && id != "TXX") continue
            if (size > MAX_TEXT_FRAME_BYTES) continue
            var skip = 0
            var frameUnsync = unsyncAll
            if (major == 3) {
                if (fmt and 0xC0 != 0) continue
                if (fmt and 0x20 != 0) skip += 1
            } else if (major == 4) {
                if (fmt and 0x0C != 0) continue
                if (fmt and 0x40 != 0) skip += 1
                if (fmt and 0x01 != 0) skip += 4
                if (fmt and 0x02 != 0) frameUnsync = true
            }
            if (skip >= size) continue
            var data = source.read(dataStart + skip, (size - skip).toInt()) ?: return
            if (frameUnsync) data = removeUnsync(data)
            parseTxxx(data)?.let { tags.add(it.first, it.second) }
        }
    }

    private fun parseTxxx(data: ByteArray): Pair<String, String>? {
        if (data.isEmpty()) return null
        val encoding = u8(data, 0)
        if (encoding > 3) return null
        val wide = encoding == 1 || encoding == 2
        val descEnd = findTerminator(data, 1, wide)
        val description = decodeText(data.copyOfRange(1, descEnd), encoding)
        val valueStart = descEnd + if (wide) 2 else 1
        if (valueStart > data.size) return null
        val valueBytes = data.copyOfRange(valueStart, data.size)
        val valueEnd = findTerminator(valueBytes, 0, wide)
        return description to decodeText(valueBytes.copyOfRange(0, valueEnd), encoding)
    }

    private fun findTerminator(data: ByteArray, start: Int, wide: Boolean): Int {
        if (!wide) {
            for (i in start until data.size) if (data[i].toInt() == 0) return i
            return data.size
        }
        var i = start
        while (i + 1 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
            i += 2
        }
        return data.size
    }

    private fun decodeText(bytes: ByteArray, encoding: Int): String = when (encoding) {
        0 -> latin1(bytes, 0, bytes.size)
        3 -> bytes.decodeToString()
        else -> {
            var bigEndian = encoding == 2
            var start = 0
            if (encoding == 1 && bytes.size >= 2) {
                if (u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xFE) {
                    start = 2
                } else if (u8(bytes, 0) == 0xFE && u8(bytes, 1) == 0xFF) {
                    bigEndian = true; start = 2
                }
            }
            val sb = StringBuilder()
            var i = start
            while (i + 1 < bytes.size) {
                val unit = if (bigEndian) u8(bytes, i) shl 8 or u8(bytes, i + 1)
                else u8(bytes, i + 1) shl 8 or u8(bytes, i)
                sb.append(unit.toChar())
                i += 2
            }
            sb.toString()
        }
    }

    private fun removeUnsync(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size)
        var i = 0
        while (i < data.size) {
            out.add(data[i])
            if (u8(data, i) == 0xFF && i + 1 < data.size && data[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    // ---- FLAC ----

    private fun readFlac(source: ByteSource, start: Long): ReplayGainInfo? {
        var pos = start
        repeat(MAX_FRAMES) {
            val header = source.read(pos, 4) ?: return null
            val isLast = u8(header, 0) and 0x80 != 0
            val type = u8(header, 0) and 0x7F
            val length = (u8(header, 1) shl 16 or (u8(header, 2) shl 8) or u8(header, 3)).toLong()
            val dataStart = pos + 4
            if (type == 4) {
                val tags = Tags()
                readVorbisComments(RangeStream(source, dataStart, dataStart + length), tags)
                return tags.result
            }
            if (isLast || type == 127) return null
            pos = dataStart + length
        }
        return null
    }

    // ---- Ogg ----

    private fun readOgg(source: ByteSource): ReplayGainInfo? {
        val first = OggPage.read(source, 0) ?: return null
        val head = source.read(first.dataStart, 8) ?: return null
        val isOpus = when {
            matches(head, 0, "OpusHead") -> true
            u8(head, 0) == 1 && matches(head, 1, "vorbis") -> false
            else -> return null
        }
        val stream = OggStream(source, first.end, first.serial)
        val magic = stream.read(if (isOpus) 8 else 7) ?: return null
        val ok = if (isOpus) matches(magic, 0, "OpusTags") else u8(magic, 0) == 3 && matches(magic, 1, "vorbis")
        if (!ok) return null
        val tags = Tags(isOpus)
        readVorbisComments(stream, tags)
        return tags.result
    }

    private fun readVorbisComments(stream: ByteStream, tags: Tags) {
        val vendorLength = stream.read(4) ?: return
        if (!stream.skip(uint32le(vendorLength, 0))) return
        val countBytes = stream.read(4) ?: return
        val count = uint32le(countBytes, 0)
        var i = 0L
        while (i < count && i < MAX_COMMENTS) {
            i++
            val lengthBytes = stream.read(4) ?: return
            val length = uint32le(lengthBytes, 0)
            if (length > MAX_COMMENT_BYTES) {
                if (!stream.skip(length)) return
                continue
            }
            val comment = stream.read(length.toInt()) ?: return
            val text = comment.decodeToString()
            val eq = text.indexOf('=')
            if (eq > 0) tags.add(text.substring(0, eq), text.substring(eq + 1))
        }
    }

    // ---- MP4 ----

    private fun readMp4(source: ByteSource): ReplayGainInfo? {
        val moov = findAtom(source, 0, source.length, "moov") ?: return null
        val udta = findAtom(source, moov.dataStart, moov.end, "udta") ?: return null
        val meta = findAtom(source, udta.dataStart, udta.end, "meta") ?: return null
        var metaChildren = meta.dataStart
        val probe = source.read(meta.dataStart + 4, 4)
        if (probe != null && !matches(probe, 0, "hdlr")) metaChildren += 4
        val ilst = findAtom(source, metaChildren, meta.end, "ilst") ?: return null
        val tags = Tags()
        var pos = ilst.dataStart
        var items = 0
        while (items < MAX_ATOMS && pos < ilst.end) {
            items++
            val item = Atom.read(source, pos, ilst.end) ?: break
            pos = item.end
            if (item.type != "----" || item.end - item.dataStart > MAX_TEXT_FRAME_BYTES) continue
            readFreeformAtom(source, item, tags)
        }
        return tags.result
    }

    private fun readFreeformAtom(source: ByteSource, item: Atom, tags: Tags) {
        var mean: String? = null
        var name: String? = null
        var value: String? = null
        var pos = item.dataStart
        var i = 0
        while (i < 8 && pos < item.end) {
            i++
            val child = Atom.read(source, pos, item.end) ?: return
            pos = child.end
            val skip = if (child.type == "data") 8 else 4
            val length = child.end - child.dataStart - skip
            if (length < 0) continue
            val bytes = source.read(child.dataStart + skip, length.toInt()) ?: return
            val text = bytes.decodeToString()
            when (child.type) {
                "mean" -> mean = text
                "name" -> name = text
                "data" -> if (value == null) value = text
            }
        }
        if (name == null || value == null) return
        if (mean != null && mean != "com.apple.iTunes") return
        tags.add(name, value)
    }

    private fun findAtom(source: ByteSource, start: Long, end: Long, type: String): Atom? {
        var pos = start
        var i = 0
        while (i < MAX_ATOMS && pos < end) {
            i++
            val atom = Atom.read(source, pos, end) ?: return null
            if (atom.type == type) return atom
            pos = atom.end
        }
        return null
    }

    // ---- helpers ----

    internal fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    internal fun matches(bytes: ByteArray, offset: Int, ascii: String): Boolean {
        if (offset + ascii.length > bytes.size) return false
        for (i in ascii.indices) if (u8(bytes, offset + i) != ascii[i].code) return false
        return true
    }

    private fun syncsafe(b: ByteArray, o: Int): Int? {
        if ((u8(b, o) or u8(b, o + 1) or u8(b, o + 2) or u8(b, o + 3)) and 0x80 != 0) return null
        return u8(b, o) shl 21 or (u8(b, o + 1) shl 14) or (u8(b, o + 2) shl 7) or u8(b, o + 3)
    }

    internal fun uint32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()

    internal fun uint32le(b: ByteArray, o: Int): Long =
        u8(b, o).toLong() or (u8(b, o + 1).toLong() shl 8) or (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24)

    internal fun latin1(b: ByteArray, start: Int, end: Int): String {
        val sb = StringBuilder(end - start)
        for (i in start until end) sb.append(u8(b, i).toChar())
        return sb.toString()
    }
}

private class Tags(val isOpus: Boolean = false) {
    var trackGain: Double? = null
    var albumGain: Double? = null
    var trackPeak: Double? = null
    var albumPeak: Double? = null
    var r128TrackGain: Double? = null
    var r128AlbumGain: Double? = null

    fun add(key: String, value: String) {
        when (key.trim().uppercase()) {
            "REPLAYGAIN_TRACK_GAIN" -> trackGain = trackGain ?: ReplayGainReader.parseGain(value)
            "REPLAYGAIN_ALBUM_GAIN" -> albumGain = albumGain ?: ReplayGainReader.parseGain(value)
            "REPLAYGAIN_TRACK_PEAK" -> trackPeak = trackPeak ?: ReplayGainReader.parsePeak(value)
            "REPLAYGAIN_ALBUM_PEAK" -> albumPeak = albumPeak ?: ReplayGainReader.parsePeak(value)
            "R128_TRACK_GAIN" -> if (isOpus) r128TrackGain = r128TrackGain ?: parseR128(value)
            "R128_ALBUM_GAIN" -> if (isOpus) r128AlbumGain = r128AlbumGain ?: parseR128(value)
        }
    }

    /** Q7.8 relative to −23 LUFS → ReplayGain reference (+5 dB). */
    private fun parseR128(value: String): Double? {
        val raw = value.trim().toIntOrNull() ?: return null
        if (raw < -32768 || raw > 32767) return null
        return raw / 256.0 + 5
    }

    val result: ReplayGainInfo?
        get() {
            val info = ReplayGainInfo(
                trackGain = r128TrackGain ?: trackGain,
                albumGain = r128AlbumGain ?: albumGain,
                trackPeak = if (r128TrackGain != null) null else trackPeak,
                albumPeak = if (r128AlbumGain != null) null else albumPeak,
            )
            return if (info.trackGain != null || info.albumGain != null) info else null
        }
}

private interface ByteStream {
    fun read(count: Int): ByteArray?
    fun skip(count: Long): Boolean
}

private class RangeStream(val source: ByteSource, var pos: Long, val end: Long) : ByteStream {
    override fun read(count: Int): ByteArray? {
        if (pos + count > end) return null
        val data = source.read(pos, count)
        pos += count
        return data
    }

    override fun skip(count: Long): Boolean {
        pos += count
        return pos <= end
    }
}

private class OggPage(val serial: Long, val dataStart: Long, val end: Long) {
    companion object {
        fun read(source: ByteSource, offset: Long): OggPage? {
            val header = source.read(offset, 27) ?: return null
            if (!ReplayGainReader.matches(header, 0, "OggS")) return null
            val serial = ReplayGainReader.uint32le(header, 14)
            val segments = ReplayGainReader.u8(header, 26)
            val table = source.read(offset + 27, segments) ?: return null
            var dataLength = 0L
            for (i in table.indices) dataLength += ReplayGainReader.u8(table, i)
            val dataStart = offset + 27 + segments
            return OggPage(serial, dataStart, dataStart + dataLength)
        }
    }
}

private class OggStream(val source: ByteSource, private var nextPage: Long, val serial: Long) : ByteStream {
    private var pos = 0L
    private var end = 0L
    private var pages = 0

    private fun advance(): Boolean {
        while (pages++ < 512) {
            val page = OggPage.read(source, nextPage) ?: return false
            nextPage = page.end
            if (page.serial != serial) continue
            pos = page.dataStart
            end = page.end
            return true
        }
        return false
    }

    override fun read(count: Int): ByteArray? {
        val out = ByteArray(count)
        var written = 0
        while (written < count) {
            if (pos >= end && !advance()) return null
            val take = minOf((count - written).toLong(), end - pos).toInt()
            val data = source.read(pos, take) ?: return null
            data.copyInto(out, written)
            pos += take
            written += take
        }
        return out
    }

    override fun skip(count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            if (pos >= end && !advance()) return false
            val take = minOf(remaining, end - pos)
            pos += take
            remaining -= take
        }
        return true
    }
}

private class Atom(val type: String, val dataStart: Long, val end: Long) {
    companion object {
        fun read(source: ByteSource, offset: Long, limit: Long): Atom? {
            val header = source.read(offset, 8) ?: return null
            var size = ReplayGainReader.uint32(header, 0)
            val type = ReplayGainReader.latin1(header, 4, 8)
            var headerLength = 8
            if (size == 1L) {
                val large = source.read(offset + 8, 8) ?: return null
                size = ReplayGainReader.uint32(large, 0) * 0x100000000L + ReplayGainReader.uint32(large, 4)
                headerLength = 16
            } else if (size == 0L) {
                size = limit - offset
            }
            if (size < headerLength || offset + size > limit) return null
            return Atom(type, offset + headerLength, offset + size)
        }
    }
}
