package de.yurtemre.sonora.shared.audio

import kotlin.math.min

/**
 * "Fade between songs": a volume factor from the position in the song. It ramps up over the first [fadeMs] and,
 * when another song follows, down over the last [fadeMs], so songs dip into each other without a hard cut. A
 * single player cannot overlap two songs, so this is a fade out and a fade in, not a true crossfade.
 */
object TransitionFade {
    /** Fade lengths offered in settings, in seconds; 0 = off. */
    val OPTIONS_SECONDS = listOf(0, 2, 4, 6, 8, 12)

    /**
     * Linear factor 0..1 at [positionMs] of a song of [durationMs]. 1.0 when [fadeMs] is 0 or the length is unknown.
     * A song shorter than two fades gets fades of half its length. [fadeOut] = a following song exists, so the end
     * fades too; without it (last song, repeat off) only the start fades.
     */
    fun gain(positionMs: Long, durationMs: Long, fadeMs: Long, fadeOut: Boolean): Double {
        if (fadeMs <= 0L || durationMs <= 0L) return 1.0
        val fade = min(fadeMs, durationMs / 2).coerceAtLeast(1L).toDouble()
        val position = positionMs.coerceIn(0L, durationMs)
        val fadeIn = (position / fade).coerceIn(0.0, 1.0)
        val fadeOutGain = if (fadeOut) ((durationMs - position) / fade).coerceIn(0.0, 1.0) else 1.0
        return min(fadeIn, fadeOutGain)
    }
}
