package de.yurtemre.sonora.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/** Builds the app's ExoPlayer (PLAY-04/11). Gapless is ExoPlayer's default for concatenated items. */
object PlayerFactory {
    /**
     * @param handleAudioFocus false when P4-04's custom focus controller (pause-on-duck) owns focus.
     */
    fun create(context: Context, handleAudioFocus: Boolean = false): ExoPlayer =
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                handleAudioFocus,
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(false) // NoisyAndReconnectHandler honours the setting
            .build()
}
