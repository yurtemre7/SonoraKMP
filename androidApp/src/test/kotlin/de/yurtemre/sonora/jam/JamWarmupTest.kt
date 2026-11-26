package de.yurtemre.sonora.jam

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class JamWarmupTest {
    private var warmed = 0
    private var closed = 0
    private val warm = { warmed++; AutoCloseable { closed++ } }

    @Test fun holdsTheSearchWhileEnabledAndReleasesWhenTurnedOff() = runTest {
        val enabled = MutableStateFlow(true)
        val job = launch { runJamWarmup(enabled, { true }, warm) }
        runCurrent()
        assertEquals(1, warmed)
        enabled.value = false
        runCurrent()
        assertEquals(1, closed)
        enabled.value = true
        runCurrent()
        assertEquals(2, warmed)
        job.cancel()
        runCurrent()
        assertEquals(2, closed)
    }

    @Test fun staysIdleWithoutTheLocalNetworkGrant() = runTest {
        val job = launch { runJamWarmup(MutableStateFlow(true), { false }, warm) }
        runCurrent()
        assertEquals(0, warmed)
        job.cancel()
    }

    @Test fun staysIdleWhileTheSettingIsOff() = runTest {
        val job = launch { runJamWarmup(MutableStateFlow(false), { true }, warm) }
        runCurrent()
        assertEquals(0, warmed)
        job.cancel()
    }
}
