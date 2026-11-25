package de.yurtemre.sonora.ui.format

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedLengthTest {
    @Test
    fun slowedSongGetsLonger() {
        val l = SpeedLength.of(200_000, 0.8f)!!
        assertEquals(250_000, l.effectiveMs)
        assertEquals(50_000, l.deltaMs)
        assertTrue(l.ratio > 1f)
    }

    @Test
    fun spedUpSongGetsShorter() {
        val l = SpeedLength.of(180_000, 1.5f)!!
        assertEquals(120_000, l.effectiveMs)
        assertEquals(-60_000, l.deltaMs)
    }

    @Test
    fun normalSpeedIsUnchanged() = assertTrue(SpeedLength.of(180_000, 1f)!!.isUnchanged)

    @Test
    fun subSecondDifferenceCountsAsUnchanged() = assertTrue(SpeedLength.of(60_000, 1.005f)!!.isUnchanged)

    @Test
    fun clearDifferenceIsAChange() = assertFalse(SpeedLength.of(180_000, 1.05f)!!.isUnchanged)

    @Test
    fun unknownLengthOrBadSpeedGivesNull() {
        assertNull(SpeedLength.of(0, 1.2f))
        assertNull(SpeedLength.of(-5, 1.2f))
        assertNull(SpeedLength.of(100_000, 0f))
        assertNull(SpeedLength.of(100_000, Float.NaN))
    }
}
