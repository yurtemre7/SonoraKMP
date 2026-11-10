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
import de.yurtemre.sonora.shared.repo.api.*
import de.yurtemre.sonora.shared.repo.impl.*
import de.yurtemre.sonora.shared.scan.AndroidLibraryScanner
import de.yurtemre.sonora.shared.scan.FileByteSource
import de.yurtemre.sonora.shared.scan.SeedColorExtractor
import de.yurtemre.sonora.shared.update.GitHubReleasesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** App-supplied build info (androidApp binds it from BuildConfig). */
data class AppInfo(val versionName: String) // `<VERSION_NAME>+<VERSION_CODE>`

/**
 * Shared-layer bindings. Requires `androidContext(...)` and an [AppInfo] binding on the Koin application.
 */
val sharedModule = module {
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

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
    single { GitHubReleasesClient(get(), get()) }

    single { AndroidLibraryScanner(get<Context>()) }
    single<LibraryScanner> { get<AndroidLibraryScanner>() }
    single<LibraryRepository> {
        val scanner = get<AndroidLibraryScanner>()
        LibraryRepositoryImpl(
            db = get(),
            scanner = scanner,
            settings = get<SettingsRepository>().asLibrarySettings(),
            platform = LibraryPlatform(
                openByteSource = { path -> FileByteSource.open(File(path)) },
                extractSeedColor = SeedColorExtractor()::extract,
                backgroundArt = scanner.backgroundArt,
            ),
            clock = get(),
            scope = get(),
        )
    }
    single<PlaylistRepository> { PlaylistRepositoryImpl(get(), get(), get()) }
    single<FavoritesRepository> { FavoritesRepositoryImpl(get(), get()) }
    single<StatsRepository> { StatsRepositoryImpl(get()) }
    single<BackupRepository> {
        BackupRepositoryImpl(get(), get(), get(), get(), get(), appVersion = get<AppInfo>().versionName)
    }
}
