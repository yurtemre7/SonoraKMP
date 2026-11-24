package de.yurtemre.sonora.di

import de.yurtemre.sonora.BuildConfig
import de.yurtemre.sonora.devicesync.AndroidDeviceName
import de.yurtemre.sonora.devicesync.AndroidReceiverLibrary
import de.yurtemre.sonora.devicesync.AndroidReceiverStorage
import de.yurtemre.sonora.devicesync.AndroidReplacedFileDeleter
import de.yurtemre.sonora.devicesync.AndroidSenderDiscovery
import de.yurtemre.sonora.devicesync.AndroidSenderSessionFactory
import de.yurtemre.sonora.devicesync.DestinationFolder
import de.yurtemre.sonora.devicesync.DeviceSyncClientAdapter
import de.yurtemre.sonora.devicesync.DeviceSyncController
import de.yurtemre.sonora.devicesync.DeviceSyncDiscovery
import de.yurtemre.sonora.devicesync.DeviceSyncService
import de.yurtemre.sonora.devicesync.FingerprintCache
import de.yurtemre.sonora.devicesync.ReceiverIndexBuilder
import de.yurtemre.sonora.devicesync.SenderManifestBuilder
import de.yurtemre.sonora.jam.ClientGuestConnector
import de.yurtemre.sonora.jam.JamController
import de.yurtemre.sonora.jam.JamDiscovery
import de.yurtemre.sonora.jam.JamEnvironment
import de.yurtemre.sonora.jam.JamService
import de.yurtemre.sonora.jam.jamSpeakerDelay
import de.yurtemre.sonora.jam.ServerHostTransport
import de.yurtemre.sonora.shared.devicesync.PairingCode
import de.yurtemre.sonora.shared.di.AppInfo
import de.yurtemre.sonora.shared.jam.JamClient
import de.yurtemre.sonora.shared.jam.JamServer
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.settings.SettingsRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * App-layer bindings (ViewModels, PlaybackConnection, platform helpers).
 *
 * TODO: feature `<Feature>Module`s (P5-*), PlaybackConnection impl (P4-12) and app platform helpers (P3-10)
 * are added here or included via `includes(...)` as they land.
 */
val appModule = module {
    single { AppInfo("${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}") }

    // Device sync (DSYNC-01..13)
    single { DeviceSyncDiscovery(androidContext()) }
    single { DestinationFolder(androidContext()) }
    single {
        val ctx = androidContext()
        DeviceSyncController(
            scope = get(),
            senders = AndroidSenderSessionFactory(
                SenderManifestBuilder(ctx.contentResolver, get(), get(), get()), get(), get(),
            ),
            clients = { DeviceSyncClientAdapter() },
            discovery = AndroidSenderDiscovery(get()),
            storage = AndroidReceiverStorage(
                ctx, get(), get(), ReceiverIndexBuilder(ctx.contentResolver, get()),
                FingerprintCache(File(ctx.filesDir, "device_sync/fingerprints.json")),
            ),
            library = AndroidReceiverLibrary(ctx, get()),
            data = get(),
            deleter = AndroidReplacedFileDeleter(ctx, get()),
            deviceName = AndroidDeviceName(ctx),
            clock = get(),
            service = { DeviceSyncService.start(ctx) },
        )
    }

    // Jam: synchronized playback over the LAN
    single {
        val ctx = androidContext()
        // Main thread like the player; the Jam client and server stamp with the same monotonic clock.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val clock = { android.os.SystemClock.elapsedRealtime() }
        val library = get<LibraryRepository>().songs.stateIn(scope, SharingStarted.Eagerly, emptyList<Song>())
        val speakerDelay = jamSpeakerDelay(get<SettingsRepository>().settings, scope)
        val stats = get<StatsRepository>()
        val discovery = JamDiscovery(ctx)
        JamController(
            scope, get(),
            JamEnvironment(
                hostTransport = { ServerHostTransport(JamServer(clock)) },
                connector = ClientGuestConnector(JamClient(clock)),
                library = { library.value },
                nowMs = clock,
                speakerDelayMs = speakerDelay,
                recordSkip = { stats.recordSkip(it) },
                deviceName = { AndroidDeviceName(ctx).deviceName() },
                advertise = { port, name -> discovery.advertise(port, UUID.randomUUID().toString(), name) },
                startService = { JamService.start(ctx) },
                newCode = { PairingCode.generate() },
                debugLog = if (BuildConfig.DEBUG) { msg -> android.util.Log.d("SonoraJam", msg) } else null,
            ),
        )
    }
}
