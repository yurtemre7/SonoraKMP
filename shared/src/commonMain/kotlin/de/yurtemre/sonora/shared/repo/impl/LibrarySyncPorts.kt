package de.yurtemre.sonora.shared.repo.impl

import de.yurtemre.sonora.shared.audio.replaygain.ByteSource
import de.yurtemre.sonora.shared.model.AppSettings
import de.yurtemre.sonora.shared.settings.SettingsKeys as K
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first

/** Platform pieces injected into [LibraryRepositoryImpl] so it stays in commonMain. */
class LibraryPlatform(
    /** Opens a random-access source for ReplayGain reading (androidMain: FileByteSource). */
    val openByteSource: (filePath: String) -> ByteSource,
    /** LIB-22 seed colour from an artwork path; null = colorless. */
    val extractSeedColor: suspend (artworkPath: String) -> Int?,
    /** D-20 albumId → art path from background extraction (AndroidLibraryScanner.backgroundArt). */
    val backgroundArt: Flow<Map<Long, String>> = emptyFlow(),
)

/** The settings slice the library needs (LIB-04, LIB-21, LIB-22). */
interface LibrarySettings {
    suspend fun scanFolder(): Pair<String?, String?>
    suspend fun lastSyncMs(): Long?
    suspend fun postponeUntilMs(): Long
    suspend fun colorlessPaths(): Set<String>
    suspend fun recordSync(timestampMs: Long, durationMs: Long)
    suspend fun setPostponeUntil(ms: Long)
    suspend fun addColorless(paths: Set<String>)
}

fun SettingsRepository.asLibrarySettings(): LibrarySettings = object : LibrarySettings {
    private suspend fun s(): AppSettings = settings.first()
    override suspend fun scanFolder() = s().let { it.scanFolderPath to it.scanFolderUri }
    override suspend fun lastSyncMs() = s().lastSyncTimestampMs
    override suspend fun postponeUntilMs() = s().postponeSyncUntilMs
    override suspend fun colorlessPaths() = s().colorlessArtworkPaths
    override suspend fun recordSync(timestampMs: Long, durationMs: Long) = this@asLibrarySettings.recordSync(timestampMs, durationMs)
    override suspend fun setPostponeUntil(ms: Long) = setPostponeSyncUntil(ms)
    override suspend fun addColorless(paths: Set<String>) {
        if (paths.isNotEmpty()) edit { it[K.COLORLESS_ARTWORK_PATHS] = (it[K.COLORLESS_ARTWORK_PATHS] ?: emptySet()) + paths }
    }
}
