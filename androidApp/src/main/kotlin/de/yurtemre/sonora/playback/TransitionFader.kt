package de.yurtemre.sonora.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import de.yurtemre.sonora.shared.audio.TransitionFade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Fade between songs": while enabled, follows the player position and sets [VolumeComposer.transition] from
 * [TransitionFade] (fade in at the start of a song, fade out at the end when another one follows). Runs on [scope]'s
 * thread, which must be the player's application thread. Off = the factor stays 1.0 and no timer runs.
 */
class TransitionFader(
    private val player: Player,
    private val volumes: VolumeComposer,
    private val scope: CoroutineScope,
) {
    private var fadeMs = 0L
    private var job: Job? = null

    /** 0 turns the fade off. */
    fun setFadeSeconds(seconds: Int) {
        fadeMs = seconds.coerceAtLeast(0) * 1000L
        if (fadeMs == 0L) {
            job?.cancel(); job = null
            volumes.transition = 1.0
        } else if (job?.isActive != true) {
            job = scope.launch {
                while (isActive) {
                    update()
                    delay(TICK_MS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        volumes.transition = 1.0
    }

    private fun update() {
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) { volumes.transition = 1.0; return }
        val followed = player.hasNextMediaItem() || player.repeatMode != Player.REPEAT_MODE_OFF
        volumes.transition = TransitionFade.gain(player.currentPosition, duration, fadeMs, followed)
    }

    private companion object {
        const val TICK_MS = 100L
    }
}
