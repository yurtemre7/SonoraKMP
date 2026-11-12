package de.yurtemre.sonora.shared.audio

import de.yurtemre.sonora.shared.audio.replaygain.ByteArraySource
import de.yurtemre.sonora.shared.audio.replaygain.ByteSource
import de.yurtemre.sonora.shared.audio.replaygain.ByteSourceException
import de.yurtemre.sonora.shared.audio.replaygain.ReplayGainReader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayGainReaderTest {
    private fun ascii(s: String) = s.encodeToByteArray()
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun syncsafe(v: Int) = byteArrayOf((v shr 21 and 0x7F).toByte(), (v shr 14 and 0x7F).toByte(), (v shr 7 and 0x7F).toByte(), (v and 0x7F).toByte())
    private fun cat(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }

    private fun txxx(major: Int, desc: String, value: String): ByteArray {
        val payload = byteArrayOf(3) + ascii(desc) + byteArrayOf(0) + ascii(value)
        val size = if (major == 4) syncsafe(payload.size) else be32(payload.size)
        return ascii("TXXX") + size + byteArrayOf(0, 0) + payload
    }

    private fun id3(major: Int, vararg frames: ByteArray): ByteArray {
        val body = cat(*frames) + ByteArray(16) // padding
        return ascii("ID3") + byteArrayOf(major.toByte(), 0, 0) + syncsafe(body.size) + body
    }

    private fun vorbisComments(vararg kv: String): ByteArray {
        var out = le32(3) + ascii("abc") + le32(kv.size)
        for (c in kv) out += le32(c.length) + ascii(c)
        return out
    }

    private fun flac(vararg kv: String): ByteArray {
        val streamInfo = byteArrayOf(0, 0, 0, 34) + ByteArray(34)
        val vc = vorbisComments(*kv)
        val vcBlock = byteArrayOf((0x80 or 4).toByte(), (vc.size shr 16).toByte(), (vc.size shr 8).toByte(), vc.size.toByte()) + vc
        return ascii("fLaC") + streamInfo + vcBlock
    }

    private fun oggPage(serial: Int, data: ByteArray): ByteArray {
        val segs = mutableListOf<Byte>()
        var rem = data.size
        while (rem >= 255) { segs += 255.toByte(); rem -= 255 }
        segs += rem.toByte()
        return ascii("OggS") + byteArrayOf(0, 0) + ByteArray(8) + le32(serial) + ByteArray(8) +
            byteArrayOf(segs.size.toByte()) + segs.toByteArray() + data
    }

    private fun opus(vararg kv: String) =
        oggPage(7, ascii("OpusHead") + ByteArray(11)) + oggPage(7, ascii("OpusTags") + vorbisComments(*kv))

    private fun vorbis(vararg kv: String) =
        oggPage(1, byteArrayOf(1) + ascii("vorbis") + ByteArray(23)) +
            oggPage(1, byteArrayOf(3) + ascii("vorbis") + vorbisComments(*kv))

    private fun atom(type: String, vararg children: ByteArray): ByteArray {
        val body = cat(*children)
        return be32(8 + body.size) + ascii(type) + body
    }

    private fun freeform(name: String, value: String) = atom(
        "----",
        atom("mean", ByteArray(4), ascii("com.apple.iTunes")),
        atom("name", ByteArray(4), ascii(name)),
        atom("data", ByteArray(8), ascii(value)),
    )

    private fun mp4(vararg items: ByteArray) =
        atom("ftyp", ascii("M4A "), ByteArray(4)) +
            atom("moov", atom("udta", atom("meta", ByteArray(4), atom("hdlr", ByteArray(25)), atom("ilst", *items))))

    @Test fun readsAllContainers() { id3v23(); id3v24WithAlbum(); id3v22(); flacVorbisComment(); flacBehindId3(); oggVorbis(); opusR128(); r128IgnoredOutsideOpus(); mp4Freeform() }

    private fun id3v23() {
        val info = ReplayGainReader.readBytes(id3(3, txxx(3, "REPLAYGAIN_TRACK_GAIN", "-7.43 dB"), txxx(3, "replaygain_track_peak", "0.988525")))!!
        assertEquals(-7.43, info.trackGain)
        assertEquals(0.988525, info.trackPeak)
        assertNull(info.albumGain)
    }

    private fun id3v24WithAlbum() {
        val info = ReplayGainReader.readBytes(id3(4, txxx(4, "REPLAYGAIN_ALBUM_GAIN", "+1,5 dB"), txxx(4, "REPLAYGAIN_TRACK_GAIN", "-2 dB")))!!
        assertEquals(1.5, info.albumGain)
        assertEquals(-2.0, info.trackGain)
    }

    private fun id3v22() {
        val payload = byteArrayOf(0) + ascii("REPLAYGAIN_TRACK_GAIN") + byteArrayOf(0) + ascii("-3.00 dB")
        val frame = ascii("TXX") + byteArrayOf(0, 0, payload.size.toByte()) + payload
        assertEquals(-3.0, ReplayGainReader.readBytes(id3(2, frame))!!.trackGain)
    }

    private fun flacVorbisComment() {
        val info = ReplayGainReader.readBytes(flac("REPLAYGAIN_TRACK_GAIN=-6.00 dB", "REPLAYGAIN_ALBUM_PEAK=1.2"))!!
        assertEquals(-6.0, info.trackGain)
        assertEquals(1.2, info.albumPeak)
    }

    private fun flacBehindId3() {
        val bytes = id3(3, txxx(3, "TITLE", "x")) + flac("REPLAYGAIN_ALBUM_GAIN=-4.5 dB")
        assertEquals(-4.5, ReplayGainReader.readBytes(bytes)!!.albumGain)
    }

    private fun oggVorbis() {
        assertEquals(-8.25, ReplayGainReader.readBytes(vorbis("replaygain_track_gain=-8.25 dB"))!!.trackGain)
    }

    private fun opusR128() {
        val info = ReplayGainReader.readBytes(opus("R128_TRACK_GAIN=-512", "REPLAYGAIN_TRACK_PEAK=0.9", "REPLAYGAIN_ALBUM_GAIN=-1 dB"))!!
        assertEquals(3.0, info.trackGain) // -512/256 + 5
        assertNull(info.trackPeak)
        assertEquals(-1.0, info.albumGain)
    }

    private fun r128IgnoredOutsideOpus() {
        assertNull(ReplayGainReader.readBytes(vorbis("R128_TRACK_GAIN=-512")))
    }

    private fun mp4Freeform() {
        val info = ReplayGainReader.readBytes(mp4(freeform("replaygain_track_gain", "-5.10 dB"), freeform("replaygain_track_peak", "0.5")))!!
        assertEquals(-5.1, info.trackGain)
        assertEquals(0.5, info.trackPeak)
    }

    @Test fun limitsAndMalformedInput() { parseLimits(); malformedNeverThrows(); failedFlag() }

    private fun parseLimits() {
        assertNull(ReplayGainReader.parseGain("61 dB"))
        assertNull(ReplayGainReader.parseGain("abc"))
        assertNull(ReplayGainReader.parsePeak("0"))
        assertNull(ReplayGainReader.parsePeak("11"))
    }

    private fun malformedNeverThrows() {
        assertNull(ReplayGainReader.readBytes(ByteArray(0)))
        assertNull(ReplayGainReader.readBytes(ascii("ID3")))
        val good = listOf(id3(3, txxx(3, "REPLAYGAIN_TRACK_GAIN", "-1 dB")), flac("REPLAYGAIN_TRACK_GAIN=-1"), opus("R128_TRACK_GAIN=1"), mp4(freeform("replaygain_track_gain", "-1")))
        for (g in good) for (cut in g.indices) ReplayGainReader.readBytes(g.copyOf(cut))
        val rnd = Random(42)
        repeat(300) {
            val g = good[it % good.size].copyOf()
            repeat(4) { g[rnd.nextInt(g.size)] = rnd.nextInt(256).toByte() }
            ReplayGainReader.tryRead(ByteArraySource(g))
        }
        assertNull(ReplayGainReader.readBytes(mp4()))
    }

    private fun failedFlag() {
        val broken = object : ByteSource {
            override val length = 100L
            override fun read(offset: Long, count: Int): ByteArray? = throw ByteSourceException("io")
        }
        assertTrue(ReplayGainReader.tryRead(broken).failed)
        assertTrue(ReplayGainReader.tryRead { throw IllegalStateException() }.failed)
        val untagged = ReplayGainReader.tryRead(ByteArraySource(ByteArray(64)))
        assertFalse(untagged.failed)
        assertNull(untagged.info)
    }
}
