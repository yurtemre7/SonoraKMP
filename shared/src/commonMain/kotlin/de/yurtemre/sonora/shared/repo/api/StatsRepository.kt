package de.yurtemre.sonora.shared.repo.api

import kotlinx.coroutines.flow.Flow

/** Listening stats in Room (STAT-01..07); every event is one transaction (STAT-07, D-29). */
interface StatsRepository {
    val snapshot: Flow<StatsSnapshot>

    /** STAT-01 recent milestone. */
    suspend fun recordRecent(songId: Long, atMs: Long)
    /** STAT-01 complete milestone (plays, weekday Sun=0, first-played, playlist). */
    suspend fun recordCompleteListen(songId: Long, atMs: Long, weekday: Int, localDate: String, playlistId: String?)
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
)
