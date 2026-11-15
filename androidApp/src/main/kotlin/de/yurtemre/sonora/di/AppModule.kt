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
import de.yurtemre.sonora.shared.di.AppInfo
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
}
