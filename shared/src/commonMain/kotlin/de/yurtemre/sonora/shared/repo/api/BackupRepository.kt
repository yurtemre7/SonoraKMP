package de.yurtemre.sonora.shared.repo.api

/** Backup v1 export/import (BAK-01..06, SPEC §5.5). */
interface BackupRepository {
    /** BAK-01: writes pretty JSON to [uri]. */
    suspend fun export(uri: String): BackupExportResult
    /** BAK-02..05: parse, match, plan and apply in one transaction with rollback. */
    suspend fun restore(uri: String): BackupRestoreResult
}

sealed interface BackupExportResult {
    data object Success : BackupExportResult
    data object LibraryEmpty : BackupExportResult
    data class WriteFailed(val error: Throwable) : BackupExportResult
}

sealed interface BackupRestoreResult {
    data class Success(
        val playlists: Int,
        val favorites: Int,
        val unmatchedSongs: Int,
        val localeChanged: Boolean,
    ) : BackupRestoreResult
    data object InvalidJson : BackupRestoreResult
    data object NotABackup : BackupRestoreResult
    data class UnsupportedVersion(val version: Int) : BackupRestoreResult
    data class Malformed(val reason: String) : BackupRestoreResult
    data object LibraryEmpty : BackupRestoreResult
    data object NoMatchingSongs : BackupRestoreResult
    data class WriteFailed(val error: Throwable, val rolledBack: Boolean) : BackupRestoreResult
}
