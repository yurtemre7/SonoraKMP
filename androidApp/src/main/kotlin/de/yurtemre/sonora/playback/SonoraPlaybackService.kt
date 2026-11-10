package de.yurtemre.sonora.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

/** Stub (P1-11); real implementation in P4-11 (PLAY-01, AUTO-*, WID-*). */
class SonoraPlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {}).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
