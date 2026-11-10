package de.yurtemre.sonora.playback

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import de.yurtemre.sonora.shared.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * PLAY-17. `pause_on_duck=false` → Media3 handles focus (system ducks). `true` → custom focus with
 * willPauseWhenDucked: pause on any transient loss/duck, resume on gain if we paused; permanent loss pauses.
 */
@OptIn(UnstableApi::class)
class AudioFocusController(
    context: Context,
    private val player: ExoPlayer,
    private val settings: Flow<AppSettings>,
    private val scope: CoroutineScope,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    private var custom = false
    private var request: AudioFocusRequestCompat? = null
    private var hasFocus = false
    private var pausedByFocus = false
    private var job: Job? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (pausedByFocus) { pausedByFocus = false; player.play() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (player.isPlaying || player.playWhenReady) { pausedByFocus = true; player.pause() }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false; pausedByFocus = false
                player.pause()
                abandon()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!custom) return
            if (playWhenReady) { pausedByFocus = false; requestFocus() }
        }
    }

    fun start() {
        if (job != null) return
        player.addListener(playerListener)
        job = scope.launch {
            settings.map { it.pauseOnDuck }.distinctUntilChanged().collect(::setCustom)
        }
    }

    fun stop() {
        job?.cancel(); job = null
        player.removeListener(playerListener)
        abandon()
    }

    private fun setCustom(enabled: Boolean) {
        custom = enabled
        if (enabled) {
            player.setAudioAttributes(attrs, false)
            if (player.playWhenReady) requestFocus()
        } else {
            abandon()
            player.setAudioAttributes(attrs, true)
        }
    }

    private fun requestFocus() {
        if (hasFocus) return
        val req = request ?: AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build().also { request = it }
        hasFocus = AudioManagerCompat.requestAudioFocus(audioManager, req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) player.pause()
    }

    private fun abandon() {
        request?.let { AudioManagerCompat.abandonAudioFocusRequest(audioManager, it) }
        request = null; hasFocus = false; pausedByFocus = false
    }
}
