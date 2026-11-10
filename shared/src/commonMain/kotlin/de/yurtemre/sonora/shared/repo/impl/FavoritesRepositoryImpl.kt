package de.yurtemre.sonora.shared.repo.impl

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import de.yurtemre.sonora.shared.db.FavoriteAlbumEntity
import de.yurtemre.sonora.shared.db.FavoriteArtistEntity
import de.yurtemre.sonora.shared.db.FavoriteSongEntity
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.FavoritesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** LIB-20 favorites; songs keep their file path for D-26/D-49 relink. */
class FavoritesRepositoryImpl(
    private val db: SonoraDatabase,
    private val clock: Clock,
) : FavoritesRepository {
    private val dao = db.favoritesDao()

    override val favoriteSongs: Flow<Map<Long, Long>> = dao.observeSongs().map { l -> l.associate { it.songId to it.favoritedAtMs } }
    override val favoriteAlbums: Flow<Map<String, Long>> = dao.observeAlbums().map { l -> l.associate { it.key to it.favoritedAtMs } }
    override val favoriteArtists: Flow<Map<String, Long>> = dao.observeArtists().map { l -> l.associate { it.key to it.favoritedAtMs } }

    private suspend inline fun <T> tx(crossinline block: suspend () -> T): T =
        db.useWriterConnection { c -> c.immediateTransaction { block() } }

    override suspend fun toggleSong(songId: Long, filePath: String) = tx {
        if (dao.getSongs().any { it.songId == songId }) dao.deleteSongs(listOf(songId))
        else dao.upsertSongs(listOf(FavoriteSongEntity(songId, filePath, clock.nowMs())))
    }

    override suspend fun setSongsFavorite(songs: Map<Long, String>, favorite: Boolean) {
        if (songs.isEmpty()) return
        val now = clock.nowMs()
        if (!favorite) return dao.deleteSongs(songs.keys.toList())
        tx {
            val existing = dao.getSongs().associateBy { it.songId }
            // Already-favorited songs keep their date.
            dao.upsertSongs(songs.map { (id, path) -> FavoriteSongEntity(id, path, existing[id]?.favoritedAtMs ?: now) })
        }
    }

    override suspend fun toggleAlbum(key: String) = tx {
        if (key in keys("favorite_albums")) dao.deleteAlbum(key) else dao.upsertAlbum(FavoriteAlbumEntity(key, clock.nowMs()))
    }

    override suspend fun toggleArtist(key: String) = tx {
        if (key in keys("favorite_artists")) dao.deleteArtist(key) else dao.upsertArtist(FavoriteArtistEntity(key, clock.nowMs()))
    }

    /** The DAO has no one-shot key getters; read them on the writer connection to stay inside the transaction. */
    private suspend fun keys(table: String): Set<String> = db.useWriterConnection { c ->
        c.usePrepared("SELECT `key` FROM $table") { st -> buildSet { while (st.step()) add(st.getText(0)) } }
    }

    override suspend fun mergeAll(songs: Map<Long, Pair<String, Long>>, albums: Map<String, Long>, artists: Map<String, Long>) = tx {
        val es = dao.getSongs().associateBy { it.songId }
        dao.upsertSongs(songs.filterKeys { it !in es }.map { (id, v) -> FavoriteSongEntity(id, v.first, v.second) })
        val ea = keys("favorite_albums"); val er = keys("favorite_artists")
        dao.upsertAlbums(albums.filterKeys { it !in ea }.map { FavoriteAlbumEntity(it.key, it.value) })
        dao.upsertArtists(artists.filterKeys { it !in er }.map { FavoriteArtistEntity(it.key, it.value) })
    }

    override suspend fun relinkByPath(currentIdsByPath: Map<String, Long>) = tx {
        val favs = dao.getSongs()
        val currentIds = currentIdsByPath.values.toHashSet()
        val taken = favs.map { it.songId }.toHashSet()
        for (f in favs) {
            if (f.songId in currentIds) continue
            val newId = currentIdsByPath[f.filePath] ?: continue
            dao.deleteSongs(listOf(f.songId))
            if (newId !in taken) { dao.upsertSongs(listOf(f.copy(songId = newId))); taken += newId }
        }
    }

    override suspend fun clearAll() = dao.clearAll()
}
