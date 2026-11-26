package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * "Your mornings / evenings" mix. The stats snapshot only stores one timestamp per song (lastPlayedAtMs),
 * so a song counts toward the slot of the hour it was last played in, weighted by its total plays.
 */
object TimeOfDayMix {
    enum class Slot { Morning, Afternoon, Evening, Night }

    fun slotFor(hour: Int): Slot = when (hour) {
        in 5..11 -> Slot.Morning
        in 12..16 -> Slot.Afternoon
        in 17..21 -> Slot.Evening
        else -> Slot.Night
    }

    fun songs(
        allSongs: List<Song>,
        stats: StatsSnapshot,
        slot: Slot,
        zone: TimeZone,
        nowMs: Long,
        limit: Int = 30,
    ): List<Song> {
        if (limit <= 0) return emptyList()
        data class Ranked(val song: Song, val plays: Int, val liked: Double)
        return allSongs.distinctBy { it.id }.mapNotNull { s ->
            val st = stats.songs[s.id] ?: return@mapNotNull null
            val last = st.lastPlayedAtMs ?: return@mapNotNull null
            if (st.plays <= 0) return@mapNotNull null
            val hour = Instant.fromEpochMilliseconds(last).toLocalDateTime(zone).hour
            if (slotFor(hour) != slot) return@mapNotNull null
            Ranked(s, st.plays, WeeklyMix.likedScore(s, st, nowMs))
        }.sortedWith(
            compareByDescending<Ranked> { it.plays }
                .thenByDescending { it.liked }
                .thenBy { it.song.titleLower }
                .thenBy { it.song.id },
        ).take(limit).map { it.song }
    }
}
