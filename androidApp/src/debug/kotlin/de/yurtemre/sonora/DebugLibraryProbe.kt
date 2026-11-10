package de.yurtemre.sonora

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.impl.LibraryRepositoryImpl
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Debug-only: `am broadcast -n <pkg>/de.yurtemre.sonora.DebugLibraryProbe -a de.yurtemre.sonora.DEBUG_SYNC`. */
class DebugLibraryProbe : BroadcastReceiver(), KoinComponent {
    private val library: LibraryRepository by inject()
    private val settings: SettingsRepository by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
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

    private companion object { const val TAG = "SonoraProbe" }
}
