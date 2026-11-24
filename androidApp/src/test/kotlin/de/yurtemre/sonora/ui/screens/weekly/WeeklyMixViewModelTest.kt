package de.yurtemre.sonora.ui.screens.weekly

import de.yurtemre.sonora.shared.library.WeeklyMix
import de.yurtemre.sonora.shared.library.WeeklyMixRepository
import de.yurtemre.sonora.shared.library.WeeklyMixState
import de.yurtemre.sonora.shared.model.Playlist
import de.yurtemre.sonora.shared.model.Song
import de.yurtemre.sonora.shared.repo.api.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
        var gate: CompletableDeferred<Unit>? = null
        var storeOnRefresh = true
        /** Bump to make [state] re-read the clock, as the real repository does when the library changes. */
        val tick = MutableStateFlow(0)
        override fun state(zone: TimeZone): Flow<WeeklyMixState> = combine(stored, tick) { s, _ -> s }.map { s ->
            val now = clock.now
            val week = WeeklyMix.weekStart(now, zone)
            WeeklyMixState(week, if (s == week) listOf(SONG) else emptyList(), WeeklyMix.nextRefresh(now, zone))
        }
        override suspend fun refreshIfNeeded(zone: TimeZone): Boolean {
            refreshCalls++
            gate?.await()
            if (!storeOnRefresh) return false
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
        val vm = WeeklyMixViewModel(repo, flowOf(true), clock, { zone })
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
        val vm = WeeklyMixViewModel(repo, flowOf(true), clock, { zone })
        val job = backgroundScope.launch { vm.ui.collect {} }
        advanceTimeBy(130_000)
        assertTrue(repo.refreshCalls >= 2)
        job.cancel()
        advanceTimeBy(10_000) // WhileSubscribed grace period
        val calls = repo.refreshCalls
        advanceTimeBy(600_000)
        assertEquals(calls, repo.refreshCalls)
    }

    @Test fun boundaryWakesBeforeTheMinuteTick() = runTest(dispatcher) {
        val next = WeeklyMix.nextRefresh(t0, zone)
        val clock = MutableClock(next - 30_000)
        val repo = FakeRepo(clock)
        val vm = WeeklyMixViewModel(repo, flowOf(true), clock, { zone })
        backgroundScope.launch { vm.ui.collect {} }
        advanceTimeBy(1_000)
        val first = vm.ui.value.weekStartMs
        val calls = repo.refreshCalls
        clock.now = next
        advanceTimeBy(30_000)
        assertTrue(repo.refreshCalls > calls)
        assertNotEquals(first, vm.ui.value.weekStartMs)
    }

    @Test fun libraryBecomingNonEmptyRefreshes() = runTest(dispatcher) {
        val clock = MutableClock(t0)
        val repo = FakeRepo(clock)
        val lib = MutableStateFlow(false)
        val vm = WeeklyMixViewModel(repo, lib, clock, { zone })
        backgroundScope.launch { vm.ui.collect {} }
        advanceTimeBy(1_000)
        val calls = repo.refreshCalls
        lib.value = true
        advanceTimeBy(1_000)
        assertEquals(calls + 1, repo.refreshCalls)
    }

    @Test fun onResumeRefreshes() = runTest(dispatcher) {
        val clock = MutableClock(t0)
        val repo = FakeRepo(clock)
        val vm = WeeklyMixViewModel(repo, flowOf(false), clock, { zone })
        backgroundScope.launch { vm.ui.collect {} }
        advanceTimeBy(1_000)
        val calls = repo.refreshCalls
        vm.onResume()
        advanceTimeBy(1_000)
        assertEquals(calls + 1, repo.refreshCalls)
    }

    @Test fun notLoadedUntilFirstRefreshThenEmptyStateOnlyWhenTrulyEmpty() = runTest(dispatcher) {
        val clock = MutableClock(t0)
        val repo = FakeRepo(clock).apply { gate = CompletableDeferred(); storeOnRefresh = false }
        val vm = WeeklyMixViewModel(repo, flowOf(true), clock, { zone })
        backgroundScope.launch { vm.ui.collect {} }
        advanceTimeBy(1_000)
        assertEquals(false, vm.ui.value.loaded)
        repo.gate!!.complete(Unit)
        advanceTimeBy(1_000)
        assertTrue(vm.ui.value.loaded)
        assertTrue(vm.ui.value.songs.isEmpty())
    }

    @Test fun rolloverKeepsPreviousSongsWhileRefreshing() = runTest(dispatcher) {
        val clock = MutableClock(t0)
        val repo = FakeRepo(clock)
        val vm = WeeklyMixViewModel(repo, flowOf(true), clock, { zone })
        backgroundScope.launch { vm.ui.collect {} }
        advanceTimeBy(1_000)
        assertTrue(vm.ui.value.songs.isNotEmpty())
        assertEquals(false, vm.ui.value.stale)
        repo.gate = CompletableDeferred()
        clock.now = vm.ui.value.nextRefreshMs + 1
        advanceTimeBy(61_000) // tick refresh now runs, blocked on the gate
        repo.tick.value++ // the new week has no stored mix: the repository emits an empty list
        advanceTimeBy(1_000)
        assertTrue(vm.ui.value.loaded)
        assertTrue(vm.ui.value.songs.isNotEmpty())
        assertTrue(vm.ui.value.stale)
        repo.gate!!.complete(Unit)
        advanceTimeBy(1_000)
        assertTrue(vm.ui.value.songs.isNotEmpty())
        assertEquals(false, vm.ui.value.stale)
    }

    @Test fun laterRefreshesNeverBlinkTheEmptyState() = runTest(dispatcher) {
        val clock = MutableClock(t0)
        val repo = FakeRepo(clock).apply { storeOnRefresh = false }
        val vm = WeeklyMixViewModel(repo, flowOf(false), clock, { zone })
        backgroundScope.launch { vm.ui.collect {} }
        advanceTimeBy(1_000)
        assertTrue(vm.ui.value.loaded)
        repo.gate = CompletableDeferred()
        vm.onResume()
        advanceTimeBy(1_000)
        assertTrue(vm.ui.value.loaded)
        assertTrue(vm.ui.value.songs.isEmpty())
        repo.gate!!.complete(Unit)
    }

    @Test fun slowLibraryLoadNeverFlashesTheEmptyState() = runTest(dispatcher) {
        val clock = MutableClock(t0)
        val repo = FakeRepo(clock).apply { storeOnRefresh = false } // empty library: nothing stored
        val lib = MutableStateFlow(false)
        val vm = WeeklyMixViewModel(repo, lib, clock, { zone })
        backgroundScope.launch { vm.ui.collect {} }
        advanceTimeBy(1_000)
        assertTrue(vm.ui.value.loaded)
        repo.storeOnRefresh = true
        repo.gate = CompletableDeferred()
        lib.value = true
        advanceTimeBy(1_000)
        assertEquals(false, vm.ui.value.loaded)
        repo.gate!!.complete(Unit)
        advanceTimeBy(1_000)
        assertTrue(vm.ui.value.loaded)
        assertTrue(vm.ui.value.songs.isNotEmpty())
    }

    @Test fun genuinelyEmptyLibraryEndsLoadedAndEmpty() = runTest(dispatcher) {
        val clock = MutableClock(t0)
        val repo = FakeRepo(clock).apply { storeOnRefresh = false }
        val vm = WeeklyMixViewModel(repo, flowOf(false), clock, { zone })
        backgroundScope.launch { vm.ui.collect {} }
        advanceTimeBy(1_000)
        assertTrue(vm.ui.value.loaded)
        assertTrue(vm.ui.value.songs.isEmpty())
    }

    private companion object {
        val SONG = Song(
            id = 1L, title = "t", artist = "a", album = "al", durationMs = 1000L, filePath = "/x.mp3",
        )
    }
}
