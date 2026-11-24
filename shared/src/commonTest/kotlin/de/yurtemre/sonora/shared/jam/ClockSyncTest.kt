package de.yurtemre.sonora.shared.jam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClockSyncTest {
    @Test fun nullBeforeAnySample() {
        val c = ClockSync()
        assertNull(c.offsetMs())
        assertEquals(1000L, c.toLocal(1000L))
    }

    @Test fun lowestRttWins() {
        val c = ClockSync()
        c.addSample(sentMs = 1000, hostMs = 5000, receivedMs = 1100) // rtt 100, offset 3950
        c.addSample(sentMs = 2000, hostMs = 5010, receivedMs = 2020) // rtt 20, offset 3000
        c.addSample(sentMs = 3000, hostMs = 9999, receivedMs = 3400) // rtt 400
        assertEquals(3000L, c.offsetMs())
        assertEquals(7000L, c.toLocal(10_000L))
    }

    @Test fun negativeRttIgnored() {
        val c = ClockSync()
        c.addSample(1000, 5000, 900)
        assertNull(c.offsetMs())
        c.addSample(1000, 5000, 1100)
        c.addSample(1000, 1, 900)
        assertEquals(3950L, c.offsetMs())
    }

    @Test fun onlyFirstEightSamplesCount() {
        val c = ClockSync()
        repeat(8) { c.addSample(0, 100, 100) } // rtt 100, offset 50
        c.addSample(0, 1000, 2) // rtt 2 but 9th: ignored
        assertEquals(50L, c.offsetMs())
    }

    private fun full(c: ClockSync) = repeat(8) { c.addSample(0, 100, 100) } // offset 50

    @Test fun worseFirstSampleAfterResetKeepsOffset() {
        val c = ClockSync()
        full(c)
        c.reset()
        assertEquals(50L, c.offsetMs())
        c.addSample(0, 1000, 400)
        assertEquals(50L, c.offsetMs())
    }

    @Test fun offsetSwitchesWhenEighthSampleArrives() {
        val c = ClockSync()
        full(c)
        c.reset()
        c.addSample(0, 1000, 400) // offset 800
        repeat(6) { c.addSample(0, 1000, 20) } // best offset 990
        assertEquals(50L, c.offsetMs())
        c.addSample(0, 1000, 20)
        assertEquals(990L, c.offsetMs())
    }

    @Test fun commitAppliesBestOfPending() {
        val c = ClockSync()
        full(c)
        c.reset()
        c.addSample(0, 1000, 400)
        c.addSample(0, 1000, 20)
        c.addSample(0, 1000, 100)
        assertEquals(50L, c.offsetMs())
        c.commit()
        assertEquals(990L, c.offsetMs())
    }

    @Test fun commitWithNoPendingIsNoOp() {
        val c = ClockSync()
        full(c)
        c.reset()
        c.commit()
        assertEquals(50L, c.offsetMs())
        val fresh = ClockSync()
        fresh.commit()
        assertNull(fresh.offsetMs())
    }

    @Test fun firstWindowPublishesAfterOneSampleAndImproves() {
        val c = ClockSync()
        c.addSample(0, 1000, 400)
        assertEquals(800L, c.offsetMs())
        c.addSample(0, 1000, 20)
        assertEquals(990L, c.offsetMs())
    }

    @Test fun overflowSafe() {
        val c = ClockSync()
        c.addSample(Long.MAX_VALUE - 10, Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE - (Long.MAX_VALUE - 10 + 5), c.offsetMs())
    }
}
