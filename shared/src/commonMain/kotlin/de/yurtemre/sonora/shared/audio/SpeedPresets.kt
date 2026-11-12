package de.yurtemre.sonora.shared.audio

import de.yurtemre.sonora.shared.model.MfxPreset
import kotlin.math.abs
import kotlin.math.roundToLong

/** Speed / pitch rules for custom speed and MFX presets (PLAY-12, PLAY-13, audit/playback §5.1–5.2). */
object SpeedPresets {
    const val MIN_SPEED = 0.5
    const val MAX_SPEED = 2.0
    const val MIN_PITCH = 0.5
    const val MAX_PITCH = 2.0
    val sliderStops: List<Double> = listOf(0.5, 1.0, 1.25, 1.5, 2.0)

    /** Exact preset speeds (= pitch), Flutter `MfxPreset` order. */
    val table: Map<MfxPreset, Double> = linkedMapOf(
        MfxPreset.SuperSlowed to 0.70, MfxPreset.Slowed075 to 0.75, MfxPreset.Slowed080 to 0.80,
        MfxPreset.Slowed to 0.85, MfxPreset.Slowed090 to 0.90, MfxPreset.Slowed095 to 0.95,
        MfxPreset.Normal to 1.00, MfxPreset.SpedUp105 to 1.05, MfxPreset.SpedUp110 to 1.10,
        MfxPreset.SpedUp115 to 1.15, MfxPreset.SpedUp120 to 1.20, MfxPreset.SpedUp to 1.25,
        MfxPreset.Nightcore to 1.30, MfxPreset.SuperSpedUp to 1.50,
    )

    fun speedOf(preset: MfxPreset): Double = table.getValue(preset)

    fun clampSpeed(value: Double): Double = value.coerceIn(MIN_SPEED, MAX_SPEED)

    fun clampPitch(value: Double): Double = value.coerceIn(MIN_PITCH, MAX_PITCH)

    /** Normal/null → no preset. */
    fun normalize(preset: MfxPreset?): MfxPreset? = preset?.takeUnless { it == MfxPreset.Normal }

    fun fromSpeed(speed: Double?): MfxPreset? =
        speed?.let { s -> table.entries.firstOrNull { abs(it.value - s) < 0.001 }?.key }

    /** Speed and pitch to apply: preset speed=pitch, else custom speed and custom pitch. */
    fun playbackParameters(preset: MfxPreset?, customSpeed: Double, customPitch: Double = 1.0): Pair<Double, Double> {
        val p = normalize(preset)
        return if (p != null) speedOf(p).let { it to it } else customSpeed to customPitch
    }

    fun effectiveSpeed(preset: MfxPreset?, customSpeed: Double): Double = playbackParameters(preset, customSpeed).first

    /** `round(ms / speed)`; unchanged for speed ≤ 0 or 1.0 or non-positive duration. */
    fun effectiveDurationMs(durationMs: Long, speed: Double): Long {
        if (durationMs <= 0 || speed <= 0 || speed == 1.0) return durationMs
        return (durationMs / speed).roundToLong()
    }
}
