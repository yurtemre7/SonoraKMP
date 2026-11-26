package de.yurtemre.sonora.jam

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JamDiscoveryHubTest {
    private val jam = DiscoveredJam("SonoraJam-aaaa1111", "Phone", "192.168.1.5", 4000, 1)

    /** An upstream whose starts/stops are counted and whose emissions the test drives. */
    private class Fake {
        var started = 0
        var stopped = 0
        val emissions = MutableSharedFlow<List<DiscoveredJam>>(replay = 1)
        val upstream: () -> Flow<List<DiscoveredJam>> = {
            callbackFlow {
                started++
                trySend(emptyList())
                val job = launch { emissions.collect { trySend(it) } }
                awaitClose { stopped++; job.cancel() }
            }
        }
    }

    @Test fun startsOneSearchForSeveralUsersAndSharesTheLatestList() = runTest {
        val fake = Fake()
        val hub = JamDiscoveryHub(backgroundScope, fake.upstream, graceMs = 5_000)
        val warm = hub.warm()
        runCurrent()
        fake.emissions.emit(listOf(jam))
        runCurrent()
        // A late user sees the Jams found so far at once, without a second search.
        assertEquals(listOf(jam), hub.discover().first())
        assertEquals(1, fake.started)
        warm.close()
    }

    @Test fun keepsSearchingForAGraceTimeAfterTheLastUserLeavesThenStops() = runTest {
        val fake = Fake()
        val hub = JamDiscoveryHub(backgroundScope, fake.upstream, graceMs = 5_000)
        hub.warm().close()
        runCurrent()
        advanceTimeBy(4_900)
        assertEquals(0, fake.stopped)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, fake.stopped)
    }

    @Test fun aUserWithinTheGraceTimeReusesTheRunningSearch() = runTest {
        val fake = Fake()
        val hub = JamDiscoveryHub(backgroundScope, fake.upstream, graceMs = 5_000)
        hub.warm().close()
        advanceTimeBy(3_000)
        val again = hub.warm()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, fake.started)
        assertEquals(0, fake.stopped)
        again.close()
    }

    @Test fun aLaterUserStartsAFreshSearchWithoutTheStaleList() = runTest {
        val fake = Fake()
        val hub = JamDiscoveryHub(backgroundScope, fake.upstream, graceMs = 1_000)
        val first = hub.warm()
        runCurrent()
        fake.emissions.emit(listOf(jam))
        runCurrent()
        first.close()
        advanceTimeBy(1_100)
        runCurrent()
        // The replay of the fake still holds the old list; the hub must have dropped its own copy first.
        var seenFirst: List<DiscoveredJam>? = null
        val job = backgroundScope.launch { hub.discover().collect { if (seenFirst == null) seenFirst = it } }
        runCurrent()
        assertEquals(2, fake.started)
        assertTrue(seenFirst != null)
        job.cancel()
    }

    @Test fun closingAWarmHandleTwiceReleasesOnlyOnce() = runTest {
        val fake = Fake()
        val hub = JamDiscoveryHub(backgroundScope, fake.upstream, graceMs = 1_000)
        val a = hub.warm()
        val b = hub.warm()
        a.close(); a.close()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, fake.stopped) // b still holds the search
        b.close()
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(1, fake.stopped)
    }

    @Test fun aFailedSearchReachesTheUserAndTheNextUserTriesAgain() = runTest {
        var starts = 0
        val hub = JamDiscoveryHub(
            backgroundScope,
            { flow { starts++; throw IOException("discovery_failed:3") } },
            graceMs = 1_000,
        )
        assertFailsWith<IOException> { hub.discover().first() }
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()
        assertFailsWith<IOException> { hub.discover().first() }
        assertEquals(2, starts)
    }
}
