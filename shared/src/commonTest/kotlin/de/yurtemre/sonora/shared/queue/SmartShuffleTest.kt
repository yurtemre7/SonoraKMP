package de.yurtemre.sonora.shared.queue

import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.SongStats
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartShuffleTest {
    private val now = 1_000_000_000_000L

    private fun song(id: Long, artist: String = "A", fav: Boolean = false) =
        Song(id = id, title = "t$id", artist = artist, album = "al", durationMs = 1000, filePath = "/p/$id", isFavorite = fav)

    @Test
    fun weightDefaultsToOneWithoutStats() {
        assertEquals(1.5, smartWeight(song(1), null, now))
        assertEquals(1.0, smartWeight(song(1), SongStats(plays = 5), now))
    }

    @Test
    fun skipHeavySongsWeighLess() {
        assertEquals(0.3, smartWeight(song(1), SongStats(plays = 2, skips = 4), now), 1e-9)
        assertEquals(1.0, smartWeight(song(1), SongStats(plays = 5, skips = 1), now), 1e-9)
        assertEquals(1.5, smartWeight(song(1), SongStats(plays = 0, skips = 1), now), 1e-9)
    }

    @Test
    fun recentlyPlayedWeighsHalf() {
        val s = SongStats(plays = 5, lastPlayedAtMs = now - 3_600_000L)
        assertEquals(0.5, smartWeight(song(1), s, now), 1e-9)
    }

    @Test
    fun favouriteWeighsOneAndHalf() {
        assertEquals(1.5, smartWeight(song(1, fav = true), SongStats(plays = 5), now), 1e-9)
    }

    @Test
    fun orderKeepsAllSongsExactlyOnce() {
        val songs = (1L..30L).map { song(it, "X$it") }
        val out = SmartShuffle.order(songs, emptyMap(), now, Random(1))
        assertEquals(songs.map { it.id }.sorted(), out.map { it.id }.sorted())
    }

    @Test
    fun orderIsPlainShuffleWhenAllWeightsEqual() {
        val songs = (1L..30L).map { song(it, "X$it") }
        val stats = songs.associate { it.id to SongStats(plays = 5) }
        val out = SmartShuffle.order(songs, stats, now, Random(7))
        assertEquals(songs.map { it.id }.toSet(), out.map { it.id }.toSet())
        assertEquals(songs.size, out.size)
        assertTrue(out.map { it.id } != songs.map { it.id })
    }

    @Test
    fun orderAvoidsSameArtistBackToBack() {
        val songs = listOf(song(1, "A"), song(2, "A"), song(3, "A"), song(4, "B"), song(5, "B"))
        repeat(50) { seed ->
            val out = SmartShuffle.order(songs, emptyMap(), now, Random(seed))
            out.zipWithNext().forEach { (a, b) -> assertTrue(a.artist != b.artist, "seed $seed: ${out.map { it.artist }}") }
        }
    }

    @Test
    fun orderHandlesEmptyAndSingle() {
        assertEquals(emptyList(), SmartShuffle.order(emptyList(), emptyMap(), now))
        val one = listOf(song(1))
        assertEquals(one, SmartShuffle.order(one, emptyMap(), now))
    }

    @Test
    fun skippedSongsLandLaterOnAverage() {
        val skipped = song(1, "S")
        val loved = song(2, "L")
        val others = (3L..12L).map { song(it, "O$it") }
        val songs = listOf(skipped, loved) + others
        val stats = mapOf(
            1L to SongStats(plays = 2, skips = 4),
            2L to SongStats(plays = 5),
        ) + others.associate { it.id to SongStats(plays = 5) }
        val boosted = songs.map { if (it.id == 2L) it.copy(isFavorite = true) else it }
        var skippedSum = 0L
        var lovedSum = 0L
        repeat(500) { seed ->
            val out = SmartShuffle.order(boosted, stats, now, Random(seed))
            skippedSum += out.indexOfFirst { it.id == 1L }
            lovedSum += out.indexOfFirst { it.id == 2L }
        }
        assertTrue(skippedSum > lovedSum, "skipped=$skippedSum loved=$lovedSum")
    }

    @Test
    fun fallbackMatchesTheSimpleGreedyRebuild() {
        // The incremental fallback must pick exactly what the straightforward (quadratic) version picks.
        repeat(200) { seed ->
            val r = Random(seed)
            val n = 2 + r.nextInt(60)
            val artists = 1 + r.nextInt(5)
            val songs = (1L..n).map { song(it, "A${r.nextInt(artists)}", fav = r.nextBoolean()) }
            val stats = songs.filter { r.nextBoolean() }.associate { it.id to SongStats(plays = r.nextInt(6), skips = r.nextInt(4)) }
            val expected = referenceOrder(songs, stats, Random(seed * 31 + 7))
            val actual = SmartShuffle.order(songs, stats, now, Random(seed * 31 + 7))
            assertEquals(expected.map { it.id }, actual.map { it.id }, "seed $seed")
        }
    }

    /** The pre-optimisation algorithm, kept as the reference for [fallbackMatchesTheSimpleGreedyRebuild]. */
    private fun referenceOrder(songs: List<Song>, stats: Map<Long, SongStats>, random: Random): List<Song> {
        val l = songs.map { s -> s to kotlin.math.ln(1.0 - random.nextDouble()) / smartWeight(s, stats[s.id], now) }
            .sortedByDescending { it.second }.map { it.first }.toMutableList()
        for (i in 1 until l.size) {
            if (l[i].artist != l[i - 1].artist) continue
            val end = minOf(i + 5, l.size - 1)
            val j = (i + 1..end).firstOrNull { l[it].artist != l[i - 1].artist && (i + 1 >= l.size || l[i + 1].artist != l[it].artist) }
                ?: continue
            val t = l[i]; l[i] = l[j]; l[j] = t
        }
        if ((1 until l.size).none { l[it].artist == l[it - 1].artist }) return l
        val rest = l.toMutableList()
        l.clear()
        while (rest.isNotEmpty()) {
            val prev = l.lastOrNull()?.artist
            val top = rest.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }!!
            val pick = if (top.key != prev && top.value * 2 > rest.size) rest.indexOfFirst { it.artist == top.key }
            else rest.indexOfFirst { it.artist != prev }.takeIf { it >= 0 } ?: 0
            l.add(rest.removeAt(pick))
        }
        return l
    }

    @Test
    fun largeLibraryWithDominantArtistsStaysFast() {
        // 10 000 songs, ~60% from three artists: the clash fallback runs. The bound is generous: the quadratic
        // fallback took ~2 s here, the incremental one ~0.1 s. The result must still keep every song once and separate artists where it can.
        val songs = (1L..10_000L).map { id ->
            val artist = when {
                id % 5 == 0L -> "Big1"
                id % 5 == 1L -> "Big2"
                id % 5 == 2L -> "Big3"
                else -> "Other${id % 400}"
            }
            song(id, artist)
        }
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val out = SmartShuffle.order(songs, emptyMap(), now, Random(11))
        val elapsed = started.elapsedNow().inWholeMilliseconds
        assertEquals(songs.map { it.id }.sorted(), out.map { it.id }.sorted())
        assertEquals(0, out.zipWithNext().count { (a, b) -> a.artist == b.artist })
        assertTrue(elapsed < 750, "took $elapsed ms")
    }

    @Test
    fun dominantArtistIsSpreadWhenItCannotBeFullySeparated() {
        // 7 A and 3 B: at best A B A B A B A A A A -> the fallback still alternates while it can.
        val songs = (1L..7L).map { song(it, "A") } + (8L..10L).map { song(it, "B") }
        repeat(20) { seed ->
            val out = SmartShuffle.order(songs, emptyMap(), now, Random(seed))
            assertEquals(songs.map { it.id }.toSet(), out.map { it.id }.toSet())
            assertEquals(3, out.zipWithNext().count { (a, b) -> a.artist == b.artist }, "seed $seed: ${out.map { it.artist }}")
        }
    }
}
