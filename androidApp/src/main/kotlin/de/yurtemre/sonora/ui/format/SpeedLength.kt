package de.yurtemre.sonora.ui.format

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How a speed change alters a song's length: [originalMs] at 1×, [effectiveMs] at the effective speed (the same
 * `round(raw / speed)` the player reports, PLAY-13). [deltaMs] is negative when the song got shorter.
 */
data class SpeedLength(val originalMs: Long, val effectiveMs: Long) {
    val deltaMs: Long get() = effectiveMs - originalMs

    /** Share of the original length that remains, 0..∞ (0.8 = 20 % shorter). */
    val ratio: Float get() = if (originalMs <= 0) 1f else effectiveMs.toFloat() / originalMs

    /** Under half a second is rounded away in the m:ss display, so it is not reported as a change. */
    val isUnchanged: Boolean get() = abs(deltaMs) < 500

    companion object {
        /** Null when the song's length is unknown or the speed is not a usable factor. */
        fun of(originalMs: Long, speed: Float): SpeedLength? {
            if (originalMs <= 0 || !speed.isFinite() || speed <= 0f) return null
            return SpeedLength(originalMs, (originalMs / speed.toDouble()).roundToLong())
        }
    }
}
