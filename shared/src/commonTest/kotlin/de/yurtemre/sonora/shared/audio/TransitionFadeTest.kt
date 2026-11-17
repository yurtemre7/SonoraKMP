package de.yurtemre.sonora.shared.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class TransitionFadeTest {
    private fun g(pos: Long, dur: Long = 100_000, fade: Long = 4_000, out: Boolean = true) = TransitionFade.gain(pos, dur, fade, out)

    @Test fun offOrUnknownLengthIsFullVolume() {
        assertEquals(1.0, g(0, fade = 0))
        assertEquals(1.0, g(50, dur = 0))
        assertEquals(1.0, g(50, dur = -1))
    }

    @Test fun rampsUpAtTheStartAndDownAtTheEnd() {
        assertEquals(0.0, g(0)); assertEquals(0.5, g(2_000)); assertEquals(1.0, g(4_000)); assertEquals(1.0, g(50_000))
        assertEquals(0.5, g(98_000)); assertEquals(0.25, g(99_000)); assertEquals(0.0, g(100_000))
    }

    @Test fun lastSongOnlyFadesIn() {
        assertEquals(1.0, g(99_000, out = false)); assertEquals(0.5, g(2_000, out = false))
    }

    @Test fun shortSongsGetHalfLengthFades() {
        // 6 s song, 4 s fade: fades are 3 s, so the middle is full volume only at one point.
        assertEquals(1.0, g(3_000, dur = 6_000)); assertEquals(0.5, g(1_500, dur = 6_000)); assertEquals(0.5, g(4_500, dur = 6_000))
    }

    @Test fun positionOutsideTheSongIsClamped() {
        assertEquals(0.0, g(-5)); assertEquals(0.0, g(200_000))
    }
}
