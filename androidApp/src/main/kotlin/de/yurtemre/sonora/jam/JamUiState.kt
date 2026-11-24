package de.yurtemre.sonora.jam

import de.yurtemre.sonora.shared.jam.JamError
import de.yurtemre.sonora.shared.jam.JamSettings
import de.yurtemre.sonora.shared.jam.JamSong

/** What the Jam screens show ([JamController.state]). */
sealed interface JamUiState {
    data object Idle : JamUiState

    /**
     * This phone hosts a Jam: guests join at [port] with [code]. [codeExhausted]: offer [JamController.newCode].
     * [hostSpeedEffectActive]: the host plays at a speed other than 1x (an MFX effect). Guests always play at 1x and
     * drift until the next song change, so the Jam screen can suggest turning the effect off; it is never forced.
     */
    data class Hosting(
        val code: String,
        val port: Int,
        val guests: List<JamGuestUi>,
        val settings: JamSettings,
        val codeExhausted: Boolean = false,
        val hostSpeedEffectActive: Boolean = false,
    ) : JamUiState

    data object Joining : JamUiState

    /**
     * This phone is a guest of [hostName]'s Jam. [queue] is the host's queue; a song with `available = false` is not
     * on this phone and is skipped silently. [canAdd] / [canControl] are the host's permissions for guests.
     * [pausedBySystem]: this phone paused itself (headphones unplugged, audio focus lost); the host's frames do not
     * start it again until the user taps play ([JamControls.resumeAfterSystemPause], allowed without host control)
     * or the phone resumes by itself on a safe output.
     */
    data class Joined(
        val hostName: String,
        val queue: List<JamQueueItem>,
        val index: Int,
        val playing: Boolean,
        val canAdd: Boolean,
        val canControl: Boolean,
        val sync: JamSyncState,
        val pausedBySystem: Boolean = false,
    ) : JamUiState

    /** The Jam is over; [rejoin] is set when the guest can join the same host again (with the code). */
    data class Ended(val reason: JamEndReason, val rejoin: JamRejoin? = null) : JamUiState

    /** Joining failed (wrong code, full, ...). */
    data class Error(val error: JamError) : JamUiState

    /** Hosting could not start (no free port, no network). */
    data object HostFailed : JamUiState
}

data class JamGuestUi(val id: String, val name: String)

data class JamQueueItem(val song: JamSong, val available: Boolean)

enum class JamSyncState {
    /** The clock offset is not measured yet; playback starts on the uncorrected clock. */
    Syncing,
    InSync,
    /** The connection dropped; the current song plays on while the guest reconnects. */
    Reconnecting,
    /** The host is gone or ended the Jam: the current song plays to its end, then the Jam ends. */
    Ending,
}

enum class JamEndReason { HostEnded, Kicked, HostGone, NetworkChanged }

/** Where a guest can join again after [JamEndReason.NetworkChanged] (the code is needed again). */
data class JamRejoin(val host: String, val port: Int, val hostName: String)
