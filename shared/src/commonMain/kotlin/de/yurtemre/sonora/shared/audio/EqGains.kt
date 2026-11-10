package de.yurtemre.sonora.shared.audio

import de.yurtemre.sonora.shared.model.EqMode
import de.yurtemre.sonora.shared.model.EqPreset
import kotlin.math.floor

/** Equalizer band gain math (PLAY-14, PLAY-15, audit/playback §5.3). All gains in dB. */
object EqGains {
    /** Precedence lofi > warmth(reverb) > bass boost > custom > off. */
    fun mode(lofi: Boolean, warmth: Boolean, bassBoost: Boolean, customEq: Boolean): EqMode = when {
        lofi -> EqMode.LoFi
        warmth -> EqMode.Warmth
        bassBoost -> EqMode.BassBoost
        customEq -> EqMode.Custom
        else -> EqMode.Off
    }

    /**
     * Gain for one band at [centerHz] in a fixed mode, relative to device range [min]..[max].
     * Returns null for [EqMode.Off] / [EqMode.Custom] (handled by [bandGains]).
     */
    fun modeGain(mode: EqMode, centerHz: Double, min: Double, max: Double, bluetoothPatch: Boolean): Double? {
        val f = centerHz
        return when (mode) {
            EqMode.LoFi -> if (!bluetoothPatch) when {
                f > 2000 -> min
                f < 150 -> min * 0.3
                else -> 0.0
            } else when {
                f > 3000 -> min * 0.55
                f < 150 -> min * 0.20
                f in 500.0..2000.0 -> max * 0.15
                else -> 0.0
            }
            EqMode.Warmth -> if (!bluetoothPatch) when {
                f < 250 -> max * 0.5
                f > 4000 -> min * 0.5
                else -> 0.0
            } else when {
                f < 250 -> max * 0.30
                f > 4000 -> min * 0.25
                else -> 0.0
            }
            EqMode.BassBoost -> if (f < 250) max * (if (bluetoothPatch) 0.40 else 0.8) else 0.0
            EqMode.Off, EqMode.Custom -> null
        }
    }

    /**
     * Gains for all bands. Custom: `customGains[i].clamp(min,max)` for i < size, null (= leave as is) beyond.
     * Off: all null (EQ disabled, bands untouched).
     */
    fun bandGains(
        mode: EqMode,
        centerHz: List<Double>,
        min: Double,
        max: Double,
        bluetoothPatch: Boolean,
        customGains: List<Double>?,
    ): List<Double?> = centerHz.mapIndexed { i, f ->
        when (mode) {
            EqMode.Off -> null
            EqMode.Custom -> customGains?.getOrNull(i)?.coerceIn(min, max)
            else -> modeGain(mode, f, min, max, bluetoothPatch)
        }
    }

    /** dB → Equalizer millibels. */
    fun toMillibels(db: Double): Short = kotlin.math.round(db * 100).toInt().toShort()

    /** Preset fractions mapped onto [bands] bands: `floor(i/N*5).clamp(0,4)`, gain `f≥0 ? f*max : −f*min`. */
    fun presetGains(preset: EqPreset, bands: Int, min: Double, max: Double): List<Double> =
        List(bands) { i ->
            val mapped = floor(i.toDouble() / bands * 5).toInt().coerceIn(0, 4)
            val f = preset.fractions[mapped]
            if (f >= 0) f * max else -f * min
        }
}
