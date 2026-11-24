package de.yurtemre.sonora.shared.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.Clock
import de.yurtemre.sonora.shared.repo.api.LibraryRepository
import de.yurtemre.sonora.shared.repo.api.PlaylistRepository
import de.yurtemre.sonora.shared.repo.api.StatsRepository
import de.yurtemre.sonora.shared.settings.SettingsKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The weekly mix as shown: [weekStartMs] of its week, [songs] resolved against the current library. */
data class WeeklyMixState(val weekStartMs: Long, val songs: List<Song>, val nextRefreshMs: Long)

interface WeeklyMixRepository {
    /** Current mix; re-emits when the library or the stored mix changes. Does not generate. */
    fun state(zone: TimeZone): Flow<WeeklyMixState>

    /** Generates and stores a new mix when none is stored for the current week; true when it did. */
    suspend fun refreshIfNeeded(zone: TimeZone): Boolean

    /** Copies the current mix into a new playlist; the playlist is unaffected by later refreshes. */
    suspend fun saveAsPlaylist(name: String): Playlist
}

/** Persisted value; paths (not ids) so the list survives a re-index. */
@Serializable
data class StoredWeeklyMix(val weekStartMs: Long, val songPaths: List<String>)

interface WeeklyMixStore {
    val stored: Flow<StoredWeeklyMix?>
    suspend fun save(mix: StoredWeeklyMix)
}

class DataStoreWeeklyMixStore(private val dataStore: DataStore<Preferences>) : WeeklyMixStore {
    override val stored: Flow<StoredWeeklyMix?> = dataStore.data.map { p ->
        p[SettingsKeys.WEEKLY_MIX]?.let { raw ->
            runCatching { json.decodeFromString<StoredWeeklyMix>(raw) }.getOrNull()
        }
    }

    override suspend fun save(mix: StoredWeeklyMix) {
        dataStore.edit { it[SettingsKeys.WEEKLY_MIX] = json.encodeToString(StoredWeeklyMix.serializer(), mix) }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

class WeeklyMixRepositoryImpl(
    private val library: LibraryRepository,
    private val stats: StatsRepository,
    private val playlists: PlaylistRepository,
    private val store: WeeklyMixStore,
    private val clock: Clock,
) : WeeklyMixRepository {

    override fun state(zone: TimeZone): Flow<WeeklyMixState> =
        combine(library.songs, store.stored) { songs, stored ->
            val now = clock.nowMs()
            WeeklyMixState(
                weekStartMs = stored?.weekStartMs ?: WeeklyMix.weekStart(now, zone),
                songs = resolve(stored, songs),
                nextRefreshMs = WeeklyMix.nextRefresh(now, zone),
            )
        }

    override suspend fun refreshIfNeeded(zone: TimeZone): Boolean {
        val now = clock.nowMs()
        val weekStart = WeeklyMix.weekStart(now, zone)
        if (store.stored.first()?.weekStartMs == weekStart) return false
        val songs = library.songs.first()
        // An empty library usually means "not loaded yet"; do not lock in an empty week.
        if (songs.isEmpty()) return false
        val built = WeeklyMix.build(songs, stats.snapshot.first().songs, now)
        store.save(StoredWeeklyMix(weekStart, built.map { it.filePath }))
        return true
    }

    override suspend fun saveAsPlaylist(name: String): Playlist {
        val songs = resolve(store.stored.first(), library.songs.first())
        return playlists.create(name, songs.map { it.id })
    }

    private fun resolve(stored: StoredWeeklyMix?, songs: List<Song>): List<Song> {
        if (stored == null) return emptyList()
        val byPath = songs.associateBy { it.filePath }
        return stored.songPaths.mapNotNull { byPath[it] }
    }
}
