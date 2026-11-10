package de.yurtemre.sonora.shared.di

import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.platform.AndroidFileStore
import de.yurtemre.sonora.shared.platform.SystemClock
import de.yurtemre.sonora.shared.platform.createSettingsDataStore
import de.yurtemre.sonora.shared.platform.createSonoraDatabase
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.FileStore
import de.yurtemre.sonora.shared.settings.PlaybackStateStore
import de.yurtemre.sonora.shared.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import android.content.Context
import org.koin.dsl.module

/**
 * Shared-layer bindings. Requires `androidContext(...)` (registers [Context]) on the Koin application.
 *
 * TODO(P3-15): bind repository/scanner implementations once they exist:
 *  LibraryRepository (P3-14), PlaylistRepository (P3-07), FavoritesRepository (P3-08),
 *  StatsRepository (P3-09), BackupRepository (P3-13), LibraryScanner (P3-12),
 *  GitHubReleasesClient (P2-16).
 */
val sharedModule = module {
    single { createSonoraDatabase(get<Context>()) }
    single { get<SonoraDatabase>().songDao() }
    single { get<SonoraDatabase>().playlistDao() }
    single { get<SonoraDatabase>().favoritesDao() }
    single { get<SonoraDatabase>().artistImageDao() }
    single { get<SonoraDatabase>().statsDao() }
    single { get<SonoraDatabase>().relinkDao() }

    single { createSettingsDataStore(get<Context>()) }
    single { SettingsRepository(get()) }
    single { PlaybackStateStore(get()) }

    single<FileStore> { AndroidFileStore(get<Context>()) }
    single<Clock> { SystemClock }

    single { HttpClient(OkHttp) { expectSuccess = false } }
}
