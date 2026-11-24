package de.yurtemre.sonora.shared.queue

import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.SongStats
import kotlin.math.pow
import kotlin.random.Random

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val ARTIST_WINDOW = 5

/** Weight of [song] in the smart shuffle: skipped songs sink, favourites and rarely played songs rise. */
fun smartWeight(song: Song, stats: SongStats?, nowMs: Long): Double {
    var w = 1.0
    val plays = stats?.plays ?: 0
    val skips = stats?.skips ?: 0
    val events = plays + skips
    if (events >= 3 && skips.toDouble() / events >= 0.5) w *= 0.3
    val last = stats?.lastPlayedAtMs
    if (last != null && nowMs - last < DAY_MS) w *= 0.5
    if (song.isFavorite) w *= 1.5
    if (plays < 2) w *= 1.5
    return w
}

object SmartShuffle {
    /**
     * Weighted shuffle (Efraimidis-Spirakis keys `u^(1/w)`, descending), followed by a pass that
     * separates back-to-back songs of the same artist where possible.
     */
    fun order(
        songs: List<Song>,
        stats: Map<Long, SongStats>,
        nowMs: Long,
        random: Random = Random,
    ): List<Song> {
        if (songs.size < 2) return songs
        val out = songs
            .map { s ->
                val w = smartWeight(s, stats[s.id], nowMs)
                val u = 1.0 - random.nextDouble() // (0, 1]
                s to u.pow(1.0 / w)
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .toMutableList()
        separateArtists(out)
        return out
    }

    private fun separateArtists(l: MutableList<Song>) {
        // Forward pass: swap in a different-artist song from the next few positions.
        for (i in 1 until l.size) {
            if (l[i].artist != l[i - 1].artist) continue
            val end = minOf(i + ARTIST_WINDOW, l.size - 1)
            val j = (i + 1..end).firstOrNull { l[it].artist != l[i - 1].artist && fits(l, i, l[it]) } ?: continue
            swap(l, i, j)
        }
        // Leftover clashes (e.g. a clustered tail): rebuild greedily, keeping the weighted order as
        // the preference but forcing the dominant artist in when it would otherwise be stranded.
        if ((1 until l.size).none { l[it].artist == l[it - 1].artist }) return
        val rest = l.toMutableList()
        l.clear()
        while (rest.isNotEmpty()) {
            val prev = l.lastOrNull()?.artist
            val counts = rest.groupingBy { it.artist }.eachCount()
            val top = counts.maxByOrNull { it.value }!!
            val pick = if (top.key != prev && top.value * 2 > rest.size) {
                rest.indexOfFirst { it.artist == top.key }
            } else {
                rest.indexOfFirst { it.artist != prev }.takeIf { it >= 0 } ?: 0
            }
            l.add(rest.removeAt(pick))
        }
    }

    /** Whether [candidate] placed at [i] does not clash with the song after it. */
    private fun fits(l: List<Song>, i: Int, candidate: Song): Boolean =
        i + 1 >= l.size || l[i + 1].artist != candidate.artist

    private fun swap(l: MutableList<Song>, a: Int, b: Int) {
        val t = l[a]; l[a] = l[b]; l[b] = t
    }
}
