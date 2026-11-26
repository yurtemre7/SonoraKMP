package de.yurtemre.sonora.shared.stats

import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot

/**
 * Stats at a glance for Home. [listenedMs] is an estimate (plays x song duration), as no listening time is stored.
 * [topArtist] groups by the raw `Song.artist` string; [favorite] is the most played song, else the first favourite.
 */
data class HomeGlance(
    val songCount: Int,
    val plays: Int,
    val listenedMs: Long,
    val topArtist: String?,
    val favorite: Song?,
    val favoritePlays: Int,
) {
    companion object {
        fun from(s: StatsSnapshot, songs: List<Song>): HomeGlance {
            var plays = 0
            var listened = 0L
            val perArtist = HashMap<String, Int>()
            for (song in songs) {
                val p = StatsAggregates.plays(s, song.id)
                if (p <= 0) continue
                plays += p
                listened += p.toLong() * song.durationMs.coerceAtLeast(0)
                if (song.artist.isNotBlank()) perArtist[song.artist] = (perArtist[song.artist] ?: 0) + p
            }
            val topArtist = perArtist.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()?.key
            val top = StatsAggregates.topSongs(s, songs, 1).firstOrNull()
            val favorite = top?.item ?: songs.firstOrNull { it.isFavorite }
            return HomeGlance(
                songCount = songs.size,
                plays = plays,
                listenedMs = listened,
                topArtist = topArtist,
                favorite = favorite,
                favoritePlays = top?.plays ?: 0,
            )
        }
    }
}
