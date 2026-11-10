package de.yurtemre.sonora.playback

import de.yurtemre.sonora.playback.api.PlaybackConnection
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** P4-12: MediaController-backed [PlaybackConnection]. */
val playbackModule = module {
    single<PlaybackConnection> { PlaybackConnectionImpl(androidContext(), get(), get()) }
}
