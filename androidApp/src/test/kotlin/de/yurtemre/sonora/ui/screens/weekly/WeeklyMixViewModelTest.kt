package de.yurtemre.sonora.ui.screens.weekly

import de.yurtemre.sonora.shared.library.WeeklyMix
import de.yurtemre.sonora.shared.library.WeeklyMixRepository
import de.yurtemre.sonora.shared.library.WeeklyMixState
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyMixViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val zone = TimeZone.UTC
    private val t0 = 1_700_000_000_000L

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** Stores one stamped mix; [refreshIfNeeded] replaces it when the clock is in a newer week. */
    private class FakeRepo(private val clock: MutableClock) : WeeklyMixRepository {
        val stored = MutableStateFlow<Long?>(null)
        var refreshCalls = 0
        override fun state(zone: TimeZone): Flow<WeeklyMixState> = stored.map { s ->
            val now = clock.now
            val week = WeeklyMix.weekStart(now, zone)
            WeeklyMixState(week, if (s == week) listOf(SONG) else emptyList(), WeeklyMix.nextRefresh(now, zone))
        }
        override suspend fun refreshIfNeeded(zone: TimeZone): Boolean {
            refreshCalls++
            val week = WeeklyMix.weekStart(clock.now, zone)
            if (stored.value == week) return false
            stored.value = week
            return true
        }
        override suspend fun saveAsPlaylist(name: String): Playlist = error("unused")
    }

    private class MutableClock(var now: Long) : Clock { override fun nowMs() = now }

    @Test fun viewModelRefreshesInPlaceWhenCountdownHitsZero() = runTest(dispatcher) {
        val clock = MutableClock(t0)
        val repo = FakeRepo(clock)
        val vm = WeeklyMixViewModel(repo, flowOf(true), clock, zone)
        val job = backgroundScope.launch { vm.ui.collect {} }
        advanceTimeBy(1_000)
        val first = vm.ui.value
        assertTrue(first.songs.isNotEmpty())

        clock.now = first.nextRefreshMs + 1
        advanceTimeBy(61_000)

        val second = vm.ui.value
        assertNotEquals(first.weekStartMs, second.weekStartMs)
        assertTrue(second.songs.isNotEmpty())
        job.cancel()
    }

    @Test fun viewModelTickerStopsWhenNotCollected() = runTest(dispatcher) {
        val clock = MutableClock(t0)
        val repo = FakeRepo(clock)
        val vm = WeeklyMixViewModel(repo, flowOf(true), clock, zone)
        val job = backgroundScope.launch { vm.ui.collect {} }
        advanceTimeBy(130_000)
        assertTrue(repo.refreshCalls >= 2)
        job.cancel()
        advanceTimeBy(10_000) // WhileSubscribed grace period
        val calls = repo.refreshCalls
        advanceTimeBy(600_000)
        assertEquals(calls, repo.refreshCalls)
    }

    private companion object {
        val SONG = Song(
            id = 1L, title = "t", artist = "a", album = "al", durationMs = 1000L, filePath = "/x.mp3",
        )
    }
}
