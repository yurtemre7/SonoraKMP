package de.yurtemre.sonora.shared.audio

/** Transport fade curve (PLAY-09, audit/playback §4.1): 400 ms, 20 linear steps of 20 ms. */
object FadeCurve {
    const val DURATION_MS = 400L
    const val STEPS = 20
    const val STEP_MS = DURATION_MS / STEPS
    /** Volumes ≤ this are treated as "faded" and restored to 1.0. */
    const val FADED_THRESHOLD = 0.05

    /** `next = current + (target − current) / (STEPS − step + 1)` for step in 1..STEPS. */
    fun next(current: Double, target: Double, step: Int): Double =
        current + (target - current) / (STEPS - step + 1)

    /** Volume to restore after a fade: the volume unless ≤ 0.05, then 1.0. */
    fun restoreVolume(volume: Double): Double = if (volume > FADED_THRESHOLD) volume else 1.0

    /** Fade-in target: restore if > 0.05, else volume if > 0.05, else 1.0. */
    fun fadeInTarget(restore: Double?, volume: Double): Double =
        if (restore != null && restore > FADED_THRESHOLD) restore else restoreVolume(volume)

    /** All 20 values of an uninterrupted ramp from [from] to [target]. */
    fun ramp(from: Double, target: Double): List<Double> {
        var v = from
        return (1..STEPS).map { step -> next(v, target, step).also { v = it } }
    }
}
