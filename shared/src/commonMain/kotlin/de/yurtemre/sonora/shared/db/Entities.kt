package de.yurtemre.sonora.shared.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Library cache row (SPEC §5.2 `songs`). */
@Entity(tableName = "songs", indices = [Index(value = ["file_path"], unique = true)])
data class SongEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "artwork_path") val artworkPath: String? = null,
    val format: String? = null,
    val bitrate: Int? = null,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int? = null,
    @ColumnInfo(name = "last_modified_ms") val lastModifiedMs: Long? = null,
    @ColumnInfo(name = "date_added_ms") val dateAddedMs: Long? = null,
    @ColumnInfo(name = "file_size") val fileSize: Long? = null,
    @ColumnInfo(name = "has_lyrics") val hasLyrics: Boolean = false,
    @ColumnInfo(name = "seed_color") val seedColor: Int? = null,
    @ColumnInfo(name = "track_number") val trackNumber: Int? = null,
    @ColumnInfo(name = "disc_number") val discNumber: Int? = null,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    @ColumnInfo(name = "album_id") val albumId: Long? = null,
    @ColumnInfo(name = "rg_track_gain") val rgTrackGain: Double? = null,
    @ColumnInfo(name = "rg_album_gain") val rgAlbumGain: Double? = null,
    @ColumnInfo(name = "rg_track_peak") val rgTrackPeak: Double? = null,
    @ColumnInfo(name = "rg_album_peak") val rgAlbumPeak: Double? = null,
    @ColumnInfo(name = "rg_scanned") val rgScanned: Boolean = false,
    /** Duration re-measured from the file (MP3 frame walk); reset when path/size/mtime change. */
    @ColumnInfo(name = "duration_verified", defaultValue = "0") val durationVerified: Boolean = false,
)

/** Song favorite; [filePath] enables relink when MediaStore ids change (D-26/D-49). */
@Entity(tableName = "favorite_songs", indices = [Index("file_path")])
data class FavoriteSongEntity(
    @PrimaryKey @ColumnInfo(name = "song_id") val songId: Long,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "favorited_at_ms") val favoritedAtMs: Long,
)

@Entity(tableName = "favorite_albums")
data class FavoriteAlbumEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "favorited_at_ms") val favoritedAtMs: Long,
)

@Entity(tableName = "favorite_artists")
data class FavoriteArtistEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "favorited_at_ms") val favoritedAtMs: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "cover_path") val coverPath: String? = null,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    val position: Int,
)

/** Ordered playlist slot; [filePath] (nullable: unknown for ids missing from library) enables D-49 relink. */
@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlist_id", "position"],
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlist_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("song_id"), Index("file_path")],
)
data class PlaylistEntryEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val position: Int,
    @ColumnInfo(name = "song_id") val songId: Long,
    @ColumnInfo(name = "file_path") val filePath: String? = null,
)

@Entity(tableName = "artist_images")
data class ArtistImageEntity(
    @PrimaryKey @ColumnInfo(name = "artist_key") val artistKey: String,
    val path: String,
)

@Entity(tableName = "song_stats", indices = [Index("file_path")])
data class SongStatsEntity(
    @PrimaryKey @ColumnInfo(name = "song_id") val songId: Long,
    @ColumnInfo(name = "file_path") val filePath: String? = null,
    val plays: Int = 0,
    val skips: Int = 0,
    val restarts: Int = 0,
    @ColumnInfo(name = "last_played_at_ms") val lastPlayedAtMs: Long? = null,
)

@Entity(tableName = "playlist_stats")
data class PlaylistStatsEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: String,
    val plays: Int = 0,
)

/** [day] 0 = Sunday .. 6 = Saturday. */
@Entity(tableName = "weekday_stats")
data class WeekdayStatsEntity(
    @PrimaryKey val day: Int,
    val plays: Int = 0,
)

/** Single row, [id] = 0. */
@Entity(tableName = "stats_summary")
data class StatsSummaryEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "complete_song_listens") val completeSongListens: Int = 0,
    @ColumnInfo(name = "first_played_song_id") val firstPlayedSongId: Long = -1,
    @ColumnInfo(name = "first_played_date") val firstPlayedDate: Long? = null,
    @ColumnInfo(name = "shuffle_session_starts") val shuffleSessionStarts: Int = 0,
)

/** Relink projection: old id → current id for the same path. */
data class IdRelink(
    @ColumnInfo(name = "old_id") val oldId: Long,
    @ColumnInfo(name = "new_id") val newId: Long,
)
