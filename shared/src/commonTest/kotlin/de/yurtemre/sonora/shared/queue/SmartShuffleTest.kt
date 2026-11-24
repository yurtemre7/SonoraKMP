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
}
