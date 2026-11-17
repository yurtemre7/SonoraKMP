package de.yurtemre.sonora.playback

import androidx.media3.common.Player
import de.yurtemre.sonora.shared.audio.ReplayGainMath

/**
 * Single writer of the player volume: user × fade × song-change fade × ReplayGain, clamped to [0, 1] (PLAY-10).
 * [fade] is the play/pause ramp, [transition] the "fade between songs" factor.
 */
class VolumeComposer(private val player: Player) {
    var userVolume: Double = 1.0
        set(value) { field = value.coerceIn(0.0, 1.0); apply() }
    var fade: Double = 1.0
        set(value) { field = value.coerceIn(0.0, 1.0); apply() }
    var transition: Double = 1.0
        set(value) { field = value.coerceIn(0.0, 1.0); apply() }
    var replayGain: Double = 1.0
        set(value) { field = value; apply() }

    val effective: Double get() = ReplayGainMath.effectiveVolume(userVolume, fade * transition, replayGain)

    fun apply() { player.volume = effective.toFloat() }
}
