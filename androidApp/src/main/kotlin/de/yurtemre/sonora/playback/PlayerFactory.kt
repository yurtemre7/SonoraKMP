package de.yurtemre.sonora.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor

/** Builds the app's ExoPlayer (PLAY-04/11). Gapless is ExoPlayer's default for concatenated items. */
object PlayerFactory {
    /**
     * @param handleAudioFocus false when P4-04's custom focus controller (pause-on-duck) owns focus.
     */
    @OptIn(UnstableApi::class)
    fun create(context: Context, handleAudioFocus: Boolean = false): ExoPlayer {
        // Index seeking makes VBR MP3s without a Xing/VBRI header seek and report duration exactly
        // instead of estimating from the first frame's bitrate.
        val extractors = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
            .setConstantBitrateSeekingEnabled(true)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
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
}
