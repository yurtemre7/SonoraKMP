package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.SongStats
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Instant

/**
 * Weekly mix scheduling. A week starts Monday 08:00 in the given local zone; all arithmetic is done
 * on [LocalDateTime] so DST changes are handled (a week may be 167 or 169 hours long).
 */
object WeeklyMix {
    private const val START_HOUR = 8
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val HALF_LIFE_DAYS = 30.0
    private const val FRESH_DAYS = 30L
    private const val ARTIST_CAP = 3
    private const val LIKED_SHARE = 0.6
    private const val FRESH_SHARE = 0.3

    /** Epoch millis of the latest Monday 08:00 (local) that is not after [nowMs]. */
    fun weekStart(nowMs: Long, zone: TimeZone): Long =
        latestStart(nowMs, zone).toInstant(zone).toEpochMilliseconds()

    /** Epoch millis of the Monday 08:00 (local) following the current week's start; always > [nowMs]. */
    fun nextRefresh(nowMs: Long, zone: TimeZone): Long {
        val start = latestStart(nowMs, zone)
        val next = LocalDateTime(start.date.plus(DatePeriod(days = 7)), start.time)
        return next.toInstant(zone).toEpochMilliseconds()
    }

    /**
     * How strongly [song] reads as "known and liked": plays (+2 for a favourite), decayed with a
     * 30-day half-life since the last play, halved when at least half of its starts were skipped.
     * Never-played, non-favourite songs score 0.
     */
    fun likedScore(song: Song, stats: SongStats?, nowMs: Long): Double {
        val plays = stats?.plays ?: 0
        val skips = stats?.skips ?: 0
        var score = plays + if (song.isFavorite) 2.0 else 0.0
        val last = stats?.lastPlayedAtMs
        if (last != null) {
            val days = max(0L, nowMs - last).toDouble() / DAY_MS
            score *= 0.5.pow(days / HALF_LIFE_DAYS)
        }
        if (plays + skips > 0 && skips.toDouble() / (plays + skips) >= 0.5) score *= 0.5
        return score
    }

    /**
     * Builds up to [limit] songs: ~60% weighted by [likedScore], ~30% from songs added in the last
     * 30 days or played at most once, the rest from whatever remains. At most 3 songs per artist
     * while other candidates exist; the cap is relaxed only to reach the limit. Final order shuffled.
     */
    fun build(
        songs: List<Song>,
        stats: Map<Long, SongStats>,
        nowMs: Long,
        random: Random = Random,
        limit: Int = 50,
    ): List<Song> {
        if (limit <= 0) return emptyList()
        val remaining = songs.distinctBy { it.id }.toMutableList()
        val picked = ArrayList<Song>()
        val perArtist = HashMap<String, Int>()

        fun take(pool: List<Song>, n: Int, cap: Int?, weight: (Song) -> Double) {
            if (n <= 0) return
            val ordered = pool.mapNotNull { s ->
                val w = weight(s)
                if (w <= 0.0) null else s to (1.0 - random.nextDouble()).pow(1.0 / w) // Efraimidis-Spirakis
            }.sortedByDescending { it.second }
            var left = n
            for ((s, _) in ordered) {
                if (left == 0) break
                if (cap != null && (perArtist[s.artist] ?: 0) >= cap) continue
                picked += s
                perArtist[s.artist] = (perArtist[s.artist] ?: 0) + 1
                remaining.remove(s)
                left--
            }
        }

        val likedQuota = (limit * LIKED_SHARE).roundToInt()
        val freshQuota = (limit * FRESH_SHARE).roundToInt()
        take(remaining.toList(), likedQuota, ARTIST_CAP) { likedScore(it, stats[it.id], nowMs) }
        val fresh = remaining.filter { s ->
            val added = s.dateAddedMs
            (added != null && nowMs - added <= FRESH_DAYS * DAY_MS) || (stats[s.id]?.plays ?: 0) <= 1
        }
        take(fresh, minOf(freshQuota, limit - picked.size), ARTIST_CAP) { 1.0 }
        take(remaining.toList(), limit - picked.size, ARTIST_CAP) { 1.0 }
        take(remaining.toList(), limit - picked.size, null) { 1.0 }
        return picked.shuffled(random)
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
