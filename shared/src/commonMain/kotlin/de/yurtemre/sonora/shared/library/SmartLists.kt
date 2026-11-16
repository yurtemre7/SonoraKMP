package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song

/** Built-in, always up-to-date song lists derived from the library and play counts. */
enum class SmartListKind(val key: String) {
    RecentlyAdded("recently_added"), NeverPlayed("never_played"), MostPlayed("most_played");

    companion object {
        fun fromKey(key: String?): SmartListKind = entries.firstOrNull { it.key == key } ?: RecentlyAdded
    }
}

object SmartLists {
    const val DEFAULT_LIMIT = 50

    private val titleThenId: Comparator<Song> = compareBy<Song>({ it.titleLower }, { it.artistLower }, { it.id })

    private val Song.addedMs: Long get() = dateAddedMs?.takeIf { it > 0 } ?: lastModifiedMs ?: 0L

    /** Newest [limit] songs by date added; songs with no known date are left out. */
    fun recentlyAdded(songs: List<Song>, limit: Int = DEFAULT_LIMIT): List<Song> =
        songs.filter { it.addedMs > 0L }
            .sortedWith(compareByDescending<Song> { it.addedMs }.then(titleThenId))
            .take(limit)

    /** Songs with zero plays, in title order. */
    fun neverPlayed(songs: List<Song>, plays: (Long) -> Int): List<Song> =
        songs.filter { plays(it.id) <= 0 }.sortedWith(titleThenId)

    /** Top [limit] songs by play count (zero plays excluded); ties in title order. */
    fun mostPlayed(songs: List<Song>, limit: Int = DEFAULT_LIMIT, plays: (Long) -> Int): List<Song> =
        songs.filter { plays(it.id) > 0 }
            .sortedWith(compareByDescending<Song> { plays(it.id) }.then(titleThenId))
            .take(limit)

    fun build(kind: SmartListKind, songs: List<Song>, plays: (Long) -> Int): List<Song> = when (kind) {
        SmartListKind.RecentlyAdded -> recentlyAdded(songs)
        SmartListKind.NeverPlayed -> neverPlayed(songs, plays)
        SmartListKind.MostPlayed -> mostPlayed(songs, plays = plays)
    }
}
