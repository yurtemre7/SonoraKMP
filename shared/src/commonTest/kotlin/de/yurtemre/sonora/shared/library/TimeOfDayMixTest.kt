package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.repo.api.SlotPlays
import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeOfDayMixTest {
    private val zone = TimeZone.UTC
    private val hourMs = 3_600_000L
    private val day = 20_000L * 24 * hourMs
    private val now = day + 30 * hourMs

    private fun at(hour: Int) = day + hour * hourMs
    private fun snap(vararg e: Pair<Long, SongStats>) = StatsSnapshot(songs = mapOf(*e))
    private fun st(plays: Int, hour: Int) = SongStats(plays = plays, lastPlayedAtMs = at(hour))

    @Test fun slotBoundaries() {
        val m = TimeOfDayMix.Slot.Morning
        assertEquals(TimeOfDayMix.Slot.Night, TimeOfDayMix.slotFor(4))
        assertEquals(m, TimeOfDayMix.slotFor(5))
        assertEquals(m, TimeOfDayMix.slotFor(11))
        assertEquals(TimeOfDayMix.Slot.Afternoon, TimeOfDayMix.slotFor(12))
        assertEquals(TimeOfDayMix.Slot.Afternoon, TimeOfDayMix.slotFor(16))
        assertEquals(TimeOfDayMix.Slot.Evening, TimeOfDayMix.slotFor(17))
        assertEquals(TimeOfDayMix.Slot.Evening, TimeOfDayMix.slotFor(21))
        assertEquals(TimeOfDayMix.Slot.Night, TimeOfDayMix.slotFor(22))
        assertEquals(TimeOfDayMix.Slot.Night, TimeOfDayMix.slotFor(0))
    }

    @Test fun ranksByPlaysInSlotOnly() {
        val songs = listOf(song(1), song(2), song(3), song(4))
        val stats = snap(1L to st(3, 8), 2L to st(9, 8), 3L to st(50, 20), 4L to st(5, 9))
        val r = TimeOfDayMix.songs(songs, stats, TimeOfDayMix.Slot.Morning, zone, now)
        assertEquals(listOf(2L, 4L, 1L), r.map { it.id })
    }

    @Test fun recordedSlotsWinOverLastPlayedHour() {
        val songs = listOf(song(1), song(2))
        // song 1: last played at night, but all 4 plays were recorded in the morning
        val s1 = SongStats(plays = 4, lastPlayedAtMs = at(23), slotPlays = SlotPlays(morning = 4))
        // song 2: last played in the morning, but recorded plays are in the evening
        val s2 = SongStats(plays = 6, lastPlayedAtMs = at(8), slotPlays = SlotPlays(evening = 6))
        val stats = snap(1L to s1, 2L to s2)
        assertEquals(listOf(1L), TimeOfDayMix.songs(songs, stats, TimeOfDayMix.Slot.Morning, zone, now).map { it.id })
        assertEquals(listOf(2L), TimeOfDayMix.songs(songs, stats, TimeOfDayMix.Slot.Evening, zone, now).map { it.id })
        assertTrue(TimeOfDayMix.songs(songs, stats, TimeOfDayMix.Slot.Night, zone, now).isEmpty())
    }

    @Test fun legacyRemainderGoesToLastPlayedSlot() {
        val songs = listOf(song(1), song(2))
        val stats = snap(1L to st(5, 8), 2L to st(3, 20))
        assertEquals(listOf(1L), TimeOfDayMix.songs(songs, stats, TimeOfDayMix.Slot.Morning, zone, now).map { it.id })
        assertEquals(listOf(2L), TimeOfDayMix.songs(songs, stats, TimeOfDayMix.Slot.Evening, zone, now).map { it.id })
    }

    @Test fun mixedRecordedAndLegacyPlaysAreSummed() {
        val songs = listOf(song(1), song(2))
        // song 1: 2 recorded morning + 3 legacy (last played 8h) = 5 morning plays
        val s1 = SongStats(plays = 5, lastPlayedAtMs = at(8), slotPlays = SlotPlays(morning = 2))
        // song 2: 4 recorded morning, no legacy
        val s2 = SongStats(plays = 4, lastPlayedAtMs = at(8), slotPlays = SlotPlays(morning = 4))
        val r = TimeOfDayMix.songs(songs, snap(1L to s1, 2L to s2), TimeOfDayMix.Slot.Morning, zone, now)
        assertEquals(listOf(1L, 2L), r.map { it.id })
        // legacy remainder does not leak into a slot other than the last-played one
        assertTrue(TimeOfDayMix.songs(songs, snap(1L to s1), TimeOfDayMix.Slot.Evening, zone, now).isEmpty())
    }

    @Test fun emptyWithoutHistory() {
        val songs = listOf(song(1), song(2))
        assertTrue(TimeOfDayMix.songs(songs, StatsSnapshot(), TimeOfDayMix.Slot.Morning, zone, now).isEmpty())
        val noTime = snap(1L to SongStats(plays = 4, lastPlayedAtMs = null), 2L to SongStats(plays = 0, lastPlayedAtMs = at(8)))
        assertTrue(TimeOfDayMix.songs(songs, noTime, TimeOfDayMix.Slot.Morning, zone, now).isEmpty())
    }

    @Test fun deterministicTieBreakByTitleThenId() {
        val songs = listOf(song(3, title = "b"), song(2, title = "a"), song(1, title = "a"))
        val stats = snap(1L to st(2, 8), 2L to st(2, 8), 3L to st(2, 8))
        val a = TimeOfDayMix.songs(songs, stats, TimeOfDayMix.Slot.Morning, zone, now)
        val b = TimeOfDayMix.songs(songs.reversed(), stats, TimeOfDayMix.Slot.Morning, zone, now)
        assertEquals(listOf(1L, 2L, 3L), a.map { it.id })
        assertEquals(a, b)
    }

    @Test fun respectsLimit() {
        val songs = (1L..10L).map { song(it) }
        val stats = StatsSnapshot(songs = songs.associate { it.id to st(it.id.toInt(), 8) })
        val r = TimeOfDayMix.songs(songs, stats, TimeOfDayMix.Slot.Morning, zone, now, limit = 4)
        assertEquals(listOf(10L, 9L, 8L, 7L), r.map { it.id })
    }
}
