package de.yurtemre.sonora.shared.queue

import de.yurtemre.sonora.shared.model.QueueState
import de.yurtemre.sonora.shared.model.RepeatMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QueueStateTest {
    private fun QueueState.ids() = entries.map { it.songId }
    private fun base(start: Long? = null) = QueueState().load((1L..10L).toList(), start)

    @Test fun loadAddAndFifoPlayNext() { loadStartsAtSongOrZero(); playNextIsFifo(); addAppendsBoth() }

    private fun loadStartsAtSongOrZero() {
        assertEquals(3, base(4).currentIndex)
        assertEquals(0, base(99).currentIndex)
        assertEquals(-1, QueueState().load(emptyList()).currentIndex)
        val q = base()
        assertEquals(q.entries.map { it.uid }.toSet().size, 10)
    }

    private fun playNextIsFifo() {
        val q = base(1).playNext(listOf(100)).playNext(listOf(200))
        assertEquals(listOf(1L, 100, 200, 2), q.ids().take(4))
        assertEquals(listOf(1L, 100, 200, 2), q.original.map { it.songId }.take(4))
        assertEquals(listOf(100L, 300, 200), q.skipTo(1).playNext(listOf(300)).ids().subList(1, 4)) // anchor resets
    }

    private fun addAppendsBoth() {
        val q = base().add(listOf(1))
        assertEquals(1L, q.entries.last().songId)
        assertEquals(q.entries.last(), q.original.last())
    }

    @Test fun removeByUidAndMoveBookkeeping() { removeByUidKeepsDuplicates(); removeIndexBookkeeping(); moveBookkeeping() }

    private fun removeByUidKeepsDuplicates() {
        val q = base(1).add(listOf(5))
        val dup = q.entries.last()
        val r = q.removeByUid(dup.uid)
        assertEquals(1, r.ids().count { it == 5L })
        assertEquals(1, r.original.count { it.songId == 5L })
        assertFalse(r.original.any { it.uid == dup.uid })
    }

    private fun removeIndexBookkeeping() {
        val q = base(5) // index 4
        assertEquals(3, q.removeByUid(q.entries[0].uid).currentIndex)
        val cur = q.removeByUid(q.entries[4].uid)
        assertEquals(4, cur.currentIndex); assertEquals(6L, cur.currentEntry!!.songId)
        val last = base(10).let { it.removeByUid(it.entries[9].uid) }
        assertEquals(8, last.currentIndex)
        val one = QueueState().load(listOf(1))
        assertEquals(-1, one.removeByUid(one.entries[0].uid).currentIndex)
    }

    private fun moveBookkeeping() {
        val q = base(5) // cur 4
        assertEquals(7, q.move(4, 7).currentIndex)
        assertEquals(3, q.move(1, 6).currentIndex)
        assertEquals(5, q.move(8, 2).currentIndex)
        assertEquals(4, q.move(0, 2).currentIndex)
        assertEquals(5L, q.move(1, 6).currentEntry!!.songId)
        assertEquals(q.original, q.move(1, 6).original)
    }

    @Test fun shuffleRepeatAndNavigation() { shufflePreservesHistoryAndUnshuffleRestores(); shuffleDeterministic(); quickShuffle(); repeatCycle(); previousThreeSecondRule(); nextRepeatSemantics() }

    private fun shufflePreservesHistoryAndUnshuffleRestores() {
        val q = base(4)
        val s = q.shuffleOn(Random(42))
        assertTrue(s.isShuffled)
        assertEquals(q.entries.take(4), s.entries.take(4))
        assertEquals(q.entries.toSet(), s.entries.toSet())
        assertNotEquals(q.entries, s.entries)
        val moved = s.skipTo(7)
        val u = moved.shuffleOff()
        assertEquals(q.entries, u.entries)
        assertEquals(moved.currentEntry, u.currentEntry)
        assertFalse(u.isShuffled)
    }

    private fun shuffleDeterministic() {
        assertEquals(base(1).shuffleOn(Random(7)).entries, base(1).shuffleOn(Random(7)).entries)
    }

    private fun quickShuffle() {
        val q = QueueState().quickShuffle((1L..10L).toList(), Random(3))
        assertTrue(q.isShuffled); assertEquals(0, q.currentIndex)
        assertEquals((1L..10L).toList(), q.original.map { it.songId })
        assertEquals((1L..10L).toList(), q.shuffleOff().ids())
    }

    private fun repeatCycle() {
        val q = QueueState()
        assertEquals(RepeatMode.All, q.cycleRepeat().repeat)
        assertEquals(RepeatMode.One, q.cycleRepeat().cycleRepeat().repeat)
        assertEquals(RepeatMode.Off, q.cycleRepeat().cycleRepeat().cycleRepeat().repeat)
    }

    private fun previousThreeSecondRule() {
        val q = base(5)
        val r = q.previous(3_001)!!
        assertEquals(NavAction.Restart, r.action); assertTrue(r.recordRestart)
        assertEquals(NavAction.MoveTo(3), q.previous(3_000)!!.action)
        assertEquals(NavAction.Restart, base(1).previous(0)!!.action)
        assertEquals(NavAction.MoveTo(9), base(1).cycleRepeat().previous(0)!!.action)
        assertTrue(isRestartSeek(5_000, 1_000)); assertFalse(isRestartSeek(3_000, 1_000))
    }

    private fun nextRepeatSemantics() {
        val last = base(10)
        assertEquals(NavAction.Restart, last.next()!!.action)
        assertFalse(last.next()!!.recordSkip)
        assertEquals(NavAction.MoveTo(0), last.cycleRepeat().next()!!.action)
        val one = base(3).cycleRepeat().cycleRepeat()
        assertEquals(NavAction.Restart, one.next()!!.action)
        assertEquals(NavAction.Restart, one.previous(0)!!.action)
        val n = base(3).next()!!
        assertEquals(NavAction.MoveTo(3), n.action); assertTrue(n.recordSkip); assertEquals(3, n.state.currentIndex)
        assertEquals(NavAction.Completed, last.onTrackEnded()!!.action)
    }
}

class QueueInsertAtTest {
    @kotlin.test.Test
    fun insertBeforeCurrentShiftsIndexAndKeepsOriginalOrder() {
        val base = de.yurtemre.sonora.shared.model.QueueState().add(listOf(1L, 2L, 3L)).let { it.copy(currentIndex = 1) }
        val removed = base.removeByUid(base.entries[0].uid)
        val restored = removed.insertAt(1L, 0)
        kotlin.test.assertEquals(listOf(1L, 2L, 3L), restored.entries.map { it.songId })
        kotlin.test.assertEquals(listOf(1L, 2L, 3L), restored.original.map { it.songId })
        kotlin.test.assertEquals(1, restored.currentIndex)
    }
}
