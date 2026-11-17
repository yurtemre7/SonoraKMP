package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song

/**
 * A user's tag edit for one song, as typed into the editor. Everything is text so the form can hold half-typed
 * values; [normalized] cleans it up and [error] says whether it can be saved.
 */
data class TagEdit(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val track: String = "",
    val disc: String = "",
    val lyrics: String = "",
) {
    enum class Error { EmptyTitle, BadYear, BadTrack, BadDisc }

    /** The first problem that blocks saving, else null. Blank numbers are fine (they clear the tag). */
    fun error(): Error? = when {
        title.isBlank() -> Error.EmptyTitle
        year.isNotBlank() && yearOrNull() == null -> Error.BadYear
        track.isNotBlank() && positiveOrNull(track) == null -> Error.BadTrack
        disc.isNotBlank() && positiveOrNull(disc) == null -> Error.BadDisc
        else -> null
    }

    fun yearOrNull(): Int? = year.trim().toIntOrNull()?.takeIf { it in 1..9999 }
    fun trackOrNull(): Int? = positiveOrNull(track)
    fun discOrNull(): Int? = positiveOrNull(disc)

    /** Trimmed text; lyrics keep their inner line breaks but lose surrounding blank space. */
    fun normalized(): TagEdit = copy(
        title = title.trim(), artist = artist.trim(), album = album.trim(), albumArtist = albumArtist.trim(),
        genre = genre.trim(), year = year.trim(), track = track.trim(), disc = disc.trim(), lyrics = lyrics.trim(),
    )

    /** Whether saving would change anything compared to [song] (lyrics are not stored on the row, so they always count). */
    fun changesRow(song: Song): Boolean {
        val n = normalized()
        return n.title != song.title || n.artist != Companion.visible(song.artist, UNKNOWN_ARTIST) || n.album != Companion.visible(song.album, UNKNOWN_ALBUM) ||
            n.albumArtist != (song.albumArtist ?: "") || n.genre != (song.genre ?: "") ||
            n.yearOrNull() != song.year || n.trackOrNull() != song.trackNumber || n.discOrNull() != song.discNumber
    }

    private fun positiveOrNull(s: String): Int? = s.trim().toIntOrNull()?.takeIf { it > 0 }

    companion object {
        private fun visible(value: String, placeholder: String) = if (value == placeholder) "" else value

        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"

        fun from(song: Song, lyrics: String = "") = TagEdit(
            title = song.title,
            artist = visible(song.artist, UNKNOWN_ARTIST),
            album = visible(song.album, UNKNOWN_ALBUM),
            albumArtist = song.albumArtist.orEmpty(),
            genre = song.genre.orEmpty(),
            year = song.year?.toString().orEmpty(),
            track = song.trackNumber?.toString().orEmpty(),
            disc = song.discNumber?.toString().orEmpty(),
            lyrics = lyrics,
        )
    }
}
