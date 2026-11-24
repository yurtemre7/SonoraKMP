package de.yurtemre.sonora.shared.queue

import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.SongStats
import kotlin.math.ln
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
     * Weighted shuffle (Efraimidis-Spirakis keys `u^(1/w)`, descending, taken in log space as `ln(u) / w` so tiny
     * weights never underflow to ties), followed by a pass that
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
                s to ln(u) / w
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
        greedyRebuild(l)
    }

    /**
     * Each step takes the earliest remaining song whose artist differs from the previous one (or the earliest at all
     * when only that artist is left), except that an artist holding more than half of the remaining songs goes
     * first whenever it may. Incremental: per-artist queues of positions, a min-heap of each artist's earliest
     * position and per-count artist buckets for the majority check, so O(n log k) for n songs of k artists.
     */
    private fun greedyRebuild(l: MutableList<Song>) {
        val rest = l.toList()
        val artistIds = HashMap<String, Int>()
        val artistOf = IntArray(rest.size) { i -> artistIds.getOrPut(rest[i].artist) { artistIds.size } }
        val k = artistIds.size
        val positions = Array(k) { ArrayDeque<Int>() }
        rest.indices.forEach { positions[artistOf[it]].addLast(it) }
        val count = IntArray(k) { positions[it].size }
        // byCount[c]: artists with exactly c songs left; maxCount only ever falls.
        val byCount = HashMap<Int, MutableSet<Int>>()
        for (a in 0 until k) byCount.getOrPut(count[a]) { LinkedHashSet() }.add(a)
        var maxCount = count.maxOrNull() ?: 0
        val heads = IntHeap(rest.size)
        for (a in 0 until k) heads.push(positions[a].first())
        var left = rest.size
        l.clear()
        var prev = -1

        fun earliestExcept(excluded: Int): Int? {
            // Heap entries are positions; one is current when it is still its artist's earliest remaining song.
            fun clean() { while (!heads.isEmpty() && positions[artistOf[heads.peek()]].firstOrNull() != heads.peek()) heads.pop() }
            clean()
            if (heads.isEmpty()) return null
            val top = heads.peek()
            if (artistOf[top] != excluded) return top
            heads.pop()
            clean()
            val second = if (heads.isEmpty()) null else heads.peek()
            heads.push(top)
            return second
        }

        while (left > 0) {
            while (maxCount > 0 && byCount[maxCount].isNullOrEmpty()) maxCount--
            val majority = if (maxCount * 2 > left) byCount.getValue(maxCount).first() else -1
            val pos = if (majority >= 0 && majority != prev) {
                positions[majority].first()
            } else {
                earliestExcept(prev) ?: earliestExcept(-1)!!
            }
            val a = artistOf[pos]
            positions[a].removeFirst()
            byCount.getValue(count[a]).remove(a)
            count[a]--
            if (count[a] > 0) {
                byCount.getOrPut(count[a]) { LinkedHashSet() }.add(a)
                heads.push(positions[a].first())
            }
            l.add(rest[pos])
            left--
            prev = a
        }
    }

    /** Whether [candidate] placed at [i] does not clash with the song after it. */
    private fun fits(l: List<Song>, i: Int, candidate: Song): Boolean =
        i + 1 >= l.size || l[i + 1].artist != candidate.artist

    private fun swap(l: MutableList<Song>, a: Int, b: Int) {
        val t = l[a]; l[a] = l[b]; l[b] = t
    }
}

/** A binary min-heap of ints (positions). */
private class IntHeap(capacity: Int) {
    private var a = IntArray(maxOf(capacity, 1))
    private var size = 0

    fun isEmpty() = size == 0
    fun peek(): Int = a[0]

    fun push(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        var i = size++
        a[i] = v
        while (i > 0) {
            val p = (i - 1) / 2
            if (a[p] <= a[i]) break
            val t = a[p]; a[p] = a[i]; a[i] = t
            i = p
        }
    }

    fun pop(): Int {
        val top = a[0]
        a[0] = a[--size]
        var i = 0
        while (true) {
            val l = 2 * i + 1
            val r = l + 1
            var m = i
            if (l < size && a[l] < a[m]) m = l
            if (r < size && a[r] < a[m]) m = r
            if (m == i) break
            val t = a[m]; a[m] = a[i]; a[i] = t
            i = m
        }
        return top
    }
}
