package de.yurtemre.sonora.di

import de.yurtemre.sonora.BuildConfig
import de.yurtemre.sonora.shared.di.AppInfo
import org.koin.dsl.module

/**
 * App-layer bindings (ViewModels, PlaybackConnection, platform helpers).
 *
 * TODO: feature `<Feature>Module`s (P5-*), PlaybackConnection impl (P4-12) and app platform helpers (P3-10)
 * are added here or included via `includes(...)` as they land.
 */
val appModule = module {
    single { AppInfo("${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}") }
}
