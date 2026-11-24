package de.yurtemre.sonora.playback

import de.yurtemre.sonora.jam.JamGuardedPlayback
import de.yurtemre.sonora.playback.api.PlaybackConnection
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** The MediaController-backed connection without the Jam guard: only the Jam controller (which drives the mirror) uses it. */
val RAW_PLAYBACK = named("rawPlayback")

/** P4-12: MediaController-backed [PlaybackConnection]; the app gets it behind the Jam guest guard. */
val playbackModule = module {
    single { QueueStateBus() }
    single<PlaybackConnection>(RAW_PLAYBACK) { PlaybackConnectionImpl(androidContext(), get(), get(), get()) }
    single<PlaybackConnection> { JamGuardedPlayback(get(RAW_PLAYBACK), get()) }
}
