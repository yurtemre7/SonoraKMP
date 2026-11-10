package de.yurtemre.sonora

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.impl.LibraryRepositoryImpl
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Debug-only: `am broadcast -n <pkg>/de.yurtemre.sonora.DebugLibraryProbe -a de.yurtemre.sonora.DEBUG_SYNC`. */
class DebugLibraryProbe : BroadcastReceiver(), KoinComponent {
    private val library: LibraryRepository by inject()
    private val settings: SettingsRepository by inject()
    private val scope: CoroutineScope by inject()
    private val playback: PlaybackConnection by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        when (intent.action) {
            ACTION_PLAY -> return play(intent, pending)
            ACTION_STATE -> return state(pending)
        }
        scope.launch {
            try {
                val folder = intent.getStringExtra("folder") ?: "/storage/emulated/0/Music/SonoraTest"
                settings.setScanFolder(folder, null)
                val outcome = library.sync()
                val songs = library.songs.first()
                val impl = library as? LibraryRepositoryImpl
                val albums = impl?.albums?.first()?.size ?: -1
                val artists = impl?.artists?.first()?.size ?: -1
                Log.i(TAG, "outcome=$outcome")
                Log.i(TAG, "songs=${songs.size} albums=$albums artists=$artists lyrics=${songs.count { it.hasLyrics }}")
            } catch (t: Throwable) {
                Log.e(TAG, "probe failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** Syncs the library, then plays all songs from index 0 via [PlaybackConnection]. */
    private fun play(intent: Intent, pending: PendingResult) {
        scope.launch {
            try {
                val folder = intent.getStringExtra("folder") ?: "/storage/emulated/0/Music/SonoraTest"
                settings.setScanFolder(folder, null)
                library.sync()
                val songs = library.songs.first()
                Log.i(TAG, "play songs=${songs.size}")
                if (songs.isNotEmpty()) playback.playSong(songs[0], songs)
            } catch (t: Throwable) {
                Log.e(TAG, "play failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun state(pending: PendingResult) {
        scope.launch(Dispatchers.Main) {
            try {
                val p = playback.player.value
                // drop the replayed value: the next tick is fresh while playing
                val pos = withTimeoutOrNull(600) { playback.positionMs.drop(1).first() }
                    ?: withTimeoutOrNull(600) { playback.positionMs.first() }
                val q = playback.queue.value
                Log.i(TAG, "playing=${p.isPlaying} title=${p.currentSong?.title} pos=$pos queue=${q.items.size}@${q.currentIndex}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SonoraProbe"
        const val ACTION_PLAY = "de.yurtemre.sonora.DEBUG_PLAY"
        const val ACTION_STATE = "de.yurtemre.sonora.DEBUG_STATE"
    }
}
