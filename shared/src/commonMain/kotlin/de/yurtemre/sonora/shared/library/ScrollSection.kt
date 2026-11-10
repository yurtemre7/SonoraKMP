package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.model.SongSort

/** Fast-scroller bubble label (UI-07). UI renders [Minutes] / [PlayCount] with localized strings. */
sealed interface ScrollSection {
    data class Letter(val letter: Char) : ScrollSection
    data class Minutes(val minutes: Long) : ScrollSection
    data class PlayCount(val count: Int) : ScrollSection

    companion object {
        /** First char uppercased if A–Z, else `#`. */
        fun letterOf(text: String): Letter {
            val c = text.firstOrNull()?.uppercaseChar()
            return Letter(if (c != null && c in 'A'..'Z') c else '#')
        }

        fun forSong(song: Song, sort: SongSort, playCount: (Song) -> Int = { 0 }): ScrollSection = when (sort) {
            SongSort.Artist -> letterOf(song.artist)
            SongSort.Duration -> Minutes(song.durationMs / 60_000)
            SongSort.Plays -> PlayCount(playCount(song))
            else -> letterOf(song.title)
        }
    }
}
