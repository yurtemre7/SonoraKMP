package de.yurtemre.sonora.playback

import de.yurtemre.sonora.shared.model.QueueState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-process hand-off of the queue from [SonoraPlaybackService] to [PlaybackConnectionImpl] (same process).
 * The queue used to travel as JSON in the session extras: encoding, parcelling to the system and decoding
 * the whole list on the main thread for every track change made taps lag on slower devices.
 */
class QueueStateBus {
    val state = MutableStateFlow(QueueState())
}
