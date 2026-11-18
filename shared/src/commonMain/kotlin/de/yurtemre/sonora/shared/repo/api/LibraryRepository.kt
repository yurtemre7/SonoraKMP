package de.yurtemre.sonora.shared.repo.api

import de.yurtemre.sonora.shared.library.TagEdit
import de.yurtemre.sonora.shared.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Song library cache in Room `songs` (SPEC §5.2, LIB-03..07). */
interface LibraryRepository {
    /** All songs with favorite fields joined (LIB-20). */
    val songs: Flow<List<Song>>
    /** LIB-07: artist nameLower → image path. */
    val artistImages: Flow<Map<String, String>>
    val isSyncing: Flow<Boolean>
    /** Progress of the background enrichment after a sync (artwork, lyrics, …); null when idle. */
    val backgroundWork: StateFlow<BackgroundWork?>
    /** Every stage running right now (several run in parallel), lowest stage first; empty when idle. */
    val backgroundTasks: StateFlow<List<BackgroundWork>> get() = MutableStateFlow(emptyList())

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
    /** Writes an already-saved tag edit to the song's row so the library shows it without a rescan. */
    suspend fun updateTags(songId: Long, edit: TagEdit)
    suspend fun clearAll()
}

sealed interface SyncOutcome {
    /** [idRemap] old → new song ids applied by the D-49 relink. */
    data class Success(val songCount: Int, val durationMs: Long, val idRemap: Map<Long, Long>) : SyncOutcome
    data object FolderMissing : SyncOutcome
    data class Failed(val error: Throwable) : SyncOutcome
}

/**
 * One running stage with item progress ([done] of [total]); `total == 0` means the amount is unknown
 * (indeterminate), e.g. while the folder is being listed.
 */
data class BackgroundWork(val stage: Stage, val done: Int, val total: Int) {
    val isIndeterminate: Boolean get() = total <= 0

    /** Declaration order is the display priority: the first running stage is the headline. */
    enum class Stage { Scanning, Indexing, Artwork, Lyrics, ArtistImages, ReplayGain, Durations, Colors }
}
