package de.yurtemre.sonora.shared.library

import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * "Your mornings / evenings" mix. Songs rank by their recorded plays in the slot ([SongStats.slotPlays]); plays
 * from before slot tracking (plays minus recorded slot total) count toward the slot of lastPlayedAtMs.
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
            if (st.plays <= 0) return@mapNotNull null
            // Recorded slot counts first; plays they do not cover (legacy) go to the slot of the last play.
            var inSlot = st.slotPlays.of(slot.ordinal)
            val legacy = st.plays - st.slotPlays.total
            if (legacy > 0) {
                val last = st.lastPlayedAtMs
                if (last != null && slotFor(Instant.fromEpochMilliseconds(last).toLocalDateTime(zone).hour) == slot) inSlot += legacy
            }
            if (inSlot <= 0) return@mapNotNull null
            Ranked(s, inSlot, WeeklyMix.likedScore(s, st, nowMs))
        }.sortedWith(
            compareByDescending<Ranked> { it.plays }
                .thenByDescending { it.liked }
                .thenBy { it.song.titleLower }
                .thenBy { it.song.id },
        ).take(limit).map { it.song }
    }
}
