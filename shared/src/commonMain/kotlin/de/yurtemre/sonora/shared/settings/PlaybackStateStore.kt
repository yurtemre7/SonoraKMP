package de.yurtemre.sonora.shared.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * Persisted last-played state (PLAY-24, audit/playback §11). Repeat mode is intentionally NOT persisted;
 * ids are stored comma-separated (SPEC §5.3).
 */
data class SavedPlaybackState(
    val songId: Long,
    val queueIds: List<Long>,
    val originalQueueIds: List<Long>,
    val positionMs: Long,
    val isShuffled: Boolean,
)

class PlaybackStateStore(private val dataStore: DataStore<Preferences>) {

    /** Returns null when no song id was saved. */
    suspend fun load(): SavedPlaybackState? {
        val p = dataStore.data.first()
        val songId = p[SettingsKeys.LAST_PLAYED_SONG_ID] ?: return null
        return SavedPlaybackState(
            songId = songId,
            queueIds = parseIds(p[SettingsKeys.LAST_PLAYED_QUEUE_IDS]),
            originalQueueIds = parseIds(p[SettingsKeys.LAST_PLAYED_ORIGINAL_QUEUE_IDS]),
            positionMs = p[SettingsKeys.LAST_PLAYED_POSITION_MS] ?: 0L,
            isShuffled = p[SettingsKeys.LAST_PLAYED_IS_SHUFFLED] ?: false,
        )
    }

    suspend fun save(state: SavedPlaybackState) {
        dataStore.edit {
            it[SettingsKeys.LAST_PLAYED_SONG_ID] = state.songId
            it[SettingsKeys.LAST_PLAYED_QUEUE_IDS] = state.queueIds.joinToString(",")
            it[SettingsKeys.LAST_PLAYED_ORIGINAL_QUEUE_IDS] = state.originalQueueIds.joinToString(",")
            it[SettingsKeys.LAST_PLAYED_POSITION_MS] = state.positionMs
            it[SettingsKeys.LAST_PLAYED_IS_SHUFFLED] = state.isShuffled
        }
    }

    /** Position-only update for the periodic save. */
    suspend fun savePosition(positionMs: Long) {
        dataStore.edit { it[SettingsKeys.LAST_PLAYED_POSITION_MS] = positionMs }
    }

    /** Clear on close player / notification dismiss (audit/playback §11). */
    suspend fun clear() {
        dataStore.edit {
            it.remove(SettingsKeys.LAST_PLAYED_SONG_ID)
            it.remove(SettingsKeys.LAST_PLAYED_QUEUE_IDS)
            it.remove(SettingsKeys.LAST_PLAYED_ORIGINAL_QUEUE_IDS)
            it.remove(SettingsKeys.LAST_PLAYED_POSITION_MS)
            it.remove(SettingsKeys.LAST_PLAYED_IS_SHUFFLED)
        }
    }

    internal companion object {
        fun parseIds(raw: String?): List<Long> =
            raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
    }
}
