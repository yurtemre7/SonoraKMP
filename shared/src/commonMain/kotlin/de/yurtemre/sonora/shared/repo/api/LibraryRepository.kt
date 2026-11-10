package de.yurtemre.sonora.shared.repo.api

import de.yurtemre.sonora.shared.model.Song
import kotlinx.coroutines.flow.Flow

/** Song library cache in Room `songs` (SPEC §5.2, LIB-03..07). */
interface LibraryRepository {
    /** All songs with favorite fields joined (LIB-20). */
    val songs: Flow<List<Song>>
    /** LIB-07: artist nameLower → image path. */
    val artistImages: Flow<Map<String, String>>
    val isSyncing: Flow<Boolean>

    suspend fun getSong(id: Long): Song?
    suspend fun getSongByPath(filePath: String): Song?

    /**
     * LIB-04 incremental sync via [LibraryScanner]; when MediaStore ids change, performs the D-49 relink
     * (playlists, stats, favorites by file path) in the same transaction. Errors keep the cache (D-19).
     */
    suspend fun sync(): SyncOutcome

    /** LIB-05: patch art paths of rows after background extraction (D-20). */
    suspend fun updateArtwork(artworkBySongId: Map<Long, String?>)
    suspend fun updateSeedColor(songId: Long, seedColor: Int?)
    /** PLAY-09 ReplayGain scan results. */
    suspend fun updateReplayGain(songs: List<Song>)
    suspend fun clearAll()
}

sealed interface SyncOutcome {
    /** [idRemap] old → new song ids applied by the D-49 relink. */
    data class Success(val songCount: Int, val durationMs: Long, val idRemap: Map<Long, Long>) : SyncOutcome
    data object FolderMissing : SyncOutcome
    data class Failed(val error: Throwable) : SyncOutcome
}
