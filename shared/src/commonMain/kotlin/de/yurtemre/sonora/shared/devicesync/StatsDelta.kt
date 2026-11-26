package de.yurtemre.sonora.shared.devicesync

import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot

/**
 * Stats are exchanged as a difference against the last synced baseline so repeated syncs never
 * double-count plays (DSYNC-06, D-71).
 */
object StatsDelta {
    /** What the sender listened to since [baseline]; a sender reset (fewer complete listens) ignores it. */
    fun difference(now: SyncStats, baseline: SyncStats?): SyncStats {
        val base = baseline?.takeUnless { now.completeListens < it.completeListens }
        val songs = buildMap {
            for ((path, c) in now.songs) {
                val b = base?.songs?.get(path)
                val diff = SongCounters(
                    plays = (c.plays - (b?.plays ?: 0)).coerceAtLeast(0),
                    skips = (c.skips - (b?.skips ?: 0)).coerceAtLeast(0),
                    restarts = (c.restarts - (b?.restarts ?: 0)).coerceAtLeast(0),
                    lastPlayedAtMs = c.lastPlayedAtMs,
                )
                val unchanged = diff.plays == 0 && diff.skips == 0 && diff.restarts == 0 &&
                    c.lastPlayedAtMs == b?.lastPlayedAtMs
                if (!unchanged) put(path, diff)
            }
        }
        return SyncStats(
            songs = songs,
            weekday = diffCounts(now.weekday, base?.weekday),
            playlistPlays = diffCounts(now.playlistPlays, base?.playlistPlays),
            completeListens = (now.completeListens - (base?.completeListens ?: 0)).coerceAtLeast(0),
            shuffleSessions = (now.shuffleSessions - (base?.shuffleSessions ?: 0)).coerceAtLeast(0),
            firstPlayedPath = now.firstPlayedPath,
            firstPlayedDate = now.firstPlayedDate,
            recentlyPlayed = now.recentlyPlayed,
        )
    }

    /**
     * The baseline to store after applying [now] (DSYNC-06: the sender totals actually imported). Songs in
     * [imported] (they matched a receiver song) advance to [now]; every other song keeps its [previous] entry, or
     * none, so its plays are added once it matches in a later device sync. The aggregate counters (complete
     * listens, weekdays, playlist plays, shuffle sessions, first play) were applied in full and advance. After a
     * sender reset ([difference] ignores [previous]) the old entries are dropped as well.
     */
    fun nextBaseline(now: SyncStats, previous: SyncStats?, imported: Set<String>): SyncStats {
        val base = previous?.takeUnless { now.completeListens < it.completeListens }
        fun <V> keep(current: Map<String, V>, old: Map<String, V>?): Map<String, V> = buildMap {
            for ((path, v) in current) {
                if (path in imported) put(path, v) else old?.get(path)?.let { put(path, it) }
            }
        }
        return now.copy(songs = keep(now.songs, base?.songs), recentlyPlayed = keep(now.recentlyPlayed, base?.recentlyPlayed))
    }

    /** Maps sender paths to receiver song ids; unknown paths are dropped. */
    fun toSnapshotDelta(delta: SyncStats, pathToReceiverId: Map<String, Long>): StatsSnapshot {
        val songs = HashMap<Long, SongStats>()
        for ((path, c) in delta.songs) {
            val id = pathToReceiverId[path] ?: continue
            val add = SongStats(c.plays, c.skips, c.restarts, c.lastPlayedAtMs)
            songs[id] = songs[id]?.let { sumSong(it, add) } ?: add
        }
        for ((path, ms) in delta.recentlyPlayed) {
            val id = pathToReceiverId[path] ?: continue
            val cur = songs[id] ?: SongStats()
            songs[id] = cur.copy(lastPlayedAtMs = maxNullable(cur.lastPlayedAtMs, ms))
        }
        val firstId = delta.firstPlayedPath?.let { pathToReceiverId[it] }
        return StatsSnapshot(
            songs = songs,
            weekdayCounts = delta.weekday,
            playlistPlays = delta.playlistPlays,
            completeListens = delta.completeListens,
            shuffleSessions = delta.shuffleSessions,
            firstPlayedSongId = firstId,
            firstPlayedDate = if (firstId != null) delta.firstPlayedDate else null,
        )
    }

    fun merge(receiver: StatsSnapshot, delta: StatsSnapshot): StatsSnapshot {
        val songs = HashMap(receiver.songs)
        for ((id, d) in delta.songs) {
            songs[id] = songs[id]?.let { sumSong(it, d) } ?: d
        }
        val useDelta = delta.firstPlayedSongId != null && delta.firstPlayedDate != null &&
            (receiver.firstPlayedDate == null || delta.firstPlayedDate < receiver.firstPlayedDate)
        return StatsSnapshot(
            songs = songs,
            weekdayCounts = sumCounts(receiver.weekdayCounts, delta.weekdayCounts),
            playlistPlays = sumCounts(receiver.playlistPlays, delta.playlistPlays),
            completeListens = receiver.completeListens + delta.completeListens,
            shuffleSessions = receiver.shuffleSessions + delta.shuffleSessions,
            firstPlayedSongId = if (useDelta) delta.firstPlayedSongId else receiver.firstPlayedSongId,
            firstPlayedDate = if (useDelta) delta.firstPlayedDate else receiver.firstPlayedDate,
        )
    }

    private fun <K> diffCounts(now: Map<K, Int>, base: Map<K, Int>?): Map<K, Int> = buildMap {
        for ((k, v) in now) {
            val d = (v - (base?.get(k) ?: 0)).coerceAtLeast(0)
            if (d > 0) put(k, d)
        }
    }

    private fun <K> sumCounts(a: Map<K, Int>, b: Map<K, Int>): Map<K, Int> {
        val out = HashMap(a)
        for ((k, v) in b) out[k] = (out[k] ?: 0) + v
        return out
    }

    private fun sumSong(a: SongStats, b: SongStats) = SongStats(
        plays = a.plays + b.plays,
        skips = a.skips + b.skips,
        restarts = a.restarts + b.restarts,
        lastPlayedAtMs = maxNullable(a.lastPlayedAtMs, b.lastPlayedAtMs),
        slotPlays = a.slotPlays + b.slotPlays,
    )

    private fun maxNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
}

/** Sender-side helper: expresses a local snapshot by absolute file path. Songs with unknown paths are skipped. */
fun SyncStats.Companion.fromSnapshot(snapshot: StatsSnapshot, pathOf: (Long) -> String?): SyncStats {
    val songs = LinkedHashMap<String, SongCounters>()
    val recent = LinkedHashMap<String, Long>()
    for ((id, s) in snapshot.songs) {
        val path = pathOf(id) ?: continue
        songs[path] = SongCounters(s.plays, s.skips, s.restarts, s.lastPlayedAtMs)
        s.lastPlayedAtMs?.let { recent[path] = it }
    }
    return SyncStats(
        songs = songs,
        weekday = snapshot.weekdayCounts,
        playlistPlays = snapshot.playlistPlays,
        completeListens = snapshot.completeListens,
        shuffleSessions = snapshot.shuffleSessions,
        firstPlayedPath = snapshot.firstPlayedSongId?.let(pathOf),
        firstPlayedDate = snapshot.firstPlayedDate,
        recentlyPlayed = recent,
    )
}
