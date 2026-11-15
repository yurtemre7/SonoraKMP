package de.yurtemre.sonora.shared.repo.impl

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import de.yurtemre.sonora.shared.backup.BackupCodec
import de.yurtemre.sonora.shared.backup.BackupDocument
import de.yurtemre.sonora.shared.backup.BackupError
import de.yurtemre.sonora.shared.backup.RestorePlanner
import de.yurtemre.sonora.shared.backup.SettingsWhitelist
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.devicesync.DeviceSyncSettings
import de.yurtemre.sonora.shared.devicesync.StatsDelta
import de.yurtemre.sonora.shared.devicesync.SyncBundle
import de.yurtemre.sonora.shared.devicesync.SyncStats
import de.yurtemre.sonora.shared.devicesync.fromSnapshot
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.DeviceSyncApplyResult
import de.yurtemre.sonora.shared.repo.api.DeviceSyncDataException
import de.yurtemre.sonora.shared.repo.api.DeviceSyncDataRepository
import de.yurtemre.sonora.shared.repo.api.FileStore
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * DSYNC-05/06/12 over Room + DataStore, reusing the backup v1 pipeline ([BackupCodec], [RestorePlanner],
 * [BackupDataAccess]).
 *
 * The bundle's backup JSON carries an empty `stats` section; the planner's stats replacement is always dropped
 * on apply, stats travel only as [SyncBundle.stats] and are added as a difference against the per-sender
 * baseline `files/device_sync/peers/<senderDeviceId>.json`, written after a successful apply.
 */
class DeviceSyncDataRepositoryImpl(
    private val db: SonoraDatabase,
    settings: SettingsRepository,
    private val stats: StatsRepository,
    private val files: FileStore,
    private val clock: Clock,
    /** `<VERSION_NAME>+<VERSION_CODE>`. */
    private val appVersion: String,
) : DeviceSyncDataRepository {
    private val data = BackupDataAccess(db, settings, clock)
    private val idLock = Mutex()
    private val applyLock = Mutex()

    override suspend fun deviceId(): String = idLock.withLock {
        val path = files.filesPath(DEVICE_ID_FILE)
        if (files.exists(path)) {
            files.readText(path).trim().takeIf(::isValidId)?.let { return@withLock it }
        }
        Uuid.random().toString().also { files.writeText(path, it) }
    }

    override suspend fun exportBundle(): SyncBundle {
        val library = data.library()
        val stored = data.storedSettings(data.readPrefs()).filterKeys { !DeviceSyncSettings.isExcluded(it) }
        val snapshot = stats.snapshot.first()
        val doc = if (library.isEmpty()) {
            BackupDocument(createdAtMs = clock.nowMs(), appVersion = appVersion, songs = emptyMap(),
                settings = SettingsWhitelist.toBackup(stored))
        } else {
            // Built with the real stats so every stats song gets a song ref, then the stats section is emptied.
            BackupCodec.buildExport(
                library = library, playlists = data.playlists(),
                favoriteAlbums = data.favoriteAlbums(), favoriteArtists = data.favoriteArtists(),
                stats = snapshot, storedSettings = stored, appVersion = appVersion, nowMs = clock.nowMs(),
            ).copy(stats = null)
        }
        val pathById = library.associate { it.id to it.filePath }
        return SyncBundle(BackupCodec.encode(doc), SyncStats.fromSnapshot(snapshot, pathById::get))
    }

    override suspend fun applyBundle(bundle: SyncBundle, senderDeviceId: String): DeviceSyncApplyResult {
        if (!isValidId(senderDeviceId)) throw DeviceSyncDataException.InvalidPeerId()
        val doc = try {
            BackupCodec.parse(bundle.backupJson)
        } catch (e: BackupError) {
            throw DeviceSyncDataException.InvalidBundle(e)
        }
        return applyLock.withLock {
            stats.pauseRecording()
            try {
                apply(doc, bundle.stats, files.filesPath("$PEERS_DIR/$senderDeviceId.json"))
            } finally {
                stats.resumeRecording()
            }
        }
    }

    private suspend fun apply(doc: BackupDocument, senderStats: SyncStats, baselinePath: String): DeviceSyncApplyResult {
        val prefsBefore = data.readPrefs()
        val state = data.restoreState(prefsBefore)
        val libraryEmpty = state.library.isEmpty()
        // Empty library: settings only; stats and the baseline wait for a sync that can map songs.
        val source = if (libraryEmpty) BackupDocument(doc.createdAtMs, doc.appVersion, emptyMap(), doc.settings, doc.hasSettings) else doc
        val raw = RestorePlanner.plan(source, state, clock.nowMs(), requireMatches = false)
        val plan = raw.copy(
            settingWrites = raw.settingWrites.filterKeys { !DeviceSyncSettings.isExcluded(it) },
            settingRemovals = raw.settingRemovals.filterNot(DeviceSyncSettings::isExcluded).toSet(),
            stats = null,
        )
        try {
            data.applySettings(plan)
            data.applyDb(plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Room rolled its transaction back itself; restore DataStore from the snapshot.
            val rolledBack = runCatching { data.writePrefs(prefsBefore) }.isSuccess
            throw DeviceSyncDataException.WriteFailed(e, rolledBack)
        }

        var statsSongs = 0
        var listens = 0
        if (!libraryEmpty) {
            try {
                val baseline = readBaseline(baselinePath)
                val diff = StatsDelta.difference(senderStats, baseline)
                val pathToId = plan.idMap.mapNotNull { (ref, id) -> doc.songs[ref]?.let { it.path to id } }.toMap()
                val delta = StatsDelta.toSnapshotDelta(diff, pathToId)
                stats.mergeDelta(delta)
                files.writeText(baselinePath, json.encodeToString(SyncStats.serializer(), senderStats))
                statsSongs = delta.songs.values.count { it.plays > 0 || it.skips > 0 || it.restarts > 0 }
                listens = delta.completeListens
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw DeviceSyncDataException.WriteFailed(e, rolledBack = false)
            }
        }
        val c = plan.counts
        return DeviceSyncApplyResult(
            playlists = c.playlistsRestored,
            favorites = c.favoriteSongsRestored + c.favoriteAlbumsAndArtistsRestored,
            settings = plan.settingWrites.size,
            statsSongsAdded = statsSongs,
            completeListensAdded = listens,
            localeChanged = c.localeChanged,
        )
    }

    /** A missing or unreadable baseline counts as none (the sender's full totals are added). */
    private suspend fun readBaseline(path: String): SyncStats? =
        if (!files.exists(path)) null
        else runCatching { json.decodeFromString(SyncStats.serializer(), files.readText(path)) }.getOrNull()

    override suspend fun relinkReplaced(oldToNew: Map<Long, Long>) {
        if (oldToNew.isEmpty()) return
        val relinkDao = db.relinkDao()
        val playlistDao = db.playlistDao()
        val songDao = db.songDao()
        db.useWriterConnection { c ->
            c.immediateTransaction {
                val pairs = oldToNew.filter { (old, new) -> old != new && songDao.getById(new) != null }
                if (pairs.isEmpty()) return@immediateTransaction
                for ((old, new) in pairs) {
                    relinkDao.relinkFavorite(old, new)
                    playlistDao.relinkSongId(old, new)
                }
                db.statsDao().moveSongStats(pairs)
                val newIds = pairs.values.distinct()
                relinkDao.refreshFavoritePaths(newIds)
                relinkDao.refreshPlaylistPaths(newIds)
                relinkDao.refreshStatsPaths(newIds)
            }
        }
    }

    private companion object {
        const val DEVICE_ID_FILE = "device_sync/device_id"
        const val PEERS_DIR = "device_sync/peers"
        val ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
        val json = Json { ignoreUnknownKeys = true }

        fun isValidId(id: String) = ID_PATTERN.matches(id)
    }
}
