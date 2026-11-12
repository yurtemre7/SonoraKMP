package de.yurtemre.sonora.shared.audio

import de.yurtemre.sonora.shared.audio.replaygain.ByteArraySource
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Mp3DurationTest {
    @Test fun exactDurationFromFrames() { vbrWithoutXingHeaderIsMeasuredFrameByFrame(); cbrFramesBehindId3v2AndId3v1(); garbageIsNull() }

    private fun vbrWithoutXingHeaderIsMeasuredFrameByFrame() {
        val bytes = javaClass.getResourceAsStream("/mp3/vbr_noxing.mp3")!!.use { it.readBytes() }
        val ms = Mp3Duration.measureMs(ByteArraySource(bytes))!!
        assertTrue(abs(ms - 180_000) <= 100, "measured $ms ms")
    }

    private fun cbrFramesBehindId3v2AndId3v1() {
        // MPEG1 layer III, 128 kbps, 44.1 kHz, no padding: 417-byte frames of 1152 samples.
        val frame = ByteArray(417).also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = 0x90.toByte(); it[3] = 0x44 }
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 20) + ByteArray(20)
        val tag = "TAG".encodeToByteArray() + ByteArray(125)
        val frames = 383 // 383 * 1152 / 44100 = 10.005 s
        val data = id3 + (0 until frames).fold(ByteArray(0)) { acc, _ -> acc + frame } + tag
        assertEquals(frames * 1152L * 1000 / 44100, Mp3Duration.measureMs(ByteArraySource(data)))
    }

    private fun garbageIsNull() {
        assertNull(Mp3Duration.measureMs(ByteArraySource(Random(7).nextBytes(50_000))))
        assertNull(Mp3Duration.measureMs(ByteArraySource(ByteArray(0))))
    }
}
