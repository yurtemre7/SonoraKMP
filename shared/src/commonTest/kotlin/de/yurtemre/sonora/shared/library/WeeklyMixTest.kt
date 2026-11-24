package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.SongStats
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class WeeklyMixTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val hourMs = 3_600_000L
    private val dayMs = 24 * hourMs

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: TimeZone = berlin): Long =
        LocalDateTime(y, mo, d, h, mi).toInstant(zone).toEpochMilliseconds()

    private fun local(ms: Long, zone: TimeZone = berlin) =
        Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)

    // 2026-11-23 is a Monday.
    @Test
    fun mondayBefore8UsesPreviousWeek() {
        assertEquals(ms(2026, 11, 16, 8, 0), WeeklyMix.weekStart(ms(2026, 11, 23, 7, 59), berlin))
    }

    @Test
    fun mondayAt8IsItsOwnStart() {
        val t = ms(2026, 11, 23, 8, 0)
        assertEquals(t, WeeklyMix.weekStart(t, berlin))
    }

    @Test
    fun sundayLateUsesMondayBefore() {
        assertEquals(ms(2026, 11, 16, 8, 0), WeeklyMix.weekStart(ms(2026, 11, 22, 23, 59), berlin))
    }

    @Test
    fun nextRefreshIsAfterNowAndOneLocalWeekLater() {
        val now = ms(2026, 11, 25, 12, 0)
        val start = WeeklyMix.weekStart(now, berlin)
        val next = WeeklyMix.nextRefresh(now, berlin)
        assertTrue(next > now)
        assertEquals(ms(2026, 11, 30, 8, 0), next)
        assertEquals(start + 7 * dayMs, next)
        // exactly at the boundary the next refresh is a full week away
        assertEquals(ms(2026, 11, 30, 8, 0), WeeklyMix.nextRefresh(ms(2026, 11, 23, 8, 0), berlin))
    }

    @Test
    fun dstChangeShortensWeekByAnHour() {
        // Berlin switches to summer time on Sunday 2026-03-29.
        val now = ms(2026, 3, 25, 12, 0)
        val start = WeeklyMix.weekStart(now, berlin)
        val next = WeeklyMix.nextRefresh(now, berlin)
        assertEquals(7 * dayMs - hourMs, next - start)
        assertEquals(8, local(start).hour)
        assertEquals(8, local(next).hour)
        assertEquals(ms(2026, 3, 23, 8, 0), start)
        assertEquals(ms(2026, 3, 30, 8, 0), next)
    }

    @Test
    fun zoneChangeGivesDifferentValues() {
        val now = ms(2026, 11, 25, 12, 0)
        assertNotEquals(WeeklyMix.weekStart(now, berlin), WeeklyMix.weekStart(now, tokyo))
        assertNotEquals(WeeklyMix.nextRefresh(now, berlin), WeeklyMix.nextRefresh(now, tokyo))
        assertEquals(ms(2026, 11, 23, 8, 0, tokyo), WeeklyMix.weekStart(now, tokyo))
    }

    // ---- builder ----
    private val now = ms(2026, 11, 23, 8, 0)

    private fun mk(id: Long, artist: String = "A$id", fav: Boolean = false, added: Long? = null) =
        song(id, artist = artist).copy(isFavorite = fav, dateAddedMs = added)

    private fun played(plays: Int, skips: Int = 0, last: Long? = null) = SongStats(plays, skips, 0, last)

    @Test
    fun likedScoreRisesWithPlaysAndFavourite() {
        assertEquals(3.0, WeeklyMix.likedScore(mk(1), played(3), now), 1e-9)
        assertEquals(5.0, WeeklyMix.likedScore(mk(1, fav = true), played(3), now), 1e-9)
        assertEquals(2.0, WeeklyMix.likedScore(mk(1, fav = true), null, now), 1e-9)
        assertEquals(0.0, WeeklyMix.likedScore(mk(1), null, now), 1e-9)
    }

    @Test
    fun likedScoreHalvesEvery30Days() {
        assertEquals(8.0, WeeklyMix.likedScore(mk(1), played(8, last = now), now), 1e-9)
        assertEquals(4.0, WeeklyMix.likedScore(mk(1), played(8, last = now - 30 * dayMs), now), 1e-9)
        assertEquals(2.0, WeeklyMix.likedScore(mk(1), played(8, last = now - 60 * dayMs), now), 1e-9)
    }

    @Test
    fun likedScoreHalvedWhenSkipRateAtLeastHalf() {
        assertEquals(2.0, WeeklyMix.likedScore(mk(1), played(4, skips = 4), now), 1e-9)
        assertEquals(1.0, WeeklyMix.likedScore(mk(1), played(2, skips = 2), now), 1e-9)
        assertEquals(4.0, WeeklyMix.likedScore(mk(1), played(4, skips = 1), now), 1e-9)
    }

    private fun bigLibrary(n: Int = 300): Pair<List<Song>, Map<Long, SongStats>> {
        val songs = (1L..n).map { mk(it, artist = "Art${it % 40}", added = if (it % 3 == 0L) now - 5 * dayMs else null) }
        val stats = songs.filter { it.id % 3 != 0L }.associate { it.id to played(5 + (it.id % 7).toInt(), last = now - dayMs) }
        return songs to stats
    }

    @Test
    fun buildNeverExceedsLimit() {
        val (songs, stats) = bigLibrary()
        assertEquals(50, WeeklyMix.build(songs, stats, now, Random(1)).size)
        assertEquals(10, WeeklyMix.build(songs, stats, now, Random(1), limit = 10).size)
        assertTrue(WeeklyMix.build(songs, stats, now, Random(1), limit = 0).isEmpty())
    }

    @Test
    fun buildHasNoDuplicates() {
        val (songs, stats) = bigLibrary()
        val ids = WeeklyMix.build(songs + songs.take(20), stats, now, Random(2)).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun buildCapsThreePerArtist() {
        // 20 artists x 5 songs: cap of 3 still leaves room for 50.
        val songs = (1L..100L).map { mk(it, artist = "Art${it % 20}") }
        val out = WeeklyMix.build(songs, emptyMap(), now, Random(3))
        assertEquals(50, out.size)
        assertTrue(out.groupBy { it.artist }.values.all { it.size <= 3 })
    }

    @Test
    fun buildCapsThreePerArtistUntilOthersRunOut() {
        val songs = (1L..100L).map { mk(it, artist = if (it % 2 == 0L) "X" else "Y") } +
            (101L..110L).map { mk(it, artist = "Other$it") }
        val stats = songs.associate { it.id to played(10, last = now) }
        val out = WeeklyMix.build(songs, stats, now, Random(4))
        // All ten other artists make it in; the cap only relaxes to fill the remaining 40 slots.
        assertEquals(50, out.size)
        assertEquals(10, out.count { it.id > 100 })
    }

    @Test
    fun buildRelaxesArtistCapWhenLibraryTooSmall() {
        val songs = (1L..30L).map { mk(it, artist = "Solo") }
        assertEquals(30, WeeklyMix.build(songs, emptyMap(), now, Random(5)).size)
    }

    @Test
    fun buildIncludesFreshSongs() {
        val (songs, stats) = bigLibrary()
        val out = WeeklyMix.build(songs, stats, now, Random(6))
        val fresh = out.count { it.dateAddedMs != null && now - it.dateAddedMs!! <= 30 * dayMs }
        // 15 fresh slots, plus a few of the 5 filler slots may also be fresh.
        assertTrue(fresh in 10..20, "fresh=$fresh")
    }

    @Test
    fun buildReturnsShorterListForSmallLibrary() {
        val songs = (1L..7L).map { mk(it) }
        assertEquals(7, WeeklyMix.build(songs, emptyMap(), now, Random(7)).size)
    }

    @Test
    fun buildEmptyLibraryGivesEmpty() {
        assertTrue(WeeklyMix.build(emptyList(), emptyMap(), now, Random(8)).isEmpty())
    }

    @Test
    fun buildIsDeterministicForSeededRandom() {
        val (songs, stats) = bigLibrary()
        val a = WeeklyMix.build(songs, stats, now, Random(42)).map { it.id }
        val b = WeeklyMix.build(songs, stats, now, Random(42)).map { it.id }
        val c = WeeklyMix.build(songs, stats, now, Random(43)).map { it.id }
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
