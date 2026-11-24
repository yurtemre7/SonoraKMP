package de.yurtemre.sonora.jam

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import de.yurtemre.sonora.playback.PlaybackPersistence
import de.yurtemre.sonora.shared.settings.PlaybackStateStore
import de.yurtemre.sonora.shared.settings.SavedPlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Jam mirror is never persisted: an app killed mid-Jam comes back with the guest's own queue. */
@OptIn(ExperimentalCoroutinesApi::class)
class JamControllerPersistenceTest {
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private val own = SavedPlaybackState(songId = 2, queueIds = listOf(1, 2, 3), originalQueueIds = listOf(1, 2, 3), positionMs = 42_000, isShuffled = false)
    private val jam = SavedPlaybackState(songId = 9, queueIds = listOf(9, 8), originalQueueIds = listOf(9, 8), positionMs = 0, isShuffled = false)

    @Test fun appKilledMidJamRestoresThePreviousQueueNotTheJamQueue() = runTest {
        val store = PlaybackStateStore(MemoryDataStore())
        var current: SavedPlaybackState? = own
        var position = 42_000L // what the periodic save writes
        val persistence = PlaybackPersistence(store, backgroundScope, { current }, { position }, { true })

        persistence.beginMirror() // saves the guest's own queue (and position) one last time
        runCurrent()
        current = jam
        position = 5_000L
        persistence.onTrackChanged()
        persistence.onIsPlayingChanged(true)
        advanceTimeBy(PlaybackPersistence.PERIODIC_MS * 3)
        persistence.onIsPlayingChanged(false)
        persistence.saveNow()
        runCurrent()

        // The process dies here; the next start reads the store.
        val restored = PlaybackPersistence(store, backgroundScope, { null }, { 0L }, { true }).loadForRestore()
        assertEquals(own, restored)
    }

    @Test fun afterTheMirrorEndsSavingResumes() = runTest {
        val store = PlaybackStateStore(MemoryDataStore())
        var current: SavedPlaybackState? = own
        val persistence = PlaybackPersistence(store, backgroundScope, { current }, { 1_000L }, { true })
        persistence.beginMirror()
        current = jam
        persistence.saveNow()
        persistence.endMirror()
        current = own.copy(songId = 3)
        persistence.saveNow()
        runCurrent()
        assertEquals(3L, store.load()?.songId)
    }
}
