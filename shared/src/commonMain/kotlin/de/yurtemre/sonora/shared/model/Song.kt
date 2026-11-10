package de.yurtemre.sonora.shared.model

import kotlinx.serialization.Serializable

/** A library track (SPEC §5.1, audit/data §1). Favorite fields are joined from `favorite_songs`. */
@Serializable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val filePath: String,
    val artworkPath: String? = null,
    val format: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val lastModifiedMs: Long? = null,
    val fileSize: Long? = null,
    val hasLyrics: Boolean = false,
    val seedColor: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    /** Album-artist tag; groups albums when present (departs from Flutter). */
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val albumId: Long? = null,
    val replayGain: ReplayGainInfo? = null,
    val replayGainScanned: Boolean = false,
    val isFavorite: Boolean = false,
    val favoriteDateMs: Long? = null,
) {
    val titleLower: String by lazy { title.lowercase() }
    val artistLower: String by lazy { artist.lowercase() }
    val albumLower: String by lazy { album.lowercase() }
}
