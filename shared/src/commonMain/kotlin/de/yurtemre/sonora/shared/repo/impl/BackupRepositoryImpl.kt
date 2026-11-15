package de.yurtemre.sonora.shared.repo.impl

import de.yurtemre.sonora.shared.backup.BackupCodec
import de.yurtemre.sonora.shared.backup.BackupError
import de.yurtemre.sonora.shared.backup.RestorePlanner
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.repo.api.BackupExportResult
import de.yurtemre.sonora.shared.repo.api.BackupRepository
import de.yurtemre.sonora.shared.repo.api.BackupRestoreResult
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.FileStore
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * BAK-01..05 over Room + DataStore. Restore writes DataStore first, then all DB changes in one immediate
 * transaction; if the DB write fails the DataStore snapshot taken before the restore is written back.
 */
class BackupRepositoryImpl(
    db: SonoraDatabase,
    settings: SettingsRepository,
    private val stats: StatsRepository,
    private val files: FileStore,
    private val clock: Clock,
    /** `<VERSION_NAME>+<VERSION_CODE>`. */
    private val appVersion: String,
) : BackupRepository {
    private val data = BackupDataAccess(db, settings, clock)

    override suspend fun export(uri: String): BackupExportResult = try {
        val prefs = data.readPrefs()
        val doc = BackupCodec.buildExport(
            library = data.library(), playlists = data.playlists(),
            favoriteAlbums = data.favoriteAlbums(), favoriteArtists = data.favoriteArtists(),
            stats = stats.snapshot.first(), storedSettings = data.storedSettings(prefs),
            appVersion = appVersion, nowMs = clock.nowMs(),
        )
        files.writeUriText(uri, BackupCodec.encode(doc))
        BackupExportResult.Success
    } catch (e: CancellationException) {
        throw e
    } catch (_: BackupError.LibraryEmpty) {
        BackupExportResult.LibraryEmpty
    } catch (e: Throwable) {
        BackupExportResult.WriteFailed(e)
    }

    override suspend fun restore(uri: String): BackupRestoreResult {
        val doc = try {
            BackupCodec.parse(BackupCodec.decodeFile(files.readUriBytes(uri)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupError) {
            return e.toResult()
        } catch (e: Throwable) {
            return BackupRestoreResult.Malformed("read failed: ${e.message}")
        }
        stats.pauseRecording()
        try {
            val prefsBefore = data.readPrefs()
            val plan = try {
                RestorePlanner.plan(doc, data.restoreState(prefsBefore), clock.nowMs())
            } catch (e: BackupError) {
                return e.toResult()
            }
            try {
                data.applySettings(plan)
                data.applyDb(plan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Room rolled its transaction back itself; restore DataStore from the snapshot.
                val rolledBack = runCatching { data.writePrefs(prefsBefore) }.isSuccess
                return BackupRestoreResult.WriteFailed(e, rolledBack)
            }
            val c = plan.counts
            return BackupRestoreResult.Success(
                playlists = c.playlistsRestored,
                favorites = c.favoriteSongsRestored + c.favoriteAlbumsAndArtistsRestored,
                unmatchedSongs = c.unmatchedSongs,
                localeChanged = c.localeChanged,
            )
        } finally {
            stats.resumeRecording()
        }
    }

    private fun BackupError.toResult(): BackupRestoreResult = when (this) {
        is BackupError.InvalidJson -> BackupRestoreResult.InvalidJson
        is BackupError.NotABackup -> BackupRestoreResult.NotABackup
        is BackupError.UnsupportedVersion -> BackupRestoreResult.UnsupportedVersion(version.toIntOrNull() ?: -1)
        is BackupError.Malformed -> BackupRestoreResult.Malformed(path)
        is BackupError.LibraryEmpty -> BackupRestoreResult.LibraryEmpty
        is BackupError.NoMatchingSongs -> BackupRestoreResult.NoMatchingSongs
        is BackupError.ReadFailed -> BackupRestoreResult.Malformed(message ?: "read failed")
        is BackupError.WriteFailed -> BackupRestoreResult.WriteFailed(this, rolledBack)
    }
}
