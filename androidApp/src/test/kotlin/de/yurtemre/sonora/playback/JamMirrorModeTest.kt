package de.yurtemre.sonora.playback

import de.yurtemre.sonora.shared.model.QueueEntry
import de.yurtemre.sonora.shared.model.QueueState
import de.yurtemre.sonora.shared.model.RepeatMode
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import de.yurtemre.sonora.shared.settings.PlaybackStateStore
import de.yurtemre.sonora.shared.settings.SavedPlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JamMirrorModeTest {
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private val own = SavedPlaybackState(songId = 2, queueIds = listOf(1, 2, 3), originalQueueIds = listOf(1, 2, 3), positionMs = 42_000, isShuffled = false)
    private val jam = SavedPlaybackState(songId = 9, queueIds = listOf(9, 8), originalQueueIds = listOf(9, 8), positionMs = 0, isShuffled = false)
    private val speed = mutableListOf<Pair<Float, Float>?>()
    private val silence = mutableListOf<Boolean>()
    private val store = PlaybackStateStore(MemoryDataStore())
    private var current: SavedPlaybackState? = own

    private fun TestScope.mode(): Pair<JamMirrorMode, PlaybackPersistence> {
        val persistence = PlaybackPersistence(store, backgroundScope, { current }, { 0L }, { true })
        return JamMirrorMode(persistence, applyRate = { s, p -> speed += if (s == null || p == null) null else s to p }, setSkipSilence = { silence += it }) to persistence
    }

    @Test fun skipSilenceIsOffWhileMirroringAndTheUsersValueComesBack() = runTest {
        val (mode, _) = mode()
        mode.userSkipSilence = true
        assertEquals(listOf(true), silence)
        mode.begin { assertTrue("the audio settings change after the load", speed.isEmpty()) }
        assertEquals(listOf(true, false), silence)
        assertEquals(listOf(1f to 1f), speed)
        mode.userSkipSilence = true // the setting is re-read while in the Jam: stays off
        assertEquals(false, silence.last())
        mode.end { assertEquals("the pause comes first, at Jam speed", listOf(1f to 1f), speed) }
        assertEquals(true, silence.last())
        assertEquals(listOf(1f to 1f, null), speed)
        mode.end { error("idempotent") }
        assertEquals(2, speed.size)
    }

    @Test fun theGuestPlaysAtTheHostsRateAndTheUsersOwnComesBack() = runTest {
        val (mode, _) = mode()
        mode.setRate(1.25f, 1.25f) // told before the first load: used by it
        mode.begin { }
        assertEquals(listOf(1.25f to 1.25f), speed)
        mode.setRate(0.8f, 1f)
        assertEquals(listOf(1.25f to 1.25f, 0.8f to 1f), speed)
        mode.end { }
        assertEquals(null, speed.last())
        mode.setRate(1.5f, 1.5f) // not mirroring: nothing applied
        assertEquals(3, speed.size)
    }

    /** ExoPlayer reports the pause from inside pauseImmediately(): that save must not store the Jam queue. */
    @Test fun endingTheMirrorNeverSavesTheJamQueue() = runTest {
        val (mode, persistence) = mode()
        mode.begin { current = jam } // the own queue is saved before the mirror loads
        persistence.onIsPlayingChanged(true)
        runCurrent()
        mode.end { persistence.onIsPlayingChanged(false) } // close player / leave: pause first
        runCurrent()
        assertEquals(own, store.load())
        current = own.copy(songId = 3) // the restored own queue saves normally again
        persistence.saveNow()
        runCurrent()
        assertEquals(3L, store.load()?.songId)
    }

    private val playing = QueueState(entries = listOf(QueueEntry(7, 1), QueueEntry(8, 2)), currentIndex = 0, nextUid = 9)

    @Test fun samePlayingSongIsKeptAndAPlayRequestHonoured() {
        val edit = mirrorEdit(applied = playing, latest = playing, mirroring = true, ids = listOf(1, 3), index = 0, positionMs = 5_000, play = true)
        edit as MirrorEdit.Keep
        assertTrue(edit.play)
        assertEquals(listOf(QueueEntry(7, 1), QueueEntry(9, 3)), edit.state.entries)
        assertEquals(RepeatMode.Off, edit.state.repeat)
    }

    @Test fun aLoadStillBeingBuiltIsReplacedWithTheGivenPositionAndPlay() {
        // The first mirror load is still being built: never fall back to "position 0, whatever the player had".
        val pending = playing.copy(nextUid = 20)
        val edit = mirrorEdit(applied = playing, latest = pending, mirroring = true, ids = listOf(1, 2), index = 0, positionMs = 5_000, play = true)
        assertEquals(MirrorEdit.Load::class, edit::class)
        edit as MirrorEdit.Load
        assertEquals(5_000L, edit.positionMs)
        assertTrue(edit.play)
    }

    @Test fun firstMirrorLoadIsALoad() {
        val edit = mirrorEdit(applied = playing, latest = playing, mirroring = false, ids = listOf(1), index = 0, positionMs = 0, play = false)
        assertTrue(edit is MirrorEdit.Load)
    }

    @Test fun repeatOneKeepsThePlayingEntryAndLoopsIt() {
        val edit = mirrorEdit(applied = playing, latest = playing, mirroring = true, ids = listOf(1), index = 0, positionMs = 5_000, play = true, repeatOne = true)
        edit as MirrorEdit.Keep
        assertEquals(RepeatMode.One, edit.state.repeat)
    }
}
