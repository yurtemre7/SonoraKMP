package de.yurtemre.sonora.devicesync

import android.net.Uri
import de.yurtemre.sonora.shared.devicesync.ServerState
import de.yurtemre.sonora.shared.devicesync.TransferPlan
import de.yurtemre.sonora.shared.devicesync.TransferProgress

/** Where the receiver connects: a discovered sender or a manually entered `IP:port` ([name] null). */
data class SyncTarget(val host: String, val port: Int, val name: String? = null) {
    companion object {
        fun of(sender: DiscoveredSender) = SyncTarget(sender.host, sender.port, sender.name)
    }
}

/** Why a device sync stopped. Only [CodeMismatch] counts toward the sender's attempt limit. */
enum class DeviceSyncFailure {
    /** The code did not match (J-PAKE validation failed). */
    CodeMismatch,
    /** The phones speak different protocol versions: update both. */
    VersionMismatch,
    /** Connection lost, refused or timed out. */
    Network,
    /** The peer sent malformed or unexpected data. */
    Protocol,
    /** The destination could not be read or written. */
    Storage,
    /** Playlists, favorites, settings or stats could not be applied ([DeviceSyncDataException]). */
    DataApply,
    /** The sender could not build its file list or open its port. */
    SenderStart,
}

/** One file that was not copied, with the client's reason code (`checksum_mismatch`, `read_failed`, ...). */
data class FailedFile(val relPath: String, val reason: String)

/** Result of a finished receive (DSYNC-12/13). */
data class DeviceSyncSummary(
    /** Committed files that replaced nothing (New items). */
    val filesCopied: Int,
    /** Replace items whose old file was deleted. */
    val filesReplaced: Int,
    /** Replace items whose new file arrived but whose old file stayed (deletion declined, impossible or skipped). */
    val filesKeptBoth: Int,
    val failed: List<FailedFile>,
    /** Bytes of all committed files. */
    val bytes: Long,
    val playlists: Int,
    val favorites: Int,
    val settings: Int,
    val statsSongsAdded: Int,
    val completeListensAdded: Int,
    /** The app language setting changed: the UI applies it (as after a backup restore). */
    val localeChanged: Boolean,
)

/** Step within [DeviceSyncState.Applying]. */
enum class ApplyStep {
    /** Library rescan so the new files get song ids. */
    LibrarySync,
    /** Relinking and deleting replaced files (may wait for the system delete confirmation). */
    Replacing,
    /** Playlists, favorites, settings and stats. */
    Data,
}

/** The one state the device sync UI and the service observe ([DeviceSyncController.state]). */
sealed interface DeviceSyncState {
    data object Idle : DeviceSyncState

    /** Sender role. [address] is `IP:port` for manual entry (null when no IPv4 address is known). */
    data class Sending(
        val code: String,
        val codeExpiresAtMs: Long,
        val address: String?,
        val server: ServerState,
    ) : DeviceSyncState

    /** Receiver role: senders found so far. [discoveryFailed]: mDNS could not start, offer manual `IP:port`. */
    data class Discovering(
        val senders: List<DiscoveredSender> = emptyList(),
        val discoveryFailed: Boolean = false,
    ) : DeviceSyncState

    /** Pairing with [target] and loading its manifest (also covers the first plan). */
    data class Connecting(val target: SyncTarget) : DeviceSyncState

    /**
     * Connected to [senderName]. [plan] is null while [computing] or when there is no writable [destination]
     * ([needsDestination]): the UI asks for a folder and calls [DeviceSyncController.setDestination].
     * [freeBytes] null = unknown (the transfer is allowed; the UI warns).
     */
    data class Planning(
        val senderName: String,
        val destination: Uri?,
        val plan: TransferPlan?,
        val freeBytes: Long?,
        val computing: Boolean = false,
    ) : DeviceSyncState {
        val needsDestination: Boolean get() = destination == null

        /** DSYNC-09: needed bytes + 200 MB margin within the free space (unknown free space counts as fitting). */
        fun fits(selectedIds: Set<Int>): Boolean {
            val p = plan ?: return false
            return freeBytes == null || p.fits(selectedIds, freeBytes)
        }
    }

    data class Transferring(val progress: TransferProgress) : DeviceSyncState

    data class Applying(val step: ApplyStep) : DeviceSyncState

    data class Finished(val summary: DeviceSyncSummary) : DeviceSyncState

    /**
     * [filesKept]: verified files already written stay (a retry re-plans them as Present).
     * [canRetry]: [DeviceSyncController.retry] is available.
     */
    data class Failed(val reason: DeviceSyncFailure, val filesKept: Int = 0, val canRetry: Boolean = true) :
        DeviceSyncState
}
