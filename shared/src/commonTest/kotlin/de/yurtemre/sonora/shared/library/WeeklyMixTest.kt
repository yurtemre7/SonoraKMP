package de.yurtemre.sonora.shared.library

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
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
}
