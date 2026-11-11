package de.yurtemre.sonora.shared.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayDurationTest {
    @Test
    fun picksTheLargestKnownTotal() {
        assertEquals(180_000L, displayDurationMs(null, 180_000L, 0L))       // player unknown → library
        assertEquals(180_000L, displayDurationMs(150_000L, 180_000L, 0L))   // player under-estimates
        assertEquals(180_000L, displayDurationMs(180_000L, 29_887L, 0L))    // scanner under-reports (measured)
        assertEquals(200_000L, displayDurationMs(180_000L, 180_000L, 200_000L)) // never below position
    }
}
