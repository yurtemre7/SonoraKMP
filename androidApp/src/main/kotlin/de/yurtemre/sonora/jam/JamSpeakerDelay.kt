package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The per-device speaker delay for [JamEnvironment.speakerDelayMs]: follows [settings] from the first emission on
 * (a change in Settings › Playback reaches a running Jam at its next start), clamped to
 * [AppSettings.JAM_SPEAKER_DELAY_RANGE_MS]. 0 until the settings are read.
 */
fun jamSpeakerDelay(settings: Flow<AppSettings>, scope: CoroutineScope): () -> Int {
    val delay = settings
        .map { it.jamSpeakerDelayMs.coerceIn(AppSettings.JAM_SPEAKER_DELAY_RANGE_MS) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, 0)
    return { delay.value }
}
