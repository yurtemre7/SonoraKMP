package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.Song

/**
 * Album grouping (LIB-10). Departs from Flutter: albums are keyed by (album name, album artist ?: primary
 * artist), and songs with an unknown artist keep their album name instead of being folded away.
 */
object AlbumGrouper {
    const val UNKNOWN_ALBUM = "Unknown Album"

    /** Display artist that owns [song]'s album: the album-artist tag, else the primary parsed artist. */
    fun albumArtistOf(song: Song): String =
        song.albumArtist?.trim()?.takeIf { it.isNotEmpty() } ?: ArtistParser.parse(song.artist).first()

    fun build(songs: List<Song>): List<AlbumGroup> {
        val buckets = LinkedHashMap<Pair<String, String>, MutableList<Song>>()
        for (song in songs) {
            val name = song.album.trim().ifEmpty { UNKNOWN_ALBUM }
            buckets.getOrPut(name.lowercase() to albumArtistOf(song).lowercase()) { ArrayList() }.add(song)
        }
        return buckets.values
            .map { AlbumGroup(it.first().album.trim().ifEmpty { UNKNOWN_ALBUM }, albumArtistOf(it.first()), sortTracks(it)) }
            .sortedBy { it.name.lowercase() }
    }

    fun sortTracks(songs: List<Song>): List<Song> = songs.sortedWith { a, b ->
        val disc = (a.discNumber ?: 1).compareTo(b.discNumber ?: 1)
        if (disc != 0) return@sortedWith disc
        val ta = a.trackNumber ?: 0
        val tb = b.trackNumber ?: 0
        val track = ta.compareTo(tb)
        if (track != 0) return@sortedWith track
        if (ta == 0 && tb == 0) a.filePath.compareTo(b.filePath) else a.titleLower.compareTo(b.titleLower)
    }

    /** `buildAlbumGroup(name, artist, songs)` lookup (audit/data §5.3). */
    fun find(name: String, artist: String, songs: List<Song>): AlbumGroup {
        val albums = build(songs)
        val nameLower = name.trim().lowercase()
        val artistLower = artist.trim().lowercase()
        return albums.firstOrNull { album ->
            album.name.lowercase() == nameLower && (album.artist.lowercase() == artistLower ||
                ArtistParser.parse(album.artist).any { it.lowercase() == artistLower })
        } ?: albums.firstOrNull { it.name.lowercase() == nameLower } ?: AlbumGroup(name, artist, emptyList())
    }
}
