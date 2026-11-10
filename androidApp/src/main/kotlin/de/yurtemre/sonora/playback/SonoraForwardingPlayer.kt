package de.yurtemre.sonora.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import de.yurtemre.sonora.shared.audio.FadeCurve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Logical next/previous (QueueNavigation rules); implemented by [QueueController]. */
interface QueueNavigator {
    fun skipNext()
    fun skipPrevious(positionMs: Long)
}

/**
 * Wraps ExoPlayer so every controller (UI, notification, Bluetooth, widget) gets faded play/pause
 * (PLAY-09), composed volume (PLAY-10) and Sonora's transport rules (PLAY-04).
 * Must be used on the player's application thread; [scope] should run on that thread.
 */
@OptIn(UnstableApi::class)
class SonoraForwardingPlayer(
    player: Player,
    private val scope: CoroutineScope,
    val volumes: VolumeComposer = VolumeComposer(player),
) : ForwardingPlayer(player) {

    var navigator: QueueNavigator? = null
    private var fadeJob: Job? = null
    private var wantsPlayback = false

    val isFading: Boolean get() = fadeJob?.isActive == true

    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun play() {
        wantsPlayback = true
        if (playbackState == Player.STATE_ENDED) super.seekTo(0)
        if (super.getPlayWhenReady() && !isFading) return
        if (!super.getPlayWhenReady()) {
            fadeJob?.cancel()
            volumes.fade = 0.0
            super.play()
        }
        rampTo(1.0) {}
    }

    override fun pause() {
        wantsPlayback = false
        if (!super.getPlayWhenReady()) { finishFade(); return }
        rampTo(0.0) { super.pause(); finishFade() }
    }

    /** Pause without fade (noisy, sleep-timer boundary, focus loss). */
    fun pauseImmediately() {
        wantsPlayback = false
        fadeJob?.cancel()
        super.pause()
        finishFade()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

    override fun getVolume(): Float = volumes.userVolume.toFloat()
    override fun setVolume(volume: Float) { volumes.userVolume = volume.toDouble() }

    override fun seekToNext() = navigator?.skipNext() ?: fallbackNext()
    override fun seekToNextMediaItem() = seekToNext()
    override fun seekToPrevious() = navigator?.skipPrevious(currentPosition) ?: fallbackPrevious()
    override fun seekToPreviousMediaItem() = seekToPrevious()

    private fun fallbackNext() = if (repeatMode == REPEAT_MODE_ONE) super.seekTo(0) else super.seekToNextMediaItem()
    private fun fallbackPrevious() =
        if (repeatMode == REPEAT_MODE_ONE || currentPosition > 3_000) super.seekTo(0) else super.seekToPreviousMediaItem()

    /** Linear 20-step ramp from the current fade factor; a new ramp cancels (reverses) the old one. */
    private fun rampTo(target: Double, onDone: () -> Unit) {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            for (step in 1..FadeCurve.STEPS) {
                delay(FadeCurve.STEP_MS)
                volumes.fade = FadeCurve.next(volumes.fade, target, step)
            }
            onDone()
            if (target >= 1.0) finishFade()
        }
    }

    /** Back to full fade factor; the user volume is never touched by a fade (departs from Flutter). */
    private fun finishFade() {
        volumes.fade = 1.0
    }
}
