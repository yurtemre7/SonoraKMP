package de.yurtemre.sonora.shared.repo.api

import de.yurtemre.sonora.shared.devicesync.SyncBundle

/** Device sync data side (DSYNC-05, DSYNC-06, DSYNC-12): what is sent besides the files, and how it is applied. */
interface DeviceSyncDataRepository {
    /** This device's random id, created once and kept in `files/device_sync/device_id`. */
    suspend fun deviceId(): String

    /** Backup v1 JSON without stats and device-specific settings, plus the stats totals keyed by file path. */
    suspend fun exportBundle(): SyncBundle

    /**
     * Applies a received bundle: playlists, favorites and settings by the backup rules (BAK-03/04), stats as the
     * difference since the last bundle from [senderDeviceId] (D-71). Idempotent.
     *
     * An empty receiver library is not an error: settings are applied, everything else (including stats and the
     * baseline) is skipped and the counts are 0. "No matching songs" is not an error either: the bundle is applied
     * and unmatched song references are dropped, as for partly matching backups.
     *
     * @throws DeviceSyncDataException on an invalid sender id, an unreadable bundle or a write failure.
     */
    suspend fun applyBundle(bundle: SyncBundle, senderDeviceId: String): DeviceSyncApplyResult

    /** D-72: moves favorites, playlist entries and stats from each old song id to its new one, in one transaction. */
    suspend fun relinkReplaced(oldToNew: Map<Long, Long>)
}

data class DeviceSyncApplyResult(
    val playlists: Int,
    /** Matched favorite songs + favorite album and artist entries. */
    val favorites: Int,
    /** Stored settings written. */
    val settings: Int,
    /** Receiver songs whose plays, skips or restarts grew. */
    val statsSongsAdded: Int,
    val completeListensAdded: Int,
    val localeChanged: Boolean,
)

sealed class DeviceSyncDataException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The sender id is not `[A-Za-z0-9-]{1,64}`; nothing was written. */
    class InvalidPeerId : DeviceSyncDataException("Invalid sender device id")
    /** The backup JSON in the bundle failed to parse (BAK-02); nothing was written. */
    class InvalidBundle(cause: Throwable) : DeviceSyncDataException("Invalid device sync bundle: ${cause.message}", cause)
    /**
     * Writing failed. Settings/DB failure: Room rolled back and DataStore was restored when [rolledBack].
     * A failure in the stats step leaves the (idempotent) playlists/favorites/settings applied and the baseline
     * untouched, so the next sync adds the same difference again.
     */
    class WriteFailed(cause: Throwable, val rolledBack: Boolean) :
        DeviceSyncDataException("Device sync apply failed: ${cause.message}", cause)
}
