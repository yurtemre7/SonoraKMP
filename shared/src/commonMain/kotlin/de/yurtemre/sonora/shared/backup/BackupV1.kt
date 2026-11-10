package de.yurtemre.sonora.shared.backup

import de.yurtemre.sonora.shared.repo.api.SongStats
import de.yurtemre.sonora.shared.repo.api.StatsSnapshot

/** Flutter SharedPreferences value types used by the backup whitelist. */
enum class PrefType { Boolean, Integer, Decimal, String, StringList }

/** A typed settings value as it appears in the backup `settings` section. */
sealed interface SettingValue {
    data class Bool(val value: Boolean) : SettingValue
    data class Int(val value: Long) : SettingValue
    data class Decimal(val value: Double) : SettingValue
    data class Str(val value: String) : SettingValue
    data class StrList(val value: List<String>) : SettingValue

    fun matches(type: PrefType): Boolean = when (type) {
        PrefType.Boolean -> this is Bool
        PrefType.Integer -> this is Int
        PrefType.Decimal -> this is Decimal
        PrefType.String -> this is Str
        PrefType.StringList -> this is StrList
    }
}

/** `songs[]` entry: the device-local id plus what is needed to find the song on another device. */
data class BackupSongRef(
    val id: Long,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

data class BackupPlaylist(
    val id: String,
    val name: String,
    val description: String?,
    val songIds: List<Long>,
)

/** Backup `stats` section (audit/data §11.4). Ids are backup-local. */
data class BackupStats(
    val completeSongListens: Long = 0,
    val firstPlayedSongId: Long = -1,
    val firstPlayedDate: String? = null,
    val shuffleSessionStarts: Long = 0,
    val songPlayCounts: Map<Long, Long> = emptyMap(),
    val playlistPlayCounts: Map<String, Long> = emptyMap(),
    val weeklyPlayCounts: Map<Long, Long> = emptyMap(),
    val songSkipCounts: Map<Long, Long> = emptyMap(),
    val songRestartCounts: Map<Long, Long> = emptyMap(),
    val songLastPlayedAtMs: Map<Long, Long> = emptyMap(),
) {
    /** Song ids in Flutter order: first played, plays, skips, restarts, last played. */
    val songIds: List<Long>
        get() = buildList {
            if (firstPlayedSongId >= 0) add(firstPlayedSongId)
            addAll(songPlayCounts.keys); addAll(songSkipCounts.keys)
            addAll(songRestartCounts.keys); addAll(songLastPlayedAtMs.keys)
        }

    /** Re-keys song maps through [idMap]; unmatched dropped, collisions summed, last played = max. */
    fun remap(idMap: Map<Long, Long>): BackupStats {
        fun remap(source: Map<Long, Long>, join: (Long, Long) -> Long): Map<Long, Long> {
            val out = LinkedHashMap<Long, Long>()
            for ((id, v) in source) {
                val newId = idMap[id] ?: continue
                out[newId] = out[newId]?.let { join(it, v) } ?: v
            }
            return out
        }
        return copy(
            firstPlayedSongId = idMap[firstPlayedSongId] ?: -1,
            songPlayCounts = remap(songPlayCounts, Long::plus),
            songSkipCounts = remap(songSkipCounts, Long::plus),
            songRestartCounts = remap(songRestartCounts, Long::plus),
            songLastPlayedAtMs = remap(songLastPlayedAtMs, ::maxOf),
        )
    }

    fun toSnapshot(): StatsSnapshot {
        val ids = LinkedHashSet<Long>().apply {
            addAll(songPlayCounts.keys); addAll(songSkipCounts.keys)
            addAll(songRestartCounts.keys); addAll(songLastPlayedAtMs.keys)
        }
        return StatsSnapshot(
            songs = ids.associateWith { id ->
                SongStats(
                    plays = (songPlayCounts[id] ?: 0).toInt(),
                    skips = (songSkipCounts[id] ?: 0).toInt(),
                    restarts = (songRestartCounts[id] ?: 0).toInt(),
                    lastPlayedAtMs = songLastPlayedAtMs[id]?.takeIf { it > 0 },
                )
            },
            weekdayCounts = weeklyPlayCounts.entries.associate { (k, v) -> k.toInt() to v.toInt() },
            playlistPlays = playlistPlayCounts.mapValues { it.value.toInt() },
            completeListens = completeSongListens.toInt(),
            shuffleSessions = shuffleSessionStarts.toInt(),
            firstPlayedSongId = firstPlayedSongId.takeIf { it >= 0 },
            firstPlayedDate = firstPlayedDate,
        )
    }

    companion object {
        fun fromSnapshot(s: StatsSnapshot): BackupStats {
            fun pick(f: (SongStats) -> Long?) =
                s.songs.mapNotNull { (id, st) -> f(st)?.takeIf { it > 0 }?.let { id to it } }.toMap()
            return BackupStats(
                completeSongListens = s.completeListens.toLong(),
                firstPlayedSongId = s.firstPlayedSongId ?: -1,
                firstPlayedDate = s.firstPlayedDate,
                shuffleSessionStarts = s.shuffleSessions.toLong(),
                songPlayCounts = pick { it.plays.toLong() },
                playlistPlayCounts = s.playlistPlays.mapValues { it.value.toLong() },
                weeklyPlayCounts = s.weekdayCounts.entries.associate { (k, v) -> k.toLong() to v.toLong() },
                songSkipCounts = pick { it.skips.toLong() },
                songRestartCounts = pick { it.restarts.toLong() },
                songLastPlayedAtMs = pick { it.lastPlayedAtMs },
            )
        }
    }
}

/** A parsed backup file, version 1 (SPEC §5.5). Settings carry Flutter key names and types. */
data class BackupDocument(
    val createdAtMs: Long,
    val appVersion: String,
    /** Insertion-ordered; keyed by backup-local id. */
    val songs: Map<Long, BackupSongRef>,
    val settings: Map<String, SettingValue>,
    /** False when the file had no `settings` section at all. */
    val hasSettings: Boolean = true,
    val playlists: List<BackupPlaylist>? = null,
    /** Backup id → favorite date (null = unknown). */
    val favoriteSongs: Map<Long, Long?>? = null,
    val favoriteAlbums: Map<String, Long>? = null,
    val favoriteArtists: Map<String, Long>? = null,
    val stats: BackupStats? = null,
) {
    val referencedSongIds: Set<Long>
        get() = LinkedHashSet<Long>().apply {
            playlists?.forEach { addAll(it.songIds) }
            favoriteSongs?.let { addAll(it.keys) }
            stats?.let { addAll(it.songIds) }
        }
}
