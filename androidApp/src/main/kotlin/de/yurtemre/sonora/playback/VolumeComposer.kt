package de.yurtemre.sonora.playback

import androidx.media3.common.Player
import de.yurtemre.sonora.shared.audio.ReplayGainMath

/** Single writer of the player volume: user × fade × ReplayGain, clamped to [0, 1] (PLAY-10). */
class VolumeComposer(private val player: Player) {
    var userVolume: Double = 1.0
        set(value) { field = value.coerceIn(0.0, 1.0); apply() }
    var fade: Double = 1.0
        set(value) { field = value.coerceIn(0.0, 1.0); apply() }
    var replayGain: Double = 1.0
        set(value) { field = value; apply() }

    val effective: Double get() = ReplayGainMath.effectiveVolume(userVolume, fade, replayGain)

    fun apply() { player.volume = effective.toFloat() }
}
