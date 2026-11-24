package de.yurtemre.sonora.ui.format

import org.junit.Test
import kotlin.test.assertEquals

class CountdownFormatTest {
    @Test fun countdownBreaksDownDaysHoursMinutes() {
        val ms = (3L * 86_400 + 4 * 3_600 + 12 * 60 + 59) * 1000
        assertEquals(CountdownParts(days = 3, hours = 4, minutes = 12), formatCountdown(ms))
    }

    @Test fun countdownClampsNegativeToZero() {
        assertEquals(CountdownParts(0, 0, 0), formatCountdown(-5_000))
    }
}
