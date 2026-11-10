package de.yurtemre.sonora.shared.backup

/** Why a backup could not be exported or restored (audit/data §12.3, BAK-02). Nothing is written except for [WriteFailed]. */
sealed class BackupError(message: String) : Exception(message) {
    class InvalidJson(message: String) : BackupError(message)
    class NotABackup : BackupError("Missing \"format\": \"sonora-backup\"")
    class UnsupportedVersion(val version: String) : BackupError("Unsupported backup version: $version")
    class Malformed(val path: String) : BackupError(path)
    class LibraryEmpty : BackupError("The library is empty; scan the music folder first")
    class NoMatchingSongs : BackupError("None of the backed-up songs are in this library")
    class ReadFailed(message: String) : BackupError(message)
    class WriteFailed(message: String, val rolledBack: Boolean) : BackupError(message)
}
