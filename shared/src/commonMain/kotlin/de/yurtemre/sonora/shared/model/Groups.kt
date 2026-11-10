package de.yurtemre.sonora.shared.model

/**
 * Album grouping with identity semantics (SPEC §5.1, audit/data §5.2).
 * [key] = "nameLower|||artistLower" (also the favorite_albums key).
 */
class AlbumGroup(val name: String, val artist: String, val songs: List<Song>) {
    fun key(): String = albumKey(name, artist)

    companion object {
        fun albumKey(name: String, artist: String): String = "${name.lowercase()}|||${artist.lowercase()}"
    }
}

/** Artist grouping with identity semantics (SPEC §5.1, audit/data §5.3). */
class ArtistGroup(
    val name: String,
    val songs: List<Song>,
    val albums: List<AlbumGroup>,
    val localImagePath: String?,
) {
    fun key(): String = name.lowercase()
}
