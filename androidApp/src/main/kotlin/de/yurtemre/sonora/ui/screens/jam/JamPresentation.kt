package de.yurtemre.sonora.ui.screens.jam

import androidx.annotation.StringRes
import de.yurtemre.sonora.R
import de.yurtemre.sonora.jam.JamEndReason
import de.yurtemre.sonora.jam.JamSyncState
import de.yurtemre.sonora.jam.JamUiState
import de.yurtemre.sonora.shared.jam.JamError
import de.yurtemre.sonora.ui.screens.devicesync.groupCode
import de.yurtemre.sonora.ui.screens.devicesync.spokenDigits

// Pure presentation logic of the Jam screens (unit-tested in JamPresentationTest and JamViewModelTest).

/** "123456" → "123 456", like the device sync code. */
fun formatJamCode(code: String): String = groupCode(code)

/** "1 2 3 4 5 6": TalkBack reads the code digit by digit. */
fun spokenJamCode(code: String): String = spokenDigits(code)

// ---- manual address -------------------------------------------------------------------------------------------------

data class JamAddress(val host: String, val port: Int)

/**
 * Manual join when mDNS is blocked: "IPv4:port" or "hostname:port" (letters, digits and hyphens per label, labels
 * of 1–63 characters not starting or ending with a hyphen, at most 253 characters), port 1..65535. A host made only
 * of digits and dots must be a valid IPv4 address (not 0.0.0.0 or the broadcast address). No IPv6, no scheme.
 */
fun parseJamAddress(text: String): JamAddress? {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    val (host, portText) = parts
    if (portText.isEmpty() || portText.length > 5 || !portText.all { it in '0'..'9' }) return null
    val port = portText.toInt()
    if (port !in 1..65535) return null
    val ok = if (host.all { it in '0'..'9' || it == '.' }) isIpv4(host) else isHostname(host)
    return if (ok) JamAddress(host, port) else null
}

private fun isIpv4(host: String): Boolean {
    val octets = host.split('.')
    if (octets.size != 4) return false
    if (octets.any { it.isEmpty() || it.length > 3 || it.toInt() > 255 }) return false
    return host != "0.0.0.0" && host != "255.255.255.255"
}

private fun isHostname(host: String): Boolean {
    if (host.isEmpty() || host.length > 253) return false
    return host.split('.').all { label ->
        label.length in 1..63 && !label.startsWith('-') && !label.endsWith('-') &&
            label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
    }
}

// ---- errors ---------------------------------------------------------------------------------------------------------

/** What the join screen offers next to an error. */
enum class JamErrorAction {
    /** Type the code again (the field is cleared). */
    TryAgain,
    /** Joining is refused for a few seconds; a countdown runs. */
    Wait,
    /** The code is used up: only the host's new code helps. */
    AskForNewCode,
    /** Host and guest run different versions of the app. */
    UpdateApp,
    /** Nothing this phone can do (the Jam is full). */
    None,
}

@StringRes
fun jamErrorMessage(error: JamError): Int = when (error) {
    JamError.WrongCode -> R.string.jam_error_wrong_code
    JamError.Locked -> R.string.jam_error_locked_plain
    JamError.Busy -> R.string.jam_error_busy
    JamError.CodeExhausted -> R.string.jam_error_code_exhausted
    JamError.Full -> R.string.jam_error_full
    JamError.VersionMismatch -> R.string.jam_error_version
    JamError.HostGone -> R.string.jam_error_host_gone
    JamError.ReconnectRefused -> R.string.jam_error_reconnect_refused
}

fun jamErrorAction(error: JamError): JamErrorAction = when (error) {
    JamError.WrongCode, JamError.HostGone, JamError.ReconnectRefused, JamError.Busy -> JamErrorAction.TryAgain
    JamError.Locked -> JamErrorAction.Wait
    JamError.CodeExhausted -> JamErrorAction.AskForNewCode
    JamError.VersionMismatch -> JamErrorAction.UpdateApp
    JamError.Full -> JamErrorAction.None
}

@StringRes
fun jamEndMessage(reason: JamEndReason): Int = when (reason) {
    JamEndReason.HostEnded -> R.string.jam_ended_host_ended
    JamEndReason.Kicked -> R.string.jam_ended_kicked
    JamEndReason.HostGone -> R.string.jam_ended_host_gone
    JamEndReason.NetworkChanged -> R.string.jam_ended_network
}

// ---- Now Playing in a Jam -------------------------------------------------------------------------------------------

enum class JamRole { None, Host, Guest }

/** The guest's connection, in plain words. */
enum class JamStatus {
    Syncing, InSync, Reconnecting, EndingAfterSong,

    /** This phone paused itself (headphones unplugged, audio focus lost): play rejoins. */
    PausedBySystem,
}

@StringRes
fun jamStatusText(status: JamStatus): Int = when (status) {
    JamStatus.Syncing -> R.string.jam_status_syncing
    JamStatus.InSync -> R.string.jam_status_in_sync
    JamStatus.Reconnecting -> R.string.jam_status_reconnecting
    JamStatus.EndingAfterSong -> R.string.jam_status_ending
    JamStatus.PausedBySystem -> R.string.jam_status_paused_by_system
}

/**
 * What Now Playing shows for the Jam state: a guest's player mirrors the host, so local controls that would diverge
 * (seek, reorder/remove, shuffle, repeat, sleep timer) are hidden, and skip / play-pause only show when the host
 * allows them (they become requests to the host). [ended] is set once the controller reports the Jam over — for a
 * host that dropped, only after the current song finished.
 */
data class JamPlayerUi(
    val role: JamRole = JamRole.None,
    val hostName: String = "",
    val guestCount: Int = 0,
    val showSeek: Boolean = true,
    val showQueueEditing: Boolean = true,
    val showShuffleRepeat: Boolean = true,
    val showSleepTimer: Boolean = true,
    val showSkip: Boolean = true,
    val showPlayPause: Boolean = true,
    val showAddSong: Boolean = false,
    val status: JamStatus? = null,
    val ended: JamUiState.Ended? = null,
) {
    val isGuest: Boolean get() = role == JamRole.Guest

    companion object {
        val None = JamPlayerUi()
    }
}

fun jamPlayerUi(state: JamUiState): JamPlayerUi = when (state) {
    is JamUiState.Joined -> JamPlayerUi(
        role = JamRole.Guest,
        hostName = state.hostName,
        showSeek = false,
        showQueueEditing = false,
        showShuffleRepeat = false,
        showSleepTimer = false,
        showSkip = state.canControl,
        // After a system pause play only resumes this phone, so it shows even without host control.
        showPlayPause = state.canControl || state.pausedBySystem,
        showAddSong = state.canAdd,
        status = if (state.pausedBySystem && state.sync != JamSyncState.Ending) JamStatus.PausedBySystem else when (state.sync) {
            JamSyncState.Syncing -> JamStatus.Syncing
            JamSyncState.InSync -> JamStatus.InSync
            JamSyncState.Reconnecting -> JamStatus.Reconnecting
            JamSyncState.Ending -> JamStatus.EndingAfterSong
        },
    )
    is JamUiState.Hosting -> JamPlayerUi(role = JamRole.Host, guestCount = state.guests.size)
    is JamUiState.Ended -> JamPlayerUi(ended = state)
    else -> JamPlayerUi.None
}

/** What the mini player shows ([miniPlayerControls]); the bar reads it instead of assuming local playback. */
data class MiniPlayerControls(
    val showPlayPause: Boolean = true,
    val showSkip: Boolean = true,
    /** A Jam guest's skip goes to the host, so it does not depend on the local (mirrored) queue's next song. */
    val skipAlwaysEnabled: Boolean = false,
    val showSleepTimer: Boolean = true,
    /** Show "Only the host can skip or pause" instead of the buttons. */
    val hostOnlyHint: Boolean = false,
) {
    fun canSkipNext(hasNext: Boolean): Boolean = showSkip && (skipAlwaysEnabled || hasNext)
    fun canSkipPrevious(hasPrevious: Boolean): Boolean = showSkip && (skipAlwaysEnabled || hasPrevious)
}

/** The mini player's controls for the Jam state, matching the full player's ([JamPlayerUi]). */
fun miniPlayerControls(jam: JamPlayerUi): MiniPlayerControls = MiniPlayerControls(
    showPlayPause = jam.showPlayPause,
    showSkip = jam.showSkip,
    skipAlwaysEnabled = jam.isGuest,
    showSleepTimer = jam.showSleepTimer,
    hostOnlyHint = jam.isGuest && !jam.showSkip && !jam.showPlayPause,
)
