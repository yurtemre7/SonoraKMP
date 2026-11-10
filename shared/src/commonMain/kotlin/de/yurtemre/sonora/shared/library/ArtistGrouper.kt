package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.AlbumGroup
import de.yurtemre.sonora.shared.model.ArtistGroup
import de.yurtemre.sonora.shared.model.Song

/** Artist grouping (LIB-11, audit/data §5.3). */
object ArtistGrouper {
    fun build(songs: List<Song>, albums: List<AlbumGroup>, localImages: Map<String, String>? = null): List<ArtistGroup> {
        val names = LinkedHashMap<String, String>()
        val songsByArtist = HashMap<String, MutableList<Song>>()
        for (song in songs) {
            for (artist in ArtistParser.parse(song.artist)) {
                val lower = artist.lowercase()
                names.getOrPut(lower) { artist }
                songsByArtist.getOrPut(lower) { ArrayList() }.add(song)
            }
        }
        val albumArtists = albums.map { album ->
            val set = HashSet<String>()
            album.songs.forEach { s -> ArtistParser.parse(s.artist).forEach { set += it.lowercase() } }
            set += album.artist.lowercase()
            set
        }
        return names.map { (lower, display) ->
            ArtistGroup(
                name = display,
                songs = songsByArtist.getValue(lower),
                albums = albums.filterIndexed { i, _ -> lower in albumArtists[i] },
                localImagePath = localImages?.get(lower),
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** `buildArtistGroup(name, songs)` lookup. */
    fun find(name: String, songs: List<Song>, localImages: Map<String, String>? = null): ArtistGroup {
        val lower = name.trim().lowercase()
        return build(songs, AlbumGrouper.build(songs), localImages).firstOrNull { it.name.lowercase() == lower }
            ?: ArtistGroup(name, emptyList(), emptyList(), null)
    }
}
