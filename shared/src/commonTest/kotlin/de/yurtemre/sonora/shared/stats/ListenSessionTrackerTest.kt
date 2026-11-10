package de.yurtemre.sonora.shared.stats

import de.yurtemre.sonora.shared.stats.StatsEvent.*
import kotlin.test.*

class ListenSessionTrackerTest {
    private val events = mutableListOf<StatsEvent>()
    private var delay: Long? = null
    private val t = ListenSessionTracker({ 42L }, { delay = it }, { events += it })

    @Test fun milestones() {
        assertEquals(10_000, ListenSessionTracker.recentMilestone(20_000))
        assertEquals(15_000, ListenSessionTracker.recentMilestone(60_000))
        assertEquals(30_000, ListenSessionTracker.recentMilestone(600_000))
        assertEquals(5_000, ListenSessionTracker.recentMilestone(5_000))
        assertEquals(160_000, ListenSessionTracker.completeMilestone(200_000))
    }

    @Test fun firesRecentThenCompleteUnderSpeed() {
        t.onItemStarted(7, 200_000, "p")
        t.onPlayingChanged(true, 0)
        assertEquals(30_000, delay)
        t.onSpeedChanged(2f, 10_000)
        assertEquals(10_000, delay)
        t.onTimerFired(29_600) // within 500 ms tolerance
        assertEquals(listOf<StatsEvent>(RecordRecent(7, 42)), events)
        assertEquals(65_200, delay)
        t.onTimerFired(160_000)
        assertEquals(RecordComplete(7, 42, "p"), events.last())
        assertNull(delay)
        t.onTimerFired(190_000)
        assertEquals(2, events.size)
    }

    @Test fun seekForwardAddsNoListenedTime() {
        t.onItemStarted(1, 100_000, null); t.onPlayingChanged(true, 0)
        t.onSeek(0, 90_000)
        assertTrue(events.isEmpty()); assertEquals(25_000, delay)
        t.onTimerFired(100_000) // only 10 s actually listened
        assertTrue(events.isEmpty()); assertEquals(10_000, t.listenedMs)
    }

    @Test fun pausedNeverCountsAndZeroDuration() {
        t.onItemStarted(1, 100_000, null); t.onPlayingChanged(false, 0)
        t.onSeek(0, 90_000)
        assertTrue(events.isEmpty()); assertNull(delay)
        t.onItemStarted(2, 0, null); t.onPlayingChanged(true, 0); t.onTimerFired(1_000)
        assertTrue(events.isEmpty())
    }

    @Test fun seekBackResetsAndRestarts() {
        t.onItemStarted(1, 100_000, null); t.onPlayingChanged(true, 0)
        t.onTimerFired(25_000)
        t.onSeek(26_000, 1_000)
        assertEquals(listOf(RecordRecent(1, 42), RecordRestart(1)), events)
        t.onTimerFired(26_000)
        assertEquals(RecordRecent(1, 42), events.last()); assertEquals(3, events.size)
        t.onSeek(2_000, 1_000) // from < 3 s: no restart
        assertEquals(3, events.size)
    }

    @Test fun skipsRestartsShuffleAndPauseNesting() {
        t.onItemStarted(5, 100_000, null)
        t.onSkipNext(isLastItem = true); t.onSkipNext(isLastItem = false)
        t.onPrevious(2_000); t.onPrevious(4_000)
        t.onShuffleSessionStarted()
        assertEquals(listOf(RecordSkip(5), RecordRestart(5), RecordShuffleSession), events)
        t.pauseRecording(); t.pauseRecording(); t.resumeRecording()
        t.onSkipNext(false); assertEquals(3, events.size)
        t.resumeRecording(); t.onSkipNext(false); assertEquals(4, events.size)
    }
}
