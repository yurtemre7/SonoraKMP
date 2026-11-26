package de.yurtemre.sonora.shared.repo.api

import kotlinx.coroutines.flow.Flow

/** Listening stats in Room (STAT-01..07); every event is one transaction (STAT-07, D-29). */
interface StatsRepository {
    val snapshot: Flow<StatsSnapshot>

    /** STAT-01 recent milestone. */
    suspend fun recordRecent(songId: Long, atMs: Long)
    /** STAT-01 complete milestone (plays, weekday Sun=0, first-played, playlist). [hour] is the local hour 0..23 (-1 = unknown, no slot counted). */
    suspend fun recordCompleteListen(songId: Long, atMs: Long, weekday: Int, localDate: String, playlistId: String?, hour: Int = -1)
    /** STAT-02 */
    suspend fun recordSkip(songId: Long)
    suspend fun recordRestart(songId: Long)
    suspend fun recordShuffleSession()

    /** STAT-02: nesting counter; while paused, record calls are no-ops (restore). */
    fun pauseRecording()
    fun resumeRecording()

    /** STAT-06 */
    suspend fun reset()
    /** BAK-04: replace all stats with the remapped backup snapshot. */
    suspend fun replaceAll(snapshot: StatsSnapshot)
    /**
     * DSYNC-06: adds a device sync stats difference ([de.yurtemre.sonora.shared.devicesync.StatsDelta.merge]
     * semantics) in one transaction. Like [replaceAll] it is an import and runs even while recording is paused.
     */
    suspend fun mergeDelta(delta: StatsSnapshot)
    /** D-49: move per-song stats from old to new ids (collisions summed, lastPlayed max). */
    suspend fun relinkSongIds(idRemap: Map<Long, Long>)
}

/** Raw stats state (audit/data §11). */
data class StatsSnapshot(
    val songs: Map<Long, SongStats> = emptyMap(),
    /** Weekday Sun=0..Sat=6 → count. */
    val weekdayCounts: Map<Int, Int> = emptyMap(),
    val playlistPlays: Map<String, Int> = emptyMap(),
    val completeListens: Int = 0,
    val shuffleSessions: Int = 0,
    val firstPlayedSongId: Long? = null,
    /** yyyy-MM-dd local. */
    val firstPlayedDate: String? = null,
)

data class SongStats(
    val plays: Int = 0,
    val skips: Int = 0,
    val restarts: Int = 0,
    val lastPlayedAtMs: Long? = null,
    /** Local-only (not part of backup or device sync); plays recorded since slot tracking began. */
    val slotPlays: SlotPlays = SlotPlays(),
)

/** Complete listens per time-of-day slot; index order matches `TimeOfDayMix.Slot.ordinal`. */
data class SlotPlays(
    val morning: Int = 0,
    val afternoon: Int = 0,
    val evening: Int = 0,
    val night: Int = 0,
) {
    val total: Int get() = morning + afternoon + evening + night
    operator fun plus(o: SlotPlays) = SlotPlays(morning + o.morning, afternoon + o.afternoon, evening + o.evening, night + o.night)
    fun of(slotOrdinal: Int): Int = when (slotOrdinal) { 0 -> morning; 1 -> afternoon; 2 -> evening; 3 -> night; else -> 0 }
    fun increment(slotOrdinal: Int): SlotPlays = when (slotOrdinal) {
        0 -> copy(morning = morning + 1)
        1 -> copy(afternoon = afternoon + 1)
        2 -> copy(evening = evening + 1)
        3 -> copy(night = night + 1)
        else -> this
    }
}
