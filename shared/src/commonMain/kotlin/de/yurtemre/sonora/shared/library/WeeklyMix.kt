package de.yurtemre.sonora.shared.library

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Weekly mix scheduling. A week starts Monday 08:00 in the given local zone; all arithmetic is done
 * on [LocalDateTime] so DST changes are handled (a week may be 167 or 169 hours long).
 */
object WeeklyMix {
    private const val START_HOUR = 8

    /** Epoch millis of the latest Monday 08:00 (local) that is not after [nowMs]. */
    fun weekStart(nowMs: Long, zone: TimeZone): Long =
        latestStart(nowMs, zone).toInstant(zone).toEpochMilliseconds()

    /** Epoch millis of the Monday 08:00 (local) following the current week's start; always > [nowMs]. */
    fun nextRefresh(nowMs: Long, zone: TimeZone): Long {
        val start = latestStart(nowMs, zone)
        val next = LocalDateTime(start.date.plus(DatePeriod(days = 7)), start.time)
        return next.toInstant(zone).toEpochMilliseconds()
    }

    private fun latestStart(nowMs: Long, zone: TimeZone): LocalDateTime {
        val local = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone)
        val monday = local.date.minus(DatePeriod(days = local.date.dayOfWeek.ordinal))
        val candidate = LocalDateTime(monday.year, monday.month, monday.day, START_HOUR, 0)
        return if (candidate.toInstant(zone).toEpochMilliseconds() > nowMs) {
            LocalDateTime(candidate.date.minus(DatePeriod(days = 7)), candidate.time)
        } else {
            candidate
        }
    }
}
