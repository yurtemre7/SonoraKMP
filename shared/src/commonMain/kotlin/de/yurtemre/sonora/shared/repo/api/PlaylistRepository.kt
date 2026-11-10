package de.yurtemre.sonora.shared.repo.api

import de.yurtemre.sonora.shared.model.Playlist
import kotlinx.coroutines.flow.Flow

/** Playlists (LIB-16..19). Song ids not in the library are kept in storage (LIB-16). */
interface PlaylistRepository {
    val playlists: Flow<List<Playlist>>
    fun playlist(id: String): Flow<Playlist?>

    /** LIB-16: id = epoch-ms string, bumped while colliding (D-24). */
    suspend fun create(name: String, songIds: List<Long> = emptyList(), description: String? = null): Playlist
    suspend fun delete(id: String)
    suspend fun rename(id: String, name: String)
    /** null clears. */
    suspend fun setDescription(id: String, description: String?)
    /** LIB-17: stores processed JPEG bytes, deletes the old file; null removes the cover. */
    suspend fun setCover(id: String, jpegBytes: ByteArray?)
    /** LIB-16: dedupes, appends. */
    suspend fun addSongs(id: String, songIds: List<Long>)
    /** LIB-16: removes all occurrences. */
    suspend fun removeSongs(id: String, songIds: List<Long>)
    suspend fun reorder(id: String, songIds: List<Long>)
    /** LIB-16 save queue as playlist — no dedupe. */
    suspend fun createFromQueue(name: String, songIds: List<Long>): Playlist
    /** LIB-18: returns the absolute path of `cacheDir/share/<name>.m3u`. */
    suspend fun exportM3u(id: String): String
    /** LIB-19: null when nothing matched. */
    suspend fun importM3u(uri: String, fileName: String, description: String): Playlist?
    /** D-49: rewrite entries whose song id changed on re-index (old → new, matched by file path). */
    suspend fun relinkSongIds(idRemap: Map<Long, Long>)
    /** BAK-04 merge (backup wins by id). */
    suspend fun upsertAll(playlists: List<Playlist>)
    suspend fun clearAll()
}
