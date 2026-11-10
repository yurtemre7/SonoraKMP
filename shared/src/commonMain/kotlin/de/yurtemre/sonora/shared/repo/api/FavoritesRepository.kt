package de.yurtemre.sonora.shared.repo.api

import kotlinx.coroutines.flow.Flow

/** Favorites (LIB-20): songs by id + path, albums by `nameLower|||artistLower`, artists by `nameLower`. */
interface FavoritesRepository {
    /** songId → favoritedAtMs. */
    val favoriteSongs: Flow<Map<Long, Long>>
    /** album key → favoritedAtMs. */
    val favoriteAlbums: Flow<Map<String, Long>>
    /** artist key → favoritedAtMs. */
    val favoriteArtists: Flow<Map<String, Long>>

    suspend fun toggleSong(songId: Long, filePath: String)
    /** Bulk set uses one `now`; [songs] = songId → filePath. */
    suspend fun setSongsFavorite(songs: Map<Long, String>, favorite: Boolean)
    suspend fun toggleAlbum(key: String)
    suspend fun toggleArtist(key: String)
    /** BAK-04: merge keeping existing dates; song favorites union. */
    suspend fun mergeAll(songs: Map<Long, Pair<String, Long>>, albums: Map<String, Long>, artists: Map<String, Long>)

    /**
     * D-26 / D-49: favorites whose song id vanished are re-linked by file path.
     * [currentIdsByPath] = library filePath → current song id.
     */
    suspend fun relinkByPath(currentIdsByPath: Map<String, Long>)
    suspend fun clearAll()
}
