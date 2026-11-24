package de.yurtemre.sonora.shared.jam

import de.yurtemre.sonora.shared.backup.SongMatcher
import de.yurtemre.sonora.shared.model.Song
import kotlin.math.abs

/** Finds the local library song for a Jam song: title+artist (normalised like backup restore), duration within 2 s. */
object JamSongMatcher {
    fun match(jam: JamSong, library: List<Song>): Song? {
        val title = SongMatcher.norm(jam.title)
        val artist = SongMatcher.norm(jam.artist)
        val album = SongMatcher.norm(jam.album)
        return library
            .filter {
                SongMatcher.norm(it.title) == title && SongMatcher.norm(it.artist) == artist &&
                    abs(it.durationMs - jam.durationMs) <= SongMatcher.DURATION_TOLERANCE_MS
            }
            .minWithOrNull(
                compareBy<Song> { if (SongMatcher.norm(it.album) == album) 0 else 1 }
                    .thenBy { abs(it.durationMs - jam.durationMs) }
                    .thenBy { it.filePath }
                    .thenBy { it.id },
            )
    }
}
