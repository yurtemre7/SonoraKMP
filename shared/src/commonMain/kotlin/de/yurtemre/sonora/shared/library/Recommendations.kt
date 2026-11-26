package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot

/**
 * Home "recommended" row: songs the user likes ([WeeklyMix.likedScore]) plus unplayed or lightly played
 * songs that share an artist or album with their top songs. Songs played in the last [RECENT_EXCLUDE_MS]
 * are left out. Falls back to [SmartLists.recentlyAdded] when there is no listening history.
 * Deterministic: ties break by title, then id.
 */
object Recommendations {
    const val DEFAULT_LIMIT = 20
    const val RECENT_EXCLUDE_MS = 6 * 60 * 60 * 1000L

    private const val SEED_COUNT = 5
    private const val LIGHT_PLAYS = 1
    private const val SHARE_BOOST = 0.3

    private val titleThenId: Comparator<Song> = compareBy<Song>({ it.titleLower }, { it.id })

    private val byScore: Comparator<Pair<Song, Double>> =
        Comparator { a, b -> b.second.compareTo(a.second).let { if (it != 0) it else titleThenId.compare(a.first, b.first) } }

    fun recommended(
        songs: List<Song>,
        stats: StatsSnapshot,
        nowMs: Long,
        limit: Int = DEFAULT_LIMIT,
    ): List<Song> {
        if (limit <= 0) return emptyList()
        val unique = songs.distinctBy { it.id }
        val scored = unique.map { it to WeeklyMix.likedScore(it, stats.songs[it.id], nowMs) }
        val seeds = scored.filter { it.second > 0.0 }
            .sortedWith(byScore)
            .take(SEED_COUNT)
        if (seeds.isEmpty()) return SmartLists.recentlyAdded(unique, limit).distinctBy { it.id }

        val topScore = seeds.first().second
        val seedIds = seeds.map { it.first.id }.toSet()
        val seedArtists = seeds.map { it.first.artist }.filter { it.isNotBlank() }.toSet()
        val seedAlbums = seeds.map { it.first.album }.filter { it.isNotBlank() }.toSet()

        return scored.asSequence()
            .filter { (s, _) ->
                val last = stats.songs[s.id]?.lastPlayedAtMs
                last == null || nowMs - last > RECENT_EXCLUDE_MS
            }
            .map { (s, score) ->
                var total = score
                val plays = stats.songs[s.id]?.plays ?: 0
                if (s.id !in seedIds && plays <= LIGHT_PLAYS) {
                    if (s.artist in seedArtists) total += SHARE_BOOST * topScore
                    if (s.album in seedAlbums) total += SHARE_BOOST * topScore
                }
                s to total
            }
            .filter { it.second > 0.0 }
            .sortedWith(byScore)
            .map { it.first }
            .take(limit)
            .toList()
    }
}
