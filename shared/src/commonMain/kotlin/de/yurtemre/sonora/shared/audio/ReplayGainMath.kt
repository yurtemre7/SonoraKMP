package de.yurtemre.sonora.shared.audio

import de.yurtemre.sonora.shared.model.ReplayGainInfo
import de.yurtemre.sonora.shared.model.ReplayGainMode
import kotlin.math.pow

/** ReplayGain volume math (PLAY-10, audit/playback §6). */
object ReplayGainMath {
    /** Linear multiplier; album falls back to track and vice versa; known peak caps against clipping. */
    fun multiplier(info: ReplayGainInfo?, mode: ReplayGainMode): Double {
        if (info == null || mode == ReplayGainMode.Off) return 1.0
        val gain = if (mode == ReplayGainMode.Album) info.albumGain ?: info.trackGain
        else info.trackGain ?: info.albumGain
        if (gain == null || !gain.isFinite()) return 1.0
        val peak = if (mode == ReplayGainMode.Album) info.albumPeak ?: info.trackPeak
        else info.trackPeak ?: info.albumPeak
        var m = 10.0.pow(gain / 20.0)
        if (peak != null && peak > 0 && m * peak > 1.0) m = 1.0 / peak
        return m
    }

    /** Player volume = clamp(user × fade × multiplier, 0, 1); the player cannot amplify. */
    fun effectiveVolume(volume: Double, multiplier: Double): Double =
        (volume * multiplier).coerceIn(0.0, 1.0)

    fun effectiveVolume(userVolume: Double, fade: Double, multiplier: Double): Double =
        effectiveVolume(userVolume * fade, multiplier)
}
