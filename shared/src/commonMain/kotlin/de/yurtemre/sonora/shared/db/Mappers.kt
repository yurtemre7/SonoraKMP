package de.yurtemre.sonora.shared.db

import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.ReplayGainInfo
import de.yurtemre.sonora.shared.model.Song

fun SongEntity.toModel(favorite: FavoriteSongEntity? = null): Song = Song(
    id = id, title = title, artist = artist, album = album, durationMs = durationMs, filePath = filePath,
    artworkPath = artworkPath, format = format, bitrate = bitrate, sampleRate = sampleRate,
    lastModifiedMs = lastModifiedMs, dateAddedMs = dateAddedMs, fileSize = fileSize, hasLyrics = hasLyrics, seedColor = seedColor,
    trackNumber = trackNumber, discNumber = discNumber, albumArtist = albumArtist, genre = genre, year = year, albumId = albumId,
    replayGain = if (rgTrackGain == null && rgAlbumGain == null && rgTrackPeak == null && rgAlbumPeak == null) null
    else ReplayGainInfo(rgTrackGain, rgAlbumGain, rgTrackPeak, rgAlbumPeak),
    replayGainScanned = rgScanned,
    durationVerified = durationVerified,
    isFavorite = favorite != null,
    favoriteDateMs = favorite?.favoritedAtMs,
)

fun Song.toEntity(): SongEntity = SongEntity(
    id = id, title = title, artist = artist, album = album, durationMs = durationMs, filePath = filePath,
    artworkPath = artworkPath, format = format, bitrate = bitrate, sampleRate = sampleRate,
    lastModifiedMs = lastModifiedMs, dateAddedMs = dateAddedMs, fileSize = fileSize, hasLyrics = hasLyrics, seedColor = seedColor,
    trackNumber = trackNumber, discNumber = discNumber, albumArtist = albumArtist, genre = genre, year = year, albumId = albumId,
    rgTrackGain = replayGain?.trackGain, rgAlbumGain = replayGain?.albumGain,
    rgTrackPeak = replayGain?.trackPeak, rgAlbumPeak = replayGain?.albumPeak, rgScanned = replayGainScanned,
    durationVerified = durationVerified,
)

/** Entries must belong to this playlist; order by position. */
fun PlaylistEntity.toModel(entries: List<PlaylistEntryEntity>): Playlist = Playlist(
    id = id, name = name, songIds = entries.sortedBy { it.position }.map { it.songId },
    coverPath = coverPath, description = description,
)

fun Playlist.toEntity(createdAtMs: Long, position: Int): PlaylistEntity =
    PlaylistEntity(id = id, name = name, description = description, coverPath = coverPath,
        createdAtMs = createdAtMs, position = position)

fun Playlist.toEntryEntities(pathOf: (Long) -> String? = { null }): List<PlaylistEntryEntity> =
    songIds.mapIndexed { i, sid -> PlaylistEntryEntity(id, i, sid, pathOf(sid)) }

/** Groups all entries by playlist and builds models in playlist order. */
fun buildPlaylists(playlists: List<PlaylistEntity>, entries: List<PlaylistEntryEntity>): List<Playlist> {
    val byId = entries.groupBy { it.playlistId }
    return playlists.map { it.toModel(byId[it.id].orEmpty()) }
}
