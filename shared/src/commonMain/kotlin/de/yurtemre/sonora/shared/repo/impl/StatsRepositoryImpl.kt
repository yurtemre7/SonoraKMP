package de.yurtemre.sonora.shared.repo.impl

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import de.yurtemre.sonora.shared.db.PlaylistStatsEntity
import de.yurtemre.sonora.shared.db.SongStatsEntity
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.db.StatsSummaryEntity
import de.yurtemre.sonora.shared.db.WeekdayStatsEntity
import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * STAT-01..07: each event is one immediate transaction (D-29).
 * `stats_summary.first_played_date` is a Long column; the `yyyy-MM-dd` date is stored as `yyyyMMdd`.
 */
@OptIn(ExperimentalAtomicApi::class)
class StatsRepositoryImpl(private val db: SonoraDatabase) : StatsRepository {
    private val dao = db.statsDao()
    private val songs = db.songDao()
    private val pauseDepth = AtomicInt(0)

    override val snapshot: Flow<StatsSnapshot> = combine(
        dao.observeSongStats(), dao.observeWeekdayStats(), dao.observePlaylistStats(), dao.observeSummary(),
    ) { s, w, p, sum ->
        StatsSnapshot(
            songs = s.associate { it.songId to SongStats(it.plays, it.skips, it.restarts, it.lastPlayedAtMs) },
            weekdayCounts = w.associate { it.day to it.plays },
            playlistPlays = p.associate { it.playlistId to it.plays },
            completeListens = sum?.completeSongListens ?: 0,
            shuffleSessions = sum?.shuffleSessionStarts ?: 0,
            firstPlayedSongId = sum?.firstPlayedSongId?.takeIf { it >= 0 },
            firstPlayedDate = sum?.firstPlayedDate?.let(::decodeDate),
        )
    }.distinctUntilChanged()

    private val paused get() = pauseDepth.load() > 0

    private suspend inline fun tx(crossinline block: suspend () -> Unit) {
        if (paused) return
        db.useWriterConnection { c -> c.immediateTransaction { block() } }
    }

    private suspend fun song(id: Long): SongStatsEntity =
        dao.getSongStats(id) ?: SongStatsEntity(id, songs.getById(id)?.filePath)

    override suspend fun recordRecent(songId: Long, atMs: Long) = tx {
        dao.upsertSongStats(listOf(song(songId).copy(lastPlayedAtMs = atMs)))
    }

    override suspend fun recordCompleteListen(songId: Long, atMs: Long, weekday: Int, localDate: String, playlistId: String?) = tx {
        val s = song(songId)
        dao.upsertSongStats(listOf(s.copy(plays = s.plays + 1)))
        val w = dao.getWeekday(weekday) ?: WeekdayStatsEntity(weekday)
        dao.upsertWeekdayStats(listOf(w.copy(plays = w.plays + 1)))
        if (playlistId != null) {
            val p = dao.getPlaylistStats(playlistId) ?: PlaylistStatsEntity(playlistId)
            dao.upsertPlaylistStats(listOf(p.copy(plays = p.plays + 1)))
        }
        val sum = dao.getSummary() ?: StatsSummaryEntity()
        dao.upsertSummary(
            if (sum.firstPlayedSongId < 0) sum.copy(completeSongListens = sum.completeSongListens + 1,
                firstPlayedSongId = songId, firstPlayedDate = encodeDate(localDate))
            else sum.copy(completeSongListens = sum.completeSongListens + 1)
        )
    }

    override suspend fun recordSkip(songId: Long) = tx { song(songId).let { dao.upsertSongStats(listOf(it.copy(skips = it.skips + 1))) } }
    override suspend fun recordRestart(songId: Long) = tx { song(songId).let { dao.upsertSongStats(listOf(it.copy(restarts = it.restarts + 1))) } }
    override suspend fun recordShuffleSession() = tx {
        val sum = dao.getSummary() ?: StatsSummaryEntity()
        dao.upsertSummary(sum.copy(shuffleSessionStarts = sum.shuffleSessionStarts + 1))
    }

    override fun pauseRecording() { pauseDepth.fetchAndAdd(1) }
    override fun resumeRecording() {
        while (true) {
            val v = pauseDepth.load()
            if (v == 0 || pauseDepth.compareAndSet(v, v - 1)) return
        }
    }

    override suspend fun reset() = dao.clearAll()

    override suspend fun replaceAll(snapshot: StatsSnapshot) {
        db.useWriterConnection { c ->
            c.immediateTransaction {
                dao.clearAll()
                dao.upsertSongStats(snapshot.songs.map { (id, s) ->
                    SongStatsEntity(id, songs.getById(id)?.filePath, s.plays, s.skips, s.restarts, s.lastPlayedAtMs)
                })
                dao.upsertWeekdayStats(snapshot.weekdayCounts.map { WeekdayStatsEntity(it.key, it.value) })
                dao.upsertPlaylistStats(snapshot.playlistPlays.map { PlaylistStatsEntity(it.key, it.value) })
                dao.upsertSummary(StatsSummaryEntity(
                    completeSongListens = snapshot.completeListens,
                    firstPlayedSongId = snapshot.firstPlayedSongId ?: -1,
                    firstPlayedDate = snapshot.firstPlayedDate?.let(::encodeDate),
                    shuffleSessionStarts = snapshot.shuffleSessions,
                ))
            }
        }
    }

    override suspend fun relinkSongIds(idRemap: Map<Long, Long>) {
        if (idRemap.isEmpty()) return
        db.useWriterConnection { c ->
            c.immediateTransaction {
                val all = dao.getAllSongStats().associateBy { it.songId }.toMutableMap()
                val moved = idRemap.filter { (old, new) -> old != new && old in all }
                for ((old, new) in moved) {
                    val src = all.remove(old)!!
                    val dst = all[new]
                    all[new] = if (dst == null) src.copy(songId = new) else dst.copy(
                        plays = dst.plays + src.plays, skips = dst.skips + src.skips, restarts = dst.restarts + src.restarts,
                        lastPlayedAtMs = listOfNotNull(dst.lastPlayedAtMs, src.lastPlayedAtMs).maxOrNull(),
                        filePath = dst.filePath ?: src.filePath,
                    )
                }
                if (moved.isEmpty()) return@immediateTransaction
                dao.deleteSongStats(moved.keys.toList())
                dao.upsertSongStats(moved.values.toSet().mapNotNull { all[it] })
                val sum = dao.getSummary()
                val first = sum?.firstPlayedSongId
                if (sum != null && first != null && first in moved) dao.upsertSummary(sum.copy(firstPlayedSongId = moved.getValue(first)))
            }
        }
    }

    private fun encodeDate(d: String): Long? = d.replace("-", "").toLongOrNull()
    private fun decodeDate(v: Long): String {
        val s = v.toString().padStart(8, '0')
        return "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}"
    }
}
