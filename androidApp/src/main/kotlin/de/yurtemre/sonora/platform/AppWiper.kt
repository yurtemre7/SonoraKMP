package de.yurtemre.sonora.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import coil3.SingletonImageLoader
import de.yurtemre.sonora.locale.AppLocales
import de.yurtemre.sonora.playback.api.PlaybackConnection
import de.yurtemre.sonora.shared.db.ArtistImageDao
import de.yurtemre.sonora.shared.model.AppLocale
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.settings.PlaybackStateStore
import de.yurtemre.sonora.shared.settings.SettingsRepository
import de.yurtemre.sonora.widget.WidgetRenderer
import de.yurtemre.sonora.widget.WidgetState
import de.yurtemre.sonora.widget.WidgetStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SET-08 full wipe (FIX D-01): stops playback, clears the saved session, DataStore, every Room table, covers,
 * embedded art, caches (incl. Coil), widget state, persisted folder grants and the per-app locale.
 * Call [restart] afterwards; with `onboarding_completed` gone the gate routes to onboarding.
 */
class AppWiper(
    private val context: Context,
    private val playback: PlaybackConnection,
    private val settings: SettingsRepository,
    private val playbackState: PlaybackStateStore,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val favorites: FavoritesRepository,
    private val stats: StatsRepository,
    private val artistImages: ArtistImageDao,
) {
    suspend fun wipe() {
        withContext(Dispatchers.Main) { playback.closePlayer() }
        withContext(Dispatchers.IO) {
            playbackState.clear()
            settings.edit { it.clear() }
            playlists.clearAll()
            favorites.clearAll()
            stats.reset()
            artistImages.clearAll()
            library.clearAll()

            val app = context.applicationContext
            File(app.filesDir, "playlist_covers").deleteRecursively()
            File(app.filesDir, "embedded_art").deleteRecursively()
            app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            SingletonImageLoader.get(app).let { loader ->
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
            app.contentResolver.persistedUriPermissions.forEach { perm ->
                runCatching {
                    app.contentResolver.releasePersistableUriPermission(
                        perm.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            (if (perm.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0),
                    )
                }
            }
            WidgetStateStore.save(app, WidgetState.EMPTY)
            WidgetRenderer.renderAsync(app)
        }
        withContext(Dispatchers.Main) { AppLocales.apply(AppLocale.System) }
    }

    companion object {
        /** Relaunches the task from the launcher activity so the gate re-reads the (now empty) settings. */
        fun restart(activity: Activity) {
            val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
            activity.startActivity(Intent.makeRestartActivityTask(intent.component))
            activity.finish()
        }
    }
}
