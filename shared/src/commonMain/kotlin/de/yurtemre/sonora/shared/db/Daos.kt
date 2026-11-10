package de.yurtemre.sonora.shared.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs")
    suspend fun getAll(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE file_path = :path")
    suspend fun getByPath(path: String): SongEntity?

    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE songs SET seed_color = :color WHERE id = :id")
    suspend fun updateSeedColor(id: Long, color: Int?)

    @Query("UPDATE songs SET artwork_path = :path WHERE album_id = :albumId")
    suspend fun updateArtworkForAlbum(albumId: Long, path: String?)

    @Query("DELETE FROM songs")
    suspend fun clearAll()

    /** Replaces the whole cache atomically (LIB-04). */
    @Transaction
    suspend fun replaceAll(songs: List<SongEntity>) {
        clearAll()
        upsertAll(songs)
    }
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY position, created_at_ms")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_entries ORDER BY playlist_id, position")
    fun observeEntries(): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlists ORDER BY position, created_at_ms")
    suspend fun getPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylist(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :playlistId ORDER BY position")
    suspend fun getEntries(playlistId: String): List<PlaylistEntryEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlists")
    suspend fun maxPosition(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Upsert
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE playlist_id = :playlistId")
    suspend fun deleteEntries(playlistId: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE playlists SET description = :description WHERE id = :id")
    suspend fun updateDescription(id: String, description: String?)

    @Query("UPDATE playlists SET cover_path = :coverPath WHERE id = :id")
    suspend fun updateCover(id: String, coverPath: String?)

    @Query("UPDATE playlist_entries SET song_id = :newId WHERE song_id = :oldId")
    suspend fun relinkSongId(oldId: Long, newId: Long)

    /** Replaces the ordered song list of a playlist (positions 0..n-1). */
    @Transaction
    suspend fun setEntries(playlistId: String, songIds: List<Long>, pathOf: (Long) -> String? = { null }) {
        deleteEntries(playlistId)
        insertEntries(songIds.mapIndexed { i, id -> PlaylistEntryEntity(playlistId, i, id, pathOf(id)) })
    }

    @Transaction
    suspend fun upsertWithEntries(playlist: PlaylistEntity, entries: List<PlaylistEntryEntity>) {
        upsertPlaylist(playlist)
        deleteEntries(playlist.id)
        insertEntries(entries)
    }

    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    @Query("DELETE FROM playlist_entries")
    suspend fun clearEntries()

    @Transaction
    suspend fun clearAll() {
        clearEntries()
        clearPlaylists()
    }
}

@Dao
interface FavoritesDao {
    @Query("SELECT * FROM favorite_songs")
    fun observeSongs(): Flow<List<FavoriteSongEntity>>

    @Query("SELECT * FROM favorite_albums")
    fun observeAlbums(): Flow<List<FavoriteAlbumEntity>>

    @Query("SELECT * FROM favorite_artists")
    fun observeArtists(): Flow<List<FavoriteArtistEntity>>

    @Query("SELECT * FROM favorite_songs")
    suspend fun getSongs(): List<FavoriteSongEntity>

    @Upsert suspend fun upsertSongs(items: List<FavoriteSongEntity>)
    @Upsert suspend fun upsertAlbum(item: FavoriteAlbumEntity)
    @Upsert suspend fun upsertArtist(item: FavoriteArtistEntity)
    @Upsert suspend fun upsertAlbums(items: List<FavoriteAlbumEntity>)
    @Upsert suspend fun upsertArtists(items: List<FavoriteArtistEntity>)

    @Query("DELETE FROM favorite_songs WHERE song_id IN (:ids)")
    suspend fun deleteSongs(ids: List<Long>)

    @Query("DELETE FROM favorite_albums WHERE `key` = :key")
    suspend fun deleteAlbum(key: String)

    @Query("DELETE FROM favorite_artists WHERE `key` = :key")
    suspend fun deleteArtist(key: String)

    /** Bulk favorite/unfavorite with one timestamp (LIB-20). */
    @Transaction
    suspend fun setSongsFavorite(items: List<Pair<Long, String>>, favorite: Boolean, nowMs: Long) {
        if (favorite) upsertSongs(items.map { FavoriteSongEntity(it.first, it.second, nowMs) })
        else deleteSongs(items.map { it.first })
    }

    @Query("DELETE FROM favorite_songs") suspend fun clearSongs()
    @Query("DELETE FROM favorite_albums") suspend fun clearAlbums()
    @Query("DELETE FROM favorite_artists") suspend fun clearArtists()

    @Transaction
    suspend fun clearAll() {
        clearSongs(); clearAlbums(); clearArtists()
    }
}

@Dao
interface ArtistImageDao {
    @Query("SELECT * FROM artist_images")
    fun observeAll(): Flow<List<ArtistImageEntity>>

    @Upsert suspend fun upsertAll(items: List<ArtistImageEntity>)

    @Query("DELETE FROM artist_images")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(items: List<ArtistImageEntity>) {
        clearAll(); upsertAll(items)
    }
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM song_stats") fun observeSongStats(): Flow<List<SongStatsEntity>>
    @Query("SELECT * FROM playlist_stats") fun observePlaylistStats(): Flow<List<PlaylistStatsEntity>>
    @Query("SELECT * FROM weekday_stats ORDER BY day") fun observeWeekdayStats(): Flow<List<WeekdayStatsEntity>>
    @Query("SELECT * FROM stats_summary WHERE id = 0") fun observeSummary(): Flow<StatsSummaryEntity?>

    @Query("SELECT * FROM song_stats WHERE song_id = :songId") suspend fun getSongStats(songId: Long): SongStatsEntity?
    @Query("SELECT * FROM song_stats") suspend fun getAllSongStats(): List<SongStatsEntity>
    @Query("SELECT * FROM playlist_stats WHERE playlist_id = :id") suspend fun getPlaylistStats(id: String): PlaylistStatsEntity?
    @Query("SELECT * FROM weekday_stats WHERE day = :day") suspend fun getWeekday(day: Int): WeekdayStatsEntity?
    @Query("SELECT * FROM stats_summary WHERE id = 0") suspend fun getSummary(): StatsSummaryEntity?

    @Upsert suspend fun upsertSongStats(items: List<SongStatsEntity>)
    @Upsert suspend fun upsertPlaylistStats(items: List<PlaylistStatsEntity>)
    @Upsert suspend fun upsertWeekdayStats(items: List<WeekdayStatsEntity>)
    @Upsert suspend fun upsertSummary(item: StatsSummaryEntity)

    @Query("DELETE FROM song_stats WHERE song_id IN (:ids)") suspend fun deleteSongStats(ids: List<Long>)

    /** Records one play (song + weekday + optional playlist) atomically. */
    @Transaction
    suspend fun recordPlay(songId: Long, filePath: String?, nowMs: Long, weekday: Int, playlistId: String?) {
        val s = getSongStats(songId) ?: SongStatsEntity(songId, filePath)
        upsertSongStats(listOf(s.copy(plays = s.plays + 1, lastPlayedAtMs = nowMs, filePath = filePath ?: s.filePath)))
        val w = getWeekday(weekday) ?: WeekdayStatsEntity(weekday)
        upsertWeekdayStats(listOf(w.copy(plays = w.plays + 1)))
        if (playlistId != null) {
            val p = getPlaylistStats(playlistId) ?: PlaylistStatsEntity(playlistId)
            upsertPlaylistStats(listOf(p.copy(plays = p.plays + 1)))
        }
        val sum = getSummary() ?: StatsSummaryEntity()
        if (sum.firstPlayedSongId == -1L) upsertSummary(sum.copy(firstPlayedSongId = songId, firstPlayedDate = nowMs))
    }

    @Transaction
    suspend fun recordSkip(songId: Long, filePath: String?) {
        val s = getSongStats(songId) ?: SongStatsEntity(songId, filePath)
        upsertSongStats(listOf(s.copy(skips = s.skips + 1)))
    }

    @Transaction
    suspend fun recordRestart(songId: Long, filePath: String?) {
        val s = getSongStats(songId) ?: SongStatsEntity(songId, filePath)
        upsertSongStats(listOf(s.copy(restarts = s.restarts + 1)))
    }

    @Transaction
    suspend fun updateSummary(transform: (StatsSummaryEntity) -> StatsSummaryEntity) {
        upsertSummary(transform(getSummary() ?: StatsSummaryEntity()))
    }

    @Query("DELETE FROM song_stats") suspend fun clearSongStats()
    @Query("DELETE FROM playlist_stats") suspend fun clearPlaylistStats()
    @Query("DELETE FROM weekday_stats") suspend fun clearWeekdayStats()
    @Query("DELETE FROM stats_summary") suspend fun clearSummary()

    @Transaction
    suspend fun clearAll() {
        clearSongStats(); clearPlaylistStats(); clearWeekdayStats(); clearSummary()
    }
}

/** D-26/D-49: re-points favorites, playlist entries and stats to new song ids by file path. */
@Dao
interface RelinkDao {
    @Query("SELECT f.song_id AS old_id, s.id AS new_id FROM favorite_songs f JOIN songs s ON s.file_path = f.file_path WHERE f.song_id != s.id")
    suspend fun favoriteRelinks(): List<IdRelink>

    @Query("SELECT DISTINCT e.song_id AS old_id, s.id AS new_id FROM playlist_entries e JOIN songs s ON s.file_path = e.file_path WHERE e.song_id != s.id")
    suspend fun playlistRelinks(): List<IdRelink>

    @Query("SELECT t.song_id AS old_id, s.id AS new_id FROM song_stats t JOIN songs s ON s.file_path = t.file_path WHERE t.song_id != s.id")
    suspend fun statsRelinks(): List<IdRelink>

    @Query("UPDATE OR REPLACE favorite_songs SET song_id = :newId WHERE song_id = :oldId")
    suspend fun relinkFavorite(oldId: Long, newId: Long)

    @Query("UPDATE playlist_entries SET song_id = :newId WHERE song_id = :oldId AND file_path IN (SELECT file_path FROM songs WHERE id = :newId)")
    suspend fun relinkPlaylistEntry(oldId: Long, newId: Long)

    @Query("UPDATE OR REPLACE song_stats SET song_id = :newId WHERE song_id = :oldId")
    suspend fun relinkStats(oldId: Long, newId: Long)

    /** Fills missing paths of playlist entries / stats from the current library. */
    @Query("UPDATE playlist_entries SET file_path = (SELECT file_path FROM songs WHERE songs.id = playlist_entries.song_id) WHERE file_path IS NULL")
    suspend fun backfillPlaylistPaths()

    @Query("UPDATE song_stats SET file_path = (SELECT file_path FROM songs WHERE songs.id = song_stats.song_id) WHERE file_path IS NULL")
    suspend fun backfillStatsPaths()

    @Transaction
    suspend fun relinkAll() {
        favoriteRelinks().forEach { relinkFavorite(it.oldId, it.newId) }
        playlistRelinks().forEach { relinkPlaylistEntry(it.oldId, it.newId) }
        statsRelinks().forEach { relinkStats(it.oldId, it.newId) }
        backfillPlaylistPaths()
        backfillStatsPaths()
    }
}
