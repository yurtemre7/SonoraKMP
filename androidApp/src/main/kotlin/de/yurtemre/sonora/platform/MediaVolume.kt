package de.yurtemre.sonora.platform

import android.content.Context
import android.media.AudioManager

/**
 * PLAY-28 (Flutter `ensureMediaVolume`): if STREAM_MUSIC is muted (0), raise it to max/3, without showing the
 * volume panel. The playback service calls it once when it is created, not on every play.
 */
object MediaVolume {
    /** Returns true if the volume was raised. */
    fun ensureAudible(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != 0) return@runCatching false
        val target = (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 3).coerceAtLeast(1)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        true
    }.getOrDefault(false)

    fun current(context: Context): Pair<Int, Int> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) to am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }
}
