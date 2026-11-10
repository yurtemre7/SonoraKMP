package de.yurtemre.sonora.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PositionTickerTest {
    private var pos = 0L
    private var playing = false

    private fun TestScope.setup(): Pair<PositionTicker, MutableList<Long>> {
        val ticker = PositionTicker(backgroundScope, { pos }, { playing })
        val out = mutableListOf<Long>()
        backgroundScope.launch { ticker.positions.toList(out) }
        runCurrent()
        return ticker to out
    }

    @Test fun emitsCurrentPositionOnSubscribeAndTicksEvery200msWhilePlaying() = runTest {
        pos = 1_000; playing = true
        val (ticker, out) = setup()
        assertEquals(listOf(1_000L), out)
        assertTrue(ticker.isTicking)
        pos = 1_200; advanceTimeBy(201)
        pos = 1_400; advanceTimeBy(200)
        assertEquals(listOf(1_000L, 1_200L, 1_400L), out)
    }

    @Test fun stopsTickingWhenPausedOrBackgrounded() = runTest {
        playing = true
        val (ticker, out) = setup()
        playing = false; ticker.onPlayerEvent()
        assertFalse(ticker.isTicking)
        pos = 5_000; advanceTimeBy(1_000)
        assertEquals(listOf(0L), out)
        playing = true; ticker.onPlayerEvent(); ticker.setForeground(false)
        assertFalse(ticker.isTicking)
        ticker.setForeground(true); runCurrent()
        assertEquals(5_000L, out.last()) // catches up immediately on return
    }

    @Test fun dropsSmallBackwardCorrectionsButKeepsLargeOnes() = runTest {
        pos = 2_000; playing = true
        val (_, out) = setup()
        pos = 1_000; advanceTimeBy(201) // 1000 ms back: dropped
        pos = 400; advanceTimeBy(200)   // 1600 ms back: emitted
        assertEquals(listOf(2_000L, 400L), out)
    }

    @Test fun discontinuityAllowsAnyBackwardMove() = runTest {
        pos = 2_000
        val (ticker, out) = setup()
        pos = 1_500; ticker.onDiscontinuity(); runCurrent()
        assertEquals(listOf(2_000L, 1_500L), out)
    }
}
