package de.yurtemre.sonora.shared.repo.impl

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import de.yurtemre.sonora.shared.backup.RestorePlan
import de.yurtemre.sonora.shared.backup.RestoreState
import de.yurtemre.sonora.shared.backup.SettingValue
import de.yurtemre.sonora.shared.db.FavoriteAlbumEntity
import de.yurtemre.sonora.shared.db.FavoriteArtistEntity
import de.yurtemre.sonora.shared.db.FavoriteSongEntity
import de.yurtemre.sonora.shared.db.PlaylistStatsEntity
import de.yurtemre.sonora.shared.db.SongStatsEntity
import de.yurtemre.sonora.shared.db.SonoraDatabase
import de.yurtemre.sonora.shared.db.StatsSummaryEntity
import de.yurtemre.sonora.shared.db.WeekdayStatsEntity
import de.yurtemre.sonora.shared.db.buildPlaylists
import de.yurtemre.sonora.shared.db.toEntity
import de.yurtemre.sonora.shared.db.toEntryEntities
import de.yurtemre.sonora.shared.db.toModel
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.settings.SettingsKeys
import de.yurtemre.sonora.shared.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Reads and writes shared by backup restore (BAK-04) and device sync (DSYNC-05): the current library,
 * playlists, favorites and DataStore settings, and applying a [RestorePlan].
 */
internal class BackupDataAccess(
    private val db: SonoraDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val songDao = db.songDao()
    private val playlistDao = db.playlistDao()
    private val favDao = db.favoritesDao()
    private val statsDao = db.statsDao()

    // ── Reads ───────────────────────────────────────────────────────────────

    suspend fun library(): List<Song> {
        val favs = favDao.getSongs().associateBy { it.songId }
        return songDao.getAll().map { it.toModel(favs[it.id]) }
    }

    suspend fun playlists(): List<Playlist> =
        buildPlaylists(playlistDao.getPlaylists(), playlistDao.observeEntries().first())

    suspend fun favoriteAlbums(): Map<String, Long> = favDao.observeAlbums().first().associate { it.key to it.favoritedAtMs }

    suspend fun favoriteArtists(): Map<String, Long> = favDao.observeArtists().first().associate { it.key to it.favoritedAtMs }

    suspend fun restoreState(prefs: Preferences): RestoreState = RestoreState(
        library = library(), playlists = playlists(),
        favoriteAlbums = favoriteAlbums(), favoriteArtists = favoriteArtists(),
        settings = storedSettings(prefs),
    )

    suspend fun readPrefs(): Preferences {
        var snap: Preferences? = null
        settings.edit { snap = it.toPreferences() }
        return snap!!
    }

    /** DataStore values → backup-typed [SettingValue]s (Long/Int → Int, Double → Decimal, gains JSON stays Str). */
    fun storedSettings(p: Preferences): Map<String, SettingValue> {
        val out = LinkedHashMap<String, SettingValue>()
        for ((key, value) in p.asMap()) {
            out[key.name] = when (value) {
                is Boolean -> SettingValue.Bool(value)
                is Int -> SettingValue.Int(value.toLong())
                is Long -> SettingValue.Int(value)
                is Double -> SettingValue.Decimal(value)
                is Float -> SettingValue.Decimal(value.toDouble())
                is String -> SettingValue.Str(value)
                else -> continue
            }
        }
        return out
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    suspend fun writePrefs(snapshot: Preferences) = settings.edit { m ->
        m.clear()
        @Suppress("UNCHECKED_CAST")
        for ((k, v) in snapshot.asMap()) m[k as Preferences.Key<Any>] = v
    }

    suspend fun applySettings(plan: RestorePlan) {
        if (plan.settingWrites.isEmpty() && plan.settingRemovals.isEmpty()) return
        settings.edit { m ->
            for (k in plan.settingRemovals) {
                m.asMap().keys.filter { it.name == k }.forEach { m.remove(it) }
            }
            for ((k, v) in plan.settingWrites) {
                // Drop any same-named key of another type first.
                m.asMap().keys.filter { it.name == k }.forEach { m.remove(it) }
                when (v) {
                    is SettingValue.Bool -> m[booleanPreferencesKey(k)] = v.value
                    is SettingValue.Int ->
                        if (k in longKeys) m[longPreferencesKey(k)] = v.value else m[intPreferencesKey(k)] = v.value.toInt()
                    is SettingValue.Decimal -> m[doublePreferencesKey(k)] = v.value
                    is SettingValue.Str -> m[stringPreferencesKey(k)] = v.value
                    is SettingValue.StrList -> Unit // never produced for stored keys
                }
            }
        }
    }

    suspend fun applyDb(plan: RestorePlan) = db.useWriterConnection { c ->
        c.immediateTransaction {
            val pathById = songDao.getAll().associate { it.id to it.filePath }
            plan.favoriteAlbums?.let { m -> favDao.upsertAlbums(m.map { FavoriteAlbumEntity(it.key, it.value) }) }
            plan.favoriteArtists?.let { m -> favDao.upsertArtists(m.map { FavoriteArtistEntity(it.key, it.value) }) }
            if (plan.newFavoriteSongs.isNotEmpty()) {
                favDao.upsertSongs(plan.newFavoriteSongs.mapNotNull { (id, date) ->
                    pathById[id]?.let { FavoriteSongEntity(id, it, date) }
                })
            }
            plan.playlists?.let { list ->
                var pos = playlistDao.maxPosition()
                for (p in list) {
                    val existing = playlistDao.getPlaylist(p.id)
                    val entity = p.toEntity(existing?.createdAtMs ?: (p.id.toLongOrNull() ?: clock.nowMs()), existing?.position ?: ++pos)
                    playlistDao.upsertWithEntries(entity, p.toEntryEntities { pathById[it] })
                }
            }
            plan.stats?.let { s ->
                statsDao.clearAll()
                statsDao.upsertSongStats(s.songs.map { (id, st) ->
                    SongStatsEntity(id, pathById[id], st.plays, st.skips, st.restarts, st.lastPlayedAtMs)
                })
                statsDao.upsertWeekdayStats(s.weekdayCounts.map { WeekdayStatsEntity(it.key, it.value) })
                statsDao.upsertPlaylistStats(s.playlistPlays.map { PlaylistStatsEntity(it.key, it.value) })
                statsDao.upsertSummary(StatsSummaryEntity(
                    completeSongListens = s.completeListens,
                    firstPlayedSongId = s.firstPlayedSongId ?: -1,
                    firstPlayedDate = s.firstPlayedDate?.replace("-", "")?.toLongOrNull(), // yyyyMMdd
                    shuffleSessionStarts = s.shuffleSessions,
                ))
            }
        }
    }

    private companion object {
        /** Integer settings stored as DataStore Long keys. */
        val longKeys = setOf(SettingsKeys.DEFAULT_THEME_COLOR.name)
    }
}
